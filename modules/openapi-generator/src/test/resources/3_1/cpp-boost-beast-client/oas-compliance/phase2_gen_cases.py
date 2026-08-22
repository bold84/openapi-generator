#!/usr/bin/env python3
# =============================================================================
# Phase-2 raw-instance validator — case manifest generator
# =============================================================================
# Wave-0 K-18 / GS4 (plan Wave 0 item 2, §5, GS4).
#
# Reads semantic-cases.yaml and decides, for every EXPANDED row (the same
# expansion the Gate A classifier uses: `id[idx]` for entries with a cases[]
# list), whether the case can be resolved by the *generated* raw-instance
# validation path (fromJsonValue_<Schema> + validate_<Schema>_branch_N) or by
# a thin hand-written type check, and emits a C++ include (`phase2_cases.inc`)
# the compiled runner consumes.
#
# Honesty contract:
#   * Only rows that carry a literal JSON `payload` (a raw instance) with an
#     expected outcome in {decode_accept, decode_reject} are emitted as raw
#     cases.  Rows whose expected outcome is round_trip, generation_failure,
#     or which reference a response object / outbound-client wire behaviour,
#     or an external spec file not present in this repository, are NOT emitted
#     and remain DEFERRED with an explicit reason in the Phase-2 report.
#   * A row is dispatched through the GENERATED validator when its schema is in
#     RESOLVABLE_SCHEMAS.  Only the inline `type: number` request body
#     (postNumberOnly) is resolved via a minimal hand-written type check, and
#     that is reported explicitly as the 'handwritten' path.
#
# Emits stdout report lines of the form:
#   RESOLVED <count> DEFERRED <count>
#   row  <id>  <schema-or-builtin>  <outcome>
#   skip <id>  <reason>
# =============================================================================

import os
import sys
import yaml

COMPLIANCE_DIR = os.environ.get(
    "SCRIPT_DIR", os.path.abspath(os.path.dirname(__file__)))
SEMANTIC_FILE = os.path.join(COMPLIANCE_DIR, "semantic-cases.yaml")
OUT_INC = os.environ.get("PHASE2_INC", os.path.join(COMPLIANCE_DIR, "phase2_cases.inc"))

# Schemas for which the generator emits a real fromJsonValue_<Schema> raw
# instance validator (which internally uses validate_<Schema>_branch_N).
RESOLVABLE_SCHEMAS = {
    "ConstrainedNumber",
    "IntOrNumber",
    "AnyOfEnumUnion",
    "AllNullAnyOf",
    "DuplicateNullOneOf",
    "OverlappingAnimal",
    "DiscriminatorOneOf",
    "AllOfEnumIntersection",
    "OptionalImpossibleAllOf",
    "OneOfStringStringEnum",
    # Wave-6 GS4 closure: nullable/tri-state (round-trip), response-union,
    # SSE strict + typed (discriminator) schemas.
    "TriStateContainer",
    "NullableObjectRoot",
    "FullResource",
    "SummaryResource",
    "Evt",
    "ResponseStreamEvent",
    "ResponseCreatedEvent",
    "ResponseCompletedEvent",
}

# Response-union branch rows: (expanded case row id) -> (spec file, status
# key, response schema name or VOID for no-content).  Verified against the
# in-repo spec at generation time (a mismatch aborts generation).
RESPONSE_BRANCH_SCHEMAS = {
    "response-union-200[0]": ("response-union-regression.yaml", "200", "FullResource"),
    "response-union-201[0]": ("response-union-regression.yaml", "201", "SummaryResource"),
    "response-union-204[0]": ("response-union-regression.yaml", "204", "VOID"),
}

# Rows whose response bodies are plain JSON strings, validated raw as
# JSON_STRING after verifying the in-repo operation's response contents.
STRING_BODY_ROW_PREFIXES = {"range-default-exact-precedence"}

# All 6 spec files referenced by the previously-deferred semantic rows are
# now materialized in-repo (Wave-6 GS4 closure): nullable-object-regression,
# optional-nullable-regression, response-union-regression,
# multipart-encoding-regression (siblings of oas-compliance/) and
# pure-sse-object, composed-schema-lowering.  No spec is external any more.
EXTERNAL_SPECS = set()


