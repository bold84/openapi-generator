#!/usr/bin/env python3
"""Wave-4.3 GA1 gate: annotation-output semantics for the GENERATED path.

Wraps a demo schema carrying every annotation keyword (meta-data vocab,
format-annotation, content vocab, unknown keyword, and a $comment that MUST
produce no output) through the SAME wrap->generate->compile->run pipeline as
the JSTS corpus, then asserts the collected annotation records:

  keyword | instance JSON-pointer | schema-location (emitter row name) |
  absolute schema-location URI (urn:oas31:res:<resource>) | value (JSON text)

Exit 0 = all records as expected; non-zero = list the failures.
"""
import importlib.util
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SUITE_DIR = os.path.join(HERE, "..")
JAR = os.path.normpath(os.path.join(
    SUITE_DIR, "..", "..", "..", "..", "..", "..", "..", "..",
    "modules", "openapi-generator-cli", "target", "openapi-generator-cli.jar"))
assert os.path.exists(JAR), "jar not built: " + JAR

spec = importlib.util.spec_from_file_location(
    "jsts", os.path.join(HERE, "jsts_genpath_slice.py"))
sl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sl)
LEXEME_SRC = sl.LEXEME_SRC if getattr(sl, "LEXEME_SRC", None) else None

DEMO_SCHEMA = {
    "type": "object",
    "title": "demo title",
    "description": "demo description",
    "$comment": "must never appear in the annotation output",
    "format": "demo-format",
    "default": {"a": 1},
    "examples": [1, "two"],
    "unknownKeyword": {"x": 1},
    "readOnly": True,
    "deprecated": True,
    "writeOnly": False,
    "properties": {
        "name": {"type": "string", "title": "name title", "minLength": 1},
        "payload": {"type": "string", "contentEncoding": "base64",
                    "contentMediaType": "text/plain",
                    "contentSchema": {"type": "string",
                                      "title": "payload cs title"}},
        "level": {"type": "integer", "readOnly": True, "deprecated": True,
                  "writeOnly": False, "default": 3},
    },
    "required": ["name"],
}

INSTANCE = {"name": "x", "payload": "aGk=", "level": 2}

# (keyword, instancePath) pairs that MUST appear (values checked loosely below).
EXPECTED = [
    ("title", ""),
    ("description", ""),
    ("default", ""),
    ("examples", ""),
    ("format", ""),
    ("readOnly", ""),
    ("deprecated", ""),
    ("writeOnly", ""),
    ("unknownKeyword", ""),
    ("title", "/name"),
    ("contentEncoding", "/payload"),
    ("contentMediaType", "/payload"),
    ("contentSchema", "/payload"),
    ("readOnly", "/level"),
    ("deprecated", "/level"),
    ("writeOnly", "/level"),
    ("default", "/level"),
]

DRIVER = r'''
#include <boost/json.hpp>
#include <cstdio>
#include <string>
#include "oas31_object_array.hpp"
#include "oas31_validator.hpp"
#include "schema_ir.generated.hpp"
int main() {
    std::string pl = "%INSTANCE%";
    boost::json::value v = boost::json::parse(pl);
    std::string lx;
    oas31::RawInstance ri(&v, lx);
    oas31::ValidationPath p;
    oas31::ValidationContext c;
    oas31::SchemaEvaluator ev(oas31::schemaRegistry());
    oas31::ValidationResult r = ev.validate(
        oas31::schemaNodeFor("G0_branch_0"), ri, p, c);
    printf("VALIDATION %s\n", r.success ? "PASS" : "FAIL");
    for (oas31::Annotation const& a : c.annotations.all()) {
        printf("ANNOT|%s|%s|%s|%s|%s\n", a.keyword.c_str(),
               a.instancePath.c_str(), a.schemaPath.c_str(),
               a.absSchemaUri.c_str(), a.value.c_str());
    }
    return r.success ? 0 : 1;
}
'''


def main():
    import base64
    import shutil
    import subprocess
    import tempfile

    work = tempfile.mkdtemp(prefix="jsts-ann-gate-")
    groups = [{"schema": DEMO_SCHEMA, "tests": []}]
    spec_path = os.path.join(work, "spec.json")
    with open(spec_path, "w") as f:
        json.dump(sl.wrap_spec(groups), f)
    gen_dir = os.path.join(work, "gen")
    r = sl.generate(JAR, spec_path, gen_dir)
    if r.returncode != 0:
        print("GENERATION FAILED", file=sys.stderr)
        print(r.stderr[-1200:], file=sys.stderr)
        return 2

    payload = json.dumps(INSTANCE, separators=(",", ":"))
    payload_c = payload.replace("\\", "\\\\").replace('"', '\\"')
    if not os.path.exists(os.path.join(work, "oas31_lexeme.hpp")):
        src = LEXEME_SRC
        if src and os.path.exists(src):
            shutil.copy(src, os.path.join(work, "oas31_lexeme.hpp"))
    main_path = os.path.join(work, "main.cpp")
    with open(main_path, "w") as f:
        f.write(DRIVER.replace("%INSTANCE%", payload_c))
    binary = os.path.join(work, "run")
    rr, err = sl.compile_run(
        main_path, gen_dir, work, binary, 300, "ann-gate")
    if rr is None:
        print("COMPILE FAILED", file=sys.stderr)
        print((err or "")[-1200:], file=sys.stderr)
        return 2

    records = []
    found = {}
    for ln in rr.stdout.splitlines():
        if ln.startswith("ANNOT|"):
            parts = ln.split("|", 5)  # ANNOT|kw|ip|sp|uri|value
            rec = parts[1:]
            records.append(rec)
            found.setdefault((rec[0], rec[1]), []).append(rec)
        elif ln.startswith("VALIDATION"):
            print(ln.strip())

    problems = []
    for kw, ip in EXPECTED:
        hits = found.get((kw, ip))
        if not hits:
            problems.append("MISSING %s @ %s" % (kw, ip))
            continue
        rec = hits[0]
        if not rec[2]:
            problems.append("EMPTY schemaPath %s @ %s" % (kw, ip))
        if not rec[3].startswith("urn:oas31:res:"):
            problems.append("BAD absSchemaUri %r @ %s" % (rec[3], ip))
        if not rec[4]:
            problems.append("EMPTY value %s @ %s" % (kw, ip))
    # $comment MUST produce nothing.
    for kw, ip, sp, uri, val in records:
        if kw == "$comment" or kw == "comment":
            problems.append("$comment LEAKED: %s @ %s" % (kw, ip))
    # unknown keyword must carry the literal JSON value.
    for rec in found.get(("unknownKeyword", ""), []):
        if rec[4] != '{"x":1}':
            problems.append("unknownKeyword value %r" % rec[4])
    # contentSchema must carry its child's schema-location as the value
    # (annotation-only: the child is NEVER evaluated against the instance).
    for rec in found.get(("contentSchema", "/payload"), []):
        if "contentSchema" not in rec[4]:
            problems.append("contentSchema value %r" % rec[4][:40])

    for kw, ip, sp, uri, val in records:
        print("  %-18s @ %-8s value=%-22s schema=%s uri=%s"
              % (kw, ip, val[:22], sp[:40], uri))
    if problems:
        print("FAIL (" + str(len(problems)) + "):")
        for p in problems:
            print("  - " + p)
        return 1
    print("GA1 GATE PASS (%d annotation records, $comment silent)"
          % len(records))
    return 0


if __name__ == "__main__":
    sys.exit(main())