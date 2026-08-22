// =============================================================================
// Wave-M1 taxonomy probe — pins destination-domain behavior of the GENERATED
// model axis at every number/type boundary (GM1 operational definitions).
//
// For each probe row: parse the instance, run the GENERATED fromJsonValue_*
// decode, re-encode via the generated toJsonValue axis, and record the
// observed outcome class:
//   parse-error   transport-level (malformed JSON)
//   schema-invalid  validator rejected (validation exception)
//   convert-error   representation-level exception (conversion/overflow)
//   ok              decode + exact re-encode equality
//   mismatch        decode "succeeded" but re-encode differs — SILENT
//                   truncation/narrowing (a candidate M2 fix)
// Raw parser probes record the boost::json value kind for boundary literals.
// =============================================================================
#include <cstdio>
#include <cstring>
#include <string>
#include <typeinfo>

#include <boost/json.hpp>

// Exact number-aware deep JSON equality (1 == 1.0; all numbers via
// ExactNumber, never Boost.JSON double shortcuts).
#include "oas31_deep_equal.hpp"

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

// Re-encode a decoded value to JSON, per schema shape.
static boost::json::value reencode(const char* schema, void const* value) {
    if (std::strcmp(schema, "Int32Box") == 0) {
        auto const& m = *static_cast<Int32Box const*>(value);
        return m.toJsonValue();
    }
    if (std::strcmp(schema, "Int64Box") == 0) {
        auto const& m = *static_cast<Int64Box const*>(value);
        return m.toJsonValue();
    }
    if (std::strcmp(schema, "FloatBox") == 0) {
        auto const& m = *static_cast<FloatBox const*>(value);
        return m.toJsonValue();
    }
    if (std::strcmp(schema, "DoubleBox") == 0) {
        auto const& m = *static_cast<DoubleBox const*>(value);
        return m.toJsonValue();
    }
    if (std::strcmp(schema, "StringBox") == 0) {
        auto const& m = *static_cast<StringBox const*>(value);
        return m.toJsonValue();
    }
    if (std::strcmp(schema, "BoolBox") == 0) {
        auto const& m = *static_cast<BoolBox const*>(value);
        return m.toJsonValue();
    }
    if (std::strcmp(schema, "EnumBox") == 0) {
        auto const& m = *static_cast<EnumBox const*>(value);
        return m.toJsonValue();
    }
    if (std::strcmp(schema, "NullableBox") == 0) {
        auto const& m = *static_cast<NullableBox const*>(value);
        return m.toJsonValue();
    }
    if (std::strcmp(schema, "AnyType") == 0) {
        auto const& v = *static_cast<AnyType const*>(value);
        return v;  // AnyType = boost::json::value alias; identity
    }
    if (std::strcmp(schema, "PolyBox") == 0) {
        auto const& v = *static_cast<PolyBox const*>(value);
        return toJsonValue_PolyBox(v);
    }
    if (std::strcmp(schema, "CollectionBox") == 0) {
        auto const& m = *static_cast<CollectionBox const*>(value);
        return m.toJsonValue();
    }
    throw std::invalid_argument(std::string("no reencoder for ") + schema);
}

// Decode into a stack-allocated slot; returns the slot pointer (valid until
// the next decode call) or throws.
static void* decodeInto(const char* schema, boost::json::value const& jv,
                        void* slot) {
    if (std::strcmp(schema, "Int32Box") == 0) {
        *static_cast<Int32Box*>(slot) = fromJsonValue_Int32Box(jv);
        return slot;
    }
    if (std::strcmp(schema, "Int64Box") == 0) {
        *static_cast<Int64Box*>(slot) = fromJsonValue_Int64Box(jv);
        return slot;
    }
    if (std::strcmp(schema, "FloatBox") == 0) {
        *static_cast<FloatBox*>(slot) = fromJsonValue_FloatBox(jv);
        return slot;
    }
    if (std::strcmp(schema, "DoubleBox") == 0) {
        *static_cast<DoubleBox*>(slot) = fromJsonValue_DoubleBox(jv);
        return slot;
    }
    if (std::strcmp(schema, "StringBox") == 0) {
        *static_cast<StringBox*>(slot) = fromJsonValue_StringBox(jv);
        return slot;
    }
    if (std::strcmp(schema, "BoolBox") == 0) {
        *static_cast<BoolBox*>(slot) = fromJsonValue_BoolBox(jv);
        return slot;
    }
    if (std::strcmp(schema, "EnumBox") == 0) {
        *static_cast<EnumBox*>(slot) = fromJsonValue_EnumBox(jv);
        return slot;
    }
    if (std::strcmp(schema, "NullableBox") == 0) {
        *static_cast<NullableBox*>(slot) = fromJsonValue_NullableBox(jv);
        return slot;
    }
    if (std::strcmp(schema, "AnyType") == 0) {
        *static_cast<AnyType*>(slot) = jv;  // alias; identity
        return slot;
    }
    if (std::strcmp(schema, "PolyBox") == 0) {
        *static_cast<PolyBox*>(slot) = fromJsonValue_PolyBox(jv);
        return slot;
    }
    if (std::strcmp(schema, "CollectionBox") == 0) {
        *static_cast<CollectionBox*>(slot) = fromJsonValue_CollectionBox(jv);
        return slot;
    }
    throw std::invalid_argument(std::string("no decoder for ") + schema);
}

