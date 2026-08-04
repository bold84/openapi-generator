// ============================================================================
// oas31_validator.hpp — shared SchemaEvaluator + ValidationContext (ADR
// D2/D3/D5, Option B). Thin validate_<id> dispatch dispatches into this shared
// schema interpreter over the densified IR tables in oas31_ir.hpp.
//
// Wave-1: boolean/type/exact-number/not/deep-const/deep-enum/uniqueItems/$ref.
// Wave-2 (this pass, FROZEN §10): object traversal (properties/required/
// min-maxProperties/additionalProperties tri-state), array traversal
// (prefixItems-by-index + items-remainder + min-maxItems), applicator walk
// (allOf/anyOf/oneOf, transactional annotations), best-effort
// unevaluatedProperties, and container-depth EXACT numeric lexemes via the
// optional InstanceLexemeTable (oas31_object_array.hpp). uniqueItems:false is
// a no-op. $ref + siblings both apply (2020-12).
//
// HEADER-ONLY. Built under -Werror with g++ -std=c++17.
// ============================================================================
#ifndef OAS31_VALIDATOR_HPP_
#define OAS31_VALIDATOR_HPP_

#include "oas31_ir.hpp"
#include "oas31_deep_equal.hpp"
#include "oas31_object_array.hpp"

#include <boost/json.hpp>

#include <cstddef>
#include <cstdint>
#include <regex>
#include <set>
#include <string>
#include <vector>

namespace oas31 {

// ============================================================================
// ValidationPath — JSON-instance-path accumulator (mirrors Wave-0's).
// ============================================================================
struct ValidationPath {
    std::string path_;

    ValidationPath() = default;
    explicit ValidationPath(std::string path) : path_(std::move(path)) {}

    ValidationPath& enter(std::string const& segment) {
        if (path_.empty()) path_ = segment;
        else { path_.push_back('.'); path_.append(segment); }
        return *this;
    }
    ValidationPath& enterIndex(std::size_t index) {
        path_.push_back('[');
        path_.append(std::to_string(index));
        path_.push_back(']');
        return *this;
    }
    ValidationPath& exit() {
        auto const dot = path_.rfind('.');
        auto const brk = path_.rfind('[');
        if (brk != std::string::npos && (dot == std::string::npos || brk > dot)) path_.resize(brk);
        else if (dot != std::string::npos) path_.resize(dot);
        else path_.clear();
        return *this;
    }
    std::string const& str() const { return path_; }
    bool empty() const { return path_.empty(); }
};

// ============================================================================
// ValidationResult — carries the outcome of validating a schema object.
// ============================================================================
struct ValidationResult {
    bool        success;
    std::string failurePath;
    std::string failureMessage;

    static ValidationResult valid() {
        return ValidationResult{true, std::string(), std::string()};
    }
    static ValidationResult invalid(std::string path, std::string message) {
        return ValidationResult{false, std::move(path), std::move(message)};
    }
    static ValidationResult invalidAt(ValidationPath const& path, std::string message) {
        return ValidationResult{false, path.str(), std::move(message)};
    }
};

// ============================================================================
// RawInstance — the ADR's raw-instance view. Holds the typed Boost.JSON view
// (representability gate) PLUS exact numeric lexemes. Exactness sources, in
// priority order:
//   1. this->numericLexeme (Wave-1 scalar-root capture),
//   2. an attached InstanceLexemeTable entry at this->instancePath_ (Wave-2
//      container-depth capture; atMember/atIndex propagate path+table),
//   3. the Boost.JSON value kind (lossy fallback, unchanged Wave-1 behaviour).
// ============================================================================
struct RawInstance {
    boost::json::value const* value = nullptr;
    std::string numericLexeme;   // exact lexeme when this instance is a JSON number (may be empty)
    InstanceLexemeTable const* lexemes = nullptr;  // optional container-depth table
    std::string instancePath_;   // canonical JSON-pointer path ("" root)

    RawInstance() = default;
    explicit RawInstance(boost::json::value const* v) : value(v) {}
    RawInstance(boost::json::value const* v, std::string lexeme)
        : value(v), numericLexeme(std::move(lexeme)) {}
    RawInstance(boost::json::value const* v, InstanceLexemeTable const* table)
        : value(v), lexemes(table) {}

    bool isNull() const { return value == nullptr || value->is_null(); }
    bool isNumber() const {
        return value != nullptr && (value->is_int64() || value->is_uint64() || value->is_double());
    }
    bool isObject() const { return value != nullptr && value->is_object(); }
    bool isArray() const { return value != nullptr && value->is_array(); }
    bool asBool() const { return value != nullptr && value->is_bool() && value->as_bool(); }

