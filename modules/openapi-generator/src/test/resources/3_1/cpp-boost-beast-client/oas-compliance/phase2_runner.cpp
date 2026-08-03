// =============================================================================
// Phase-2 compiled raw-instance validator runner  (Wave-0 K-18 / GS4)
// =============================================================================
// Compiles the generated cpp-boost-beast-client model axis against Boost and,
// for every raw-instance case in phase2_cases.inc (derived from
// semantic-cases.yaml by phase2_gen_cases.py), constructs the raw JSON payload,
// runs the GENERATED raw-instance validation path (fromJsonValue_<Schema>,
// which enforces oneOf/anyOf membership via the generated validate_*_branch_N
// validators), and classifies the outcome against the expected class.
//
// A tiny hand-written path (TYPE_NUMBER -> boost::json type check) covers the
// single inline `type: number` request-body row for which no component schema
// is generated; it is reported per-row as 'handwritten' so it is never masked.
//
// Output: per-row PASS/FAIL lines and a machine-readable semantic-resolved.tsv
// (case_id <TAB> PASS|FAIL <TAB> expected <TAB> detail) consumed by Gate A to
// replace the DEFERRED classification with real accept/reject evidence.
// =============================================================================

#include <boost/json.hpp>

#include <cstddef>
#include <cstdio>
#include <cstring>
#include <exception>
#include <iostream>
#include <string>

#include "phase2_cases.inc"

#include "model/ConstrainedNumber.h"
#include "model/IntOrNumber.h"
#include "model/AnyOfEnumUnion.h"
#include "model/AllNullAnyOf.h"
#include "model/DuplicateNullOneOf.h"
#include "model/OverlappingAnimal.h"
#include "model/DiscriminatorOneOf.h"
#include "model/AllOfEnumIntersection.h"
#include "model/OptionalImpossibleAllOf.h"
#include "model/OneOfStringStringEnum.h"

using namespace model;

// ---------------------------------------------------------------------------
// Dispatch: run raw-instance validation of `v` against the schema named by
// `schema`.  Accepts by returning; rejects (validation failure) by throwing.
// Reuses the generated fromJsonValue_* validators, which themselves drive the
// generated validate_*_branch_N membership checks (oneOf exactly-one, anyOf
// at-least-one, type/enum/multipleOf/required/discriminator/etc.).
// ---------------------------------------------------------------------------
static void dispatchSchema(const char* schema, boost::json::value const& v) {
    if (std::strcmp(schema, "ConstrainedNumber") == 0) {
        (void)fromJsonValue_ConstrainedNumber(v); return;
    }
    if (std::strcmp(schema, "IntOrNumber") == 0) {
        (void)fromJsonValue_IntOrNumber(v); return;
    }
    if (std::strcmp(schema, "AnyOfEnumUnion") == 0) {
        (void)fromJsonValue_AnyOfEnumUnion(v); return;
    }
    if (std::strcmp(schema, "AllNullAnyOf") == 0) {
        (void)fromJsonValue_AllNullAnyOf(v); return;
    }
    if (std::strcmp(schema, "DuplicateNullOneOf") == 0) {
        (void)fromJsonValue_DuplicateNullOneOf(v); return;
    }
    if (std::strcmp(schema, "OverlappingAnimal") == 0) {
        (void)fromJsonValue_OverlappingAnimal(v); return;
    }
    if (std::strcmp(schema, "DiscriminatorOneOf") == 0) {
        (void)fromJsonValue_DiscriminatorOneOf(v); return;
    }
    if (std::strcmp(schema, "AllOfEnumIntersection") == 0) {
        (void)fromJsonValue_AllOfEnumIntersection(v); return;
    }
    if (std::strcmp(schema, "OptionalImpossibleAllOf") == 0) {
        (void)fromJsonValue_OptionalImpossibleAllOf(v); return;
    }
    if (std::strcmp(schema, "OneOfStringStringEnum") == 0) {
        (void)fromJsonValue_OneOfStringStringEnum(v); return;
    }
    throw std::invalid_argument(std::string("no dispatcher for schema: ") + schema);
}

// Base JSON runtime type-name check used by the single TYPE_NUMBER row.
// Reuses the generated ValidationTypes.h isJsonNumber (type:number semantics).
// Returns true if the raw instance is accepted against the case's schema.
static bool validateCase(RawCase const& c, std::string& detail) {
    boost::json::value v;
    try {
        v = boost::json::parse(c.payload);
    } catch (std::exception const& e) {
        detail = std::string("JSON parse failed: ") + e.what();
        return false;  // a syntactically-invalid payload is a hard FAIL, not accept
    }
    try {
        if (std::strcmp(c.schema, "TYPE_NUMBER") == 0) {
            if (!isJsonNumber(v)) {
                detail = "type: number — value is not a JSON number";
                return false;
            }
        } else {
            dispatchSchema(c.schema, v);
        }
        return true;  // accept
    } catch (std::exception const& e) {
        detail = e.what();
        return false;  // reject
    }
}

int main(int argc, char** argv) {
    const char* outTsv = "semantic-resolved.tsv";
    if (argc > 1) {
        outTsv = argv[1];
    }

    std::FILE* tsv = std::fopen(outTsv, "w");
    if (!tsv) {
        std::cerr << "cannot open " << outTsv << "\n";
        return 2;
    }
    std::fprintf(tsv, "case_id\tresult\texpected\tdetail\n");

    int pass = 0;
    int fail = 0;

    for (std::size_t i = 0; i < kCaseCount; ++i) {
        RawCase const& c = kCases[i];
        const bool expectedAccept = (std::strcmp(c.expected, "decode_accept") == 0);
        std::string detail;
        const bool accepted = validateCase(c, detail);

        bool ok = (accepted == expectedAccept);
        const char* verdict = ok ? "PASS" : "FAIL";
        if (ok) ++pass; else ++fail;

        std::string note = c.path;
        if (!ok) {
            note += ": ";
            note += detail;
        }
        std::printf("  [%s] %s  payload=%s  schema=%s(%s)  expected=%s  detail=%s\n",
                    verdict, c.id, c.payload, c.schema, c.path, c.expected,
                    detail.empty() ? "-" : detail.c_str());
        std::fprintf(tsv, "%s\t%s\t%s\t%s\n",
                     c.id, verdict, c.expected, note.c_str());
    }

    // Sanity: the runner must have been given at least one case; zero cases
    // would be a silent pass, which we refuse.
    if (kCaseCount == 0) {
        std::printf("__PHASE2_RUNNER_ERROR__=no-cases (refusing silent green)\n");
        std::fclose(tsv);
        return 2;
    }

    std::printf("\n__PHASE2_TOTAL__=%zu __PHASE2_PASS__=%d __PHASE2_FAIL__=%d\n",
                kCaseCount, pass, fail);
    std::fclose(tsv);
    return (fail == 0) ? 0 : 1;
}
