// =============================================================================
// Phase-2 GENERATED-PATH integration driver — Wave-1 numeric/boolean slice.
// =============================================================================
// This driver PROVES the ADR Option-B GENERATED path, not just the custom
// driver.  It compiles + links the artifacts actually emitted by the REAL
// generator (CppBoostBeastClientCodegen) from the committed OAS 3.1 doc
// `oas31-generated-path-regression.yaml`:
//
//     model/schema_ir.generated.hpp   -> densified SchemaResourceRegistry
//     model/schema_ir.generated.cpp   -> table storage + schemaNodeFor()
//     model/schema_validate.generated.cpp -> thin validate_<id> dispatch (D5)
//     model/oas31_{exact_number,ir,validator}.hpp -> shared engine (header-only)
//     oas-compliance/oas31_lexeme.hpp  -> raw number-lexeme tokenizer
//
// Each numeric/boolean case:
//   1. captures the EXACT numeric lexeme from the raw payload string (before
//      boost::json canonicalizes it to int64/uint64/double),
//   2. parses the payload with boost::json purely as the typed/transport view,
//   3. builds a RawInstance(value, lexeme) so asExactNumber() is exact,
//   4. dispatches through the GENERATED validate_<Schema>_branch_0 symbol
//      (the exact function emitted by the generator — not a hand-written
//      validateNumeric helper),
//   5. compares the accept/reject verdict to the expected value and reports
//      PASS/FAIL with the case totals __WIRE_GEN_PASS__ / __WIRE_GEN_FAIL__.
//
// The generated .cpp files are produced by running the real generator and are
// NOT committed (gitignored); the generator emits them deterministically from
// the committed OAS 3.1 doc.  gate-generated-path.sh orchestrates generate +
// compile + run.  -Werror clean, C++17, Boost header-only.
// =============================================================================

#include <boost/json.hpp>

#include <cstddef>
#include <cstdio>
#include <cstring>
#include <exception>
#include <iostream>
#include <string>

#include "oas31_lexeme.hpp"
#include "oas31_validator.hpp"   // RawInstance / ValidationPath / ValidationContext
#include "schema_ir.generated.hpp"

using oas31::RawInstance;
using oas31::ValidationContext;
using oas31::ValidationPath;
using oas31::ValidationResult;

// Forward declarations of the GENERATED thin dispatch entry points.  The real
// generator emits these at GLOBAL scope (not inside namespace oas31) in
// schema_validate.generated.cpp; here we reference exactly the emitted symbols
// so the GENERATED path is what actually links and runs.
oas31::ValidationResult validate_ExactEqualsOne_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_ExactIntegerType_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_MulTenth_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_MulThird_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_RangeMinMax_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_ZeroConst_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_BigConst_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_HugeMax_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_TinyMin_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_BoolConstTrue_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_BoolEnumTrue_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_NumberEnumSpellings_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);

/// A single raw-instance case: schema target + raw scalar payload + expected.
struct WireGenCase {
    const char* id;
    const char* schema;    // generated schema name -> validate_<schema>_branch_0
    const char* payload;   // raw JSON scalar instance
    bool        expectAccept;
};

static const WireGenCase kCases[] = {
    // -- const:1 — 1 == 1.0 == 1e0 == 100e-2 (ExactNumber equality) --
    {"exact-equals-one[0]", "ExactEqualsOne", "1", true},
    {"exact-equals-one[1]", "ExactEqualsOne", "1.0", true},
    {"exact-equals-one[2]", "ExactEqualsOne", "1e0", true},
    {"exact-equals-one[3]", "ExactEqualsOne", "100e-2", true},
    {"exact-equals-one[4]", "ExactEqualsOne", "2", false},
    // -- type:integer — classification by mathematical value, not spelling --
    {"exact-integer-type[0]", "ExactIntegerType", "1", true},
    {"exact-integer-type[1]", "ExactIntegerType", "1.0", true},
    {"exact-integer-type[2]", "ExactIntegerType", "-3.0", true},
    {"exact-integer-type[3]", "ExactIntegerType", "1.5", false},
    // -- multipleOf 0.1 — exact decimal divmod (0.3/0.1 == 3 rem 0) --
    {"mult-tenth[0]", "MulTenth", "0.3", true},
    {"mult-tenth[1]", "MulTenth", "0.7", true},
    {"mult-tenth[2]", "MulTenth", "0.25", false},
    // -- multipleOf 0.3 — 1.0 is NOT a multiple of 0.3 (10/3 rem 1) --
    {"mult-third[0]", "MulThird", "0.9", true},
    {"mult-third[1]", "MulThird", "1.0", false},
    // -- minimum/maximum — non-exclusive inclusive bounds --
    {"range[0]", "RangeMinMax", "5", true},
    {"range[1]", "RangeMinMax", "10", true},
    {"range[2]", "RangeMinMax", "7.5", true},
    {"range[3]", "RangeMinMax", "4.999", false},
    {"range[4]", "RangeMinMax", "10.001", false},
    {"range[5]", "RangeMinMax", "1e400", false},
    // -- const:0 — negative zero is mathematically 0 --
    {"zero-const[0]", "ZeroConst", "-0", true},
    {"zero-const[1]", "ZeroConst", "0", true},
    {"zero-const[2]", "ZeroConst", "0.0", true},
    {"zero-const[3]", "ZeroConst", "1", false},
    // -- const:2^70 — beyond uint64; lexeme path keeps it exact --
    {"big-const-exact[0]", "BigConst", "1180591620717411303424", true},
    {"big-const-exact[1]", "BigConst", "1180591620717411303425", false},
    // -- exclusiveMaximum 1e300 — huge exponent, exact (double gives inf) --
    {"huge-max-exact[0]", "HugeMax", "1e400", false},
    {"huge-max-exact[1]", "HugeMax", "5e299", true},
    // -- exclusiveMinimum 1e-300 — tiny exponent, exact (double underflows) --
    {"tiny-min-exact[0]", "TinyMin", "1e-400", false},
    {"tiny-min-exact[1]", "TinyMin", "5e-200", true},
    // -- const:true — boolean const --
    {"bool-const-true[0]", "BoolConstTrue", "true", true},
    {"bool-const-true[1]", "BoolConstTrue", "false", false},
    // -- enum [true] — boolean enum; null is not equal to true --
    {"bool-enum-true[0]", "BoolEnumTrue", "true", true},
    {"bool-enum-true[1]", "BoolEnumTrue", "false", false},
    {"bool-enum-true[2]", "BoolEnumTrue", "null", false},
    // -- enum [1, 2.5] — numeric enum equality across spellings --
    {"number-enum-spellings[0]", "NumberEnumSpellings", "1", true},
    {"number-enum-spellings[1]", "NumberEnumSpellings", "1.0", true},
    {"number-enum-spellings[2]", "NumberEnumSpellings", "2.50", true},
    {"number-enum-spellings[3]", "NumberEnumSpellings", "3", false},
};