    std::string asString() const {
        if (value != nullptr && value->is_string())
            return std::string(value->as_string().data(), value->as_string().size());
        return std::string();
    }

    JsonType kind() const {
        if (value == nullptr) return JsonType::null_;
        switch (value->kind()) {
            case boost::json::kind::null: return JsonType::null_;
            case boost::json::kind::bool_: return JsonType::boolean;
            case boost::json::kind::int64:
            case boost::json::kind::uint64:
            case boost::json::kind::double_: return JsonType::number;
            case boost::json::kind::string: return JsonType::string;
            case boost::json::kind::array: return JsonType::array;
            case boost::json::kind::object: return JsonType::object;
        }
        return JsonType::null_;
    }

    std::size_t size() const {
        if (value == nullptr) return 0;
        if (value->is_array()) return value->as_array().size();
        if (value->is_object()) return value->as_object().size();
        return 0;
    }

    bool hasMember(char const* key) const {
        if (value != nullptr && value->is_object()) {
            return value->as_object().find(key) != value->as_object().end();
        }
        return false;
    }

    RawInstance atMember(char const* key) const {
        if (value != nullptr && value->is_object()) {
            auto const* obj = value->if_object();
            auto it = obj->find(key);
            if (it != obj->end()) {
                RawInstance child(&it->value());
                child.instancePath_ = instancePath_.empty()
                    ? jsonPointerEscape(key)
                    : instancePath_ + "/" + jsonPointerEscape(key);
                child.lexemes = lexemes;
                return child;
            }
        }
        return RawInstance();
    }

    RawInstance atIndex(std::size_t i) const {
        if (value != nullptr && value->is_array()) {
            auto const& arr = value->as_array();
            if (i < arr.size()) {
                RawInstance child(&arr[i]);
                child.instancePath_ = instancePath_ + "/" + std::to_string(i);
                child.lexemes = lexemes;
                return child;
            }
        }
        return RawInstance();
    }

    /// Ordered list of object member KEYS (as decoded by Boost.JSON). The
    /// instance keys are the authoritative enumeration source for the
    /// additionalProperties / unevaluatedProperties passes.
    std::vector<std::string> objectKeys() const {
        std::vector<std::string> keys;
        if (value != nullptr && value->is_object()) {
            boost::json::object const& ob = value->as_object();
            keys.reserve(ob.size());
            for (auto const& kv : ob) {
                keys.emplace_back(kv.key().data(), kv.key().size());
            }
        }
        return keys;
    }

