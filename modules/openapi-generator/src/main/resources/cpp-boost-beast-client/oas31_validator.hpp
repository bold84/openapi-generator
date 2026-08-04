// ============================================================================
// oas31_validator.hpp — Wave-1 shared SchemaEvaluator + ValidationContext (ADR
// D2/D3/D5, Option B). Thin validate_<id> dispatch dispatches into this shared
// schema interpreter over the densified IR tables in oas31_ir.hpp.
//
// HEADER-ONLY. Built under -Werror with g++ -std=c++17.
//
// Skeletal slice: RawInstance, ValidationContext, AnnotationStore and the
// SchemaEvaluator API + basic boolean/type/exact-number/not handling are
// implemented. Full applicator walk (allOf/anyOf/oneOf), annotation
// transaction wiring, unevaluated* and $dynamicRef are LATER-agents' scope.
// ============================================================================
#ifndef OAS31_VALIDATOR_HPP_
#define OAS31_VALIDATOR_HPP_

#include "oas31_ir.hpp"
#include "oas31_deep_equal.hpp"

#include <boost/json.hpp>

#include <cstddef>
#include <cstdint>
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
// (representability gate) PLUS an optional exact numeric lexeme captured by the
// number-lexeme tokenizer (runner agent, later slice). When the lexeme is
// present, asExactNumber() is exact; otherwise it degrades to the Boost.JSON
// value kind (lossy) — recorded as an honest limitation until the tokenizer.
// ============================================================================
struct RawInstance {
    boost::json::value const* value = nullptr;
    std::string numericLexeme;   // exact lexeme when this instance is a JSON number (may be empty)

    RawInstance() = default;
    explicit RawInstance(boost::json::value const* v) : value(v) {}
    RawInstance(boost::json::value const* v, std::string lexeme)
        : value(v), numericLexeme(std::move(lexeme)) {}

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

    RawInstance atMember(char const* key) const {
        if (value != nullptr && value->is_object()) {
            auto const* obj = value->if_object();
            auto it = obj->find(key);
            if (it != obj->end()) return RawInstance(&it->value());
        }
        return RawInstance();
    }

    RawInstance atIndex(std::size_t i) const {
        if (value != nullptr && value->is_array()) {
            auto const& arr = value->as_array();
            if (i < arr.size()) return RawInstance(&arr[i]);
        }
        return RawInstance();
    }

    /// Exact number when a lexeme is available; otherwise converted (lossy).
    ExactNumber asExactNumber() const {
        if (!numericLexeme.empty()) return ExactNumber::parseLexeme(numericLexeme);
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
// positionally; the instance side is lexeme-first (exact), degrading to value
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
// value; both sides may carry numeric lexemes, compared via ExactNumber.
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
                RawInstance m = a.atMember(kv.key().data());
                if (m.value == nullptr) return false;   // a lacks this key
                if (!deepRawEqual(m, RawInstance(&kv.value()))) return false;
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

    /// Validate a single schema object. Later slices add applicator walking,
    /// annotation emit, and unevaluated* tracking.
    ValidationResult validateSchemaNode(SchemaNode const& node, RawInstance const& instance,
                                        ValidationPath& path, ValidationContext& ctx) const {
        // Boolean value-schema (OAS 3.1).
        if (node.booleanValue == BooleanValue::true_) return ValidationResult::valid();
        if (node.booleanValue == BooleanValue::false_)
            return ValidationResult::invalidAt(path, "boolean value-schema false");

        // K-29 $ref: transparent applicator to the generation-time-resolved target.
        if (node.applicator == ApplicatorKind::ref && !node.children.empty()) {
            return this->validate(node.children[0], instance, path, ctx);
        }

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
                // JSON Schema requires multipleOf > 0. With a non-positive
                // divisor the exact divmod is undefined (0) or unrepresentable
                // (negative); fail closed on the malformed schema rather than
                // producing a bogus verdict or throwing.
                if (isNonPositive(node.multipleOf))
                    return ValidationResult::invalidAt(
                        path, "invalid schema: multipleOf must be > 0");
                ExactNumber q, r;
                n.divmod(node.multipleOf, q, r);
                if (!r.isZero())
                    return ValidationResult::invalidAt(path, "not a multiple of");
            }
        }

        // Enum — EXACT deep JSON equality (K-30/K-34). The candidate list is:
        //  1. node.enumNumbers — exact numeric lexemes (handles huge numbers
        //     beyond uint64/double without any boost::json double round-trip),
        //  2. node.enumJson — the full deep JSON member set (all kinds), compared
        //     via deepInstanceEqual (ExactNumber for every number),
        //  3. legacy scalar enumStrings / enumBooleans (backward-compat only).
        // A numeric instance is matched against the EXACT numeric bucket first so a
        // big const/enum is never decided by a lossy double.
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
        // 1 == 1.0 under ExactNumber, [1,2,1.0] is rejected (duplicate).
        if (node.hasUniqueItems && instance.isArray()) {
            std::size_t const n = instance.size();
            for (std::size_t i = 0; i < n; ++i) {
                for (std::size_t j = i + 1; j < n; ++j) {
                    if (deepRawEqual(instance.atIndex(i), instance.atIndex(j)))
                        return ValidationResult::invalidAt(path, "duplicate item in uniqueItems array");
                }
            }
        }


        // `not` subschema reference: valid iff the subschema does NOT match.
        if (node.notSchema != kNoSchema) {
            ValidationResult inner =
                this->validate(node.notSchema, instance, path, ctx);
            if (inner.success) return ValidationResult::invalidAt(path, "not subschema matched");
        }

        // NOTE: allOf/anyOf/oneOf applicator walking, annotation transaction
        // wiring, and unevaluated* tracking are LATER agents' scope (Wave-1).
        // This skeletal slice returns valid for composing applicators.
        (void)ctx;

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

    SchemaResourceRegistry const& registry_;
};

} // namespace oas31

#endif // OAS31_VALIDATOR_HPP_
