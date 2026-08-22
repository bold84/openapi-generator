// =============================================================================
// Wave-M2 — mapping driver (GM1/GM2): executes the M corpus against the
// GENERATED model axis and classifies each row into the five-class taxonomy.
//
// Per row: parse (transportParseError) -> GENERATED fromJsonValue_X decode ->
// classify the failure by message + instance-number integrality
// (schemaInvalid vs unrepresentable) -> re-encode via the GENERATED
// toJsonValue axis -> exact compare (representable) or idempotence check
// (narrowed).  The GM2 separation is the core assertion: representation
// failures ("not exact" for integral out-of-range, "non-finite destination")
// are NEVER reported as schema validity, and schemaInvalid rows are never
// reported as representation issues.
//
// Output: m-resolved.tsv (id TAB PASS|FAIL TAB expected TAB detail) +
// per-row lines.  Exits non-zero on any FAIL or on an empty manifest.
// =============================================================================
#include <cmath>
#include <cstdio>
#include <cstring>
#include <exception>
#include <string>
#include <typeinfo>

#include <boost/json.hpp>

#include "oas31_deep_equal.hpp"
#include "phase2_m.inc"

#include "model/Int32Box.h"
#include "model/Int64Box.h"
#include "model/FloatBox.h"
#include "model/DoubleBox.h"
#include "model/StringBox.h"
#include "model/BoolBox.h"
#include "model/EnumBox.h"
#include "model/NullableBox.h"
#include "model/AnyType.h"
#include "model/PolyBox.h"
#include "model/CollectionBox.h"
#include "model/Child.h"

using namespace model;

// ---------------------------------------------------------------------------
// Decode/re-encode dispatch (identical surface to the Wave-M1 probe).
// ---------------------------------------------------------------------------
struct Slots {
    Int32Box i32; Int64Box i64; FloatBox f; DoubleBox d; StringBox s;
    BoolBox b; EnumBox e; NullableBox n; AnyType a; PolyBox p; CollectionBox c;
};

static void* fieldFor(const char* schema, Slots& slots) {
    if (std::strcmp(schema, "Int32Box") == 0) return &slots.i32;
    if (std::strcmp(schema, "Int64Box") == 0) return &slots.i64;
    if (std::strcmp(schema, "FloatBox") == 0) return &slots.f;
    if (std::strcmp(schema, "DoubleBox") == 0) return &slots.d;
    if (std::strcmp(schema, "StringBox") == 0) return &slots.s;
    if (std::strcmp(schema, "BoolBox") == 0) return &slots.b;
    if (std::strcmp(schema, "EnumBox") == 0) return &slots.e;
    if (std::strcmp(schema, "NullableBox") == 0) return &slots.n;
    if (std::strcmp(schema, "AnyType") == 0) return &slots.a;
    if (std::strcmp(schema, "PolyBox") == 0) return &slots.p;
    if (std::strcmp(schema, "CollectionBox") == 0) return &slots.c;
    throw std::invalid_argument(std::string("no slot for ") + schema);
}

static void decodeInto(const char* schema, boost::json::value const& jv,
                       void* slot) {
    if (std::strcmp(schema, "Int32Box") == 0) {
        *static_cast<Int32Box*>(slot) = fromJsonValue_Int32Box(jv); return;
    }
    if (std::strcmp(schema, "Int64Box") == 0) {
        *static_cast<Int64Box*>(slot) = fromJsonValue_Int64Box(jv); return;
    }
    if (std::strcmp(schema, "FloatBox") == 0) {
        *static_cast<FloatBox*>(slot) = fromJsonValue_FloatBox(jv); return;
    }
    if (std::strcmp(schema, "DoubleBox") == 0) {
        *static_cast<DoubleBox*>(slot) = fromJsonValue_DoubleBox(jv); return;
    }
    if (std::strcmp(schema, "StringBox") == 0) {
        *static_cast<StringBox*>(slot) = fromJsonValue_StringBox(jv); return;
    }
    if (std::strcmp(schema, "BoolBox") == 0) {
        *static_cast<BoolBox*>(slot) = fromJsonValue_BoolBox(jv); return;
    }
    if (std::strcmp(schema, "EnumBox") == 0) {
        *static_cast<EnumBox*>(slot) = fromJsonValue_EnumBox(jv); return;
    }
    if (std::strcmp(schema, "NullableBox") == 0) {
        *static_cast<NullableBox*>(slot) = fromJsonValue_NullableBox(jv); return;
    }
    if (std::strcmp(schema, "AnyType") == 0) {
        *static_cast<AnyType*>(slot) = jv; return;  // alias
    }
    if (std::strcmp(schema, "PolyBox") == 0) {
        *static_cast<PolyBox*>(slot) = fromJsonValue_PolyBox(jv); return;
    }
    if (std::strcmp(schema, "CollectionBox") == 0) {
        *static_cast<CollectionBox*>(slot) = fromJsonValue_CollectionBox(jv);
        return;
    }
    throw std::invalid_argument(std::string("no decoder for ") + schema);
}