def spec_path(name):
    """Locate a semantic-case spec file: oas-compliance/ first, then the
    sibling test-resource root."""
    for base in (COMPLIANCE_DIR, os.path.dirname(COMPLIANCE_DIR)):
        cand = os.path.join(base, name)
        if os.path.exists(cand):
            return cand
    return None


def verify_response_branch_rows():
    """Verify RESPONSE_BRANCH_SCHEMAS + STRING_BODY_ROW_PREFIXES against the
    in-repo specs; return the verified map (case_id -> schema name)."""
    verified = {}
    # response-union-regression.yaml: /items/{id} GET
    p = spec_path("response-union-regression.yaml")
    if not p:
        raise SystemExit("response-union-regression.yaml missing")
    spec = yaml.safe_load(open(p))
    op = (spec.get("paths") or {}).get("/items/{id}", {}).get("get", {})
    for case_id, (spec_name, status, expected_schema) in RESPONSE_BRANCH_SCHEMAS.items():
        resp = (op.get("responses") or {}).get(status)
        if resp is None:
            raise SystemExit(f"verify: {case_id}: response {status} missing")
        content = resp.get("content") or {}
        if expected_schema == "VOID":
            if content:
                raise SystemExit(f"verify: {case_id}: expected no-content but content present")
        else:
            ref = ""
            for media in content.values():
                sch = media.get("schema") or {}
                ref = (sch.get("$ref") or "").split("/")[-1]
                break
            if ref != expected_schema:
                raise SystemExit(
                    f"verify: {case_id}: response {status} schema {ref!r} "
                    f"!= expected {expected_schema!r}")
        verified[case_id] = expected_schema
    # fixtures.yaml: /range-default GET 200/2XX/default all JSON string bodies
    p = spec_path("fixtures.yaml")
    spec = yaml.safe_load(open(p))
    op = (spec.get("paths") or {}).get("/range-default", {}).get("get", {})
    range_branches = ("200", "2XX", "default")
    for status in range_branches:
        resp = (op.get("responses") or {}).get(status)
        if resp is None:
            raise SystemExit(f"verify: /range-default response {status} missing")
        for media in (resp.get("content") or {}).values():
            if (media.get("schema") or {}).get("type") != "string":
                raise SystemExit(f"verify: /range-default {status} body not type string")
    for idx in range(3):
        verified[f"range-default-exact-precedence[{idx}]"] = "JSON_STRING"
    return verified

# object escaping for C string literal
def c_quote(s):
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def expand_rows(spec):
    """Yield (row_id, entry_id, schema_name, case_dict, entry, expected, note)."""
    for entry in spec or []:
        entry_id = entry.get("id", "?")
        schema_name = entry.get("schema", "")
        entry_spec = entry.get("spec", "")
        cases_list = entry.get("cases", [])
        if cases_list:
            for idx, c in enumerate(cases_list):
                exp = c.get("expected", entry.get("expected", "?"))
                note = c.get("note", entry.get("note", ""))
                yield (f"{entry_id}[{idx}]", entry_id, schema_name, c, entry, exp, note)
        else:
            exp = entry.get("expected", "?")
            note = entry.get("note", "")
            yield (entry_id, entry_id, schema_name, None, entry, exp, note)


