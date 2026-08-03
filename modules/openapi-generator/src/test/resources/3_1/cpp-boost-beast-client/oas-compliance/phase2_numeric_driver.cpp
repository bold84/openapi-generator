// =============================================================================
// Phase-2 Wave-1 numeric/boolean raw-instance driver — SchemaEvaluator +
// ExactNumber + raw number-lexeme tokenizer.  (ADR D1 / D5 / Option B)
// =============================================================================
// This is the slice's NEW compiled test-driver.  Unlike the Wave-0 runner
// (phase2_runner.cpp, which drives the generated fromJsonValue_<Schema>
// validators and therefore loses the numeric lexeme through boost::json), this
// driver links the REAL Wave-1 engine:
//
//     oas31_ir.hpp        -> densified SchemaResourceRegistry (frozen layout)
//     oas31_validator.hpp -> shared SchemaEvaluator + RawInstance
//     oas31_exact_number.hpp -> D1 exact decimal (mantissa * 10^exp)
//     oas31_lexeme.hpp    -> raw JSON number-lexeme tokenizer (this slice)
//
// and, for every numeric/boolean raw-instance case in phase2_numeric_cases.inc,
//  1. captures the EXACT numeric lexeme straight from the payload string
//     (before boost::json canonicalizes it to int64/uint64/double),
//  2. parses the payload with boost::json purely as the typed/transport view,
//  3. constructs RawInstance(value, lexeme) so asExactNumber() is exact,
//  4. dispatches via validate_<id> -> SchemaEvaluator -> ExactNumber,
//  5. records the accept/reject verdict (appended to the gate-a resolved TSV).
//
// The whole numeric validity path (const/enum equality across spellings,
// multipleOf exact divmod, range bounds, type:integer classification, and
// explicit REFUSAL of malformed multipleOf:<=0) is decided on ExactNumber,
// NEVER on a floating-point double.
// =============================================================================

#include <boost/json.hpp>

#include <cstddef>
#include <cstdio>
#include <cstring>
#include <exception>
#include <iostream>
#include <string>

#include "oas31_lexeme.hpp"
#include "phase2_numeric_cases.inc"
#include "schema_ir_numeric.generated.hpp"

using oas31::RawInstance;
using oas31::ValidationContext;
using oas31::ValidationPath;
using oas31::ValidationResult;

// Run raw-instance validation of `payload` against the numeric slice schema
// named by `schema`.  Returns true on accept, false on reject.  Optional exact
// numeric lexeme capture is always attempted; when the root token is a JSON
// number the captured lexeme is fed to asExactNumber() (the whole point).
static bool validateNumericCase(RawNumCase const& c, std::string& detail,
                                std::string& lexeme) {
    std::string pl(c.payload);
    // 1. Capture the raw numeric lexeme from the payload STRING first.
    if (oas31::captureLeadingNumberLexeme(pl, lexeme)) {
        detail += "lexeme='" + lexeme + "' ";
    } else {
        detail += "lexeme=<non-number-root> ";
    }

    // 2. Typed/transport view (representability gate only — never validity).
    boost::json::value v;
    try {
        v = boost::json::parse(pl);
    } catch (std::exception const& e) {
        detail += "JSON parse failed: " + std::string(e.what());
        return false;  // syntactically-invalid payload is a hard reject
    }
    RawInstance ri(&v, lexeme);   // lexeme-first exact number path

    // 3. validate_<id> -> SchemaEvaluator (single shared registry).
    ValidationPath path;
    ValidationContext ctx;
    ValidationResult res = oas31::validateNumeric(c.schema, ri, path, ctx);
    if (res.success) {
        detail += "-> accept";
        return true;
    }
    detail += "-> reject (" + res.failureMessage + ")";
    return false;
}

int main(int argc, char** argv) {
    const char* outTsv = "semantic-resolved.tsv";
    if (argc > 1) {
        outTsv = argv[1];
    }

    // APPEND (the Wave-0 runner already wrote the header + its rows to this
    // file; we add the numeric/boolean rows so the gate-a classifier sees both).
    std::FILE* tsv = std::fopen(outTsv, "a");
    if (!tsv) {
        std::cerr << "cannot open " << outTsv << "\n";
        return 2;
    }

    int pass = 0;
    int fail = 0;

    for (std::size_t i = 0; i < kNumCaseCount; ++i) {
        RawNumCase const& c = kNumCases[i];
        const bool expectedAccept = (std::strcmp(c.expected, "decode_accept") == 0);
        std::string detail;
        std::string lexeme;
        const bool accepted = validateNumericCase(c, detail, lexeme);

        bool ok = (accepted == expectedAccept);
        const char* verdict = ok ? "PASS" : "FAIL";
        if (ok) ++pass; else ++fail;

        std::printf("  [%s] %s  schema=%s  payload=%s  expected=%s  %s\n",
                    verdict, c.id, c.schema, c.payload, c.expected, detail.c_str());
        // Machine-readable evidence for the gate-a classifier (PASS/FAIL only;
        // the classifier's line is <case_id>\tPASS|FAIL\t<expected>).
        std::fprintf(tsv, "%s\t%s\t%s\t%s\n",
                     c.id, verdict, c.expected, detail.c_str());
    }

    if (kNumCaseCount == 0) {
        std::printf("__PHASE2_NUM_ERROR__=no-cases (refusing silent green)\n");
        std::fclose(tsv);
        return 2;
    }

    std::printf("\n__PHASE2_NUM_TOTAL__=%zu __PHASE2_NUM_PASS__=%d __PHASE2_NUM_FAIL__=%d\n",
                kNumCaseCount, pass, fail);
    std::fclose(tsv);
    return (fail == 0) ? 0 : 1;
}