    /// Exact number: raw lexeme (node-local, then container-depth table),
    /// degraded to the typed value kind only when no lexeme is available.
    ExactNumber asExactNumber() const {
        if (!numericLexeme.empty()) return ExactNumber::parseLexeme(numericLexeme);
        if (lexemes != nullptr) {
            std::string const* lx = lexemes->lexemeAt(instancePath_);
            if (lx != nullptr) return ExactNumber::parseLexeme(*lx);
        }
        if (value != nullptr) {
            if (value->is_int64()) return ExactNumber::fromInt(value->as_int64());
            if (value->is_uint64()) return ExactNumber::fromUint(value->as_uint64());
            if (value->is_double()) return ExactNumber::fromDouble(value->as_double());
        }
        return ExactNumber();
    }
};

// ============================================================================
// EXACT deep JSON equality, instance vs. stored schema value (K-30/K-34/K-22).
// Number equality is ALWAYS via ExactNumber (1 == 1.0 == 1e0); never double.
// Objects compare by exact key set (missing key => not equal); arrays
// positionally; the instance side is lexeme-first (exact, INCLUDING at
// container depths via the attached InstanceLexemeTable), degrading to value
// kind only for container children that carry no captured lexeme.
// ============================================================================
inline bool deepInstanceEqual(RawInstance const& inst, boost::json::value const& sv) {
    switch (inst.kind()) {
        case JsonType::null_:
            return sv.is_null();
        case JsonType::boolean:
            return sv.is_bool() && inst.asBool() == sv.as_bool();
        case JsonType::number:
            if (!(sv.is_int64() || sv.is_uint64() || sv.is_double())) return false;
            return inst.asExactNumber() == exactValueOf(sv);
        case JsonType::string: {
            if (!sv.is_string()) return false;
            boost::json::string const& ss = sv.as_string();
            return inst.asString() ==
                   std::string(ss.data(), ss.size());
        }
        case JsonType::array: {
            if (!sv.is_array()) return false;
            boost::json::array const& sa = sv.as_array();
            if (inst.size() != sa.size()) return false;
            for (std::size_t i = 0; i < sa.size(); ++i) {
                if (!deepInstanceEqual(inst.atIndex(i), sa[i])) return false;
            }
            return true;
        }
        case JsonType::object: {
            if (!sv.is_object()) return false;
            boost::json::object const& so = sv.as_object();
            if (inst.size() != so.size()) return false;
            for (auto const& kv : so) {
                RawInstance m = inst.atMember(kv.key().data());
                if (m.value == nullptr) return false;   // instance lacks this key
                if (!deepInstanceEqual(m, kv.value())) return false;
            }
            return true;
        }
        case JsonType::integer: /* not a raw-instance kind */ break;
    }
    return false;
}

// ============================================================================
// EXACT deep equality between TWO raw instances (used by uniqueItems, K-22).
// Identical semantics to deepInstanceEqual but neither side is a stored schema
// value; BOTH sides may carry numeric lexemes (node-local and table), compared
// via ExactNumber at container depth.
// ============================================================================
inline bool deepRawEqual(RawInstance const& a, RawInstance const& b) {
    JsonType const ka = a.kind();
    JsonType const kb = b.kind();
    if (ka != kb) return false;
    switch (ka) {
        case JsonType::null_:
            return true;
        case JsonType::boolean:
            return a.asBool() == b.asBool();
        case JsonType::number:
            return a.asExactNumber() == b.asExactNumber();
        case JsonType::string:
            return a.asString() == b.asString();
        case JsonType::array: {
            if (a.size() != b.size()) return false;
            for (std::size_t i = 0; i < a.size(); ++i) {
                if (!deepRawEqual(a.atIndex(i), b.atIndex(i))) return false;
            }
            return true;
        }
        case JsonType::object: {
            if (!a.value || !b.value || a.size() != b.size()) return false;
            boost::json::object const& ob = b.value->as_object();
            for (auto const& kv : ob) {
                RawInstance const ma = a.atMember(kv.key().data());
                RawInstance const mb = b.atMember(kv.key().data());
                if (ma.value == nullptr || mb.value == nullptr) return false;
                if (!deepRawEqual(ma, mb)) return false;
            }
            return true;
        }
        case JsonType::integer: /* not a raw-instance kind */ break;
    }
    return false;
}

// ============================================================================
// Annotation + transactional branch support (D2/D3).
// ============================================================================
struct Annotation {
    std::string keyword;
    std::string instancePath;
    std::string schemaPath;
    std::string absSchemaUri;
    std::string value;
};

struct AnnotationStore {
    void add(Annotation a) { annotations_.push_back(std::move(a)); }
    std::size_t snapshot() const { return annotations_.size(); }
    void rollbackTo(std::size_t marker) {
        if (marker < annotations_.size()) annotations_.resize(marker);
    }
    std::vector<Annotation> const& all() const { return annotations_; }
    void clear() { annotations_.clear(); }

private:
    std::vector<Annotation> annotations_;
};

struct ValidationContext {
    AnnotationStore annotations;
    std::set<std::string> evaluatedProperties;
    std::set<std::size_t> evaluatedItems;

    struct Branch {
        std::size_t annotationMark = 0;
        std::set<std::string> evaluatedProperties;
        std::set<std::size_t> evaluatedItems;
    };

    Branch beginBranch() {
        Branch b;
        b.annotationMark = annotations.snapshot();
        b.evaluatedProperties = evaluatedProperties;
        b.evaluatedItems = evaluatedItems;
        return b;
    }
    /// Keep this branch's additions (annotations + evaluated coverage) in place.
    void commitBranch(Branch const&) { /* current state already includes branch output */ }
    /// Discard this branch's output and restore annotations + coverage.
    void rollbackBranch(Branch const& b) {
        annotations.rollbackTo(b.annotationMark);
        evaluatedProperties = b.evaluatedProperties;
        evaluatedItems = b.evaluatedItems;
    }
};

// ============================================================================
// SchemaEvaluator — the shared interpreter (Option B).
// ============================================================================
class SchemaEvaluator {
public:
    explicit SchemaEvaluator(SchemaResourceRegistry const& registry) : registry_(registry) {}

    SchemaResourceRegistry const& registry() const { return registry_; }

    /// Thin-dispatch entry: validate the schema referenced by `node`.
    ValidationResult validate(SchemaIndex node, RawInstance const& instance,
                              ValidationPath& path, ValidationContext& ctx) const {
        // schemaNodeFor(<id>) resolves id -> SchemaIndex; here node is that index.
        return validateSchemaNode(registry_.node(node), instance, path, ctx);
    }