struct ProbeCase {
    const char* id;
    const char* schema;
    const char* payload;
};

// Storage for every decode target type.
struct Slots {
    Int32Box i32; Int64Box i64; FloatBox f; DoubleBox d; StringBox s;
    BoolBox b; EnumBox e; NullableBox n; AnyType a; PolyBox p; CollectionBox c;
};

// Per-schema field pointer — every decode/re-encode must address the SAME
// member (never the struct base).
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

static const char* kindName(boost::json::value const& v) {
    switch (v.kind()) {
    case boost::json::kind::int64: return "int64";
    case boost::json::kind::uint64: return "uint64";
    case boost::json::kind::double_: return "double";
    case boost::json::kind::string: return "string";
    case boost::json::kind::bool_: return "bool";
    case boost::json::kind::null: return "null";
    case boost::json::kind::object: return "object";
    case boost::json::kind::array: return "array";
    }
    return "?";
}

static void probeModelCase(ProbeCase const& c, Slots& slots) {
    boost::json::value v;
    try {
        v = boost::json::parse(c.payload);
    } catch (std::exception const& e) {
        std::printf("PROBE|%s|schema=%s|parse-error|detail=%s\n",
                    c.id, c.schema, e.what());
        return;
    }
    try {
        void* slot = fieldFor(c.schema, slots);
        decodeInto(c.schema, v, slot);
        boost::json::value out = reencode(c.schema, slot);
        if (oas31::deepJsonValueEqual(out, v)) {
            std::printf("PROBE|%s|schema=%s|ok|kind=%s\n",
                        c.id, c.schema, kindName(v));
        } else {
            std::printf("PROBE|%s|schema=%s|MISMATCH|input=%s reencoded=%s\n",
                        c.id, c.schema, boost::json::serialize(v).c_str(),
                        boost::json::serialize(out).c_str());
        }
    } catch (std::exception const& e) {
        // Classify the exception: validation vs representation.  The
        // generated converter (boost::json number_cast) reports
        // range/kind failures with its own wording; schema validation uses
        // the validator's language.  Both surface as std::invalid_argument
        // through the model wrapper, so the M driver keys on the message.
        const std::string msg = e.what();
        const bool repr = msg.find("not exact") != std::string::npos
            || msg.find("not a number") != std::string::npos
            || msg.find("to_number") != std::string::npos
            || msg.find("to_integer") != std::string::npos;
        std::printf("PROBE|%s|schema=%s|%s|exc=%s|detail=%.140s\n",
                    c.id, c.schema, repr ? "representation-error"
                                         : "schema-invalid",
                    typeid(e).name(), e.what());
    }
}

static void probeRaw(const char* id, const char* payload) {
    boost::json::value v;
    try {
        v = boost::json::parse(payload);
    } catch (std::exception const& e) {
        std::printf("PROBE|%s|schema=RAW|parse-error|detail=%.120s\n",
                    id, e.what());
        return;
    }
    std::printf("PROBE|%s|schema=RAW|ok|kind=%s|value=%s\n",
                id, kindName(v), boost::json::serialize(v).c_str());
}

