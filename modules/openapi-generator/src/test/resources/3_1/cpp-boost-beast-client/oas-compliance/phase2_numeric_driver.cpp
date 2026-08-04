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
//  2. ALSO runs the engine's container-depth lexeme scanner
//     (oas31::captureInstanceLexemes) so numbers nested inside objects/arrays
//     keep their exact lexeme (multipleOf/uniqueItems/deep-const at depth),
//  3. constructs RawInstance(value, lexeme, table) so asExactNumber() is
//     exact at ANY container depth,
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
#include "oas31_object_array.hpp"
#include "phase2_numeric_cases.inc"
#include "schema_ir_numeric.generated.hpp"

using oas31::RawInstance;
using oas31::ValidationContext;
using oas31::ValidationPath;
using oas31::ValidationResult;

// ---------------------------------------------------------------------------
// Path-canonicalisation shim for the engine's container-depth lexeme table.
//
// The engine scanner (oas31::captureInstanceLexemes) keys every lexeme by an
// RFC-6901-style path that ALWAYS begins with '/' (e.g. "/price");
// RawInstance::atMember/atIndex build the lookup path WITHOUT a leading '/'
// for object-first segments (e.g. "price", "price/0") all the way down, while
// root-array index segments DO carry the leading '/' ("/0").  To make the
// harness robust against that inconsistency we alias EVERY table key into BOTH
// forms ("price" <-> "/price", ...).  The engine value is byte-identical in
// either spelling; this is a harness-side compatibility shim, not a semantic
// override — it only guarantees that whatever path form the engine actually
// uses, the exact lexeme is found at container depth.
// ---------------------------------------------------------------------------
static void aliasLexemeTablePaths(oas31::InstanceLexemeTable& table) {
    // Snapshot first (adding keys while iterating would rehash/interleave).
    auto const extra = table.entries;
    for (auto const& kv : extra) {
        if (kv.first.empty()) continue;               // root number: path ""
        if (kv.first[0] == '/') {
            table.entries[kv.first.substr(1)] = kv.second;
        } else {
            table.entries["/" + kv.first] = kv.second;
        }
    }
}

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
    // 2b. Container-depth exact lexemes: scan the raw payload STRING before
    //     boost::json canonicalizes nested numbers.  Alias both path forms
    //     (see shim above) so RawInstance::asExactNumber finds every nested
    //     number's lexeme regardless of the engine's internal path shape.
    oas31::InstanceLexemeTable table;
    oas31::captureInstanceLexemes(pl, table);
    aliasLexemeTablePaths(table);

    // 3. RawInstance carrying BOTH the scalar-root lexeme and the container
    //    table, so exactness holds at any depth (multipleOf/uniqueItems/
    //    deep const/enum into container members).
    RawInstance ri(&v, lexeme);
    ri.lexemes = &table;

    // 4. validate_<id> -> SchemaEvaluator (single shared registry).
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
