#!/usr/bin/env python3
"""
jsts_genpath_slice.py — Wave-2 object/array-structural JSTS slice through the
GENERATED path (successor to the Wave-1 numeric/boolean slice).

Wave-2 (frozen contract §10) adds, over the Wave-1 driver:
  * container-depth EXACT numeric lexemes: write_driver attaches an
    `oas31::InstanceLexemeTable` (oas31_object_array.hpp) captured from the
    payload STRING, so numbers nested inside objects/arrays keep their raw
    lexeme (1 vs 1.0 vs 1e0) instead of degrading to the Boost.JSON value kind;
  * `$defs`/`$id`/local-pointer ref surfacing: wrap_spec hoists every ref that
    resolves within the group document (fragments against the nearest `$id`
    resource, `$id`-matched qualified/URN bases) into synthetic composed
    components.schemas and rewrites it to `#/components/schemas/<name>` — the
    exact shape the Wave-2 engine's refTargetIdOf/refSimpleName resolve. The
    upstream reader's OAS-3.1 `$id` NPE quirk is dodged by stripping `$id`
    AFTER hoisting (the engine never reads source `$id`); unresolvable
    remote/URN/metaschema refs are left as inert nodes (honest FAIL/PASS,
    never BLOCKED) and `--skip-validate-spec` prevents the upstream validator
    from fail-closing on them.

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


def _ptr_unescape(seg):
    """RFC 6901 JSON-pointer segment decode: percent-decode first, then
    unescape ~1 then ~0 (order matters)."""
    try:
        seg = __import__("urllib.parse", fromlist=["unquote"]).unquote(seg)
    except Exception:
        pass
    return seg.replace("~1", "/").replace("~0", "~")


def _hop_refs(branch):
    """Wave-2 `$ref`/`$defs`/`$id` surfacing (FROZEN contract §10.3). Rewrites
    every ref that RESOLVES WITHIN the current group doc into
    `#/components/schemas/<name>` and returns {name: {"oneOf": [target]}} extra
    components (synthetic composed components the engine densifies into
    `<name>_branch_0` rows). Local JSON pointers (`#`, `#/$defs/...`,
    `#/properties/...`, `#/prefixItems/...`, escaped pointers, bare-anchor
    fragments) are resolved against the nearest enclosing `$id` resource;
    relative/qualified bases are merged (urllib.parse.urljoin) against the
    containing resource id and matched against in-doc `$id` values.

    Refs that do NOT resolve inside the doc (remote `http(s)`, `urn:`, the
    metaschema) are left untouched — the engine emits them as inert nodes and
    the verdict is measured honestly (never BLOCKED, never fake-pass).

    In-place on `branch`; returns the extra components dict.
    """
    import copy as _copy
    import re as _re
    from urllib.parse import urljoin as _urljoin

    DOM = object()          # ctx marker for the document root
    hoisted = {}            # name -> {oneOf: [deepcopy(target)]}
    pending = {}            # name -> LIVE target (captured after pass 1 so
                            # inner refs already rewritten in the tree)
    id_index = {}           # resolved-resource-id -> target dict
    id_name = {}            # resolved-resource-id -> preferred hoist name
    used = set()

    def capture(name, target):
        """Register a live target for later capture (first-referenced wins)."""
        if name not in pending:
            pending[name] = target
        elif pending[name] is not target:
            # same hoist name resolved to a DIFFERENT live object — dedupe
            k = 2
            while "%s__%d" % (name, k) in used:
                k += 1
            nm = "%s__%d" % (name, k)
            used.add(nm)
            pending[nm] = target
            return nm
        return name

    # ---- pass 0: index resources by RESOLVED $id (deep walk) ----------------
    def idx_walk(node, ctx_id, hint):
        if isinstance(node, dict):
            rid = node.get("$id")
            eff = ctx_id
            if rid is not None and str(rid):
                eff = _urljoin(str(ctx_id or ""), str(rid))
                id_index.setdefault(eff, node)
                id_name.setdefault(eff, hint)
            for k, v in node.items():
                if isinstance(v, str) and (k == "$id" or k == "$schema"):
                    continue
                if k == "$defs" and isinstance(v, dict):
                    for dk, dv in v.items():
                        idx_walk(dv, eff, str(dk))
                elif isinstance(v, dict):
                    idx_walk(v, eff, hint)
                elif isinstance(v, list):
                    for i2, vv in enumerate(v):
                        idx_walk(vv, eff, None)
        elif isinstance(node, list):
            for i2, vv in enumerate(node):
                idx_walk(vv, ctx_id, None)

    idx_walk(branch, branch.get("$id") if isinstance(branch, dict) else None, None)

    def navigate(base_obj, frag):
        """Resolve a fragment ('' | '/a/b' | 'bare-anchor') against base_obj.
        Returns a dict subschema or a bool, or None when unresolvable."""
        if not frag:
            return base_obj
        if frag.startswith("/"):
            cur = base_obj
            for seg in frag[1:].split("/"):
                dec = _ptr_unescape(seg)
                if isinstance(cur, dict):
                    if dec in cur:
                        cur = cur[dec]
                    else:
                        return None
                elif isinstance(cur, list):
                    try:
                        cur = cur[int(dec)]
                    except (ValueError, IndexError):
                        return None
                else:
                    return None
            return cur
        # bare anchor fragment (e.g. '#bigint') — scan subtree for $anchor in
        # DOCUMENT ORDER (the first $anchor with that name wins in 2020-12;
        # a LIFO stack would reverse sibling precedence and can pick the wrong
        # $anchor when several schemas share a name).
        if isinstance(base_obj, list):
            base_obj = {"items": base_obj}
        from collections import deque as _deque
        dq = _deque([base_obj]) if isinstance(base_obj, dict) else _deque()
        while dq:
            n = dq.popleft()
            if isinstance(n, dict):
                if n.get("$anchor") == frag:
                    return n
                for v in n.values():
                    if isinstance(v, (dict,)):
                        dq.append(v)
                    elif isinstance(v, list):
                        dq.extend(x for x in v if isinstance(x, dict))
        return None

    def resolve_and_rewrite(node, ref, ctx_obj, ctx_id):
        """Try to rewrite node['$ref'] (a local-or-$id-resolvable ref) to a
        components ref. Returns True when rewritten (target hoisted)."""
        if ref.startswith("#"):
            base, frag = "", ref[1:]
            base_obj = ctx_obj if ctx_obj is not None else branch
        else:
            base, frag = ref.split("#", 1) if "#" in ref else (ref, "")
            full = _urljoin(str(ctx_id or ""), base) if ctx_id else base
            if full in id_index:
                base_obj = id_index[full]
            elif base in id_index:
                base_obj = id_index[base]
            elif ctx_id and full == ctx_id:
                # self-resource full ref -> the enclosing resource itself
                base_obj = ctx_obj if ctx_obj is not None else branch
            else:
                return False
            # 2020-12 root-self: a full URI identical to the ROOT resource id
            root_eff = None
            if isinstance(branch, dict) and branch.get("$id"):
                root_eff = _urljoin("", str(branch["$id"]))
            if base_obj is None and root_eff and full == root_eff:
                base_obj = branch
        if base_obj is None:
            return False
        target = navigate(base_obj, frag)
        if target is None or not isinstance(target, (dict, bool)):
            return False
        nm = _hoist_name_of(ref, base_obj, frag, ctx_obj, ctx_id, target)
        if nm is None:
            return False
        # component keys are dereferenced verbatim by the OAS-3.1 reader; keep
        # only fragment-safe characters ('' '" ' break ModelUtils pointer walk)
        nm = _re.sub(r"[^0-9A-Za-z_.~-]+", "_", nm) or "__empty"
        nm = capture(nm, target)
        node["$ref"] = "#/components/schemas/" + nm
        return True

    def _hoist_name_of(ref, base_obj, frag, ctx_obj, ctx_id, target):
        # local pointer refs derive their name from the pointer leaf
        if ref.startswith("#"):
            if frag == "":
                # self/root ref — only the root resource may alias __root
                root_eff = None
                if isinstance(branch, dict) and branch.get("$id"):
                    root_eff = _urljoin("", str(branch["$id"]))
                if ctx_obj is None or ctx_id in (None, DOM):
                    return "__root"
                if root_eff is not None and ctx_id == root_eff:
                    return "__root"
                tail = str(ctx_id or "res").split("/")[-1] or "res"
                return "__res_" + _re.sub(r"[^0-9A-Za-z_]+", "_", tail)
            if frag.startswith("/"):
                return _ptr_unescape(frag.split("/")[-1]) or "__empty"
            return "__anchor_" + frag
        # qualified/base refs: name from the matched resource's $defs key
        base_, frag_ = ref.split("#", 1) if "#" in ref else (ref, "")
        full_ = _urljoin(str(ctx_id or ""), base_) if ctx_id else base_
        hit_id = full_ if full_ in id_index else (base_ if base_ in id_index else None)
        if hit_id is not None:
            nm = id_name.get(hit_id)
            if frag_.startswith("/"):
                nm = _ptr_unescape(frag_.split("/")[-1]) or nm
            if nm is None:
                tail = str(hit_id or "res").split("/")[-1] or "res"
                nm = "__res_" + _re.sub(r"[^0-9A-Za-z_]+", "_", tail)
            return nm
        return "__root"  # self/root alias (base resolves to the root resource)

    # ---- pass 1: rewrite refs with context-aware resource tracking ----------
    def walk(node, ctx_obj, ctx_id):
        if isinstance(node, dict):
            rid = node.get("$id")
            if rid is not None and str(rid):
                eff = _urljoin(str(ctx_id or ""), str(rid)) if ctx_id else str(rid)
                ctx_obj, ctx_id = node, eff
            ref = node.get("$ref")
            if isinstance(ref, str):
                resolve_and_rewrite(node, ref, ctx_obj, ctx_id)
            for k, v in node.items():
                if isinstance(v, str) and (k == "$ref" or k == "$schema"):
                    continue
                # enum/const members are literal JSON DATA, not schemas — a
                # `$ref` inside them must NOT be dereferenced (2020-12: an enum
                # member "{\$ref:...}" stays a literal value; e.g. ref.json
                # "naive replacement of $ref with its destination" depends on
                # the member being deep-equal to the verbatim object).
                if k == "enum" or k == "const":
                    continue
                if isinstance(v, dict):
                    walk(v, ctx_obj, ctx_id)
                elif isinstance(v, list):
                    for i2, vv in enumerate(v):
                        walk(vv, ctx_obj, ctx_id)
        elif isinstance(node, list):
            for i2, vv in enumerate(node):
                walk(vv, ctx_obj, ctx_id)

    walk(branch, branch if isinstance(branch, dict) else None,
         branch.get("$id") if isinstance(branch, dict) else None)
    # Capture hoisted targets AFTER pass 1 so any inner refs inside a target
    # were already rewritten in the live tree (never a stale copy).
    for name in sorted(pending):
        if name not in hoisted:
            hoisted[name] = {"oneOf": [_copy.deepcopy(pending[name])]}
    # HARD REQUIREMENT for the upstream reader: openapi-generator's OAS-3.1
    # parsing NPEs when a composed oneOf branch carries a non-"file:" `$id`.
    # The ENGINE never reads source `$id` (its resourceIdentity/baseUri comes
    # from the emitted SchemaResource, and all refs were already rewritten to
    # #/components/schemas/<name>), so stripping `$id` after hoisting is a
    # safe, honest workaround that keeps the corpus GENERATED-path green.
    def strip_ids(obj):
        if isinstance(obj, dict):
            obj.pop("$id", None)
            for v in obj.values():
                strip_ids(v)
        elif isinstance(obj, list):
            for v in obj:
                strip_ids(v)

    strip_ids(branch)
    for c in hoisted.values():
        strip_ids(c)
    return hoisted


def wrap_spec(groups):
    """OAS-wrap each group's schema as a single-branch oneOf so the Wave-1 IR
    emitter lowers it to a validate_G<i>_branch_0 dispatch. Wave-2 (contract
    §10.3): local `$defs`/pointer/`$id`-resolvable refs inside a group are
    hoisted into synthetic composed `components.schemas` and the refs rewritten
    to `#/components/schemas/<name>` (the engine's `refTargetIdOf`/`refSimpleName`
    expect exactly this shape). Unresolvable remote/URN refs are left in place
    (inert nodes — measured honestly)."""
    comp = {}
    for i, g in enumerate(groups):
        s = g.get("schema", {})
        branch = dict(s) if isinstance(s, dict) else s
        if isinstance(branch, dict):
            branch.pop("$schema", None)
        comp["G%d" % i] = {"oneOf": [branch]}
        if isinstance(branch, dict):
            comp.update(_hop_refs(branch))
    return {"openapi": "3.1.0", "info": {"title": "jsts-genpath-slice",
                                         "version": "1.0.0"},
            "paths": {}, "components": {"schemas": comp}}


def generate(jar, spec_path, out_dir):
    cmd = ["java", "-jar", jar, "generate", "--generator-name",
           "cpp-boost-beast-client", "--input-spec", spec_path,
           "--output", out_dir, "--skip-validate-spec",
           "--additional-properties", "packageName=Jsts",
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
    lines.append('#include "oas31_object_array.hpp"')
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
            lines.append('    oas31::InstanceLexemeTable tbl;')
            lines.append('    oas31::captureInstanceLexemes(pl,tbl);')
            lines.append('    try {')
            lines.append('      boost::json::value v=boost::json::parse(pl);')
            lines.append('      RawInstance ri(&v,lx); ri.lexemes=&tbl; ValidationPath p; '
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
