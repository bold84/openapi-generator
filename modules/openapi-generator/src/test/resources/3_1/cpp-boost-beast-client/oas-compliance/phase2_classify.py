#!/usr/bin/env python3
# =============================================================================
# Gate A — semantic case classifier (reads optional Phase-2 raw-instance
# evidence to REPLACE the DEFERRED classification with real accept/reject
# results).  Wave-0 K-18 / GS4.
#
# Baseline (no BOOST_PHASE2_RESOLVED file): identical to the historical Gate A
# classifier — accept/reject rows whose model header exists are DEFERRED
# (runtime decode requires the compiled Phase-2 runner); rows with external
# spec references are DEFERRED; generation_failure rows are DEFERRED to the
# Step 2b negative-fixture checks.
#
# With a resolved file (from phase2_runner.cpp): every row whose case_id
# appears in the file is classified with the RUNNER'S actual verdict
# (PASS == runner accept matched expected; FAIL == mismatch).  Rows not in
# the file remain DEFERRED.  This is honest evidence, never a silent pass.
# =============================================================================

import os
import sys
import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
try:
    from phase2_numeric_gen import NUMERIC_SLICES as _NUMERIC_SLICE_MAP
    NUMERIC_SLICE_SCHEMAS = set(_NUMERIC_SLICE_MAP.keys())
except Exception:
    # If the numeric driver/generator module is unavailable, treat no schema as
    # numeric-slice; the Wave-0 header check applies unchanged (honest FAIL).
    NUMERIC_SLICE_SCHEMAS = set()

compliance_dir = os.environ.get("SCRIPT_DIR", os.path.abspath("."))
output_dir = os.environ.get("OUTPUT_DIR", "")
semantic_file = os.path.join(compliance_dir, "semantic-cases.yaml")
tsv_file = os.path.join(compliance_dir, "semantic-results.tsv")
model_dir = os.path.join(output_dir, "model") if output_dir else ""

# Optional Phase-2 raw-instance evidence (case_id -> (result, expected)).
resolved = {}
resolved_file = os.environ.get("BOOST_PHASE2_RESOLVED", "")
if resolved_file and os.path.exists(resolved_file):
    with open(resolved_file) as f:
        next(f, None)  # header
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3:
                resolved[parts[0]] = (parts[1], parts[2])

with open(semantic_file) as f:
    spec = yaml.safe_load(f) or []

results = []
buckets = {"generation_failure": 0, "compile_failure": 0,
           "decode_accept": 0, "decode_reject": 0, "round_trip": 0}
bucket_errors = {"generation_failure": 0, "compile_failure": 0,
                 "decode_accept": 0, "decode_reject": 0, "round_trip": 0}


