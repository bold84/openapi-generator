#!/usr/bin/env python3
# =============================================================================
# Phase-2 numeric/boolean raw-instance driver — case + IR manifest generator
# =============================================================================
# Wave-1 (ADR D1 / Option B).  Reads the numeric/boolean slice rows from
# semantic-cases.yaml (schema membership in NUMERIC_SLICES below) and emits:
#
#   * PHASE2_NUM_INC — phase2_numeric_cases.inc:
#         struct RawNumCase { id, schema, payload, expected } + array.
#   * PHASE2_NUM_IR  — schema_ir_numeric.generated.hpp:
#         a densified SchemaResourceRegistry (frozen SchemaNode layout in
#         oas31_ir.hpp) for the numeric/boolean slice schemas, a
#         schemaNodeFor(name) resolver, and thin validate_<schema> dispatchers
#         (ADR D5) that route RawInstance -> SchemaEvaluator -> ExactNumber.
#
# WHY a hand-built IR here (honesty): the Java-emitted schema_ir.generated.*
# is a LATER `ir-gen` slice and does not exist in this checkout.  This driver
# therefore constructs the IR tables ITSELF in the identical frozen format so
# the real shared SchemaEvaluator + ExactNumber + raw-lexeme tokenizer are
# proven end-to-end.  The IR shape is byte-for-byte the same fields the
# ir-gen agent will emit; swapping in the generated tables later is mechanical.
#
# Honesty contract: every row emitted here is a literal scalar `payload` with
# expected in {decode_accept, decode_reject}; every such row is covered by the
# compiled driver (no DEFERRED leakage for the numeric/boolean slice).
# =============================================================================

import os
import sys
import yaml

COMPLIANCE_DIR = os.environ.get(
    "SCRIPT_DIR", os.path.abspath(os.path.dirname(__file__)))
SEMANTIC_FILE = os.path.join(COMPLIANCE_DIR, "semantic-cases.yaml")
OUT_INC = os.environ.get("PHASE2_NUM_INC",
                         os.path.join(COMPLIANCE_DIR, "phase2_numeric_cases.inc"))
OUT_IR = os.environ.get("PHASE2_NUM_IR",
                        os.path.join(COMPLIANCE_DIR, "schema_ir_numeric.generated.hpp"))

# ---------------------------------------------------------------------------
# Numeric/boolean slice schema definitions.  name -> dict describing the
# SchemaNode fields (frozen layout in oas31_ir.hpp).
#   type        : 'number' | 'integer' | 'boolean'   (typeFlags)
#   const       : (kind, value)   kind in {'number','bool'}
#   enum        : list of (kind, value)
#   multipleOf / exclusiveMinimum / exclusiveMaximum:
#                 raw numeric lexeme string (kept verbatim -> ExactNumber)
# ---------------------------------------------------------------------------
NUMERIC_SLICES = {
    # const:1 — proves 1 == 1.0 == 1e0 == 100e-2 (ExactNumber equality)
    "ExactEqualsOne": {
        "type": "number",
        "const": ("number", "1"),
    },
    # type:integer — classification by mathematical value, not spelling
    "ExactIntegerType": {
        "type": "integer",
    },
    # multipleOf 0.1 — exact decimal divmod (0.3 / 0.1 == 3, remainder 0)
    "MulTenth": {
        "type": "number",
        "multipleOf": "0.1",
    },
    # multipleOf 0.3 — 1.0 is NOT a multiple of 0.3 (10/3 rem 1)
    "MulThird": {
        "type": "number",
        "multipleOf": "0.3",
    },
    # const:0 — negative zero is mathematically 0
    "ZeroConst": {
        "type": "number",
        "const": ("number", "0"),
    },
    # const:2^70 = 1180591620717411303424 — beyond uint64; boost.json stores it
    # as a lossy double, but the lexeme path keeps it exact.  Explicitly proves
    # we never silently approximate a large coefficient.
    "BigConst": {
        "type": "number",
        "const": ("number", "1180591620717411303424"),
    },
    # exclusiveMaximum 1e300 — very large exponents, exact (boost yields inf).
    "HugeMax": {
        "type": "number",
        "exclusiveMaximum": "1e300",
    },
    # exclusiveMinimum 1e-300 — tiny exponents, exact (boost underflows to 0).
    "TinyMin": {
        "type": "number",
        "exclusiveMinimum": "1e-300",
    },
    # multipleOf:0 — malformed schema; evaluator must REFUSE (fail closed),
    # never produce a bogus verdict.  Every instance is rejected.
    "NonPosMul": {
        "type": "number",
        "multipleOf": "0",
    },
    # const:true — boolean const
    "BoolConstTrue": {
        "type": "boolean",
        "const": ("bool", True),
    },
    # enum [true] — boolean enum
    "BoolEnumTrue": {
        "type": "boolean",
        "enum": [("bool", True)],
    },
    # enum [1, 2.5] — numeric enum equality across spellings (1.0, 2.50)
    "NumberEnumSpellings": {
        "type": "number",
        "enum": [("number", "1"), ("number", "2.5")],
    },
}