    /// Validate a single schema object against one raw instance.
    ValidationResult validateSchemaNode(SchemaNode const& node, RawInstance const& instance,
                                        ValidationPath& path, ValidationContext& ctx) const {
        // Boolean value-schema (OAS 3.1).
        if (node.booleanValue == BooleanValue::true_) return ValidationResult::valid();
        if (node.booleanValue == BooleanValue::false_)
            return ValidationResult::invalidAt(path, "boolean value-schema false");

        // Wave-2 unevaluatedProperties: snapshot the evaluated-property set at
        // ENTRY so the exit check only considers keys evaluated WITHIN this
        // node's subtree (applicators / object traversal included). Best-effort
        // subset of full annotation semantics (FROZEN §10.3).
        std::set<std::string> unevalEntry;
        if (node.hasUnevaluatedProperties && instance.isObject()) {
            unevalEntry = ctx.evaluatedProperties;
        }

        // K-29 $ref: validate the generation-time-resolved target first, then
        // FALL THROUGH to this node's OWN sibling keywords (2020-12: $ref and
        // siblings BOTH apply). Pure-ref nodes carry no siblings, so their
        // behaviour is unchanged (transparent applicator).
        if (node.applicator == ApplicatorKind::ref && !node.children.empty()) {
            ValidationResult rr = this->validate(node.children[0], instance, path, ctx);
            if (!rr.success) return rr;
        }

        // Applicator walk (allOf/anyOf/oneOf) with transactional annotations.
        ValidationResult appRes = this->walkApplicators(node, instance, path, ctx);
        if (!appRes.success) return appRes;

        // Type flags (type / type-array). `number` matches any JSON number;
        // `integer` matches only numbers whose exact mathematical value is an
        // integer (ADR D1) — so 1 and 1.0 both satisfy `type: integer`, 1.5 does
        // not. All numeric reasoning goes through ExactNumber, never `double`.
        if (node.typeFlags != 0) {
            JsonType const k = instance.kind();
            if (k == JsonType::number) {
                bool const wantNumber = (node.typeFlags &
                    (1u << static_cast<unsigned>(JsonType::number))) != 0;
                bool const wantInteger = (node.typeFlags &
                    (1u << static_cast<unsigned>(JsonType::integer))) != 0;
                bool const isIntegral =
                    wantInteger && numberIsIntegral(instance.asExactNumber());
                if (!wantNumber && !isIntegral)
                    return ValidationResult::invalidAt(path, "type mismatch");
            } else {
                if ((node.typeFlags & (1u << static_cast<unsigned>(k))) == 0)
                    return ValidationResult::invalidAt(path, "type mismatch");
            }
        }

        // Exact-number range constraints.
        if (instance.isNumber()) {
            ExactNumber const n = instance.asExactNumber();
            if (node.hasMinimum && n < node.minimum)
                return ValidationResult::invalidAt(path, "below minimum");
            if (node.hasMaximum && node.maximum < n)
                return ValidationResult::invalidAt(path, "above maximum");
            if (node.hasExclusiveMinimum && !(node.exclusiveMinimum < n))
                return ValidationResult::invalidAt(path, "at or below exclusive minimum");
            if (node.hasExclusiveMaximum && !(n < node.exclusiveMaximum))
                return ValidationResult::invalidAt(path, "at or above exclusive maximum");
            if (node.hasMultipleOf) {
                if (isNonPositive(node.multipleOf))
                    return ValidationResult::invalidAt(
                        path, "invalid schema: multipleOf must be > 0");
                ExactNumber q, r;
                n.divmod(node.multipleOf, q, r);
                if (!r.isZero())
                    return ValidationResult::invalidAt(path, "not a multiple of");
            }
        }

        // Wave-2.5 string constraints. minLength/maxLength count Unicode CODE
        // POINTS (never UTF-8 bytes, never UTF-16 units); each magnitude is
        // compared exactly via ExactNumber so decimal forms (minLength: 2.0)
        // behave identically. pattern uses ECMAScript-subset semantics with an
        // UNANCHORED search (2020-12: a pattern matches by substring search;
        // only the pattern author's own ^ and $ anchors bound it).
        if (instance.kind() == JsonType::string) {
            std::string const s = instance.asString();
            std::size_t const codePoints = countCodePoints(s);
            if (node.hasMinLength && ExactNumber::fromUint(codePoints) < node.minLength)
                return ValidationResult::invalidAt(
                    path, "string shorter than minLength");
            if (node.hasMaxLength && node.maxLength < ExactNumber::fromUint(codePoints))
                return ValidationResult::invalidAt(
                    path, "string longer than maxLength");
            if (node.hasPattern && !ecmaRegexSearch(node.pattern, s))
                return ValidationResult::invalidAt(path, "string does not match pattern");
        }

        // Enum — EXACT deep JSON equality (K-30/K-34). The candidate list is:
        //  1. node.enumNumbers — exact numeric lexemes,
        //  2. node.enumJson — the full deep JSON member set (all kinds),
        //  3. legacy scalar enumStrings / enumBooleans.
        // An EMPTY enum (hasEnumJson with zero members) rejects everything.
        bool enumFound = false;
        if (instance.isNumber() && !node.enumNumbers.empty()) {
            ExactNumber const n = instance.asExactNumber();
            for (ExactNumber const& e : node.enumNumbers)
                if (n == e) { enumFound = true; break; }
        }
        if (!enumFound && node.hasEnumJson) {
            for (boost::json::value const& e : node.enumJson)
                if (deepInstanceEqual(instance, e)) { enumFound = true; break; }
        }
        if (!enumFound && instance.kind() == JsonType::string && !node.enumStrings.empty()) {
            std::string const s = instance.asString();
            for (std::string const& e : node.enumStrings)
                if (s == e) { enumFound = true; break; }
        }
        if (!enumFound && kindIsBool(instance) && !node.enumBooleans.empty()) {
            bool const b = instance.asBool();
            for (bool e : node.enumBooleans)
                if (b == e) { enumFound = true; break; }
        }
        bool const hasAnyEnum = node.hasEnumJson || !node.enumNumbers.empty()
                || !node.enumStrings.empty() || !node.enumBooleans.empty();
        if (hasAnyEnum && !enumFound)
            return ValidationResult::invalidAt(path, "not in enum");
        if (node.hasConst) {
            if (node.constIsJson) {
                if (!deepInstanceEqual(instance, node.constJson))
                    return ValidationResult::invalidAt(path, "const mismatch");
            } else {
                bool match = false;
                if (node.constIsNumber && instance.isNumber())
                    match = (instance.asExactNumber() == node.constNumber);
                else if (node.constIsString && instance.kind() == JsonType::string)
                    match = (instance.asString() == node.constString);
                else if (node.constIsBool && kindIsBool(instance))
                    match = (instance.asBool() == node.constBool);
                if (!match) return ValidationResult::invalidAt(path, "const mismatch");
            }
        }

        // K-22 uniqueItems: array items must be pairwise NOT deep-equal. Because
        // 1 == 1.0 under ExactNumber, [1,2,1.0] is rejected (duplicate). This
        // now runs at container depth with EXACT lexemes on both sides.
        if (node.hasUniqueItems && instance.isArray()) {
            std::size_t const n = instance.size();
            for (std::size_t i = 0; i < n; ++i) {
                for (std::size_t j = i + 1; j < n; ++j) {
                    if (deepRawEqual(instance.atIndex(i), instance.atIndex(j)))
                        return ValidationResult::invalidAt(path, "duplicate item in uniqueItems array");
                }
            }
        }

        // `not` subschema: valid iff the subschema does NOT match. The
        // subschema is evaluated in a fresh branch so no annotations (and no
        // evaluated-coverage markers) leak into the outer evaluation.
        if (node.notSchema != kNoSchema) {
            ValidationContext::Branch b = ctx.beginBranch();
            ValidationResult inner =
                this->validate(node.notSchema, instance, path, ctx);
            ctx.rollbackBranch(b);
            if (inner.success) return ValidationResult::invalidAt(path, "not subschema matched");
        }

        // Object structural traversal (FROZEN §10.3).
        ValidationResult objRes = this->validateObjectTraversal(node, instance, path, ctx);
        if (!objRes.success) return objRes;

        // Array structural traversal (FROZEN §10.3).
        ValidationResult arrRes = this->validateArrayTraversal(node, instance, path, ctx);
        if (!arrRes.success) return arrRes;

        // Best-effort unevaluatedProperties (bool false-form rejects; schema
        // form validates). Only object instances are considered. Any key NOT
        // evaluated within this node's subtree is "unevaluated".
        if (node.hasUnevaluatedProperties && instance.isObject()) {
            std::set<std::string> localEval;
            for (auto const& p : ctx.evaluatedProperties)
                if (unevalEntry.count(p) == 0) localEval.insert(p);
            if (node.unevaluatedPropertiesRejects) {
                for (std::string const& k : instance.objectKeys()) {
                    if (localEval.count(k) == 0)
                        return ValidationResult::invalidAt(
                            path, "unevaluated property '" + k + "'");
                }
            } else if (node.unevaluatedSchema != kNoSchema) {
                for (std::string const& k : instance.objectKeys()) {
                    if (localEval.count(k) == 0) {
                        RawInstance const m = instance.atMember(k.c_str());
                        ValidationPath childPath = path;
                        childPath.enter(k);
                        ValidationResult r =
                            this->validate(node.unevaluatedSchema, m, childPath, ctx);
                        if (!r.success) return r;
                    }
                }
            }
        }

        return ValidationResult::valid();
    }

private:
    static bool kindIsBool(RawInstance const& i) { return i.kind() == JsonType::boolean; }