static boost::json::value reencode(const char* schema, void const* value) {
    if (std::strcmp(schema, "Int32Box") == 0) {
        return static_cast<Int32Box const*>(value)->toJsonValue();
    }
    if (std::strcmp(schema, "Int64Box") == 0) {
        return static_cast<Int64Box const*>(value)->toJsonValue();
    }
    if (std::strcmp(schema, "FloatBox") == 0) {
        return static_cast<FloatBox const*>(value)->toJsonValue();
    }
    if (std::strcmp(schema, "DoubleBox") == 0) {
        return static_cast<DoubleBox const*>(value)->toJsonValue();
    }
    if (std::strcmp(schema, "StringBox") == 0) {
        return static_cast<StringBox const*>(value)->toJsonValue();
    }
    if (std::strcmp(schema, "BoolBox") == 0) {
        return static_cast<BoolBox const*>(value)->toJsonValue();
    }
    if (std::strcmp(schema, "EnumBox") == 0) {
        return static_cast<EnumBox const*>(value)->toJsonValue();
    }
    if (std::strcmp(schema, "NullableBox") == 0) {
        return static_cast<NullableBox const*>(value)->toJsonValue();
    }
    if (std::strcmp(schema, "AnyType") == 0) {
        return *static_cast<AnyType const*>(value);
    }
    if (std::strcmp(schema, "PolyBox") == 0) {
        return toJsonValue_PolyBox(*static_cast<PolyBox const*>(value));
    }
    if (std::strcmp(schema, "CollectionBox") == 0) {
        return static_cast<CollectionBox const*>(value)->toJsonValue();
    }
    throw std::invalid_argument(std::string("no reencoder for ") + schema);
}

// ---------------------------------------------------------------------------
// Instance-number integrality (TEXT-based): does the instance text contain a
// number with a nonzero fractional part ('.' with non-zero digits, or a
// non-integer mantissa in scientific notation)?  The parsed-value route is
// unsuitable here: numbers beyond uint64 (e.g. 2^64) parse as LOSSY doubles
// whose stored expansion may look fractional although the literal is an
// integer.  The corpus semantics classify the LITERAL.
// ---------------------------------------------------------------------------
static bool hasFractionalText(const char* text) {
    // Scan for JSON number tokens: [0-9]+ ('.' [0-9]*)? ([eE] [+-]? [0-9]+)?
    // A token is fractional iff it contains '.' with any digit after it.
    const char* p = text;
    while (*p) {
        if (*p >= '0' && *p <= '9') {
            const char* tok = p;
            while (*p >= '0' && *p <= '9') ++p;
            bool fractional = false;
            if (*p == '.') {
                ++p;
                if (*p >= '0' && *p <= '9') {
                    fractional = true;  // '.' followed by digits (incl. 0)
                    while (*p >= '0' && *p <= '9') ++p;
                } else {
                    // "1." is not a valid JSON number; treat as token end.
                }
            }
            if (*p == 'e' || *p == 'E') {
                ++p;
                if (*p == '+' || *p == '-') ++p;
                while (*p >= '0' && *p <= '9') ++p;
            }
            if (fractional) {
                return true;
            }
            (void)tok;
        } else {
            ++p;
        }
    }
    return false;
}

