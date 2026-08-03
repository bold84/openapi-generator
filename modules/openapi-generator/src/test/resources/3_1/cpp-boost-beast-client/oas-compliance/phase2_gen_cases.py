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
}

# External spec files referenced by semantic-cases.yaml that are NOT part of
# the Gate A fixture set in this repository (they live with separate
# regression tests).  Rows bound to them cannot be resolved here.
EXTERNAL_SPECS = {
    "nullable-object-regression.yaml",
    "optional-nullable-regression.yaml",
    "response-union-regression.yaml",
    "multipart-encoding-regression.yaml",
    "pure-sse-object.yaml",
    "composed-schema-lowering.yaml",
}

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

    emitted = []      # (row_id, path, schema_kind, payload_json, expected)
    deferred = []     # (row_id, reason)
    counts = {"decode_accept": 0, "decode_reject": 0}

    for row_id, entry_id, schema_name, case_dict, entry, exp, note in expand_rows(spec):
        # Per-case spec override, then entry-level spec.
        spec_name = ""
        if case_dict is not None:
            spec_name = case_dict.get("spec", "") or entry.get("spec", "")
        else:
            spec_name = entry.get("spec", "")

        # Which raw payload to use (literal JSON instance) vs response object.
        if case_dict is not None and "payload" in case_dict:
            payload_json = case_dict["payload"]
            response_obj = False
        elif case_dict is not None and "response" in case_dict:
            payload_json = case_dict["response"]
            response_obj = True
        else:
            payload_json = None
            response_obj = False

        # Decide resolvability.
        if exp not in ("decode_accept", "decode_reject"):
            if exp == "generation_failure":
                deferred.append((row_id, "generation_failure handled by Step 2b negative fixtures"))
            elif exp == "round_trip":
                deferred.append((row_id, "round_trip is typed round-trip (M corpus); not raw-instance S-V"))
            else:
                deferred.append((row_id, f"unknown expected outcome {exp}"))
            continue

        if response_obj or payload_json is None:
            deferred.append((row_id, "response-dispatch / no-literal-payload case (outbound-client), not raw schema instance"))
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

        emitted.append((row_id, path, schema_kind, payload_json, exp))
        counts[exp] += 1

    # Emit C++ include.
    os.makedirs(os.path.dirname(OUT_INC) or ".", exist_ok=True)
    with open(OUT_INC, "w") as f:
        f.write("// AUTO-GENERATED by phase2_gen_cases.py — do not edit.\n")
        f.write("// Raw-instance cases emitted to the Phase-2 compiled runner.\n\n")
        f.write("struct RawCase {\n")
        f.write("    const char* id;\n")
        f.write("    const char* path;       // 'generated' | 'handwritten'\n")
        f.write("    const char* schema;     // schema name or 'TYPE_NUMBER'\n")
        f.write("    const char* payload;    // raw JSON instance\n")
        f.write("    const char* expected;   // decode_accept | decode_reject\n")
        f.write("};\n\n")
        f.write("static const RawCase kCases[] = {\n")
        for row_id, path, schema_kind, payload_json, exp in emitted:
            f.write(f'    {{"{c_quote(row_id)}", "{path}", "{schema_kind}", '
                    f'"{c_quote(str(payload_json))}", "{exp}"}},\n')
        f.write("};\n\n")
        f.write(f"static const std::size_t kCaseCount = {len(emitted)};\n")

    # Report to stdout.
    for row_id, path, schema_kind, payload_json, exp in emitted:
        print(f"  row  {row_id}  {schema_kind}  [{path}]  -> {exp}")
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