    /// True when the exact value has zero fractional part (JSON Schema integer).
    static bool numberIsIntegral(ExactNumber const& n) {
        if (n.isZero()) return true;
        ExactNumber const one(ExactNumber::Integer(1), 0);
        ExactNumber q, r;
        n.divmod(one, q, r);
        return r.isZero();
    }

    /// True when n <= 0 (used to reject malformed multipleOf schemas).
    static bool isNonPositive(ExactNumber const& n) {
        return n.isZero() || n < ExactNumber();
    }

    /// Is `key` covered by a declared `properties` entry (never additionally
    /// evaluated)?
    static bool isListedProperty(SchemaNode const& node, std::string const& key) {
        for (PropertyBinding const& pb : node.properties) {
            if (pb.name == key) return true;
        }
        return false;
    }

    // ====================================================================
    // Wave-2.5 ECMAScript-subset pattern engine (K-13 / K-09 / K-10).
    // --------------------------------------------------------------------
    // std::regex (libc++) cannot classify Unicode letters via [[:alpha:]] in
    // the default C locale, and its basic_regex has no locale-aware
    // constructor; the JSTS corpus requires \p{Letter} to match p and other
    // non-ASCII letters. We therefore translate \p{...} letter escapes into
    // explicit code-point ranges before building the wide regex. Everything
    // is decoded to Unicode code points first (never UTF-8 bytes, never
    // UTF-16 units). Patterns match by UNANCHORED search (2020-12: only the
    // pattern's own ^/$ anchor), and an unsupported pattern degrades to an
    // always-match no-op (never a spurious reject).
    // ====================================================================