static const std::size_t kCaseCount =
    sizeof(kCases) / sizeof(kCases[0]);

// Dispatch to the GENERATED validate_<id> entry point for a schema name.
// Returns true on accept, false on reject.  Uses ONLY the emitted symbols.
static bool generatedDispatch(char const* schema, RawInstance const& ri,
                              ValidationPath& path, ValidationContext& ctx,
                              std::string& detail) {
    ValidationResult res;
    if (std::strcmp(schema, "ExactEqualsOne") == 0)
        res = validate_ExactEqualsOne_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "ExactIntegerType") == 0)
        res = validate_ExactIntegerType_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "MulTenth") == 0)
        res = validate_MulTenth_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "MulThird") == 0)
        res = validate_MulThird_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "RangeMinMax") == 0)
        res = validate_RangeMinMax_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "ZeroConst") == 0)
        res = validate_ZeroConst_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "BigConst") == 0)
        res = validate_BigConst_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "HugeMax") == 0)
        res = validate_HugeMax_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "TinyMin") == 0)
        res = validate_TinyMin_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "BoolConstTrue") == 0)
        res = validate_BoolConstTrue_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "BoolEnumTrue") == 0)
        res = validate_BoolEnumTrue_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "NumberEnumSpellings") == 0)
        res = validate_NumberEnumSpellings_branch_0(ri, path, ctx);
    else {
        detail = "unknown generated schema: " + std::string(schema);
        return false;
    }
    if (res.success) {
        detail = "accept";
        return true;
    }
    detail = "reject (" + res.failureMessage + ")";
    return false;
}

int main(int argc, char** argv) {
    const char* outTsv = (argc > 1) ? argv[1] : "semantic-resolved.tsv";
    std::FILE* tsv = std::fopen(outTsv, "a");
    if (!tsv) {
        std::cerr << "cannot open " << outTsv << "\n";
        return 2;
    }

    int pass = 0;
    int fail = 0;

    for (std::size_t i = 0; i < kCaseCount; ++i) {
        WireGenCase const& c = kCases[i];
        std::string pl(c.payload);

        // 1. Capture the raw numeric lexeme from the payload STRING.
        std::string lexeme;
        if (!oas31::captureLeadingNumberLexeme(pl, lexeme)) {
            lexeme.clear();  // non-number root (null/bool): no lexeme
        }

        // 2. Typed/transport view (representability gate only).
        boost::json::value v;
        try {
            v = boost::json::parse(pl);
        } catch (std::exception const& e) {
            std::cerr << "JSON parse failed for " << c.id << ": " << e.what() << "\n";
            ++fail;
            continue;
        }
        RawInstance ri(&v, lexeme);

        // 3. GENERATED dispatch -> SchemaEvaluator.
        ValidationPath path;
        ValidationContext ctx;
        std::string detail;
        bool const verdict = generatedDispatch(c.schema, ri, path, ctx, detail);

        bool const ok = (verdict == c.expectAccept);
        std::fprintf(tsv, "%s\twire-gen\t%s\t%s\n",
                     c.id, ok ? "PASS" : "FAIL",
                     detail.c_str());
        if (ok) {
            ++pass;
            std::printf("PASS  %s (-> %s %s)\n", c.id, detail.c_str(),
                        c.expectAccept ? "accept" : "reject");
        } else {
            ++fail;
            std::printf("FAIL  %s (got %s, expected %s)\n", c.id, detail.c_str(),
                        c.expectAccept ? "accept" : "reject");
        }
    }

    std::fclose(tsv);
    std::printf("__WIRE_GEN_TOTAL__=%zu\n__WIRE_GEN_PASS__=%d\n__WIRE_GEN_FAIL__=%d\n",
                kCaseCount, pass, fail);
    return (fail == 0) ? 0 : 1;
}