def row_outcome(row_id, expected, schema_name, note, case_spec=None, is_phase2=False):
    """Classify a single expanded row, applying Phase-2 evidence when present."""
    schema_header = ""
    if schema_name:
        schema_header = os.path.join(model_dir, f"{schema_name}.h")

    # Phase-2 raw-instance evidence for concrete accept/reject rows.
    if is_phase2 and row_id in resolved:
        r_result, r_expected = resolved[row_id]
        if r_result == "PASS":
            detail = f"Phase-2 raw-instance evidence (expected {r_expected})"
            buckets[expected] += 1
            return (row_id, expected, "PASS", detail, note)
        # Runner reported FAIL: real evidence of a mismatch -> honest FAIL.
        detail = f"Phase-2 raw-instance evidence MISMATCH (runner rejected/diverged)"
        buckets[expected] += 1
        bucket_errors[expected] += 1
        return (row_id, expected, "FAIL", detail, note)

    result = "PASS"
    detail = ""

    if expected == "generation_failure":
        result = "DEFERRED"
        detail = "negative fixture case (checked in Step 2b)"
        buckets["generation_failure"] += 1
        return (row_id, expected, result, detail, note)

    if expected == "compile_failure":
        result = "DEFERRED"
        detail = "compile check requires C++ toolchain (deferred)"
        buckets["compile_failure"] += 1
        return (row_id, expected, result, detail, note)

    if expected in ("decode_accept", "decode_reject", "round_trip"):
        if case_spec:
            result = "DEFERRED"
            detail = f"exercised via spec '{case_spec}' (separate test)"
            buckets[expected] += 1
        elif schema_name in NUMERIC_SLICE_SCHEMAS:
            # Wave-1 numeric/boolean slice schemas have NO Wave-0 model header;
            # they are the shared SchemaEvaluator IR + ExactNumber engine and are
            # proven exclusively by the compiled phase2_numeric_driver.  In the
            # baseline (no Phase-2 evidence) they are honestly DEFERRED; with
            # evidence from the numeric driver they flip to PASS via the resolved
            # lookup above (never a silent pass).
            result = "DEFERRED"
            detail = f"numeric/boolean slice ({expected} — needs Wave-1 SchemaEvaluator driver)"
            buckets[expected] += 1
        elif schema_name:
            if os.path.exists(schema_header):
                result = "DEFERRED"
                detail = f"header found ({expected} — runtime decode deferred)"
                buckets[expected] += 1
            else:
                result = "FAIL"
                detail = f"schema header {schema_name}.h not found — cannot verify"
                bucket_errors[expected] += 1
        else:
            result = "FAIL"
            detail = "unbound semantic case — no schema or spec reference"
            bucket_errors[expected] += 1
    else:
        result = "FAIL"
        detail = f"unknown expected outcome: {expected}"
        bucket_errors[expected] = bucket_errors.get(expected, 0) + 1

    return (row_id, expected, result, detail, note)


for entry in spec:
    case_id = entry.get("id", "?")
    schema_name = entry.get("schema", "")
    entry_spec = entry.get("spec", "")
    cases_list = entry.get("cases", [])

    if cases_list:
        for idx, c in enumerate(cases_list):
            payload_desc = str(c.get("payload", c.get("response", "")))
            sub_id = f"{case_id}[{idx}]"
            exp = c.get("expected", entry.get("expected", "?"))
            note = c.get("note", entry.get("note", f"payload {idx}"))
            case_spec = c.get("spec", entry_spec if entry_spec else None)
            # Phase-2 evidence only applies to literal-payload accept/reject rows.
            is_phase2 = ("payload" in c) and exp in ("decode_accept", "decode_reject")
            results.append(row_outcome(sub_id, exp, schema_name, note, case_spec, is_phase2))
    else:
        exp = entry.get("expected", "?")
        note = entry.get("note", "")
        case_spec = entry_spec if entry_spec else None
        is_phase2 = ("payload" in entry) and exp in ("decode_accept", "decode_reject")
        results.append(row_outcome(case_id, exp, schema_name, note, case_spec, is_phase2))

# Write TSV
with open(tsv_file, "w") as f:
    f.write("case_id\texpected\tresult\tdetail\tnote\n")
    for r in results:
        f.write("\t".join(str(v) for v in r) + "\n")

# Bucket summary
print(f"\nOutcome buckets:")
for bucket in ("generation_failure", "compile_failure", "decode_accept", "decode_reject", "round_trip"):
    count = buckets.get(bucket, 0)
    errors = bucket_errors.get(bucket, 0)
    if count > 0 or errors > 0:
        status = "OK" if errors == 0 else f"{errors} FAIL"
        print(f"  {bucket}: {count} cases ({status})")

pass_count = sum(1 for r in results if r[2] == "PASS")
fail_count = sum(1 for r in results if r[2] == "FAIL")
deferred_total = sum(1 for r in results if r[2] == "DEFERRED")
print(f"\n__SEMANTIC_PASS__={pass_count}")
print(f"__SEMANTIC_FAIL__={fail_count}")

total_errors = sum(bucket_errors.values())
if total_errors > 0:
    print(f"__SEMANTIC_ERRORS__={total_errors}")
    sys.exit(1)

# Wave-0 K-18 shortfall accounting: rows still DEFERRED cannot be proven without
# the compiled C++ Phase-2 raw-instance runner (Boost/Beast toolchain) covering
# them.  We must NOT silently pass them.
print(f"__SEMANTIC_DEFERRED__={deferred_total}")
print(f"__SEMANTIC_ERRORS__=0")