    /// Letter ranges approximated from the Unicode Letter categories for the
    /// corpus surface (Latin, Greek, Cyrillic, Armenian, Hebrew, Arabic,
    /// Indic, CJK, Hangul). Documented approximation: not exhaustive.
    static char const* letterRanges() {
        return "a-zA-Z"
            "\\u00C0-\\u00FF\\u0100-\\u024F"
            "\\u0370-\\u03FF\\u0400-\\u04FF"
            "\\u0500-\\u052F\\u0531-\\u058F"
            "\\u0591-\\u05FF\\u0600-\\u06FF"
            "\\u0900-\\u097F\\u0A00-\\u0A7F"
            "\\u0B00-\\u0B7F\\u0C00-\\u0C7F"
            "\\u0D00-\\u0D7F\\u1E00-\\u1EFF"
            "\\u3041-\\u3096\\u30A1-\\u30FA"
            "\\u3400-\\u4DBF\\u4E00-\\u9FFF"
            "\\uAC00-\\uD7A3";
    }

    static std::string replaceAll(std::string s, std::string const& from,
                                  std::string const& to) {
        std::size_t pos = 0;
        while ((pos = s.find(from, pos)) != std::string::npos) {
            s.replace(pos, from.size(), to);
            pos += to.size();
        }
        return s;
    }

    /// Translate \p{...} / \P{...} letter escapes into explicit ranges.
    static std::wstring normalizeEcmaPattern(std::string p) {
        p = replaceAll(std::move(p), "\\p{Letter}",
                       "[" + std::string(letterRanges()) + "]");
        p = replaceAll(std::move(p), "\\p{L}",
                       "[" + std::string(letterRanges()) + "]");
        p = replaceAll(std::move(p), "\\P{Letter}",
                       "[^" + std::string(letterRanges()) + "]");
        p = replaceAll(std::move(p), "\\P{L}",
                       "[^" + std::string(letterRanges()) + "]");
        return utf8ToWide(std::move(p));
    }

    /// UTF-8 code-point count: skips continuation bytes; invalid sequences
    /// degrade to a byte count (never a crash).
    static std::size_t countCodePoints(std::string const& s) {
        std::size_t n = 0;
        for (unsigned char c : s) {
            if ((c & 0xC0) != 0x80) ++n;
        }
        return n;
    }

    /// Decode UTF-8 into code-point values stored in wchar_t (32-bit on
    /// macOS/Linux). Invalid bytes pass through verbatim.
    static std::wstring utf8ToWide(std::string const& s) {
        std::wstring out;
        out.reserve(s.size());
        std::size_t i = 0;
        while (i < s.size()) {
            unsigned char c = static_cast<unsigned char>(s[i]);
            std::uint32_t cp = 0;
            std::size_t extra = 0;
            if (c < 0x80) { out.push_back(static_cast<wchar_t>(c)); ++i; continue; }
            if ((c >> 5) == 0x6) { cp = c & 0x1F; extra = 1; }
            else if ((c >> 4) == 0xE) { cp = c & 0x0F; extra = 2; }
            else if ((c >> 3) == 0x1E) { cp = c & 0x07; extra = 3; }
            else { out.push_back(static_cast<wchar_t>(c)); ++i; continue; }
            if (i + extra >= s.size()) {
                out.push_back(static_cast<wchar_t>(c)); ++i; continue;
            }
            bool ok = true;
            for (std::size_t j = 1; j <= extra; ++j) {
                unsigned char cc = static_cast<unsigned char>(s[i + j]);
                if ((cc & 0xC0) != 0x80) { ok = false; break; }
                cp = (cp << 6) | (cc & 0x3F);
            }
            if (!ok) { out.push_back(static_cast<wchar_t>(c)); ++i; continue; }
            out.push_back(static_cast<wchar_t>(cp));
            i += extra + 1;
        }
        return out;
    }

