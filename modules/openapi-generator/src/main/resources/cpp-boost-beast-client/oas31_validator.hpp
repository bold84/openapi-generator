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

        // Enum / const (exact-number aware for numeric kinds).
        // JSON Schema `enum` requires a deep-equal match on the ENTIRE instance.
        // This slice's IR carries only scalar enum buckets (numbers/strings/
        // booleans), so the instance must equal one of the entries of the bucket
        // matching its kind — and, critically, an instance whose kind has no
        // representable bucket (null/array/object) is NOT equal to any entry and
        // therefore FAILS whenever the node declares any enum values.
        if (!node.enumNumbers.empty() || !node.enumStrings.empty() ||
            !node.enumBooleans.empty()) {
            bool found = false;
            if (instance.isNumber()) {
                ExactNumber const n = instance.asExactNumber();
                for (ExactNumber const& e : node.enumNumbers)
                    if (n == e) { found = true; break; }
            } else if (instance.kind() == JsonType::string) {
                std::string const s = instance.asString();
                for (std::string const& e : node.enumStrings)
                    if (s == e) { found = true; break; }
            } else if (kindIsBool(instance)) {
                bool const b = instance.asBool();
                for (bool e : node.enumBooleans)
                    if (b == e) { found = true; break; }
            }
            if (!found) return ValidationResult::invalidAt(path, "not in enum");
        }
        if (node.hasConst) {
            bool match = false;
            if (node.constIsNumber && instance.isNumber())
                match = (instance.asExactNumber() == node.constNumber);
            else if (node.constIsString && instance.kind() == JsonType::string)
                match = (instance.asString() == node.constString);
            else if (node.constIsBool && kindIsBool(instance))
                match = (instance.asBool() == node.constBool);
            if (!match) return ValidationResult::invalidAt(path, "const mismatch");
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