int main() {
    Slots slots{};

    // ---- int32 destination (std::int32_t) ----
    probeModelCase({"int32-max", "Int32Box", "{\"v\":2147483647}"}, slots);
    probeModelCase({"int32-overflow", "Int32Box", "{\"v\":2147483648}"}, slots);
    probeModelCase({"int32-min", "Int32Box", "{\"v\":-2147483648}"}, slots);
    probeModelCase({"int32-underflow", "Int32Box", "{\"v\":-2147483649}"}, slots);
    probeModelCase({"int32-float-form", "Int32Box", "{\"v\":1.0}"}, slots);
    probeModelCase({"int32-fraction", "Int32Box", "{\"v\":1.5}"}, slots);
    probeModelCase({"int32-2pow63", "Int32Box", "{\"v\":9223372036854775808}"}, slots);
    probeModelCase({"int32-string", "Int32Box", "{\"v\":\"x\"}"}, slots);

    // ---- int64 destination (std::int64_t) ----
    probeModelCase({"int64-max", "Int64Box", "{\"v\":9223372036854775807}"}, slots);
    probeModelCase({"int64-overflow", "Int64Box", "{\"v\":9223372036854775808}"}, slots);
    probeModelCase({"int64-min", "Int64Box", "{\"v\":-9223372036854775808}"}, slots);
    probeModelCase({"int64-uint64-max", "Int64Box", "{\"v\":18446744073709551615}"}, slots);
    probeModelCase({"int64-2pow64", "Int64Box", "{\"v\":18446744073709551616}"}, slots);

    // ---- float destination ----
    probeModelCase({"float-01", "FloatBox", "{\"v\":0.1}"}, slots);
    probeModelCase({"float-max", "FloatBox", "{\"v\":3.4028235e38}"}, slots);
    probeModelCase({"float-overflow", "FloatBox", "{\"v\":3.4028236e38}"}, slots);
    probeModelCase({"float-1e39", "FloatBox", "{\"v\":1e39}"}, slots);
    probeModelCase({"float-pi20", "FloatBox", "{\"v\":3.14159265358979323846}"}, slots);

    // ---- double destination ----
    probeModelCase({"double-01", "DoubleBox", "{\"v\":0.1}"}, slots);
    probeModelCase({"double-max", "DoubleBox", "{\"v\":1.7976931348623157e308}"}, slots);
    probeModelCase({"double-overflow", "DoubleBox", "{\"v\":1.7976931348623159e308}"}, slots);
    probeModelCase({"double-1e309", "DoubleBox", "{\"v\":1e309}"}, slots);
    probeModelCase({"double-1e400", "DoubleBox", "{\"v\":1e400}"}, slots);
    probeModelCase({"double-min-normal", "DoubleBox", "{\"v\":2.2250738585072014e-308}"}, slots);

    // ---- string / bool destinations ----
    probeModelCase({"string-ok", "StringBox", "{\"v\":\"hi\"}"}, slots);
    probeModelCase({"string-num", "StringBox", "{\"v\":42}"}, slots);
    probeModelCase({"bool-ok", "BoolBox", "{\"v\":true}"}, slots);
    probeModelCase({"bool-int", "BoolBox", "{\"v\":1}"}, slots);

    // ---- enum destination (open-value policy) ----
    probeModelCase({"enum-known", "EnumBox", "{\"v\":\"red\"}"}, slots);
    probeModelCase({"enum-unknown", "EnumBox", "{\"v\":\"chartreuse\"}"}, slots);
    probeModelCase({"enum-wrongtype", "EnumBox", "{\"v\":5}"}, slots);

    // ---- nullable tri-state ----
    probeModelCase({"nullable-null", "NullableBox", "{\"label\":\"x\",\"v\":null}"}, slots);
    probeModelCase({"nullable-missing", "NullableBox", "{\"label\":\"x\"}"}, slots);
    probeModelCase({"nullable-value", "NullableBox", "{\"label\":\"x\",\"v\":\"y\"}"}, slots);

    // ---- AnyType raw fallback ----
    probeModelCase({"any-string", "AnyType", "\"str\""}, slots);
    probeModelCase({"any-int", "AnyType", "42"}, slots);
    probeModelCase({"any-1e400", "AnyType", "1e400"}, slots);
    probeModelCase({"any-object", "AnyType", "{\"a\":[1,\"x\"]}"}, slots);
    probeModelCase({"any-null", "AnyType", "null"}, slots);
    probeModelCase({"any-bigint", "AnyType", "9223372036854775808"}, slots);

    // ---- variant (discriminator) ----
    probeModelCase({"poly-cat", "PolyBox", "{\"kind\":\"cat\",\"meow\":true}"}, slots);
    probeModelCase({"poly-dog", "PolyBox", "{\"kind\":\"dog\",\"bark\":false}"}, slots);
    probeModelCase({"poly-unknown-kind", "PolyBox", "{\"kind\":\"bird\"}"}, slots);
    probeModelCase({"poly-missing-kind", "PolyBox", "{\"meow\":true}"}, slots);

    // ---- collections ----
    probeModelCase({"coll-ok", "CollectionBox",
        "{\"tags\":[\"a\",\"b\"],\"meta\":{\"k\":\"v\"},"
        "\"children\":[{\"name\":\"c\",\"n\":1}]}"}, slots);
    probeModelCase({"coll-wrong-item", "CollectionBox",
        "{\"tags\":[1,2]}"}, slots);
    probeModelCase({"coll-nested-deep", "CollectionBox",
        "{\"children\":[{\"name\":\"a\",\"n\":1},{\"name\":\"b\",\"n\":2}]}"}, slots);

    // ---- raw parser boundaries ----
    probeRaw("raw-2pow63", "9223372036854775808");
    probeRaw("raw-2pow64", "18446744073709551616");
    probeRaw("raw-2pow70", "1180591620717411303424");
    probeRaw("raw-1e400", "1e400");
    probeRaw("raw-negzero", "-0");
    probeRaw("raw-dupkeys", "{\"a\":1,\"a\":2}");
    probeRaw("raw-30digit", "0.123456789012345678901234567890");
    probeRaw("raw-deep", "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[0]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]");

    return 0;
}