// ---------------------------------------------------------------------------
// Classify a decode exception into the taxonomy.
// ---------------------------------------------------------------------------
static const char* classifyFailure(std::string const& msg,
                                   const char* payload) {
    if (msg.find("non-finite destination") != std::string::npos) {
        return "unrepresentable";
    }
    if (msg.find("not exact") != std::string::npos) {
        // Integral out-of-range -> representation; fractional literal ->
        // schema-invalid (type: integer violation).
        return hasFractionalText(payload) ? "schemaInvalid"
                                          : "unrepresentable";
    }
    // Everything else (validator language: "Value not allowed", "value is
    // not a string", "Required field", "No matching branch", "not a
    // number") is a schema-validity failure.
    return "schemaInvalid";
}

static const char* classify(MCase const& c, boost::json::value const& v,
                            Slots& slots, std::string& detail) {
    boost::json::value out;
    try {
        void* slot = fieldFor(c.schema, slots);
        decodeInto(c.schema, v, slot);
        out = reencode(c.schema, slot);
    } catch (std::exception const& e) {
        return classifyFailure(e.what(), c.payload);
    }
    if (oas31::deepJsonValueEqual(out, v)) {
        return "representable";
    }
    // Not exact: narrowed domain — verify IDEMPOTENCE (re-decode of the
    // re-encoded value is stable).
    try {
        void* slot = fieldFor(c.schema, slots);
        decodeInto(c.schema, out, slot);
        boost::json::value out2 = reencode(c.schema, slot);
        if (oas31::deepJsonValueEqual(out2, out)) {
            return "narrowed";
        }
        detail = "re-encode not idempotent: " +
                 boost::json::serialize(out) + " -> " +
                 boost::json::serialize(out2);
        return "FAIL-unstable";
    } catch (std::exception const& e) {
        detail = std::string("re-decode of re-encoded value failed: ") + e.what();
        return "FAIL-unstable";
    }
}

int main(int argc, char** argv) {
    const char* outTsv = "m-resolved.tsv";
    if (argc > 1) {
        outTsv = argv[1];
    }

    std::FILE* tsv = std::fopen(outTsv, "w");
    if (!tsv) {
        std::cerr << "cannot open " << outTsv << "\n";
        return 2;
    }
    std::fprintf(tsv, "case_id\tresult\texpected\tdetail\n");

    if (kMCaseCount == 0) {
        std::printf("__M_RUNNER_ERROR__=no-cases (refusing silent green)\n");
        std::fclose(tsv);
        return 2;
    }

    int pass = 0;
    int fail = 0;
    Slots slots{};

    for (std::size_t i = 0; i < kMCaseCount; ++i) {
        MCase const& c = kMCases[i];
        std::string detail;
        const char* gotClass;
        if (std::strcmp(c.expected, "transportParseError") == 0) {
            try {
                (void)boost::json::parse(c.payload);
                gotClass = "representable";  // parsed when it must not
            } catch (std::exception const&) {
                gotClass = "transportParseError";
            }
        } else {
            boost::json::value v;
            gotClass = "";
            try {
                v = boost::json::parse(c.payload);
            } catch (std::exception const& e) {
                gotClass = "transportParseError";
                detail = std::string("unexpected parse failure: ") + e.what();
            }
            if (std::strcmp(gotClass, "transportParseError") != 0) {
                gotClass = classify(c, v, slots, detail);
            }
        }

        bool ok = std::strcmp(gotClass, c.expected) == 0;
        const char* verdict = ok ? "PASS" : "FAIL";
        if (ok) ++pass; else ++fail;
        std::printf("  [%s] %s  schema=%s  instance=%s  expected=%s  observed=%s%s%s\n",
                    verdict, c.id, c.schema, c.payload, c.expected, gotClass,
                    detail.empty() ? "" : "  detail=", detail.c_str());
        std::fprintf(tsv, "%s\t%s\t%s\t%s\n", c.id, verdict, c.expected,
                     detail.empty() ? gotClass : (std::string(gotClass) + ": " + detail).c_str());
    }

    std::printf("\n__M_TOTAL__=%zu __M_PASS__=%d __M_FAIL__=%d\n",
                kMCaseCount, pass, fail);
    std::fclose(tsv);
    return (fail == 0) ? 0 : 1;
}