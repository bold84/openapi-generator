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
    // Location-scoped evaluation coverage: the TOP of each stack is the
    // CURRENT instance location (an object's evaluated member keys / an
    // array's evaluated item indices). Descending into a nested object/array
    // value pushes a fresh location, so a nested object's keys can never leak
    // into the parent's unevaluated* check (instance-location correctness:
    // unevaluatedProperties g41/g42/g43).
    std::vector<std::set<std::string>> evaluatedPropertiesStack{{}};
    std::vector<std::set<std::size_t>> evaluatedItemsStack{{}};

    std::set<std::string>& curProps() { return evaluatedPropertiesStack.back(); }
    std::set<std::size_t>& curItems() { return evaluatedItemsStack.back(); }
    // Wave-3 dynamic scope: ordered chain of synthetic resource ids NAVIGATED
    // during evaluation (outermost = element 0). Pushed when evaluation
    // ENTERS a resource (resource id differs from the current top — every
    // evaluation-path resource joins the scope, not only resourceRoot rows,
    // which also push re-entries), popped when leaving. Frame counts are
    // deliberately NOT deduplicated (2020-12: re-entering a resource adds a
    // fresh frame). Branch snapshots copy the scope so failed anyOf/oneOf
    // branches do not leak frames.
    std::vector<int> dynamicScope;
    // Wave-4.2: the effective resource of the row currently being validated
    // (its own dynamicResource when stamped, else the innermost enclosing
    // marked resource — the scope top before the row's own push). Updated by
    // SchemaEvaluator::validate(); consumed by the validation-vocabulary
    // gates (dialect $vocabulary).
    int currentValidationRes = 0;
    void pushScopeFrame(int resId) { dynamicScope.push_back(resId); }
    void popScopeFrame() { if (!dynamicScope.empty()) dynamicScope.pop_back(); }
    /// Outermost-to-innermost walk for $dynamicRef: 2020-12 applies the anchor
    /// of the OUTERMOST resource in the dynamic scope that declares it, else
    /// the static fallback. Returns the anchor target index or kNoSchema.
    SchemaIndex resolveDynamicAnchor(
            SchemaResourceRegistry const& reg, std::string const& name) const {
        for (int resId : dynamicScope) {
            if (resId < 0 || static_cast<std::size_t>(resId)
                    >= reg.dynamicAnchorTables.size()) continue;
            for (auto const& entry : reg.dynamicAnchorTables[resId]) {
                if (entry.first == name) return entry.second;
            }
        }
        return kNoSchema;
    }
    void pushLocation() {
        evaluatedPropertiesStack.emplace_back();
        evaluatedItemsStack.emplace_back();
    }
    void popLocation() {
        evaluatedPropertiesStack.pop_back();
        evaluatedItemsStack.pop_back();
    }

    struct Branch {
        std::size_t annotationMark = 0;
        std::vector<std::set<std::string>> evaluatedProperties;
        std::vector<std::set<std::size_t>> evaluatedItems;
        std::vector<int> dynamicScope;
    };

    Branch beginBranch() {
        Branch b;
        b.annotationMark = annotations.snapshot();
        b.evaluatedProperties = evaluatedPropertiesStack;
        b.evaluatedItems = evaluatedItemsStack;
        b.dynamicScope = dynamicScope;
        return b;
    }
    /// Keep this branch's additions (annotations + evaluated coverage) in place.
    void commitBranch(Branch const&) { /* current state already includes branch output */ }
    /// Discard this branch's output and restore annotations + coverage + scope.
    void rollbackBranch(Branch const& b) {
        annotations.rollbackTo(b.annotationMark);
        evaluatedPropertiesStack = b.evaluatedProperties;
        evaluatedItemsStack = b.evaluatedItems;
        dynamicScope = b.dynamicScope;
    }

    /// Run a child evaluator against the CURRENT (base) coverage, capture only
    /// the additions made at the current location into `acc*`, then restore
    /// the coverage stacks to the base. This is the 2020-12 annotation-scope
    /// rule: a subschema's unevaluated* check must NOT see its SIBLINGS'
    /// coverage ("can't see inside cousins", unevaluatedProperties g22/g23/
    /// g28) — the parent merges the accumulated additions after the group.
    template <typename Fn>
    ValidationResult evaluateAndCapture(Fn fn,
                                        std::set<std::string>& accProps,
                                        std::set<std::size_t>& accItems) {
        std::vector<std::set<std::string>> baseProps = evaluatedPropertiesStack;
        std::vector<std::set<std::size_t>> baseItems = evaluatedItemsStack;
        std::vector<int> const baseScope = dynamicScope;
        std::set<std::string> const bProps = curProps();
        std::set<std::size_t> const bItems = curItems();
        ValidationResult r = fn();
        for (auto const& k : curProps())
            if (bProps.count(k) == 0) accProps.insert(k);
        for (auto const& it : curItems())
            if (bItems.count(it) == 0) accItems.insert(it);
        evaluatedPropertiesStack = baseProps;
        evaluatedItemsStack = baseItems;
        dynamicScope = baseScope;
        return r;
    }

    /// success-only capture: additions are accumulated ONLY when `fn`
    /// validates (anyOf/oneOf: a FAILED branch must never contribute
    /// evaluated-property/item coverage).
    template <typename Fn>
    ValidationResult evaluateAndCaptureValid(Fn fn,
                                             std::set<std::string>& accProps,
                                             std::set<std::size_t>& accItems) {
        std::vector<std::set<std::string>> baseProps = evaluatedPropertiesStack;
        std::vector<std::set<std::size_t>> baseItems = evaluatedItemsStack;
        std::vector<int> const baseScope = dynamicScope;
        std::set<std::string> const bProps = curProps();
        std::set<std::size_t> const bItems = curItems();
        ValidationResult r = fn();
        if (r.success) {
            for (auto const& k : curProps())
                if (bProps.count(k) == 0) accProps.insert(k);
            for (auto const& it : curItems())
                if (bItems.count(it) == 0) accItems.insert(it);
        }
        evaluatedPropertiesStack = baseProps;
        evaluatedItemsStack = baseItems;
        dynamicScope = baseScope;
        return r;
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
    /// Wave-3/4 dynamic scope: entering a schema RESOURCE pushes a
    /// dynamic-scope frame. Frames are pushed when the row's synthetic
    /// resource differs from the current top-of-scope (every evaluation-path
    /// resource joins 2020-12's dynamic scope — not only resourceRoot rows,
    /// which otherwise miss embedded-resource members like g20's $defs.stuff
    /// chain) and re-entering an already-active resource pushes again (frame
    /// counts are never deduplicated). RAII — popped on EVERY return path.
    ValidationResult validate(SchemaIndex node, RawInstance const& instance,
                              ValidationPath& path, ValidationContext& ctx) const {
        SchemaNode const& n = registry_.node(node);
        // Wave-4.2: effective resource for vocabulary gating — the row's own
        // synthetic id when stamped, else the innermost enclosing marked
        // resource (the scope top BEFORE this row's own push).
        if (n.dynamicResource != 0) {
            ctx.currentValidationRes = n.dynamicResource;
        } else if (!ctx.dynamicScope.empty()) {
            ctx.currentValidationRes = ctx.dynamicScope.back();
        } else {
            ctx.currentValidationRes = 0;
        }
        if (n.dynamicResource != 0 || n.resourceRoot) {
            int const top = ctx.dynamicScope.empty()
                    ? -1 : ctx.dynamicScope.back();
            if (n.dynamicResource != top || n.resourceRoot) {
                ctx.pushScopeFrame(n.dynamicResource);
                ValidationResult r = validateSchemaNode(n, instance, path, ctx);
                ctx.popScopeFrame();
                return r;
            }
        }
        return validateSchemaNode(registry_.node(node), instance, path, ctx);
    }

    /// Validate a single schema object against one raw instance.
    /// Wave-4.3 (GA1): record a node's annotation keywords at the current
    /// instance location. Every record carries keyword, instance JSON
    /// pointer, schema-location path (emitter row name — hoisted names
    /// encode the reference traversal), absolute schema-location URI
    /// (synthetic resource: urn:oas31:res:<id>) and value(s) as JSON text.
    /// $comment is shape-checked at generation time and NEVER emitted.
    void collectAnnotations(SchemaNode const& node,
                            ValidationPath const& path,
                            ValidationContext& ctx) const {
        auto add = [&](std::string const& kw, std::string const& val) {
            if (val.empty()) return;
            Annotation a;
            a.keyword = kw;
            // RFC 6901 instance JSON pointer ('' for the whole document).
            a.instancePath = path.str().empty()
                    ? std::string() : "/" + path.str();
            a.schemaPath = node.sourceName;
            a.absSchemaUri = "urn:oas31:res:"
                    + std::to_string(node.dynamicResource);
            a.value = val;
            ctx.annotations.add(std::move(a));
        };
        add("title", node.annTitle);
        add("description", node.annDescription);
        add("default", node.annDefaultJson);
        for (auto const& e : node.annExamplesJson) add("examples", e);
        add("deprecated", node.annDeprecatedJson);
        add("readOnly", node.annReadOnlyJson);
        add("writeOnly", node.annWriteOnlyJson);
        add("format", node.annFormat);
        add("contentEncoding", node.annContentEncoding);
        add("contentMediaType", node.annContentMediaType);
        if (node.annContentSchema >= 0 && node.annContentSchema
                < static_cast<int>(registry_.nodes.size())) {
            // contentSchema is annotation-only: its value is the child
            // schema location, never an evaluated instance check.
            add("contentSchema",
                registry_.nodes[static_cast<std::size_t>(
                        node.annContentSchema)].sourceName);
        }
        for (auto const& e : node.annExtras) add(e.first, e.second);
        // $comment: no annotation output (GA1).
    }

    ValidationResult validateSchemaNode(SchemaNode const& node, RawInstance const& instance,
                                        ValidationPath& path, ValidationContext& ctx) const {
        // Wave-4.2 dialect: validation-vocabulary gating. A resource whose
        // metaschema's $vocabulary omits the validation vocabulary runs its
        // validation keywords as inert annotations (2020-12 §8.1.2):
        // type/enum/const/ranges/lengths/counts/required/dependentRequired
        // are skipped; applicators, core boolean schemas and annotations are
        // unaffected. ctx.currentValidationRes holds the row's effective
        // resource (see validate()).
        bool const vInert =
            !registry_.validationVocabActive(ctx.currentValidationRes);

        // Boolean value-schema (OAS 3.1).
        if (node.booleanValue == BooleanValue::true_) return ValidationResult::valid();
        if (node.booleanValue == BooleanValue::false_)
            return ValidationResult::invalidAt(path, "boolean value-schema false");

        // Wave-2 unevaluatedProperties/Items: snapshot the evaluated coverage
        // at ENTRY so the exit check only considers what was evaluated WITHIN
        // this node's subtree (applicators / object/array traversal included).
        // Best-effort implementation of the 2020-12 annotation semantics
        // (FROZEN §10.3) with location-scoped coverage stacks.
        std::set<std::string> unevalEntry;
        if (node.hasUnevaluatedProperties && instance.isObject()) {
            unevalEntry = ctx.curProps();
        }
        std::set<std::size_t> unevalItemsEntry;
        if (node.hasUnevaluatedItems && instance.isArray()) {
            unevalItemsEntry = ctx.curItems();
        }

        // K-29 $ref: validate the generation-time-resolved target first, then
        // FALL THROUGH to this node's OWN sibling keywords (2020-12: $ref and
        // siblings BOTH apply). Pure-ref nodes carry no siblings, so their
        // behaviour is unchanged (transparent applicator). Wave-3: a
        // $dynamicRef node carries dynamicRefAnchor + the STATIC fallback in
        // children[0]; when the anchor resolves in the dynamic scope
        // (outermost declaring resource wins), the resolved target replaces
        // the fallback — otherwise the static fallback applies unchanged.
        if (node.applicator == ApplicatorKind::ref && !node.children.empty()) {
            SchemaIndex target = node.children[0];
            if (!node.dynamicRefAnchor.empty()
                    && dynamicAnchorEligible(target, node.dynamicRefAnchor)) {
                SchemaIndex dyn =
                    ctx.resolveDynamicAnchor(registry_, node.dynamicRefAnchor);
                if (dyn != kNoSchema) target = dyn;
            }
            ValidationResult rr = this->validate(target, instance, path, ctx);
            if (!rr.success) return rr;
        }

        // Applicator walk (allOf/anyOf/oneOf) with transactional annotations.
        ValidationResult appRes = this->walkApplicators(node, instance, path, ctx);
        if (!appRes.success) return appRes;

        // Type flags (type / type-array). `number` matches any JSON number;
        // `integer` matches only numbers whose exact mathematical value is an
        // integer (ADR D1) — so 1 and 1.0 both satisfy `type: integer`, 1.5 does
        // not. All numeric reasoning goes through ExactNumber, never `double`.
        if (!vInert && node.typeFlags != 0) {
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
        if (!vInert && instance.isNumber()) {
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
        if (!vInert && instance.kind() == JsonType::string) {
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
        if (!vInert && hasAnyEnum && !enumFound)
            return ValidationResult::invalidAt(path, "not in enum");
        if (!vInert && node.hasConst) {
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
        int const savedValidationRes = ctx.currentValidationRes;
        ValidationResult objRes = this->validateObjectTraversal(node, instance, path, ctx);
        if (!objRes.success) return objRes;
        ctx.currentValidationRes = savedValidationRes;

        // Array structural traversal (FROZEN §10.3).
        ValidationResult arrRes = this->validateArrayTraversal(node, instance, path, ctx);
        if (!arrRes.success) return arrRes;
        ctx.currentValidationRes = savedValidationRes;

        // if/then/else + dependentSchemas (annotations of the APPLIED branches
        // count for unevaluated*; evaluated BEFORE the uneval check so the
        // exit block sees them). Dependent triggers: the dependent schema
        // validates in the current context whenever its key is present.
        {
            ValidationResult condRes = this->walkConditional(node, instance, path, ctx);
            if (!condRes.success) return condRes;
            if (instance.isObject()) {
                for (auto const& dep : node.dependentSchemas) {
                    if (instance.atMember(dep.first.c_str()).value != nullptr) {
                        ValidationResult r =
                            this->validate(dep.second, instance, path, ctx);
                        if (!r.success) return r;
                    }
                }
            }
        }

        // Best-effort unevaluatedProperties (bool false-form rejects; schema
        // form validates and then marks keys evaluated; boolean-true form
        // marks every remaining key evaluated). Only object instances are
        // considered. A key is "unevaluated" when it was not yet evaluated at
        // this node's entry and not added by this node's own subtree.
        if (node.hasUnevaluatedProperties && instance.isObject()) {
            std::vector<std::string> unevalKeys;
            for (std::string const& k : instance.objectKeys()) {
                if (unevalEntry.count(k) == 0 && ctx.curProps().count(k) == 0) {
                    unevalKeys.push_back(k);
                }
            }
            if (node.unevaluatedPropertiesRejects) {
                if (!unevalKeys.empty())
                    return ValidationResult::invalidAt(
                        path, "unevaluated property '" + unevalKeys.front() + "'");
            } else if (node.unevaluatedSchema != kNoSchema) {
                for (std::string const& k : unevalKeys) {
                    RawInstance const m = instance.atMember(k.c_str());
                    ValidationPath childPath = path;
                    childPath.enter(k);
                    ctx.pushLocation();
                    ValidationResult r =
                        this->validate(node.unevaluatedSchema, m, childPath, ctx);
                    ctx.popLocation();
                    if (!r.success) return r;
                    ctx.curProps().insert(k);  // examined by unevaluatedProperties -> evaluated
                }
            } else {
                // boolean-true form: every remaining key is examined.
                ctx.curProps().insert(unevalKeys.begin(), unevalKeys.end());
            }
        }

        // Wave-2.5 unevaluatedItems: array analogue with evaluated ITEM
        // positions at the current array location.
        if (node.hasUnevaluatedItems && instance.isArray()) {
            std::vector<std::size_t> unevalIdx;
            for (std::size_t i = 0; i < instance.size(); ++i) {
                if (unevalItemsEntry.count(i) == 0 && ctx.curItems().count(i) == 0) {
                    unevalIdx.push_back(i);
                }
            }
            if (node.unevaluatedItemsRejects) {
                if (!unevalIdx.empty())
                    return ValidationResult::invalidAt(
                        path, "unevaluated item at index " + std::to_string(unevalIdx.front()));
            } else if (node.unevaluatedItemsSchema != kNoSchema) {
                for (std::size_t i : unevalIdx) {
                    RawInstance const m = instance.atIndex(i);
                    ValidationPath childPath = path;
                    childPath.enterIndex(i);
                    ctx.pushLocation();
                    ValidationResult r =
                        this->validate(node.unevaluatedItemsSchema, m, childPath, ctx);
                    ctx.popLocation();
                    if (!r.success) return r;
                    ctx.curItems().insert(i);  // examined -> evaluated
                }
            } else {
                ctx.curItems().insert(unevalIdx.begin(), unevalIdx.end());
            }
        }

        // Wave-4.3 (GA1): annotation collection at the SUCCESSFUL closure of
        // this node. Every annotation keyword present on the node is recorded
        // with the instance location (JSON pointer), the schema location
        // (emitter row name — hoisted names encode reference traversal), the
        // absolute schema-location URI (synthetic resource urn) and the
        // keyword's value (JSON text). $comment produces no annotation output.
        collectAnnotations(node, path, ctx);

        return ValidationResult::valid();
    }

private:
    static bool kindIsBool(RawInstance const& i) { return i.kind() == JsonType::boolean; }

    /// 2020-12 §8.2.3.2: dynamic replacement at a $dynamicRef applies ONLY
    /// when the initially resolved target is itself a $dynamicAnchor
    /// declaration with the same name. A plain $anchor or a non-matching
    /// $dynamicAnchor initial target behaves exactly like $ref (dynamicRef
    /// g7/g8/g10: never scope-walked). `wrapperIndex` is the __dynref_
    /// static-fallback container row; its oneOf child is the anchored
    /// subschema (the DECLARING row carries dynamicAnchorName). The model
    /// layer may insert pure-$ref hops between the wrapper and the declaring
    /// row (composed-ref inlining), so the check follows ref chains.
    bool dynamicAnchorEligible(SchemaIndex wrapperIndex,
                               std::string const& name) const {
        SchemaIndex cur = wrapperIndex;
        for (int hop = 0; hop < 16 && cur != kNoSchema; ++hop) {
            SchemaNode const& w = registry_.node(cur);
            if (w.dynamicAnchorName == name) return true;
            // wrapper rows carry the anchored content as their oneOf child
            SchemaIndex content = w.oneOfChildren.empty()
                    ? kNoSchema : w.oneOfChildren[0];
            if (content == kNoSchema) return false;
            SchemaNode const& c = registry_.node(content);
            if (c.dynamicAnchorName == name) return true;
            if (c.applicator == ApplicatorKind::ref && !c.children.empty()) {
                cur = c.children[0];   // follow the ref hop
                continue;
            }
            return false;
        }
        return false;
    }

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

    /// allOf/anyOf/oneOf applicator walk with transactional, sibling-isolated
    /// annotation merging. All three applicators may coexist (2020-12); each
    /// group walks its own member rows. Members are evaluated against the
    /// walk-entry coverage (a member's unevaluated* check never sees its
    /// SIBLINGS' coverage — "can't see inside cousins"), and only SUCCESSFUL
    /// branches contribute evaluated-property/item coverage and annotations —
    /// the rule unevaluated* relies on. (The REF applicator hop lives in
    /// validateSchemaNode; pure-ref nodes have empty member lists here.)
    ValidationResult walkApplicators(SchemaNode const& node, RawInstance const& instance,
                                     ValidationPath& path, ValidationContext& ctx) const {
        std::set<std::string> accProps;
        std::set<std::size_t> accItems;

        // allOf: EVERY member must validate; all successful members contribute.
        for (SchemaIndex c : node.allOfChildren) {
            ValidationContext::Branch b = ctx.beginBranch();
            ValidationResult r = ctx.evaluateAndCaptureValid(
                [&]() { return this->validate(c, instance, path, ctx); },
                accProps, accItems);
            if (!r.success) {
                ctx.rollbackBranch(b);
                return r;
            }
        }
        // anyOf: at least one must validate; successful branches contribute.
        if (!node.anyOfChildren.empty()) {
            bool any = false;
            std::set<std::string> grpProps;
            std::set<std::size_t> grpItems;
            for (SchemaIndex c : node.anyOfChildren) {
                ValidationContext::Branch b = ctx.beginBranch();
                ValidationResult r = ctx.evaluateAndCaptureValid(
                    [&]() { return this->validate(c, instance, path, ctx); },
                    grpProps, grpItems);
                if (r.success) { any = true; }
                else { ctx.rollbackBranch(b); }
            }
            if (!any) return ValidationResult::invalidAt(path, "anyOf: no branch matched");
            accProps.insert(grpProps.begin(), grpProps.end());
            accItems.insert(grpItems.begin(), grpItems.end());
        }
        // oneOf: EXACTLY one must validate; the successful branch contributes.
        if (!node.oneOfChildren.empty()) {
            std::size_t matches = 0;
            std::set<std::string> grpProps;
            std::set<std::size_t> grpItems;
            for (SchemaIndex c : node.oneOfChildren) {
                ValidationContext::Branch b = ctx.beginBranch();
                ValidationResult r = ctx.evaluateAndCaptureValid(
                    [&]() { return this->validate(c, instance, path, ctx); },
                    grpProps, grpItems);
                if (r.success) { ++matches; }
                else { ctx.rollbackBranch(b); }
            }
            if (matches != 1) {
                return ValidationResult::invalidAt(path,
                    matches == 0 ? "oneOf: no branch matched"
                                 : "oneOf: more than one branch matched");
            }
            accProps.insert(grpProps.begin(), grpProps.end());
            accItems.insert(grpItems.begin(), grpItems.end());
        }
        // Merge the accumulated (sibling-isolated) coverage at this location,
        // so enclosing unevaluated* checks see the successful members' output.
        ctx.curProps().insert(accProps.begin(), accProps.end());
        ctx.curItems().insert(accItems.begin(), accItems.end());
        return ValidationResult::valid();
    }

    /// if/then/else: the `if` guard runs against a THROWAWAY capture; when it
    /// SUCCEEDS its annotations are collected (applicable-subschema rule) and
    /// `then` applies in the current context; when it FAILS, `else` applies.
    /// Annotations of the not-applied branch never leak. Neither path fails
    /// this node on its own.
    ValidationResult walkConditional(SchemaNode const& node,
                                     RawInstance const& instance,
                                     ValidationPath& path,
                                     ValidationContext& ctx) const {
        if (!node.hasIf) return ValidationResult::valid();
        std::set<std::string> ifProps;
        std::set<std::size_t> ifItems;
        ValidationResult ifRes = ctx.evaluateAndCapture(
            [&] { return this->validate(node.ifSchema, instance, path, ctx); },
            ifProps, ifItems);
        if (ifRes.success) {
            ctx.curProps().insert(ifProps.begin(), ifProps.end());
            ctx.curItems().insert(ifItems.begin(), ifItems.end());
            if (node.hasThen) {
                ValidationResult r =
                    this->validate(node.thenSchema, instance, path, ctx);
                if (!r.success) return r;
            }
        } else if (node.hasElse) {
            ValidationResult r =
                this->validate(node.elseSchema, instance, path, ctx);
            if (!r.success) return r;
        }
        return ValidationResult::valid();
    }

    /// Object structural traversal. Only active for object instances; ignores
    /// non-object kinds (as JSON Schema requires).
    ValidationResult validateObjectTraversal(SchemaNode const& node, RawInstance const& instance,
                                             ValidationPath& path, ValidationContext& ctx) const {
        if (!instance.isObject()) return ValidationResult::valid();
        // Wave-4.2: counts/required/dependentRequired belong to the
        // validation vocabulary (gated by the resource dialect).
        bool const vInert =
            !registry_.validationVocabActive(ctx.currentValidationRes);

        // Wave-4.2: the object-validation block (counts, required,
        // dependentRequired) is gated by the validation vocabulary.
        if (!vInert) {
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

        // Wave-3.4 dependentRequired (K-11): when a trigger key is present,
        // every name in its list must also be present. Present-ness is a
        // string-location assertion — no subschema is evaluated.
        for (auto const& dep : node.dependentRequired) {
            if (!instance.hasMember(dep.first.c_str())) continue;
            for (std::string const& rn : dep.second) {
                if (!instance.hasMember(rn.c_str()))
                    return ValidationResult::invalidAt(
                        path, "dependentRequired '" + dep.first
                            + "' missing '" + rn + "'");
            }
        }
        }

        // Declared properties: validate each present member against its
        // property subschema; absent members are not evaluated. Member VALUES
        // live at a nested instance location, so their evaluation happens in
        // a fresh location scope (their keys must never leak into this
        // object's unevaluated* check).
        for (PropertyBinding const& pb : node.properties) {
            RawInstance m = instance.atMember(pb.name.c_str());
            if (m.value == nullptr) continue;
            ValidationPath childPath = path;
            childPath.enter(pb.name);
            ctx.pushLocation();
            ValidationResult r = this->validate(pb.node, m, childPath, ctx);
            ctx.popLocation();
            if (!r.success) return r;
            ctx.curProps().insert(pb.name);
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
                        ctx.pushLocation();
                        ValidationResult r =
                            this->validate(pb.node, m, childPath, ctx);
                        ctx.popLocation();
                        if (!r.success) return r;
                        ctx.curProps().insert(k);
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
                    ctx.pushLocation();
                    ValidationResult r =
                        this->validate(node.additionalSchema, m, childPath, ctx);
                    ctx.popLocation();
                    if (!r.success) return r;
                }
                ctx.curProps().insert(k);
            }
        } else if (node.additionalProperties == AdditionalPropertiesKind::allowed) {
            for (std::string const& k : instance.objectKeys()) {
                if (isCovered(k)) continue;
                ctx.curProps().insert(k);
            }
        }

        return ValidationResult::valid();
    }

    /// Array structural traversal. Only active for array instances.
    ValidationResult validateArrayTraversal(SchemaNode const& node, RawInstance const& instance,
                                            ValidationPath& path, ValidationContext& ctx) const {
        if (!instance.isArray()) return ValidationResult::valid();
        // Wave-4.2: item-count bounds belong to the validation vocabulary
        // (the contains MATCHING below stays applicator-active; only the
        // min/maxContains bound enforcement is gated with them).
        bool const vInert =
            !registry_.validationVocabActive(ctx.currentValidationRes);

        std::size_t const n = instance.size();
        if (!vInert) {
        if (node.hasMinItems && ExactNumber::fromUint(n) < node.minItems)
            return ValidationResult::invalidAt(path, "array has fewer items than minItems");
        if (node.hasMaxItems && node.maxItems < ExactNumber::fromUint(n))
            return ValidationResult::invalidAt(path, "array has more items than maxItems");
        }

        // prefixItems: each schema applies to the item at ITS index. Item
        // values live at nested array locations (fresh scopes).
        std::size_t const prefixCount = node.prefixItems.size();
        for (std::size_t i = 0; i < prefixCount && i < n; ++i) {
            RawInstance m = instance.atIndex(i);
            ValidationPath childPath = path;
            childPath.enterIndex(i);
            ctx.pushLocation();
            ValidationResult r = this->validate(node.prefixItems[i], m, childPath, ctx);
            ctx.popLocation();
            if (!r.success) return r;
            ctx.curItems().insert(i);
        }

        // items: applies to the REMAINDER (indices >= prefixItems.size()).
        if (node.items != kNoSchema) {
            for (std::size_t i = prefixCount; i < n; ++i) {
                RawInstance m = instance.atIndex(i);
                ValidationPath childPath = path;
                childPath.enterIndex(i);
                ctx.pushLocation();
                ValidationResult r = this->validate(node.items, m, childPath, ctx);
                ctx.popLocation();
                if (!r.success) return r;
                ctx.curItems().insert(i);
            }
        }

        // Wave-3.1 contains family (K-08): every item is tested against the
        // contains subschema; MATCHING indices are annotated as evaluated at
        // the current array location (unevaluatedItems sees them). The match
        // count must satisfy minContains (default 1; explicit 0 waives the
        // floor) and maxContains. Both bounds are inert without contains.
        if (node.hasContains) {
            std::vector<std::size_t> matched;
            matched.reserve(n);
            for (std::size_t i = 0; i < n; ++i) {
                RawInstance const m = instance.atIndex(i);
                ValidationPath childPath = path;
                childPath.enterIndex(i);
                ctx.pushLocation();
                ValidationResult r =
                    this->validate(node.containsSchema, m, childPath, ctx);
                ctx.popLocation();
                if (r.success) matched.push_back(i);
            }
            std::size_t const matchCount = matched.size();
            if (!vInert) {
            bool const minWaived =
                node.hasMinContains && node.minContains.isZero();
            if (!minWaived) {
                ExactNumber const minC = node.hasMinContains
                    ? node.minContains : ExactNumber::fromUint(1);
                if (ExactNumber::fromUint(matchCount) < minC)
                    return ValidationResult::invalidAt(
                        path, "contains: fewer matches than minContains");
            }
            if (node.hasMaxContains
                    && node.maxContains < ExactNumber::fromUint(matchCount))
                return ValidationResult::invalidAt(
                    path, "contains: more matches than maxContains");
            }
            for (std::size_t i : matched) ctx.curItems().insert(i);
        }

        return ValidationResult::valid();
    }

    SchemaResourceRegistry const& registry_;
};

} // namespace oas31

#endif // OAS31_VALIDATOR_HPP_
