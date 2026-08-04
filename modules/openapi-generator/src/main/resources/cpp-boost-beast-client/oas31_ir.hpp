// ============================================================================
// oas31_ir.hpp — Wave-1 densified schema IR tables (ADR D5 / Option B).
//
// Single shared SchemaResourceRegistry of densified SchemaNode rows that the
// SchemaEvaluator interprets. This slice FROZES the exact layout so IR emission
// (Java) and the evaluator (C++) agree without rework.
//
// HEADER-ONLY. Built under -Werror with g++ -std=c++17.
// ============================================================================
#ifndef OAS31_IR_HPP_
#define OAS31_IR_HPP_

#include "oas31_exact_number.hpp"

#include <boost/json.hpp>

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace oas31 {

/// JSON value kinds supported by the raw-instance view.
/// NOTE: `integer` is a schema-level type (JSON Schema `type: integer`), not a
/// raw instance kind; RawInstance::kind() never returns it. It exists so the
/// `typeFlags` bitmask can encode an integer type requirement. Its enum value
/// (6) collides with no real kind, so existing bit positions 0..5 stay stable.
enum class JsonType : std::uint8_t {
    null_,    // 0
    boolean,  // 1
    number,   // 2
    string,   // 3
    array,    // 4
    object,   // 5
    integer   // 6 — schema-level type only (not a raw-instance kind)
};

/// A logical operator combining child schemas.
enum class ApplicatorKind : std::uint8_t {
    none,
    allOf,
    anyOf,
    oneOf,
    not_,       // single child in children[0]
    ref,        // $ref
    dynamicRef  // $dynamicRef (Wave-2+, D4)
};

/// OAS/JSON-Schema `additionalProperties` tri-state (Wave-2).
///   absent : keyword not present  -> unlisted properties are allowed (no-op),
///   allowed: `additionalProperties: true`  -> allowed (no-op),
///   reject : `additionalProperties: false` -> REJECT any unlisted key,
///   schema : `additionalProperties: <schema>` -> validate unlisted values.
/// Listed (declared `properties`) keys are NEVER additionally evaluated.
/// Index into SchemaResourceRegistry::nodes. kNoSchema == "no schema".
using SchemaIndex = std::int32_t;
inline constexpr SchemaIndex kNoSchema = -1;

/// OAS/JSON-Schema `additionalProperties` tri-state (Wave-2).
///   absent : keyword not present  -> unlisted properties are allowed (no-op),
///   allowed: `additionalProperties: true`  -> allowed (no-op),
///   reject : `additionalProperties: false` -> REJECT any unlisted key,
///   schema : `additionalProperties: <schema>` -> validate unlisted values.
/// Listed (declared `properties`) keys are NEVER additionally evaluated.
enum class AdditionalPropertiesKind : std::uint8_t {
    absent,
    allowed,
    reject,
    schema
};

/// One declared `properties` entry: property name -> child schema node index.
struct PropertyBinding {
    std::string name;
    SchemaIndex node = kNoSchema;
};

/// OAS 3.1 boolean value-schema: true => always valid, false => never.
enum class BooleanValue : std::uint8_t { notBoolean, true_, false_ };

/// A single densified schema node. One row per schema object.
struct SchemaNode {
    // -- identity --
    std::uint32_t resourceIdentity = 0;   // index into SchemaResourceRegistry::resources
    SchemaIndex   parent = kNoSchema;

    // -- value schema --
    BooleanValue  booleanValue = BooleanValue::notBoolean;  // OAS 3.1 true/false value schema
    std::uint8_t  typeFlags = 0;          // bitmask of JsonType (type or type-array)

    // -- exact-number constraints (D1) --
    ExactNumber   minimum;                bool hasMinimum = false;
    ExactNumber   maximum;                bool hasMaximum = false;
    ExactNumber   exclusiveMinimum;       bool hasExclusiveMinimum = false;
    ExactNumber   exclusiveMaximum;       bool hasExclusiveMaximum = false;
    ExactNumber   multipleOf;             bool hasMultipleOf = false;

    // -- enum / const values --
    std::vector<ExactNumber> enumNumbers;   // numeric enum values, exact
    std::vector<std::string> enumStrings;
    std::vector<bool>        enumBooleans;

    bool               hasConst = false;
    ExactNumber        constNumber;       bool constIsNumber = false;
    std::string        constString;       bool constIsString = false;
    bool               constBool = false; bool constIsBool = false;

    // -- Wave-1 deep-equality stores (K-30/K-34/K-22) --
    // Full JSON CONST value for exact deep equality across ALL JSON kinds
    // (const: {..}, const: [..], const: 1.0, ...). When constIsJson is set the
    // evaluator compares the whole instance via deepInstanceEqual; the scalar
    // const* buckets above remain for backward-compat only.
    bool constIsJson = false;
    boost::json::value constJson;
    // Full JSON ENUM member list for exact deep equality (K-34).
    bool hasEnumJson = false;
    std::vector<boost::json::value> enumJson;
    // Array uniqueItems (K-22): true => reject when any pair of items is
    // deep-equal (1 == 1.0 counts as a duplicate).
    bool hasUniqueItems = false;

    // -- Wave-2 object structural (FROZEN §10.2) ---------------------------
    bool                     hasObjectSchema = false;
    std::vector<PropertyBinding> properties;   // declared property subschemas
    std::vector<std::string> required;
    AdditionalPropertiesKind additionalProperties = AdditionalPropertiesKind::absent;
    SchemaIndex              additionalSchema = kNoSchema;  // schema-form child
    ExactNumber              minProperties;  bool hasMinProperties = false;
    ExactNumber              maxProperties;  bool hasMaxProperties = false;

    // -- Wave-2 array structural (FROZEN §10.2) ----------------------------
    std::vector<SchemaIndex> prefixItems;     // prefixItems[i] applies to index i
    SchemaIndex              items = kNoSchema;  // applies to indices >= prefixItems.size()
    ExactNumber              minItems;  bool hasMinItems = false;
    ExactNumber              maxItems;  bool hasMaxItems = false;

    // -- Wave-2 unevaluatedProperties (bool/absent + schema form, best-effort) --
    bool         hasUnevaluatedProperties = false;
    bool         unevaluatedPropertiesRejects = false;
    SchemaIndex  unevaluatedSchema = kNoSchema;

    // -- applicators --
    ApplicatorKind         applicator = ApplicatorKind::none;
    std::vector<SchemaIndex> children;    // allOf/anyOf/oneOf member indices
    SchemaIndex            notSchema = kNoSchema;  // `not` subschema reference

    // D4 dynamic-scope data — NOT used by this slice's keyword set; reserved.
    std::string dynamicRefUri;
};

/// One schema resource (document): identity + its root schema indices.
struct SchemaResource {
    std::string             baseUri;
    std::string             dialect;
    std::string             anchor;    // $anchor / $dynamicAnchor on root
    std::vector<SchemaIndex> rootNodes; // usually one
};

/// Registry of all resources + the densified node pool.
struct SchemaResourceRegistry {
    std::vector<SchemaResource> resources;
    std::vector<SchemaNode>     nodes;

    SchemaNode const& node(SchemaIndex i) const {
        return nodes[static_cast<std::size_t>(i)];
    }

    SchemaResource const& resourceByIdentity(std::uint32_t id) const {
        return resources[static_cast<std::size_t>(id)];
    }
};

} // namespace oas31

#endif // OAS31_IR_HPP_
