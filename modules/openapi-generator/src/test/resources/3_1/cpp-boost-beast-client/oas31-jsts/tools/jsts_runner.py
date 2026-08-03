#!/usr/bin/env python3
"""
JSTS runner scaffold for cpp-boost-beast-client — Wave 0 (L2/L5 direct + L6
OAS-wrapped).

This is the GS2 gate driver for the pinned JSON Schema Test Suite (2020-12
dialect corpus). It has two modes:

  discover --suite <clone> [--out report.json]
    Walk the suite's `tests/draft2020-12/` tree, classify each file into the
    required-vocabulary groups (Core / Applicator / Unevaluated / Validation /
    Meta-Data / Format-Annotation / Content) vs `optional/`, and count
    files/groups/cases. Emits a machine-readable manifest.

  run --suite <clone> --jar <cli.jar> --work <dir>
      [--files a.json,b.json] [--oaswrap] [--timeout 300]
    For each selected test file, wrap every {description, schema, tests[]}
    group as a `components.schemas` entry in an OpenAPI 3.1 document, run the
    cpp-boost-beast generator, compile the generated C++ with Boost, and run a
    raw-instance harness that evaluates every data instance against its group.
    Reports per-file and per-group PASS / FAIL / BLOCKED tallies.

Wave-0 honesty (anti-greenwash):
  * The production pipeline in this Wave is *decode-based* (generated model
    `fromJsonValue*`); it is a partial validity oracle only. Cases whose schema
    does not materialise a generated model (scalar-only groups, etc.) are
    reported BLOCKED (no production validation path yet — K-18 gap), never as
    pass. Cases whose decode verdict disagrees with the suite's `valid` are
    reported FAIL (a genuine required-vocabulary shortfall), never excluded.
  * required-vocabulary exclusions remain zero; jsts-exclusions.yaml is the
    only place exclusions may be recorded, and only as `optional-profile`.
  * GS2 (100% required-vocab pass) is only claimable when PASS==all and
    FAIL==0 and BLOCKED==0.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time

DRAFT_DIR = "tests/draft2020-12"

REQUIRED_GROUPS = {
    "core":            ["anchor.json", "boolean_schema.json", "defs.json",
                        "ref.json", "refRemote.json", "dynamicRef.json"],
    "applicator":      ["additionalProperties.json", "allOf.json", "anyOf.json",
                        "contains.json", "dependentSchemas.json", "if-then-else.json",
                        "items.json", "oneOf.json", "patternProperties.json",
                        "prefixItems.json", "properties.json", "propertyNames.json",
                        "not.json"],
    "unevaluated":     ["unevaluatedItems.json", "unevaluatedProperties.json"],
    "validation":      ["const.json", "dependentRequired.json", "enum.json",
                        "exclusiveMaximum.json", "exclusiveMinimum.json",
                        "maxContains.json", "maximum.json", "maxItems.json",
                        "maxLength.json", "maxProperties.json", "minContains.json",
                        "minimum.json", "minItems.json", "minLength.json",
                        "minProperties.json", "multipleOf.json", "pattern.json",
                        "required.json", "type.json", "uniqueItems.json"],
    "meta-data":       ["default.json"],
    "format-annotation": ["format.json"],
    "content":         ["content.json"],
}

# Not part of the required-vocabulary validity corpus for GS2 grouping
# (metaschema guidance / infra / dialect-machinery tests).
NON_GROUPED = ["vocabulary.json", "infinite-loop-detection.json"]


def classify_file(basename):
    for grp, files in REQUIRED_GROUPS.items():
        if basename in files:
            return grp
    return None


def load_groups(path):
    with open(path) as f:
        return json.load(f)


def build_spec(groups):
    """One components.schemas entry per group (strips subschema `$schema`)."""
    schemas = {}
    for i, g in enumerate(groups):
        # A schema may be a JSON boolean (true/false schema), which is valid
        # 2020-12 superschema syntax; it is NOT an object, so must not be
        # coerced via dict(). Only object-shaped schemas get the $schema strip.
        schema = g.get("schema", {})
        if isinstance(schema, dict):
            schema = dict(schema)
            schema.pop("$schema", None)
        schemas[f"G{i}"] = schema
    return {
        "openapi": "3.1.0",
        "info": {"title": "jsts", "version": "1.0.0"},
        "paths": {},
        "components": {"schemas": schemas},
    }


def generate(jar, spec_path, out_dir):
    cmd = [
        "java", "-jar", jar, "generate",
        "--generator-name", "cpp-boost-beast-client",
        "--input-spec", spec_path,
        "--output", out_dir,
        "--additional-properties", "packageName=Jsts",
        "--additional-properties", "modelPackage=model",
    ]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=900)


def model_function(group_i, model_dir):
    header = os.path.join(model_dir, f"G{group_i}.h")
    if not os.path.exists(header):
        return None
    with open(header) as f:
        content = f.read()
    m = re.search(rf"fromJsonValue_G{group_i}\s*\(boost::json::value\s+const&",
                  content)
    if m:
        return f"fromJsonValue_G{group_i}"
    return None


def _esc(data):
    s = json.dumps(data)
    return s.replace('\\', '\\\\').replace('"', '\\"')


def build_harness(group, fn, work_dir, tag):
    lines = ['#include <boost/json.hpp>', '#include <iostream>',
             f'#include "model/G{tag}.h"', 'using namespace boost::json;',
             'int main(){']
    # The generated free function lives in namespace `model`.
    fn = f"model::{fn}"
    for ci, t in enumerate(group["tests"]):
        data = _esc(t["data"])
        lines.append(f'  {{ try {{ {fn}(parse("{data}")); '
                     f'std::cout << "MODELIDX|{ci}|ACCEPT\\n"; }} '
                     f'catch (...) {{ std::cout << "MODELIDX|{ci}|REJECT\\n"; }} }}')
    lines.append('  return 0;')
    lines.append('}')
    main_path = os.path.join(work_dir, f"main_{tag}.cpp")
    with open(main_path, "w") as f:
        f.write("\n".join(lines) + "\n")
    return main_path


def compile_run(main_path, gen_dir, binary_path, timeout, tag):
    # Link only this group's generated model .cpp so a broken sibling model
    # (e.g. one with a malformed member name) cannot mask a compilable group.
    own = os.path.join(gen_dir, "model", f"G{tag}.cpp")
    srcs = [own] if os.path.exists(own) else []
    cmd = (["g++", "-std=c++17", "-I/opt/homebrew/include", "-I", gen_dir,
            main_path] + srcs + ["-L/opt/homebrew/lib", "-lboost_json",
            "-o", binary_path])
    c = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if c.returncode != 0:
        return None, c.stderr[-1500:]
    r = subprocess.run([binary_path], capture_output=True, text=True,
                       timeout=timeout)
    return r, None


def parse_harness(out):
    return {int(m.group(1)): (m.group(2) == "ACCEPT")
            for m in (re.match(r"MODELIDX\|(\d+)\|(ACCEPT|REJECT)", ln.strip())
                      for ln in out.splitlines())
            if m}


def evaluate_file(suite, jar, work_dir, filename, timeout):
    path = os.path.join(suite, DRAFT_DIR, filename)
    groups = load_groups(path)
    per_group = {}
    verdicts = {}
    file_pass = file_fail = file_blocked = 0

    gen_dir = os.path.join(work_dir, "gen", filename.replace(".json", ""))
    os.makedirs(gen_dir, exist_ok=True)
    spec_path = os.path.join(work_dir, "spec.json")
    with open(spec_path, "w") as f:
        json.dump(build_spec(groups), f)

    t0 = time.time()
    r = generate(jar, spec_path, gen_dir)
    gen_ok = (r.returncode == 0) and os.path.isdir(os.path.join(gen_dir, "model"))

    if not gen_ok:
        for gi, g in enumerate(groups):
            cd = {"PASS": 0, "FAIL": 0, "BLOCKED": len(g["tests"]), "note": "generation shortfall"}
            per_group[gi] = cd
            file_blocked += len(g["tests"])
            for ci in range(len(g["tests"])):
                verdicts[f"{gi}:{ci}"] = "BLOCKED"
        return filename, {"generation": "FAILED", "groups": per_group,
                          "verdicts": verdicts,
                          "compile": "n/a", "run": "n/a",
                          "seconds": round(time.time() - t0, 2)}

    model_dir = os.path.join(gen_dir, "model")
    for gi, g in enumerate(groups):
        fn = model_function(gi, model_dir)
        cd = {"PASS": 0, "FAIL": 0, "BLOCKED": 0, "note": ""}
        if not fn:
            cd["BLOCKED"] = len(g["tests"])
            cd["note"] = "no production model materialised (K-18 gap)"
            file_blocked += len(g["tests"])
            for ci in range(len(g["tests"])):
                verdicts[f"{gi}:{ci}"] = "BLOCKED"
            per_group[gi] = cd
            continue
        # per-group compile+run so one broken group cannot mask siblings
        main_path = build_harness(g, fn, work_dir, gi)
        binary = os.path.join(work_dir, f"run_{gi}")
        rr, err = compile_run(main_path, gen_dir, binary, timeout, gi)
        if rr is None:
            cd["BLOCKED"] = len(g["tests"])
            cd["note"] = "compile shortfall: " + (err or "")[:160]
            file_blocked += len(g["tests"])
            for ci in range(len(g["tests"])):
                verdicts[f"{gi}:{ci}"] = "BLOCKED"
            per_group[gi] = cd
            continue
        observed = parse_harness(rr.stdout)
        for ci, t in enumerate(g["tests"]):
            valid = bool(t["valid"])
            if ci not in observed:
                cd["BLOCKED"] += 1
                verdicts[f"{gi}:{ci}"] = "BLOCKED"
                file_blocked += 1
            elif observed[ci] == valid:
                cd["PASS"] += 1
                verdicts[f"{gi}:{ci}"] = "PASS"
                file_pass += 1
            else:
                cd["FAIL"] += 1
                verdicts[f"{gi}:{ci}"] = "FAIL"
                file_fail += 1
        if cd["BLOCKED"] == 0:
            cd.pop("note", None)
        per_group[gi] = cd

    return filename, {"generation": "OK", "groups": per_group,
                      "verdicts": verdicts, "compile": "per-group",
                      "run": "per-group", "seconds": round(time.time() - t0, 2)}


def discover(suite):
    base = os.path.join(suite, DRAFT_DIR)
    manifest = {"dialect": "2020-12", "files": {}, "groups": {},
                "totals": {"required_files": 0, "required_cases": 0,
                           "optional_files": 0, "optional_cases": 0}}
    group_totals = {g: 0 for g in REQUIRED_GROUPS}
    for fn in sorted(f for f in os.listdir(base) if f.endswith(".json")):
        groups = load_groups(os.path.join(base, fn))
        cases = sum(len(g["tests"]) for g in groups)
        grp = classify_file(fn)
        manifest["files"][fn] = {"groups": len(groups), "cases": cases,
                                 "requiredGroup": grp}
        if grp:
            group_totals[grp] += cases
            manifest["totals"]["required_files"] += 1
            manifest["totals"]["required_cases"] += cases
    opt_base = os.path.join(base, "optional")
    if os.path.isdir(opt_base):
        for root, _, fs in os.walk(opt_base):
            for fn in sorted(fs):
                if fn.endswith(".json"):
                    try:
                        groups = load_groups(os.path.join(root, fn))
                        cases = sum(len(g["tests"]) for g in groups)
                    except Exception:
                        cases = -1
                    manifest["totals"]["optional_files"] += 1
                    manifest["totals"]["optional_cases"] += cases
    manifest["groups"] = group_totals
    return manifest


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("mode", choices=["discover", "run"])
    ap.add_argument("--suite", required=True)
    ap.add_argument("--jar")
    ap.add_argument("--work")
    ap.add_argument("--files", default=None)
    ap.add_argument("--out", default=None)
    ap.add_argument("--timeout", type=int, default=300)
    args = ap.parse_args()

    if args.mode == "discover":
        m = discover(args.suite)
        print(json.dumps(m, indent=2))
        if args.out:
            with open(args.out, "w") as f:
                json.dump(m, f, indent=2)
        return

    if not args.jar or not os.path.exists(args.jar):
        print("ERROR: --jar required and must exist", file=sys.stderr)
        sys.exit(2)
    base = os.path.join(args.suite, DRAFT_DIR)
    if args.files:
        files = [s.strip() for s in args.files.split(",") if s.strip()]
    else:
        files = sorted(f for f in os.listdir(base)
                       if f.endswith(".json") and classify_file(f))
    work = args.work or tempfile.mkdtemp(prefix="jsts-run-")
    os.makedirs(work, exist_ok=True)

    report = {"dialect": "2020-12", "suite": args.suite, "files": {},
              "totals": {"files": 0, "cases": 0, "PASS": 0, "FAIL": 0,
                         "BLOCKED": 0}}
    last_err = None
    for fn in files:
        print(f"== {fn} ==", flush=True)
        try:
            _, res = evaluate_file(args.suite, args.jar, work, fn, args.timeout)
        except Exception as e:
            print(f"  ERROR {fn}: {e}", flush=True)
            last_err = e
            continue
        report["files"][fn] = res
        report["totals"]["files"] += 1
        for cd in res["groups"].values():
            report["totals"]["cases"] += sum(v for k, v in cd.items()
                                             if k in ("PASS", "FAIL", "BLOCKED"))
            report["totals"]["PASS"] += cd["PASS"]
            report["totals"]["FAIL"] += cd["FAIL"]
            report["totals"]["BLOCKED"] += cd["BLOCKED"]
        fpass = sum(v["PASS"] for v in res["groups"].values())
        ffail = sum(v["FAIL"] for v in res["groups"].values())
        fb = sum(v["BLOCKED"] for v in res["groups"].values())
        print(f"  file>{fn}: PASS={fpass} FAIL={ffail} BLOCKED={fb} "
              f"(gen={res['generation']})", flush=True)

    print("\n=== TOTALS ===")
    t = report["totals"]
    print(f"  files={t['files']} cases={t['cases']} PASS={t['PASS']} "
          f"FAIL={t['FAIL']} BLOCKED={t['BLOCKED']}")
    if args.out:
        with open(args.out, "w") as f:
            json.dump(report, f, indent=2)
        print(f"  report -> {args.out}")
    if last_err:
        sys.exit(1)


if __name__ == "__main__":
    main()