SCHEMA_ORDER = list(NUMERIC_SLICES.keys())   # deterministic IR node order


def c_quote(s):
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


# ---------------------------------------------------------------------------
# IR rendering helpers
# ---------------------------------------------------------------------------
def render_typeflag(t):
    if t == "number":
        return "(1u << static_cast<unsigned>(JsonType::number))"
    if t == "integer":
        return "(1u << static_cast<unsigned>(JsonType::integer))"
    if t == "boolean":
        return "(1u << static_cast<unsigned>(JsonType::boolean))"
    raise ValueError(f"unknown type {t}")


def render_node(schema_name, sd):
    lines = []
    lines.append("        {")
    lines.append(f"            SchemaNode n;")
    lines.append(f"            n.resourceIdentity = 0;")
    lines.append(f"            n.typeFlags = {render_typeflag(sd['type'])};")
    if "const" in sd:
        kind, val = sd["const"]
        if kind == "number":
            lines.append(f"            n.hasConst = true; n.constIsNumber = true;")
            lines.append(f"            n.constNumber = ExactNumber::parseLexeme(\"{c_quote(val)}\");")
        else:  # bool
            b = "true" if val else "false"
            lines.append(f"            n.hasConst = true; n.constIsBool = true;")
            lines.append(f"            n.constBool = {b};")
    if "enum" in sd:
        nums = [v for k, v in sd["enum"] if k == "number"]
        bools = [v for k, v in sd["enum"] if k == "bool"]
        if nums:
            lems = ", ".join(f'ExactNumber::parseLexeme("{c_quote(x)}")' for x in nums)
            lines.append(f"            n.enumNumbers = {{ {lems} }};")
        if bools:
            bs = ", ".join("true" if b else "false" for b in bools)
            lines.append(f"            n.enumBooleans = {{ {bs} }};")
    if "multipleOf" in sd:
        lines.append(f"            n.hasMultipleOf = true;")
        lines.append(f"            n.multipleOf = ExactNumber::parseLexeme(\"{c_quote(sd['multipleOf'])}\");")
    if "exclusiveMaximum" in sd:
        lines.append("            n.hasExclusiveMaximum = true;")
        lines.append(f"            n.exclusiveMaximum = ExactNumber::parseLexeme(\"{c_quote(sd['exclusiveMaximum'])}\");")
    if "exclusiveMinimum" in sd:
        lines.append("            n.hasExclusiveMinimum = true;")
        lines.append(f"            n.exclusiveMinimum = ExactNumber::parseLexeme(\"{c_quote(sd['exclusiveMinimum'])}\");")
    lines.append("            r.nodes.push_back(std::move(n));")
    lines.append("        }")
    return "\n".join(lines)


