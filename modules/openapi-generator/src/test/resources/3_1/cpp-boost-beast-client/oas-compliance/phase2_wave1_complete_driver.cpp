// =============================================================================
// Wave-1 COMPLETE regression driver — boolean / not / deep-equality /
// uniqueItems / $ref (K-03/K-01/K-30/K-34/K-22/K-29).
// =============================================================================
// Proves, through the REAL-generator-emitted validate_<id> dispatch (ADR D5),
// the Wave-1 engine behaviours added in the completion pass:
//   K-03  boolean value-schemas (true/false literals),
//   K-01  `not` inversion,
//   K-30/K-34 exact DEEP JSON const/enum equality (all kinds, ExactNumber),
//   K-22  array uniqueItems (1 == 1.0 is a duplicate),
//   K-29  local $ref resolution to a real schema node.
//
// Like the wire-gen driver, each scalar-root case captures the raw numeric
// lexeme first (oas31::captureLeadingNumberLexeme) so RawInstance::asExactNumber
// is exact; object/array roots are parsed by boost::json purely as the typed
// view (container children carry no lexeme — honest limitation, the numbers in
// these cases are exactly representable).
//
// gate-wave1-complete.sh orchestrates generate (real generator on the committed
// oas31-wave1-complete-regression.yaml) + -Werror compile + run, and records
// __WAVE1_COMPLETE_PASS__ / __WAVE1_COMPLETE_FAIL__.
// =============================================================================

#include <boost/json.hpp>

#include <cstddef>
#include <cstdio>
#include <cstring>
#include <exception>
#include <iostream>
#include <string>

#include "oas31_lexeme.hpp"
#include "oas31_validator.hpp"
#include "schema_ir.generated.hpp"

using oas31::RawInstance;
using oas31::ValidationContext;
using oas31::ValidationPath;
using oas31::ValidationResult;

// Forward declarations of the GENERATED thin dispatch entry points.
oas31::ValidationResult validate_AlwaysTrueSchema_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_AlwaysFalseSchema_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_NotString_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_DeepConstObject_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_DeepConstArray_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_DeepEnumArray_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_DeepEnumMixed_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_UniqueItemsInts_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_Thing_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_RefToThing_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_ConstForty_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);
oas31::ValidationResult validate_RefConstForty_branch_0(oas31::RawInstance const&, oas31::ValidationPath&, oas31::ValidationContext&);

/// A single raw-instance case: schema target + raw JSON payload + expected.
struct Wave1Case {
    const char* id;
    const char* schema;    // -> validate_<schema>_branch_0
    const char* payload;   // raw JSON instance
    bool        expectAccept;
};

static const Wave1Case kCases[] = {
    // -- K-03 boolean value-schemas --
    {"bool-true[0]", "AlwaysTrueSchema", "5", true},
    {"bool-true[1]", "AlwaysTrueSchema", "null", true},
    {"bool-true[2]", "AlwaysTrueSchema", "\"x\"", true},
    {"bool-false[0]", "AlwaysFalseSchema", "5", false},
    {"bool-false[1]", "AlwaysFalseSchema", "null", false},
    // -- K-01 `not` inversion --
    {"not-string[0]", "NotString", "5", true},
    {"not-string[1]", "NotString", "\"hi\"", false},
    // -- K-30 deep const: object (ExactNumber + unordered keys + exact numeric) --
    {"deep-const-object[0]", "DeepConstObject", "{\"a\":1,\"b\":[true,null,2.5]}", true},
    {"deep-const-object[1]", "DeepConstObject", "{\"a\":1.0,\"b\":[true,null,2.5]}", true},
    {"deep-const-object[2]", "DeepConstObject", "{\"b\":[true,null,2.5],\"a\":1}", true},
    {"deep-const-object[3]", "DeepConstObject", "{\"a\":1,\"b\":[true,null,2.6]}", false},
    {"deep-const-object[4]", "DeepConstObject", "{\"a\":1,\"b\":[true,null,2.5],\"c\":0}", false},
    // -- K-30 deep const: array --
    {"deep-const-array[0]", "DeepConstArray", "[1,2,3]", true},
    {"deep-const-array[1]", "DeepConstArray", "[1,2,4]", false},
    {"deep-const-array[2]", "DeepConstArray", "[1,2]", false},
    // -- K-34 deep enum: array members, exact numeric across spellings --
    {"deep-enum-array[0]", "DeepEnumArray", "[1,2]", true},
    {"deep-enum-array[1]", "DeepEnumArray", "[1.0,2]", true},
    {"deep-enum-array[2]", "DeepEnumArray", "[3,4]", true},
    {"deep-enum-array[3]", "DeepEnumArray", "[5,6]", false},
    // -- K-34 deep enum: mixed members (object + number) --
    {"deep-enum-mixed[0]", "DeepEnumMixed", "{\"x\":1}", true},
    {"deep-enum-mixed[1]", "DeepEnumMixed", "7", true},
    {"deep-enum-mixed[2]", "DeepEnumMixed", "{\"x\":2}", false},
    // -- K-22 uniqueItems (exact: 1 == 1.0 is a duplicate) --
    {"unique[0]", "UniqueItemsInts", "[1,2,3]", true},
    {"unique[1]", "UniqueItemsInts", "[1,2,1]", false},
    {"unique[2]", "UniqueItemsInts", "[1,2,1.0]", false},
    // -- K-29 local $ref: direct + through a ref indirection --
    {"thing[0]", "Thing", "15", true},
    {"thing[1]", "Thing", "10", true},
    {"thing[2]", "Thing", "5", false},
    {"ref-to-thing[0]", "RefToThing", "15", true},
    {"ref-to-thing[1]", "RefToThing", "5", false},
    // -- K-29 $ref into a deep-const target --
    {"const-forty[0]", "ConstForty", "40", true},
    {"const-forty[1]", "ConstForty", "40.0", true},
    {"const-forty[2]", "ConstForty", "41", false},
    {"ref-const-forty[0]", "RefConstForty", "40", true},
    {"ref-const-forty[1]", "RefConstForty", "41", false},
};