    /// Unanchored ECMAScript-subset search on code points. Unsupported
    /// patterns degrade to an always-match no-op.
    static bool ecmaRegexSearch(std::string const& pattern,
                                std::string const& key) {
        try {
            std::wregex re(normalizeEcmaPattern(pattern),
                           std::regex_constants::ECMAScript);
            std::wstring const wide = utf8ToWide(key);
            return std::regex_search(wide, re);
        } catch (std::regex_error const&) {
            return true;
        }
    }

    /// allOf/anyOf/oneOf applicator walk with transactional annotation merging.
    /// Only SUCCESSFUL branches retain their evaluated-property/item coverage
    /// and annotations (required for correct anyOf/oneOf + unevaluated*).
    ValidationResult walkApplicators(SchemaNode const& node, RawInstance const& instance,
                                     ValidationPath& path, ValidationContext& ctx) const {
        switch (node.applicator) {
            case ApplicatorKind::none:
            case ApplicatorKind::ref:   // handled by the caller's fall-through
                return ValidationResult::valid();
            case ApplicatorKind::allOf: {
                for (SchemaIndex c : node.children) {
                    ValidationResult r = this->validate(c, instance, path, ctx);
                    if (!r.success) return r;
                }
                return ValidationResult::valid();
            }
            case ApplicatorKind::anyOf: {
                bool any = false;
                for (SchemaIndex c : node.children) {
                    ValidationContext::Branch b = ctx.beginBranch();
                    ValidationResult r = this->validate(c, instance, path, ctx);
                    if (r.success) { any = true; ctx.commitBranch(b); }
                    else { ctx.rollbackBranch(b); }
                }
                if (any) return ValidationResult::valid();
                return ValidationResult::invalidAt(path, "anyOf: no branch matched");
            }
            case ApplicatorKind::oneOf: {
                std::size_t matches = 0;
                for (SchemaIndex c : node.children) {
                    ValidationContext::Branch b = ctx.beginBranch();
                    ValidationResult r = this->validate(c, instance, path, ctx);
                    if (r.success) { ++matches; ctx.commitBranch(b); }
                    else { ctx.rollbackBranch(b); }
                }
                if (matches == 1) return ValidationResult::valid();
                if (matches == 0)
                    return ValidationResult::invalidAt(path, "oneOf: no branch matched");
                return ValidationResult::invalidAt(path, "oneOf: more than one branch matched");
            }
            default:
                break;
        }
        return ValidationResult::valid();
    }