def main():
    with open(SEMANTIC_FILE) as f:
        spec = yaml.safe_load(f) or []

    verified_branches = verify_response_branch_rows()

    emitted = []      # (row_id, path, schema_kind, payload_json, exp, mode)
    deferred = []     # (row_id, reason)
    counts = {"decode_accept": 0, "decode_reject": 0, "round_trip": 0}

    for row_id, entry_id, schema_name, case_dict, entry, exp, note in expand_rows(spec):
        # Per-case spec override, then entry-level spec.
        spec_name = ""
        if case_dict is not None:
            spec_name = case_dict.get("spec", "") or entry.get("spec", "")
        else:
            spec_name = entry.get("spec", "")

        # Which raw payload to use (literal JSON instance) vs response object.
        mode = "plain"
        if case_dict is not None and "payload" in case_dict:
            payload_json = case_dict["payload"]
            response_obj = False
        elif case_dict is not None and "response" in case_dict:
            payload_json = case_dict["response"].get("body",
                "" if case_dict["response"].get("status") == 204 else None)
            if payload_json is None:
                deferred.append((row_id, "response-case without body/status"))
                continue
            response_obj = True
        else:
            payload_json = None
            response_obj = False

        # Round-trip rows: typed decode + re-encode + exact compare (the
        # runner's round_trip mode) — the nullable/tri-state M-side rows are
        # resolvable through the GENERATED model classes.
        if exp == "round_trip":
            if payload_json is not None and schema_name in RESOLVABLE_SCHEMAS:
                emitted.append((row_id, "generated", schema_name,
                                payload_json, exp, "round_trip"))
                counts["round_trip"] += 1
            else:
                deferred.append((row_id, f"round_trip row not bound to a "
                                          f"resolved schema ({schema_name or 'none'})"))
            continue

        if exp not in ("decode_accept", "decode_reject"):
            if exp == "generation_failure":
                deferred.append((row_id, "generation_failure handled by Step 2b negative fixtures"))
            elif exp == "compile_failure":
                deferred.append((row_id, "compile_failure handled by harness compile step"))
            else:
                deferred.append((row_id, f"unknown expected outcome {exp}"))
            continue

        if response_obj:
            # Response-branch row: validate the response body against the
            # branch response's schema (VOID = no content, trivially accepts).
            if row_id in verified_branches:
                emitted.append((row_id, "generated",
                                verified_branches[row_id], payload_json,
                                exp, "plain"))
                counts[exp] += 1
            else:
                deferred.append((row_id, "response-case with no verified branch mapping"))
            continue

        if payload_json is None:
            # Wire-level rows (multipart Encoding Object emission) are
            # resolved by the Wave-5 C-profile gate evidence index in the
            # classifier, not by the raw-instance runner.
            deferred.append((row_id, "wire-level case (C-profile gate evidence index)"))
            continue

        if schema_name in RESOLVABLE_SCHEMAS:
            path = "generated"
            schema_kind = schema_name
        elif schema_name == "" and spec_name == "fixtures.yaml" \
                and entry.get("operationId") == "postNumberOnly":
            path = "handwritten"
            schema_kind = "TYPE_NUMBER"
        else:
            if spec_name in EXTERNAL_SPECS:
                deferred.append((row_id, f"external spec '{spec_name}' not present in repo"))
            else:
                deferred.append((row_id, "no generated raw validator for this schema/spec"))
            continue

        emitted.append((row_id, path, schema_kind, payload_json, exp, mode))
        counts[exp] += 1

    # Emit C++ include.
    os.makedirs(os.path.dirname(OUT_INC) or ".", exist_ok=True)
    with open(OUT_INC, "w") as f:
        f.write("// AUTO-GENERATED by phase2_gen_cases.py — do not edit.\n")
        f.write("// Raw-instance cases emitted to the Phase-2 compiled runner.\n\n")
        f.write("struct RawCase {\n")
        f.write("    const char* id;\n")
        f.write("    const char* path;       // 'generated' | 'handwritten'\n")
        f.write("    const char* schema;     // schema name | 'TYPE_NUMBER' | 'VOID'\n")
        f.write("    const char* payload;    // raw JSON instance\n")
        f.write("    const char* expected;   // decode_accept | decode_reject | round_trip\n")
        f.write("    const char* mode;       // plain | round_trip\n")
        f.write("};\n\n")
        f.write("static const RawCase kCases[] = {\n")
        for row_id, path, schema_kind, payload_json, exp, mode in emitted:
            f.write(f'    {{"{c_quote(row_id)}", "{path}", "{schema_kind}", '
                    f'"{c_quote(str(payload_json))}", "{exp}", "{mode}"}},\n')
        f.write("};\n\n")
        f.write(f"static const std::size_t kCaseCount = {len(emitted)};\n")

    # Report to stdout.
    for row_id, path, schema_kind, payload_json, exp, mode in emitted:
        print(f"  row  {row_id}  {schema_kind}  [{path}]  {mode} -> {exp}")
    for row_id, reason in deferred:
        print(f"  skip {row_id}  {reason}")
    print(f"__PHASE2_GEN_RESOLVED__={len(emitted)}")
    print(f"__PHASE2_GEN_DEFERRED__={len(deferred)}")
    if emitted:
        print(f"  generated-path rows: "
              f"{sum(1 for e in emitted if e[1]=='generated')}; "
              f"handwritten-path rows: "
              f"{sum(1 for e in emitted if e[1]=='handwritten')}")


if __name__ == "__main__":
    main()