static const std::size_t kCaseCount = sizeof(kCases) / sizeof(kCases[0]);

static bool generatedDispatch(char const* schema, RawInstance const& ri,
                              ValidationPath& path, ValidationContext& ctx,
                              std::string& detail) {
    ValidationResult res;
    if (std::strcmp(schema, "AlwaysTrueSchema") == 0)
        res = validate_AlwaysTrueSchema_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "AlwaysFalseSchema") == 0)
        res = validate_AlwaysFalseSchema_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "NotString") == 0)
        res = validate_NotString_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "DeepConstObject") == 0)
        res = validate_DeepConstObject_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "DeepConstArray") == 0)
        res = validate_DeepConstArray_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "DeepEnumArray") == 0)
        res = validate_DeepEnumArray_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "DeepEnumMixed") == 0)
        res = validate_DeepEnumMixed_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "UniqueItemsInts") == 0)
        res = validate_UniqueItemsInts_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "Thing") == 0)
        res = validate_Thing_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "RefToThing") == 0)
        res = validate_RefToThing_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "ConstForty") == 0)
        res = validate_ConstForty_branch_0(ri, path, ctx);
    else if (std::strcmp(schema, "RefConstForty") == 0)
        res = validate_RefConstForty_branch_0(ri, path, ctx);
    else {
        detail = "unknown generated schema: " + std::string(schema);
        return false;
    }
    if (res.success) { detail = "accept"; return true; }
    detail = "reject (" + res.failureMessage + ")";
    return false;
}

int main(int argc, char** argv) {
    const char* outTsv = (argc > 1) ? argv[1] : "wave1-complete.tsv";
    std::FILE* tsv = std::fopen(outTsv, "a");
    if (!tsv) { std::cerr << "cannot open " << outTsv << "\n"; return 2; }

    int pass = 0;
    int fail = 0;
    for (std::size_t i = 0; i < kCaseCount; ++i) {
        Wave1Case const& c = kCases[i];
        std::string pl(c.payload);
        std::string lexeme;
        if (!oas31::captureLeadingNumberLexeme(pl, lexeme)) lexeme.clear();

        boost::json::value v;
        try { v = boost::json::parse(pl); }
        catch (std::exception const& e) {
            std::cerr << "JSON parse failed for " << c.id << ": " << e.what() << "\n";
            ++fail; continue;
        }
        RawInstance ri(&v, lexeme);
        ValidationPath path;
        ValidationContext ctx;
        std::string detail;
        bool const verdict = generatedDispatch(c.schema, ri, path, ctx, detail);
        bool const ok = (verdict == c.expectAccept);
        std::fprintf(tsv, "%s\twave1-complete\t%s\t%s\n", c.id, ok ? "PASS" : "FAIL", detail.c_str());
        if (ok) { ++pass; std::printf("PASS  %s (-> %s)\n", c.id, detail.c_str()); }
        else    { ++fail; std::printf("FAIL  %s (got %s, expected %s)\n", c.id, detail.c_str(),
                                      c.expectAccept ? "accept" : "reject"); }
    }
    std::fclose(tsv);
    std::printf("__WAVE1_COMPLETE_TOTAL__=%zu\n__WAVE1_COMPLETE_PASS__=%d\n__WAVE1_COMPLETE_FAIL__=%d\n",
                kCaseCount, pass, fail);
    return (fail == 0) ? 0 : 1;
}