    /// Object structural traversal. Only active for object instances; ignores
    /// non-object kinds (as JSON Schema requires).
    ValidationResult validateObjectTraversal(SchemaNode const& node, RawInstance const& instance,
                                             ValidationPath& path, ValidationContext& ctx) const {
        if (!instance.isObject()) return ValidationResult::valid();

        // minProperties / maxProperties — ExactNumber from the instance size,
        // never a double; a decimal bound (1.0) compares equal to 1.
        std::size_t const n = instance.size();
        if (node.hasMinProperties && ExactNumber::fromUint(n) < node.minProperties)
            return ValidationResult::invalidAt(path, "object has fewer properties than minProperties");
        if (node.hasMaxProperties && node.maxProperties < ExactNumber::fromUint(n))
            return ValidationResult::invalidAt(path, "object has more properties than maxProperties");

        // required: every listed name must be PRESENT (a null-valued member is
        // present; absence is what fails).
        for (std::string const& rn : node.required) {
            if (!instance.hasMember(rn.c_str()))
                return ValidationResult::invalidAt(path, "missing required property '" + rn + "'");
        }

        // Declared properties: validate each present member against its
        // property subschema; absent members are not evaluated.
        for (PropertyBinding const& pb : node.properties) {
            RawInstance m = instance.atMember(pb.name.c_str());
            if (m.value == nullptr) continue;
            ValidationPath childPath = path;
            childPath.enter(pb.name);
            ValidationResult r = this->validate(pb.node, m, childPath, ctx);
            if (!r.success) return r;
            ctx.evaluatedProperties.insert(pb.name);
        }

        // Wave-2.5 patternProperties: EVERY member whose name matches any
        // pattern is validated against that pattern's schema (a name matching
        // several patterns is validated by ALL of them, 2020-12). Matched
        // members are evaluated here and are NEVER re-evaluated by
        // additionalProperties (patterns take precedence, including over
        // `additionalProperties: false`).
        std::set<std::string> patternMatched;
        if (!node.patternProperties.empty()) {
            for (std::string const& k : instance.objectKeys()) {
                for (SchemaNode::PatternPropertyBinding const& pb
                         : node.patternProperties) {
                    if (ecmaRegexSearch(pb.regex, k)) {
                        patternMatched.insert(k);
                        RawInstance const m = instance.atMember(k.c_str());
                        ValidationPath childPath = path;
                        childPath.enter(k);
                        ValidationResult r =
                            this->validate(pb.node, m, childPath, ctx);
                        if (!r.success) return r;
                        ctx.evaluatedProperties.insert(k);
                    }
                }
            }
        }

        // propertyNames: the subschema applies to EVERY member NAME (as a
        // string instance), independently of properties/additionalProperties.
        if (node.propertyNames != kNoSchema) {
            for (std::string const& k : instance.objectKeys()) {
                boost::json::value keyValue(k);
                RawInstance keyInst(&keyValue);
                ValidationPath childPath = path;
                childPath.enter(k);
                ValidationResult r =
                    this->validate(node.propertyNames, keyInst, childPath, ctx);
                if (!r.success) return r;
            }
        }

        // additionalProperties tri-state. Listed properties AND
        // pattern-matched members are NEVER additionally evaluated: they are
        // skipped. Unmatched keys are either allowed (absent/allowed),
        // rejected (false), or validated against the additionalProperties
        // schema.
        auto isCovered = [&](std::string const& k) {
            if (isListedProperty(node, k)) return true;
            return patternMatched.count(k) != 0;
        };
        if (node.additionalProperties == AdditionalPropertiesKind::reject ||
            node.additionalProperties == AdditionalPropertiesKind::schema) {
            for (std::string const& k : instance.objectKeys()) {
                if (isCovered(k)) continue;
                RawInstance m = instance.atMember(k.c_str());
                if (node.additionalProperties == AdditionalPropertiesKind::reject)
                    return ValidationResult::invalidAt(
                        path, "additionalProperties:false rejects '" + k + "'");
                if (node.additionalSchema != kNoSchema) {
                    ValidationPath childPath = path;
                    childPath.enter(k);
                    ValidationResult r =
                        this->validate(node.additionalSchema, m, childPath, ctx);
                    if (!r.success) return r;
                }
                ctx.evaluatedProperties.insert(k);
            }
        } else if (node.additionalProperties == AdditionalPropertiesKind::allowed) {
            for (std::string const& k : instance.objectKeys()) {
                if (isCovered(k)) continue;
                ctx.evaluatedProperties.insert(k);
            }
        }

        return ValidationResult::valid();
    }

    /// Array structural traversal. Only active for array instances.
    ValidationResult validateArrayTraversal(SchemaNode const& node, RawInstance const& instance,
                                            ValidationPath& path, ValidationContext& ctx) const {
        if (!instance.isArray()) return ValidationResult::valid();

        std::size_t const n = instance.size();
        if (node.hasMinItems && ExactNumber::fromUint(n) < node.minItems)
            return ValidationResult::invalidAt(path, "array has fewer items than minItems");
        if (node.hasMaxItems && node.maxItems < ExactNumber::fromUint(n))
            return ValidationResult::invalidAt(path, "array has more items than maxItems");

        // prefixItems: each schema applies to the item at ITS index.
        std::size_t const prefixCount = node.prefixItems.size();
        for (std::size_t i = 0; i < prefixCount && i < n; ++i) {
            RawInstance m = instance.atIndex(i);
            ValidationPath childPath = path;
            childPath.enterIndex(i);
            ValidationResult r = this->validate(node.prefixItems[i], m, childPath, ctx);
            if (!r.success) return r;
            ctx.evaluatedItems.insert(i);
        }

        // items: applies to the REMAINDER (indices >= prefixItems.size()).
        if (node.items != kNoSchema) {
            for (std::size_t i = prefixCount; i < n; ++i) {
                RawInstance m = instance.atIndex(i);
                ValidationPath childPath = path;
                childPath.enterIndex(i);
                ValidationResult r = this->validate(node.items, m, childPath, ctx);
                if (!r.success) return r;
                ctx.evaluatedItems.insert(i);
            }
        }

        return ValidationResult::valid();
    }

    SchemaResourceRegistry const& registry_;
};

} // namespace oas31

#endif // OAS31_VALIDATOR_HPP_