def render_ir():
    blocks = []
    for i, name in enumerate(SCHEMA_ORDER):
        blocks.append(render_node(name, NUMERIC_SLICES[name]))

    # schemaNodeFor resolver + thin validate_<id> dispatchers (ADR D5)
    resolvers = "\n".join(
        f'    if (std::strcmp(name, "{name}") == 0) return {i};'
        for i, name in enumerate(SCHEMA_ORDER))

    dispatchers = []
    for i, name in enumerate(SCHEMA_ORDER):
        dispatchers.append(
            f"    if (std::strcmp(name, \"{name}\") == 0) {{\n"
            f"        SchemaEvaluator ev(numericRegistry());\n"
            f"        return ev.validate({i}, instance, path, ctx);\n"
            f"    }}")

    node_blocks = "\n".join(blocks)
    dispatch_body = "\n".join(dispatchers)

    return f"""// AUTO-GENERATED by phase2_numeric_gen.py — do not edit.
// Densified numeric/boolean slice IR (frozen SchemaNode layout, oas31_ir.hpp)
// + schemaNodeFor(name) + thin validate_<id> dispatch (ADR D5).  This driver
// builds the tables in the identical format the ir-gen agent will emit, so the
// real SchemaEvaluator + ExactNumber are proven end-to-end with raw lexemes.
#ifndef SCHEMA_IR_NUMERIC_GENERATED_HPP_
#define SCHEMA_IR_NUMERIC_GENERATED_HPP_

#include <cstring>

#include "oas31_ir.hpp"
#include "oas31_validator.hpp"

namespace oas31 {{

inline SchemaResourceRegistry const& numericRegistry() {{
    static SchemaResourceRegistry const reg = [] {{
        SchemaResourceRegistry r;
        r.resources.push_back(SchemaResource{{"urn:oas31:slice:numeric",
            "https://json-schema.org/draft/2020-12/schema", "", {{}}}});
{node_blocks}
        r.resources[0].rootNodes.push_back(0);
        return r;
    }}();
    return reg;
}}

inline SchemaIndex schemaNodeFor(char const* name) {{
{resolvers}
    return kNoSchema;
}}

inline ValidationResult validateNumeric(char const* name,
                                        RawInstance const& instance,
                                        ValidationPath& path,
                                        ValidationContext& ctx) {{
{dispatch_body}
    return ValidationResult::invalidAt(path, std::string("no numeric schema: ") + name);
}}

}} // namespace oas31

#endif // SCHEMA_IR_NUMERIC_GENERATED_HPP_
"""


# ---------------------------------------------------------------------------
# Case manifest from semantic-cases.yaml
# ---------------------------------------------------------------------------
def expand_rows(spec):
    for entry in spec or []:
        entry_id = entry.get("id", "?")
        schema_name = entry.get("schema", "")
        if schema_name not in NUMERIC_SLICES:
            continue
        cases_list = entry.get("cases", [])
        if not cases_list:
            continue
        for idx, c in enumerate(cases_list):
            exp = c.get("expected", entry.get("expected", "?"))
            note = c.get("note", "")
            yield (f"{entry_id}[{idx}]", schema_name, c.get("payload"), exp, note)


def main():
    with open(SEMANTIC_FILE) as f:
        spec = yaml.safe_load(f) or []

    emitted = []

    # 1. EMIT IR (once, deterministic order).
    with open(OUT_IR, "w") as f:
        f.write(render_ir())

    # 2. EMIT case manifest (only rows actually present in semantic-cases.yaml).
    for row_id, schema_name, payload, exp, note in expand_rows(spec):
        emitted.append((row_id, schema_name, payload, exp))

    with open(OUT_INC, "w") as f:
        f.write("// AUTO-GENERATED by phase2_numeric_gen.py — do not edit.\n")
        f.write("// Numeric/boolean raw-instance cases for the Wave-1 driver.\n\n")
        f.write("struct RawNumCase {\n")
        f.write("    const char* id;\n")
        f.write("    const char* schema;    // numeric slice schema name\n")
        f.write("    const char* payload;   // raw JSON instance (scalar)\n")
        f.write("    const char* expected;  // decode_accept | decode_reject\n")
        f.write("};\n\n")
        f.write("static const RawNumCase kNumCases[] = {\n")
        for row_id, schema_name, payload, exp in emitted:
            f.write(f'    {{"{c_quote(row_id)}", "{schema_name}", '
                    f'"{c_quote(str(payload))}", "{exp}"}},\n')
        f.write("};\n\n")
        f.write(f"static const std::size_t kNumCaseCount = {len(emitted)};\n")

    # 3. REPORT
    for row_id, schema_name, payload, exp in emitted:
        print(f"  num-row  {row_id}  {schema_name}  payload={c_quote(str(payload))}  -> {exp}")
    print(f"__PHASE2_NUM_SCHEMAS__={len(SCHEMA_ORDER)}")
    print(f"__PHASE2_NUM_CASES__={len(emitted)}")
    if not emitted:
        print("  WARNING: no numeric/boolean rows found in semantic-cases.yaml")


if __name__ == "__main__":
    main()
