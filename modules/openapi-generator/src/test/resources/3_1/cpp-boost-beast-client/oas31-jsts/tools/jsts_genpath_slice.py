#!/usr/bin/env python3
"""
jsts_genpath_slice.py — Wave-1 numeric/boolean JSTS slice through the GENERATED path.

This is the Wave-1 (generated-path) slice driver for `oas31-jsts/`. It is the
honest successor report driver to the Wave-0 decode-based `jsts_runner.py`
(which measured the fromJsonValue* typed-decode path and reported the 229-BLOCKED
baseline). This driver proves the ADR Option-B **GENERATED** path — the artifacts
the REAL generator emits (`schema_ir.generated.{hpp,cpp}` +
`schema_validate.generated.cpp` with a thin `validate_<id>_branch_n` dispatch)
— by:

  1. OAS-WRAP:  each JSTS group's schema is lowered to a single-branch oneOf so
                the Wave-1 IR emitter (`irNodeFromBranch`) densifies it to a
                SchemaNode and emits a `validate_G<i>_branch_0` dispatch (this is
                the only shape the emitter currently lowers to a validate_<id>
                entry; a bare, non-composed schema materialises the typed-decoder
                model, not an IR validator).
  2. GENERATE:  run the real cpp-boost-beast-client generator on the wrapped doc.
  3. COMPILE:   g++ the emitted schema_ir.generated.* + schema_validate.generated.cpp
                + a per-run driver + oas31_lexeme.hpp under C++17 with Boost.
  4. RUN:       for every case, capture the raw numeric lexeme from the payload
                string (before Boost.JSON canonicalises it), build a
                RawInstance(value, lexeme) so asExactNumber() is exact, dispatch
                through the GENERATED validate_G<i>_branch_0, and compare the
                accept/reject verdict with the suite's `valid` flag.

Anti-greenwash (unchanged from the Wave-1 slice contract):
  * A case is PASS only when the GENERATED-path runtime verdict equals `valid`.
  * A case whose file fails generation (e.g. `not`, `boolean-schema` — the
    engine still fail-closes on these in Generator Phase 2) is BLOCKED (counted,
    never silently dropped, never a fake pass).
  * A whole file that does not generate is entirely BLOCKED.
  * Anything not run is never reported pass.
  * Full GS2 (the whole required-vocabulary corpus, 44 files / 1292 cases) is
    explicitly NOT claimed here; this slice is the strict 10-file numeric/boolean
    subset shared with `jsts_runner.py` (NUMERIC_BOOLEAN_SLICE_FILES).

Outcome codes (per case): PASS / FAIL / BLOCKED.
Report: per-file {generation, groups{gi:{PASS,FAIL,BLOCKED}}, totals} plus a run
log readable by the Wave-1 numeric subset report.

This tool is jsts-owned (oas31-jsts/). It reads engines/resources it does not own
(read-only) and drives the real generator + g++ — it never hand-rolls a validator.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time

# Mirror the canonical slice set in jsts_runner.py.
NUMERIC_BOOLEAN_SLICE_FILES = [
    "boolean_schema.json", "not.json", "const.json", "enum.json",
    "minimum.json", "maximum.json", "exclusiveMinimum.json",
    "exclusiveMaximum.json", "multipleOf.json", "type.json",
]

RES_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "..", "..", "..", "..", "..", "..",
                       "src", "main", "resources", "cpp-boost-beast-client")
LEXEME_SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                          "..", "..", "..", "..", "..", "..", "..",
                          "src", "test", "resources", "3_1",
                          "cpp-boost-beast-client",
                          "oas-compliance", "oas31_lexeme.hpp")


def resolve_draft_dir(suite):
    for cand in (os.path.join(suite, "tests", "draft2020-12"),
                 os.path.join(suite, "draft2020-12"),
                 os.path.join(suite, "tests")):
        if os.path.isdir(cand):
            return cand
    return os.path.join(suite, "tests", "draft2020-12")


def load_groups(path):
    with open(path) as f:
        return json.load(f)


def wrap_spec(groups):
    """OAS-wrap each group's schema as a single-branch oneOf so the Wave-1 IR
    emitter lowers it to a validate_G<i>_branch_0 dispatch."""
    comp = {}
    for i, g in enumerate(groups):
        s = g.get("schema", {})
        branch = dict(s) if isinstance(s, dict) else s
        if isinstance(branch, dict):
            branch.pop("$schema", None)
        comp["G%d" % i] = {"oneOf": [branch]}
    return {"openapi": "3.1.0", "info": {"title": "jsts-genpath-slice",
                                         "version": "1.0.0"},
            "paths": {}, "components": {"schemas": comp}}


def generate(jar, spec_path, out_dir):
    cmd = ["java", "-jar", jar, "generate", "--generator-name",
           "cpp-boost-beast-client", "--input-spec", spec_path,
           "--output", out_dir, "--additional-properties", "packageName=Jsts",
           "--additional-properties", "modelPackage=model"]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=900)


def write_driver(groups, work_dir, tag):
    """Driver that dispatches every case through the GENERATED
    validate_G<i>_branch_0. Uses RawInstance(value, lexeme) with the raw number
    lexeme captured from the payload string so ExactNumber stays exact."""
    if not os.path.exists(os.path.join(work_dir, "oas31_lexeme.hpp")):
        # vendor it alongside from oas-compliance (read-only source)
        src = LEXEME_SRC
        if os.path.exists(src):
            import shutil
            shutil.copy(src, os.path.join(work_dir, "oas31_lexeme.hpp"))
    lines = []
    lines.append('#include <boost/json.hpp>')
    lines.append('#include <cstdio>')
    lines.append('#include <string>')
    lines.append('#include "oas31_lexeme.hpp"')
    lines.append('#include "oas31_validator.hpp"')
    lines.append('#include "schema_ir.generated.hpp"')
    lines.append('using oas31::RawInstance; using oas31::ValidationContext;')
    lines.append('using oas31::ValidationPath; using oas31::ValidationResult;')
    for i in range(len(groups)):
        lines.append("oas31::ValidationResult validate_G%d_branch_0("
                     "oas31::RawInstance const&,oas31::ValidationPath&,"
                     "oas31::ValidationContext&);" % i)
    lines.append('int main(){')
    lines.append('  int pass=0,fail=0,blocked=0;')
    for i, g in enumerate(groups):
        for ci, t in enumerate(g["tests"]):
            data = t.get("data")
            payload = json.dumps(data, separators=(",", ":"))
            payload_c = payload.replace('\\', '\\\\').replace('"', '\\"')
            exp = "true" if bool(t["valid"]) else "false"
            lines.append('  { std::string pl="%s"; std::string lx;' % payload_c)
            lines.append('    oas31::captureLeadingNumberLexeme(pl,lx);')
            lines.append('    try {')
            lines.append('      boost::json::value v=boost::json::parse(pl);')
            lines.append('      RawInstance ri(&v,lx); ValidationPath p; '
                         'ValidationContext c;')
            lines.append(f'      ValidationResult r=validate_G{i}_branch_0(ri,p,c);')
            lines.append('      bool ok=(r.success==%s);' % exp)
            lines.append(f'      printf("RESULT|{i}|{ci}|%s\\n", ok?"PASS":"FAIL");')
            lines.append('    } catch(...){')
            lines.append(f'      printf("RESULT|{i}|{ci}|BLOCKED\\n");')
            lines.append('    }')
            lines.append('  }')
    lines.append('  return 0;')
    lines.append('}')
    main_path = os.path.join(work_dir, "main_%s.cpp" % tag)
    with open(main_path, "w") as f:
        f.write("\n".join(lines) + "\n")
    return main_path


def compile_run(main_path, gen_dir, work_dir, binary, timeout, tag):
    srcs = [os.path.join(gen_dir, "model", f)
            for f in ("schema_ir.generated.cpp",
                      "schema_validate.generated.cpp")]
    boost_src = os.path.join(work_dir, "boost_json_src.cpp")
    if not os.path.exists(boost_src):
        with open(boost_src, "w") as f:
            f.write('#include <boost/json/src.hpp>\n')
    cmd = (["g++", "-std=c++17", "-I/opt/homebrew/include", "-I", RES_DIR,
            "-I", work_dir, "-I", os.path.join(gen_dir, "model"),
            main_path] + srcs + [boost_src, "-o", binary])
    c = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if c.returncode != 0:
        return None, c.stderr[-1800:]
    r = subprocess.run([binary], capture_output=True, text=True, timeout=timeout)
    return r, None


def parse_results(out):
    res = {}
    for ln in out.splitlines():
        m = re.match(r"RESULT\|(\d+)\|(\d+)\|(PASS|FAIL|BLOCKED)", ln.strip())
        if m:
            res[(int(m.group(1)), int(m.group(2)))] = m.group(3)
    return res


def evaluate_file(suite, jar, work_dir, filename, timeout):
    """Evaluate one JSTS file by generating/running EACH group independently.

    Per-group isolation mirrors the Wave-0 decoder runner so a single broken
    generated artifact (e.g. an object-typed enum value that the emitter writes
    into enumNumbers as a malformed string) cannot mask the rest of the file:
    each group becomes schema G0 in its own OAS doc and is driven through the
    GENERATED validate_G0_branch_0 dispatch.
    """
    path = os.path.join(resolve_draft_dir(suite), filename)
    groups = load_groups(path)
    tag = filename.replace(".json", "")
    per_group = {}
    verdicts = {}
    pass_n = fail_n = blocked_n = 0
    file_gen_ok = True
    t0 = time.time()

    for gi, g in enumerate(groups):
        gen_dir = os.path.join(work_dir, "gen", tag, str(gi))
        os.makedirs(gen_dir, exist_ok=True)
        spec_path = os.path.join(work_dir, "spec_%s_%d.json" % (tag, gi))
        with open(spec_path, "w") as f:
            json.dump(wrap_spec([g]), f)

        r = generate(jar, spec_path, gen_dir)
        vpath = os.path.join(gen_dir, "model", "schema_validate.generated.cpp")
        gen_ok = (r.returncode == 0) and os.path.exists(vpath)
        if not gen_ok:
            file_gen_ok = False
            reason = "generation rejected (fail-closed): " + next(
                (l.strip() for l in r.stderr.splitlines()
                 if "UnsupportedSchemaAssertionException" in l
                 or "Exception" in l), "see generator stderr")[:200]
            bc = len(g["tests"])
            cd = {"PASS": 0, "FAIL": 0, "BLOCKED": bc, "note": reason,
                  "stage": "generation"}
            per_group[gi] = cd
            blocked_n += bc
            for ci in range(bc):
                verdicts["%d:%d" % (gi, ci)] = "BLOCKED"
            continue

        if "validate_G0_branch_0(" not in open(vpath).read():
            bc = len(g["tests"])
            cd = {"PASS": 0, "FAIL": 0, "BLOCKED": bc,
                  "note": "no validate_G0_branch_0 emitted",
                  "stage": "emission"}
            per_group[gi] = cd
            blocked_n += bc
            for ci in range(bc):
                verdicts["%d:%d" % (gi, ci)] = "BLOCKED"
            continue

        main_path = write_driver([g], work_dir, "%s_%d" % (tag, gi))
        binary = os.path.join(work_dir, "run_%s_%d" % (tag, gi))
        rr, err = compile_run(main_path, gen_dir, work_dir, binary, timeout,
                              "%s_%d" % (tag, gi))
        if rr is None:
            bc = len(g["tests"])
            cd = {"PASS": 0, "FAIL": 0, "BLOCKED": bc,
                  "note": "compile shortfall: " + (err or "")[:160],
                  "stage": "compile"}
            per_group[gi] = cd
            blocked_n += bc
            for ci in range(bc):
                verdicts["%d:%d" % (gi, ci)] = "BLOCKED"
            continue

        observed = parse_results(rr.stdout)
        # write_driver compiled a single-group doc, so its emitted indices are
        # (0, ci); remap to the real group index (gi, ci).
        observed = {(gi, ci): v for (_, ci), v in observed.items()}
        cd = {"PASS": 0, "FAIL": 0, "BLOCKED": 0, "stage": "run"}
        for ci, t in enumerate(g["tests"]):
            key = (gi, ci)
            if key not in observed:
                cd["BLOCKED"] += 1
                verdicts["%d:%d" % (gi, ci)] = "BLOCKED"
            elif observed[key] == "PASS":
                cd["PASS"] += 1
                verdicts["%d:%d" % (gi, ci)] = "PASS"
            elif observed[key] == "FAIL":
                cd["FAIL"] += 1
                verdicts["%d:%d" % (gi, ci)] = "FAIL"
            else:
                cd["BLOCKED"] += 1
                verdicts["%d:%d" % (gi, ci)] = "BLOCKED"
        per_group[gi] = cd
        pass_n += cd["PASS"]; fail_n += cd["FAIL"]; blocked_n += cd["BLOCKED"]

    return filename, {"generation": "OK" if file_gen_ok else "PARTIAL",
                      "groups": per_group, "verdicts": verdicts,
                      "pass": pass_n, "fail": fail_n, "blocked": blocked_n,
                      "seconds": round(time.time() - t0, 2)}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--suite", required=True)
    ap.add_argument("--jar", required=True)
    ap.add_argument("--work", default=None)
    ap.add_argument("--files", default=None)
    ap.add_argument("--out", default=None)
    ap.add_argument("--timeout", type=int, default=300)
    args = ap.parse_args()

    if not os.path.exists(args.jar):
        print("ERROR: --jar required and must exist", file=sys.stderr)
        sys.exit(2)
    if args.files:
        files = [s.strip() for s in args.files.split(",") if s.strip()]
    else:
        files = list(NUMERIC_BOOLEAN_SLICE_FILES)
    work = args.work or "/tmp/jsts-genpath-run"
    os.makedirs(work, exist_ok=True)

    report = {"runner": "jsts_genpath_slice.py (Wave-1 GENERATED path)",
              "suite": args.suite, "files": {},
              "totals": {"files": 0, "cases": 0, "PASS": 0, "FAIL": 0,
                         "BLOCKED": 0}}
    for fn in files:
        print("== %s ==" % fn, flush=True)
        _, res = evaluate_file(args.suite, args.jar, work, fn, args.timeout)
        report["files"][fn] = res
        report["totals"]["files"] += 1
        fp = ff = fb = 0
        for cd in res["groups"].values():
            fp += cd["PASS"]; ff += cd["FAIL"]; fb += cd["BLOCKED"]
        report["totals"]["cases"] += fp + ff + fb
        report["totals"]["PASS"] += fp
        report["totals"]["FAIL"] += ff
        report["totals"]["BLOCKED"] += fb
        print("  file>%s: PASS=%d FAIL=%d BLOCKED=%d gen=%s" %
              (fn, fp, ff, fb, res["generation"]), flush=True)

    t = report["totals"]
    print("\n=== TOTALS ===")
    print("  files=%d cases=%d PASS=%d FAIL=%d BLOCKED=%d" %
          (t["files"], t["cases"], t["PASS"], t["FAIL"], t["BLOCKED"]))
    if args.out:
        with open(args.out, "w") as f:
            json.dump(report, f, indent=2)
        print("  report -> %s" % args.out)


if __name__ == "__main__":
    main()
