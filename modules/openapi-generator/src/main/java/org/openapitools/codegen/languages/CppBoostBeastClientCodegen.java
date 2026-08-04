package org.openapitools.codegen.languages;


import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.apache.commons.lang3.StringUtils;
import org.openapitools.codegen.*;

import java.io.File;
import java.math.BigDecimal;
import java.util.*;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

import org.openapitools.codegen.meta.features.*;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.model.OperationsMap;
import org.openapitools.codegen.utils.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.openapitools.codegen.utils.StringUtils.camelize;

public class CppBoostBeastClientCodegen extends AbstractCppCodegen {

    /**
     * Describes a composed schema (oneOf, anyOf, allOf) with its branches,
     * preserving the original keyword and branch order after normalization.
     */
    public static final class CompositionDescriptor {
        private final String schemaName;
        private final String schemaLocation;
        private final String keyword;
        private final List<CompositionBranchDescriptor> branches;
        private final DiscriminatorDescriptor discriminator;

        public CompositionDescriptor(String schemaName, String schemaLocation,
                                     String keyword,
                                     List<CompositionBranchDescriptor> branches,
                                     DiscriminatorDescriptor discriminator) {
            this.schemaName = schemaName;
            this.schemaLocation = schemaLocation;
            this.keyword = keyword;
            this.branches = Collections.unmodifiableList(
                    new ArrayList<>(branches));
            this.discriminator = discriminator;
        }

        public String getSchemaName() { return schemaName; }
        public String getSchemaLocation() { return schemaLocation; }
        public String getKeyword() { return keyword; }
        public List<CompositionBranchDescriptor> getBranches() { return branches; }
        public DiscriminatorDescriptor getDiscriminator() { return discriminator; }
        public boolean hasDiscriminator() { return discriminator != null; }

        /** Converts this descriptor to a template-safe map for Mustache. */
        public Map<String, Object> toTemplateMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("schema-name", schemaName);
            map.put("schema-location", schemaLocation);
            map.put("keyword", keyword);
            List<Map<String, Object>> branchMaps = new ArrayList<>();
            for (CompositionBranchDescriptor branch : branches) {
                branchMaps.add(branch.toTemplateMap());
            }
            map.put("branches", branchMaps);
            if (discriminator != null) {
                map.put("discriminator-property-name", discriminator.getPropertyName());
                map.put("discriminator-mapping", discriminator.getMapping());
            }
            return map;
        }
    }

    /**
     * Describes an optional discriminator on a composed schema.
     */
    public static final class DiscriminatorDescriptor {
        private final String propertyName;
        private final Map<String, String> mapping;

        public DiscriminatorDescriptor(String propertyName, Map<String, String> mapping) {
            this.propertyName = propertyName;
            this.mapping = mapping != null
                    ? Collections.unmodifiableMap(new LinkedHashMap<>(mapping))
                    : Collections.emptyMap();
        }

        public String getPropertyName() { return propertyName; }
        public Map<String, String> getMapping() { return mapping; }
    }

    /**
     * Describes a single branch within a composed schema.
     * Captures branch index, resolved schema reference, C++ storage type,
     * validator identity, null capability, assertion metadata, and
     * validation parameter values.
     *
     * <p>{@code storageCppType} is populated after storage selection
     * (Phase 3). {@code validatorId} is populated in Phase 2 to identify
     * the generated validate_<id>() function for this branch.
     *
     * <p>Validation parameters ({@code validateParams}) carry the actual
     * assertion values (min, max, minLength, etc.) from the source schema
     * so Mustache templates can generate per-branch validators without
     * re-scanning the schema tree.
     */
    public static final class CompositionBranchDescriptor {
        private final int branchIndex;
        private final String sourceSchemaRef;
        private final String resolvedSchemaName;
        /** Phase 3 ownership: set after storage selection. */
        private final String storageCppType;
        /** Phase 2 ownership: set after descriptor build. */
        private final String validatorId;
        private final NullCapability nullCapability;
        private final List<String> supportedAssertions;
        private final List<String> unsupportedAssertions;
        /**
         * Validation parameter values for Mustache template consumption.
         * Keys: "validation-type", "validation-enum-values",
         * "validation-min", "validation-max", "validation-exclusive-min",
         * "validation-exclusive-max", "validation-multiple-of",
         * "validation-min-length", "validation-max-length",
         * "validation-pattern", "validation-min-items",
         * "validation-max-items", "validation-unique-items",
         * "validation-min-properties", "validation-max-properties",
         * "validation-required".
         * Values are Objects (String, Number, Boolean, List<String>).
         */
        private final Map<String, Object> validateParams;

        public enum NullCapability { NEVER, ALWAYS, CONDITIONAL }

        public CompositionBranchDescriptor(int branchIndex, String sourceSchemaRef,
                                           String resolvedSchemaName, String storageCppType,
                                           String validatorId, NullCapability nullCapability,
                                           List<String> supportedAssertions,
                                           List<String> unsupportedAssertions,
                                           Map<String, Object> validateParams) {
            this.branchIndex = branchIndex;
            this.sourceSchemaRef = sourceSchemaRef;
            this.resolvedSchemaName = resolvedSchemaName;
            this.storageCppType = storageCppType;
            this.validatorId = validatorId;
            this.nullCapability = nullCapability;
            this.supportedAssertions = supportedAssertions != null
                    ? Collections.unmodifiableList(new ArrayList<>(supportedAssertions))
                    : Collections.emptyList();
            this.unsupportedAssertions = unsupportedAssertions != null
                    ? Collections.unmodifiableList(new ArrayList<>(unsupportedAssertions))
                    : Collections.emptyList();
            this.validateParams = validateParams != null
                    ? Collections.unmodifiableMap(new LinkedHashMap<>(validateParams))
                    : Collections.emptyMap();
        }

        public int getBranchIndex() { return branchIndex; }
        public String getSourceSchemaRef() { return sourceSchemaRef; }
        public String getResolvedSchemaName() { return resolvedSchemaName; }
        public String getStorageCppType() { return storageCppType; }
        public String getValidatorId() { return validatorId; }
        public NullCapability getNullCapability() { return nullCapability; }
        public List<String> getSupportedAssertions() { return supportedAssertions; }
        public List<String> getUnsupportedAssertions() { return unsupportedAssertions; }
        public Map<String, Object> getValidateParams() { return validateParams; }

        /** Converts this branch descriptor to a template-safe map for Mustache. */
        public Map<String, Object> toTemplateMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("branch-index", branchIndex);
            map.put("source-schema-ref", sourceSchemaRef);
            map.put("resolved-schema-name", resolvedSchemaName);
            map.put("storage-cpp-type", storageCppType);
            map.put("validator-id", validatorId);
            map.put("null-capability", nullCapability.name().toLowerCase(Locale.ROOT));
            map.put("has-supported-assertions", !supportedAssertions.isEmpty());
            map.put("supported-assertions", supportedAssertions);
            map.put("has-unsupported-assertions", !unsupportedAssertions.isEmpty());
            map.put("unsupported-assertions", unsupportedAssertions);
            // Emit validation parameters for template-driven generator functions
            for (Map.Entry<String, Object> vp : validateParams.entrySet()) {
                map.put(vp.getKey(), vp.getValue());
            }
            return map;
        }
    }


    /**
     * Result of recursively intersecting allOf contributor schemas.
     * Captures merged properties, union required, and satisfiability.
     * Used to build synthetic object schemas for storage model generation.
     */
    public static final class AllOfIntersection {
        private final Map<String, Schema> properties;
        private final Set<String> required;
        private final boolean isSatisfiable;
        private final String unsatisfiableReason;
        /** Map of property names whose intersection is empty (optional impossible). */
        private final Set<String> optionalImpossibleProperties;
        /** Intersected root-level type across all branches (null if absent). */
        private final String rootScalarType;
        /** Intersected root-level enum values across all branches. */
        private final List<Object> rootEnumValues;
        /** Intersected root-level const value across all branches. */
        private final Object rootConstValue;
        /** Minimum numeric value (intersection takes the larger). */
        private final BigDecimal rootMinimum;
        /** Maximum numeric value (intersection takes the smaller). */
        private final BigDecimal rootMaximum;
        /** Exclusive minimum flag. */
        private final Boolean rootExclusiveMinimum;
        /** Exclusive maximum flag. */
        private final Boolean rootExclusiveMaximum;
        /** Minimum string length (intersection takes the larger). */
        private final Integer rootMinLength;
        /** Maximum string length (intersection takes the smaller). */
        private final Integer rootMaxLength;

        public AllOfIntersection(Map<String, Schema> properties, Set<String> required,
                                 boolean isSatisfiable, String unsatisfiableReason,
                                 Set<String> optionalImpossibleProperties) {
            this(properties, required, isSatisfiable, unsatisfiableReason,
                    optionalImpossibleProperties,
                    null, null, null, null, null, null, null,
                    null, null);
        }

        public AllOfIntersection(Map<String, Schema> properties, Set<String> required,
                                 boolean isSatisfiable, String unsatisfiableReason,
                                 Set<String> optionalImpossibleProperties,
                                 String rootScalarType, List<Object> rootEnumValues,
                                 Object rootConstValue,
                                 BigDecimal rootMinimum, BigDecimal rootMaximum,
                                 Boolean rootExclusiveMinimum, Boolean rootExclusiveMaximum,
                                 Integer rootMinLength, Integer rootMaxLength) {
            this.properties = properties != null
                    ? Collections.unmodifiableMap(new LinkedHashMap<>(properties))
                    : Collections.emptyMap();
            this.required = required != null
                    ? Collections.unmodifiableSet(new LinkedHashSet<>(required))
                    : Collections.emptySet();
            this.isSatisfiable = isSatisfiable;
            this.unsatisfiableReason = unsatisfiableReason;
            this.optionalImpossibleProperties = optionalImpossibleProperties != null
                    ? Collections.unmodifiableSet(new LinkedHashSet<>(optionalImpossibleProperties))
                    : Collections.emptySet();
            this.rootScalarType = rootScalarType;
            this.rootEnumValues = rootEnumValues != null
                    ? Collections.unmodifiableList(new ArrayList<>(rootEnumValues))
                    : null;
            this.rootConstValue = rootConstValue;
            this.rootMinimum = rootMinimum;
            this.rootMaximum = rootMaximum;
            this.rootExclusiveMinimum = rootExclusiveMinimum;
            this.rootExclusiveMaximum = rootExclusiveMaximum;
            this.rootMinLength = rootMinLength;
            this.rootMaxLength = rootMaxLength;
        }

        public Map<String, Schema> getProperties() { return properties; }
        public Set<String> getRequired() { return required; }
        public boolean isSatisfiable() { return isSatisfiable; }
        public String getUnsatisfiableReason() { return unsatisfiableReason; }
        public Set<String> getOptionalImpossibleProperties() { return optionalImpossibleProperties; }
        public String getRootScalarType() { return rootScalarType; }
        public List<Object> getRootEnumValues() { return rootEnumValues; }
        public Object getRootConstValue() { return rootConstValue; }
        public BigDecimal getRootMinimum() { return rootMinimum; }
        public BigDecimal getRootMaximum() { return rootMaximum; }
        public Boolean getRootExclusiveMinimum() { return rootExclusiveMinimum; }
        public Boolean getRootExclusiveMaximum() { return rootExclusiveMaximum; }
        public Integer getRootMinLength() { return rootMinLength; }
        public Integer getRootMaxLength() { return rootMaxLength; }
    }

    public static final String DEFAULT_PACKAGE_NAME = "CppBoostBeastOpenAPIClient";

    /** Policy for format-assertion validation in branch matching.
     *  "annotation" (default): format ranges participate only in destination
     *    conversion, never in oneOf/anyOf branch match counts.
     *  "strict": documented format assertions (e.g. int32 range) participate
     *    in branch validation and can affect match counts. */
    private String formatAssertionPolicy = "annotation";

    /** Value type for the formatAssertion option. */
    private static final String FORMAT_ASSERTION_POLICY_ANNOTATION = "annotation";
    private static final String FORMAT_ASSERTION_POLICY_STRICT = "strict";

    /** SSE schema interpretation mode. */
    private String sseSchemaMode = "representation";
    private static final String SSE_SCHEMA_MODE_REPRESENTATION = "representation";
    private static final String SSE_SCHEMA_MODE_JSON_EVENT_DATA = "jsonEventData";
    /** Per-operation vendor extension to opt-in to typed event-data decoding. */
    private static final String X_SSE_EVENT_DATA_SCHEMA = "x-sse-event-data-schema";

    private static final String X_CODEGEN_DEFAULT_RESPONSE_IS_RETURN_COMPATIBLE =
            "x-codegen-default-response-is-return-compatible";
    private static final String X_CODEGEN_EMPTY_BODY_TOLERANT = "x-codegen-empty-body-tolerant";
    private static final String X_CODEGEN_HAS_DEFAULT_RESPONSE = "x-codegen-has-default-response";
    private static final String X_CODEGEN_IS_RAW_BODY = "x-codegen-is-raw-body";
    private static final String X_CODEGEN_IS_OPTIONAL_QUERY_PARAMETER =
            "x-codegen-is-optional-query-parameter";
    private static final String X_CODEGEN_QUERY_COLLECTION_DELIMITER =
            "x-codegen-query-collection-delimiter";
    private static final String X_CODEGEN_QUERY_COLLECTION_MULTI =
            "x-codegen-query-collection-multi";
    private static final String X_CODEGEN_QUERY_MAP_EXPLODED =
            "x-codegen-query-map-exploded";
    private static final String X_CODEGEN_QUERY_MAP_DEEP_OBJECT =
            "x-codegen-query-map-deep-object";
    private static final String X_CODEGEN_RESPONSE_RANGE = "x-codegen-response-range";
    private static final String X_CODEGEN_RESPONSE_IS_ONE_OF = "x-codegen-response-is-oneof";
    private static final String X_CODEGEN_STREAM_IS_ONE_OF = "x-codegen-stream-is-oneof";
    private static final String X_CODEGEN_DUAL_STREAM_IS_ONE_OF = "x-codegen-dual-stream-is-oneof";
    private static final String X_CODEGEN_RESPONSE_UNION = "x-codegen-response-union";
    private static final String X_CODEGEN_RESPONSE_UNION_BODY_TYPE = "x-codegen-response-union-body-type";
    private static final String X_CODEGEN_RESPONSE_UNION_STATUS_INDEX = "x-codegen-response-union-status-index";
    private final Logger LOGGER = LoggerFactory.getLogger(CppBoostBeastClientCodegen.class);
    /** Tracks model names resolved as oneOf/anyOf variant types for shared_ptr exclusion. */
    private final Set<String> variantModels = new HashSet<>();
    private final Set<String> hasDuplicateTypesModels = new HashSet<>();
    /** Caches resolved C++ types for composed models, keyed by model name.
     *  Populated during Phase 1 of postProcessModels and used by Phase 1b to
     *  transitively resolve $ref chains through model aliases (e.g., ModelIds
     *  referencing ModelIdsShared, both ultimately std::string). */
    private final Map<String, String> resolvedAliasTypes = new HashMap<>();
    /** Retains composition semantics after named schemas are lowered to C++ aliases. */
    private final Map<String, String> composedKeywordsByModel = new HashMap<>();
    /** Descriptor index mapping schema name to composition descriptor, populated
     *  in preprocessOpenAPI after inline model flattening. Replaces raw schema
     *  inspection as the semantic source for branch lowering. */
    final Map<String, CompositionDescriptor> compositionDescriptors = new LinkedHashMap<>();

    // Wave-2: per-component "is composed (oneOf/anyOf/allOf)" snapshot captured
    // at IR emission time (post-model-extraction), used to choose the ref-target
    // row id suffix: composed components resolve via their branch row
    // (`<name>_branch_0`), plain extracted components resolve via their own
    // densified row (`<name>_component`).
    private final java.util.Map<String, Boolean> irComponentComposed = new java.util.HashMap<>();
    /** Cached allOf intersections keyed by model name. Populated during
     *  preprocessOpenAPI and consumed by fromModel to build synthetic schemas. */
    final Map<String, AllOfIntersection> allOfIntersections = new LinkedHashMap<>();

    /**
     * Returns the composition descriptor for the given schema name, or null
     * if the schema is not composed or was not indexed.
     */
    public CompositionDescriptor getCompositionDescriptor(String schemaName) {
        return compositionDescriptors.get(schemaName);
    }

    /**
     * Returns an unmodifiable view of the full composition descriptor index.
     */
    public Map<String, CompositionDescriptor> getCompositionDescriptors() {
        return Collections.unmodifiableMap(compositionDescriptors);
    }
    protected String packageName = DEFAULT_PACKAGE_NAME;

    public CodegenType getTag() {
        return CodegenType.CLIENT;
    }

    public String getName() {
        return "cpp-boost-beast-client";
    }

    public String getHelp() {
        return "Generates a cpp-boost-beast client.";
    }

    @Override
    public void preprocessOpenAPI(OpenAPI openAPI) {
        super.preprocessOpenAPI(openAPI);
        // Wave-2 §10.2: recover prefixItems that the shared OAS-3.1 normalizer
        // drops (NORMALIZE_31SPEC converts types:[array] JsonSchema to
        // ArraySchema and does not copy prefixItems). Must run BEFORE the
        // descriptor scan so the branch/child scan sees the pristine value.
        restoreNormalizerDroppedPrefixItems(openAPI);
        recoverPristineLiterals(openAPI);
        // Opt-in / internally-gated Wave-0 silent-skip scanner. Default off so the
        // existing 30.x/3.1 fixture suite is never regressed; enabled only when the
        // oas31NoSilentSkip flag is set (plan §5 Wave 0 item 1 / GH).
        if (Boolean.parseBoolean(String.valueOf(
                additionalProperties().getOrDefault(STRICT_SCANNER_OPTION, "false")))) {
            enforceNoSilentSkips(openAPI);
        }
        // Populate variantModels and build composition descriptors before
        // model processing begins so that getTypeDeclaration can resolve $ref
        // to composed models as value types and branch semantics are captured
        // before fromModel consumes composed schemas.
        Map<String, Schema> schemas = openAPI.getComponents() != null
                ? openAPI.getComponents().getSchemas() : null;
        if (schemas != null) {
            // Build descriptor index: must happen after inline model resolver
            // flattening so all inline schemas have been extracted to component
            // references with stable $ref targets.
            compositionDescriptors.clear();
            for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
                String schemaName = entry.getKey();
                Schema schema = entry.getValue();
                CompositionDescriptor descriptor = buildCompositionDescriptor(
                        schemaName, schema, openAPI, schemas, new HashSet<>());
                if (descriptor != null) {
                    // Index by toModelName so lookups via cm.classname match.
                    compositionDescriptors.put(toModelName(schemaName), descriptor);
                    // Phase 2: validate that all branch assertions are supported.
                    // Throws UnsupportedSchemaAssertionException if any branch
                    // has detectable unsupported assertions that affect membership.
                    validateDescriptorAssertions(descriptor);

                    // Phase 5: For allOf schemas, precompute the recursive intersection.
                    // This is stored as a synthetic AllOfIntersection that fromModel
                    // uses to build a synthetic object Schema for storage modeling,
                    // replacing the shallow conflict scan with full intersection logic.
                    if ("allOf".equals(descriptor.getKeyword())) {
                        AllOfIntersection intersection = computeAllOfIntersection(
                                schemaName, schema, openAPI, schemas, new HashSet<>());
                        if (intersection != null) {
                            allOfIntersections.put(toModelName(schemaName), intersection);
                        }
                    }
                }
                if ((schema.getOneOf() != null && !schema.getOneOf().isEmpty())
                        || (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty())) {
                    variantModels.add(schemaName);
                }
            }
        }
    }

    /**
     * Wave-2 §10.2 recovery: the shared OAS-3.1 normalizer (NORMALIZE_31SPEC,
     * enabled by DefaultGenerator for 3.1 specs) converts a
     * {@code types:[array]} JsonSchema into an ArraySchema and DROPS
     * {@code prefixItems} (only items/uniqueItems/minItems/maxItems are copied).
     * This emitter densifies prefixItems into the IR, so the pristine value is
     * recovered from a FRESH parse of the input spec and merged back into the
     * MUTATED graph at the matching JSON-pointer position. Pointer-aligned
     * traversal is safe because normalization preserves the schema tree SHAPE
     * (it replaces schema objects at the same locations, never re-parents).
     * When no input spec is available or the re-parse fails, nothing changes
     * (arrays keep whatever survived — honest no-op).
     */
    private void restoreNormalizerDroppedPrefixItems(OpenAPI openAPI) {
        if (openAPI == null || openAPI.getComponents() == null
                || openAPI.getComponents().getSchemas() == null
                || openAPI.getComponents().getSchemas().isEmpty()) {
            return;
        }
        // NORMALIZE_31SPEC only activates for 3.1 docs; 3.0 in-memory unit specs
        // never drop prefixItems and do not need the recovery parse.
        if (openAPI.getOpenapi() == null || !openAPI.getOpenapi().startsWith("3.1")) {
            return;
        }
        String specPath = getInputSpec();
        if (specPath == null) {
            return;
        }
        OpenAPI pristine = null;
        try {
            io.swagger.v3.parser.core.models.ParseOptions options =
                    new io.swagger.v3.parser.core.models.ParseOptions();
            options.setResolve(true);
            options.setResolveResponses(true);
            pristine = new io.swagger.v3.parser.OpenAPIV3Parser()
                    .readLocation(specPath, null, options).getOpenAPI();
        } catch (Exception e) {
            // never fail generation over a best-effort recovery
            return;
        }
        if (pristine == null || pristine.getComponents() == null
                || pristine.getComponents().getSchemas() == null) {
            return;
        }
        Map<String, Schema> mutatedSchemas = openAPI.getComponents().getSchemas();
        for (Object compNameObj : new java.util.ArrayList<>(
                pristine.getComponents().getSchemas().keySet())) {
            String compName = String.valueOf(compNameObj);
            Schema mutable = mutatedSchemas.get(compName);
            if (mutable != null) {
                mergePristineArrayStructure(
                        (Schema) pristine.getComponents().getSchemas().get(compName),
                        mutable, "$");
            }
        }    }

    /**
     * Recursively restores {@code prefixItems} from the pristine schema onto
     * the normalized/mutated schema at the SAME structural position. Only
     * fills the field when the mutated node is array-typed and lost it;
     * never overrides a surviving value.
     */
    private static void mergePristineArrayStructure(
            Schema pristine, Schema mutated, String path) {
        if (pristine == null || mutated == null) {
            return;
        }
        if (isArrayish(pristine) && pristine.getPrefixItems() != null
                && !pristine.getPrefixItems().isEmpty()
                && (mutated.getPrefixItems() == null
                        || mutated.getPrefixItems().isEmpty())) {
            mutated.setPrefixItems(pristine.getPrefixItems());
        }
        // Recurse along structural keys — normalization preserves tree SHAPE.
        java.util.List<Schema> pristineMembers = new ArrayList<>();
        java.util.List<Schema> mutatedMembers = new ArrayList<>();
        if (pristine.getAllOf() != null) pristineMembers.addAll(pristine.getAllOf());
        if (pristine.getAnyOf() != null) pristineMembers.addAll(pristine.getAnyOf());
        if (pristine.getOneOf() != null) pristineMembers.addAll(pristine.getOneOf());
        if (mutated.getAllOf() != null) mutatedMembers.addAll(mutated.getAllOf());
        if (mutated.getAnyOf() != null) mutatedMembers.addAll(mutated.getAnyOf());
        if (mutated.getOneOf() != null) mutatedMembers.addAll(mutated.getOneOf());
        int limit = Math.min(pristineMembers.size(), mutatedMembers.size());
        for (int i = 0; i < limit; i++) {
            mergePristineArrayStructure(pristineMembers.get(i),
                    mutatedMembers.get(i), path + "/" + i);
        }
        if (pristine.getProperties() != null && mutated.getProperties() != null) {
            for (Object propKeyObj : new java.util.ArrayList<>(
                    pristine.getProperties().keySet())) {
                String propKey = String.valueOf(propKeyObj);
                Schema mutatedProp = (Schema) mutated.getProperties().get(propKey);
                if (mutatedProp != null) {
                    mergePristineArrayStructure(
                            (Schema) pristine.getProperties().get(propKey),
                            mutatedProp, path + "/properties/" + propKey);
                }
            }
        }
        if (pristine.getPrefixItems() != null && mutated.getPrefixItems() != null) {
            int pi = Math.min(pristine.getPrefixItems().size(),
                    mutated.getPrefixItems().size());
            for (int i = 0; i < pi; i++) {
                mergePristineArrayStructure(
                        (Schema) pristine.getPrefixItems().get(i),
                        (Schema) mutated.getPrefixItems().get(i),
                        path + "/prefixItems/" + i);
            }
        }
        mergePristineArrayStructure(pristine.getItems(), mutated.getItems(),
                path + "/items");
        mergePristineArrayStructure(pristine.getNot(), mutated.getNot(),
                path + "/not");
        if (pristine.getAdditionalProperties() instanceof Schema
                && mutated.getAdditionalProperties() instanceof Schema) {
            mergePristineArrayStructure((Schema) pristine.getAdditionalProperties(),
                    (Schema) mutated.getAdditionalProperties(),
                    path + "/additionalProperties");
        }
        if (pristine.getUnevaluatedProperties() instanceof Schema
                && mutated.getUnevaluatedProperties() instanceof Schema) {
            mergePristineArrayStructure(
                    (Schema) pristine.getUnevaluatedProperties(),
                    (Schema) mutated.getUnevaluatedProperties(),
                    path + "/unevaluatedProperties");
        }
    }

    /** True for OAS 3.0 array schemas or OAS 3.1 schemas with array in types. */
    private static boolean isArrayish(Schema s) {
        if (s == null) return false;
        if (ModelUtils.isArraySchema(s)) return true;
        java.util.Set<String> types = s.getTypes();
        return types != null && types.contains("array");
    }

    /** Vendor extension used to recover the swallowed `enum: []` keyword. */
    private static final String EMPTY_ENUM_EXT = "x-oas31-empty-enum";

    /** True when the raw spec marked this schema as an explicit empty enum. */
    private static boolean isEmptyEnumMarked(Schema schema) {
        if (schema == null || schema.getExtensions() == null) return false;
        return Boolean.TRUE.equals(schema.getExtensions().get(EMPTY_ENUM_EXT));
    }

    /**
     * Recover the OAS/JSON-Schema `enum: []` reject-all keyword that the
     * swagger-parser swallows (an empty enum deserializes to enum=null and a
     * default types=[string], which is NOT the same semantics: `enum: []` must
     * reject every instance). The wrap and the corpus cannot express the intent
     * through the model, so the raw spec text is inspected: every component
     * whose YAML/JSON block contains an EMPTY enum array is marked with a
     * vendor extension on its oneOf/anyOf/allOf members (or itself when it is
     * not composed). The scan then treats the marker as an empty enum, and the
     * emitter lowers it to a ZERO-member deep store (hasEnumJson with []).
     * Best-effort: on any parsing failure nothing is marked (the group is then
     * honestly measured as FAIL, never silently passed).
     */
    /**
     * Format-tolerant recovery of primitives the parser loses, from the RAW
     * spec text (this generator's OAS 3.1 input files are authoritative):
     *  (a) explicit `enum: []` -> swagger-parser yields enum=null + types=[string];
     *      the owning component is marked reject-all via x-oas31-empty-enum;
     *  (b) float-form count bounds such as `minItems: 1.0` -> swagger-models
     *      getMinItems()==null and the bound silently vanishes; the exact raw
     *      LEXEME is injected via x-oas31-<keyword>-lexeme so ExactNumber
     *      preserves it (1.0 == 1 mathematically; scientific forms stay exact).
     * Component keys are located in BOTH YAML (`  Name:`) and JSON (`"Name":`)
     * shapes at ANY indentation; every recovered literal is mapped to the
     * nearest preceding component whose name exists in components.schemas
     * (walking back past non-schema keys). Format-independent.
     */
    private void recoverPristineLiterals(OpenAPI openAPI) {
        if (openAPI == null || openAPI.getComponents() == null
                || openAPI.getComponents().getSchemas() == null
                || openAPI.getComponents().getSchemas().isEmpty()) {
            return;
        }
        String specPath = getInputSpec();
        if (specPath == null || openAPI.getOpenapi() == null
                || !openAPI.getOpenapi().startsWith("3.1")) {
            return;
        }
        String text;
        try {
            text = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(specPath)),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return;
        }
        // Drop comment lines so a trailing `# enum: []` cannot be mistaken.
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : text.split("\n")) {
            if (line.trim().startsWith("#")) continue;
            sb.append(line).append('\n');
        }
        text = sb.toString();
        Map<String, Schema> schemas = openAPI.getComponents().getSchemas();
        java.util.TreeMap<Integer, String> keysAt = new java.util.TreeMap<>();
        java.util.regex.Matcher keyM = java.util.regex.Pattern.compile(
                "(?m)^\\s{2,}([A-Za-z0-9_]+):\\s*$"
              + "|\"([A-Za-z0-9_]+)\"\\s*:").matcher(text);
        while (keyM.find()) {
            String name = keyM.group(1) != null ? keyM.group(1) : keyM.group(2);
            if (name != null) keysAt.put(keyM.start(), name);
        }
        // (a) empty enum literals -> mark the owning component reject-all.
        java.util.regex.Matcher em = java.util.regex.Pattern.compile(
                "(?:\"enum\"|enum)\\s*:\\s*\\[\\s*\\]").matcher(text);
        while (em.find()) {
            Schema owner = nearestComponentBefore(keysAt, em.start(), schemas);
            if (owner != null) markComponentEmptyEnum(owner);
        }
        // (b) float-form count bounds -> inject the exact raw lexeme.
        java.util.regex.Matcher bm = java.util.regex.Pattern.compile(
                "(?:\"(minItems|maxItems|minProperties|maxProperties)\"|"
              + "(minItems|maxItems|minProperties|maxProperties))\\s*:"
              + "\\s*(-?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)").matcher(text);
        while (bm.find()) {
            String kw = bm.group(1) != null ? bm.group(1) : bm.group(2);
            String token = bm.group(3);
            if (token != null && (token.indexOf('.') >= 0
                    || token.indexOf('e') >= 0 || token.indexOf('E') >= 0)) {
                Schema owner = nearestComponentBefore(keysAt, bm.start(), schemas);
                if (owner != null) injectCountBoundLexeme(owner, kw, token);
            }
        }
    }

    /** Nearest component schema whose raw-text key precedes the offset. */
    private static Schema nearestComponentBefore(
            java.util.TreeMap<Integer, String> keysAt, int offset,
            Map<String, Schema> schemas) {
        java.util.Map.Entry<Integer, String> e = keysAt.floorEntry(offset);
        while (e != null) {
            Schema s = schemas.get(e.getValue());
            if (s != null) return s;
            e = keysAt.lowerEntry(e.getKey());
        }
        return null;
    }

    private static String countBoundExtensionName(String keyword) {
        return "x-oas31-" + keyword + "-lexeme";
    }

    /**
     * Inject an exact count-bound lexeme for a float-form bound that
     * swagger-models drops (e.g. `minItems: 1.0` -> getMinItems()==null).
     * Composed components are marked on their oneOf/anyOf/allOf members,
     * mirroring markComponentEmptyEnum.
     */
    private static void injectCountBoundLexeme(Schema schema, String keyword,
                                               String lexeme) {
        if (schema == null || lexeme == null) return;
        String ext = countBoundExtensionName(keyword);
        java.util.List<?> members = null;
        if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
            members = schema.getOneOf();
        } else if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
            members = schema.getAnyOf();
        } else if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
            members = schema.getAllOf();
        }
        if (members != null) {
            for (Object mem : members) {
                if (mem instanceof Schema) {
                    Schema ms = (Schema) mem;
                    if (ms.getExtensions() == null) {
                        ms.setExtensions(new java.util.LinkedHashMap<>());
                    }
                    ms.addExtension(ext, lexeme);
                }
            }
            return;
        }
        if (schema.getExtensions() == null) {
            schema.setExtensions(new java.util.LinkedHashMap<>());
        }
        schema.addExtension(ext, lexeme);
    }

    /** Read a recovered count-bound lexeme extension (null when absent). */
    private static String countBoundLexemeOf(Schema schema, String keyword) {
        if (schema == null || schema.getExtensions() == null) return null;
        Object v = schema.getExtensions().get(countBoundExtensionName(keyword));
        return v == null ? null : String.valueOf(v);
    }

    /** True when the raw spec region contains an explicit EMPTY enum array. */
    private static boolean hasEmptyEnumLiteral(String region) {
        // Drop YAML comment lines first: a trailing comment like
        // `# --- enum: [] reject-all ...` must not be mistaken for the keyword.
        StringBuilder cleaned = new StringBuilder(region.length());
        for (String line : region.split("\n")) {
            if (line.trim().startsWith("#")) continue;
            cleaned.append(line).append('\n');
        }
        String text = cleaned.toString();
        // YAML: `enum: []`; JSON: `"enum": []`. (A multiline enum body is
        // NOT an empty enum holder: `enum:` without an inline [ ].)
        return java.util.regex.Pattern.compile(
                "enum\\s*:\\s*\\[\\s*\\]").matcher(text).find()
            || java.util.regex.Pattern.compile(
                "\\\"enum\\\"\\s*:\\s*\\[\\s*\\]").matcher(text).find();
    }

    /** Mark a component's schema (and its composition members) reject-all. */
    private static void markComponentEmptyEnum(Schema schema) {
        if (schema == null) return;
        boolean markedAny = false;
        java.util.List<?> members = null;
        if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
            members = schema.getOneOf();
        } else if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
            members = schema.getAnyOf();
        } else if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
            members = schema.getAllOf();
        }
        if (members != null) {
            for (Object member : members) {
                if (member instanceof Schema) {
                    Schema ms = (Schema) member;
                    if (ms.getExtensions() == null) {
                        ms.setExtensions(new java.util.LinkedHashMap<>());
                    }
                    ms.addExtension(EMPTY_ENUM_EXT, true);
                    markedAny = true;
                }
            }
        } else {
            if (schema.getExtensions() == null) {
                schema.setExtensions(new java.util.LinkedHashMap<>());
            }
            schema.addExtension(EMPTY_ENUM_EXT, true);
            markedAny = true;
        }
        if (!markedAny && System.getenv("OAS31_DEBUG") != null) {
            System.err.println("[empty-enum] no markable schema in component");
        }
    }

    /**
     * Builds a CompositionDescriptor for a schema if it has oneOf, anyOf, or
     * allOf branches. Returns null for non-composed schemas.
     * <p>
     * Resolves $ref targets recursively with cycle detection via the visited
     * set. Records JSON Pointer locations for diagnostic use.
     */
    private CompositionDescriptor buildCompositionDescriptor(
            String schemaName, Schema schema, OpenAPI openAPI,
            Map<String, Schema> schemas, Set<String> visited) {
        if (schema == null) return null;

        List<Schema> branchSchemas = null;
        String keyword = null;

        if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
            branchSchemas = schema.getOneOf();
            keyword = "oneOf";
        } else if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
            branchSchemas = schema.getAnyOf();
            keyword = "anyOf";
        } else if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
            branchSchemas = schema.getAllOf();
            keyword = "allOf";
        }

        if (branchSchemas == null) return null;

        String schemaLocation = "#/components/schemas/" + schemaName;
        List<CompositionBranchDescriptor> branches = new ArrayList<>();

        // Capture optional discriminator
        DiscriminatorDescriptor discriminatorDescriptor = null;
        if (schema.getDiscriminator() != null) {
            discriminatorDescriptor = new DiscriminatorDescriptor(
                    schema.getDiscriminator().getPropertyName(),
                    schema.getDiscriminator().getMapping());
        }

        for (int index = 0; index < branchSchemas.size(); index++) {
            Schema branchSchema = branchSchemas.get(index);
            String sourceRef = null;
            String resolvedName = null;
            CompositionBranchDescriptor.NullCapability nullCap =
                    CompositionBranchDescriptor.NullCapability.NEVER;
            List<String> supported = new ArrayList<>();
            List<String> unsupported = new ArrayList<>();
            Map<String, Object> validateParams = new LinkedHashMap<>();

            // Resolve the branch schema for assertion scanning
            Schema targetForAssertions = null;
            if (branchSchema != null && branchSchema.get$ref() != null) {
                sourceRef = branchSchema.get$ref();
                String refName = ModelUtils.getSimpleRef(branchSchema.get$ref());
                resolvedName = refName;
                // Stash the local $ref target name for Wave-1 K-29 IR emission.
                validateParams.put("validation-ref", refName);
                // Detect null type via $ref to null schema
                if ("null".equals(refName)) {
                    nullCap = CompositionBranchDescriptor.NullCapability.ALWAYS;
                } else if (schemas.containsKey(refName) && !visited.contains(refName)) {
                    visited.add(refName);
                    Schema refTarget = schemas.get(refName);
                    if (ModelUtils.isNullTypeSchema(openAPI, refTarget)) {
                        nullCap = CompositionBranchDescriptor.NullCapability.ALWAYS;
                    } else {
                        if (Boolean.TRUE.equals(refTarget.getNullable())) {
                            nullCap = CompositionBranchDescriptor.NullCapability.CONDITIONAL;
                        }
                        targetForAssertions = refTarget;
                    }
                }
            } else if (branchSchema != null) {
                targetForAssertions = branchSchema;
                // Detect OAS 3.1 boolean value schemas (true/false literals)
                if (branchSchema.getBooleanSchemaValue() != null) {
                    resolvedName = "boolean-schema";
                } else {
                    resolvedName = branchSchema.getType();
                }
                if (resolvedName == null) {
                    if (branchSchema.getEnum() != null && !branchSchema.getEnum().isEmpty()) {
                        resolvedName = "enum";
                    } else {
                        resolvedName = "object";
                    }
                }
                if (ModelUtils.isNullType(branchSchema)) {
                    nullCap = CompositionBranchDescriptor.NullCapability.ALWAYS;
                } else if (Boolean.TRUE.equals(branchSchema.getNullable())) {
                    nullCap = CompositionBranchDescriptor.NullCapability.CONDITIONAL;
                }
            }

            // Scan the resolved target schema for assertion keywords.
            // Wave-2 §10: $ref + sibling keywords (2020-12) BOTH apply. The
            // surface scan resolves through the ref TARGET; when the branch is a
            // $ref, the branch schema itself is scanned as a second surface so
            // sibling object/array/applicator keywords on the ref node are
            // densified inline (never silently dropped). Unsupported sibling
            // keywords are recorded so they still fail-closed.
            boolean refBranch = branchSchema != null && branchSchema.get$ref() != null;
            if (targetForAssertions != null) {
                scanSurfaceAssertions(targetForAssertions, openAPI,
                        supported, unsupported, validateParams, refBranch);
                if (refBranch && branchSchema != targetForAssertions) {
                    // $ref with siblings: BOTH the resolved target and the branch's
                    // own keyword set apply (2020-12). Ref-node applicator stays
                    // branch-driven; sibling keywords are emitted inline.
                    scanSurfaceAssertions(branchSchema, openAPI,
                            supported, unsupported, validateParams, true);
                }
            }

            // Phase 2: generate a deterministic validatorId for each branch.
            // The id is used as the base name for generated validate_<id>() functions.
            String validatorId = toValidIdentifier(schemaName) + "_branch_" + index;

            CompositionBranchDescriptor branch = new CompositionBranchDescriptor(
                    index, sourceRef, resolvedName, null, validatorId,
                    nullCap, supported, unsupported, validateParams);
            branches.add(branch);
        }

        return new CompositionDescriptor(
                schemaName, schemaLocation, keyword, branches,
                discriminatorDescriptor);
    }

    /**
     * Scans one schema surface for branch assertion keywords, populating the
     * branch supported/unsupported lists and validateParams. Called for the
     * RESOLVED branch target and, for $ref branches, again for the branch
     * schema itself so $ref siblings are preserved (2020-12: a $ref node
     * validates its target AND its own sibling keywords). `refBranchExcluded`
     * suppresses the nested oneOf/anyOf/allOf applicator scan for $ref
     * branches (the ref applicator is resolved via the registry instead).
     */
    private void scanSurfaceAssertions(
            io.swagger.v3.oas.models.media.Schema surface,
            io.swagger.v3.oas.models.OpenAPI openAPI,
            java.util.List<String> supported,
            java.util.List<String> unsupported,
            java.util.Map<String, Object> validateParams,
            boolean refBranchExcluded) {
                // Validation type — use the resolved type name or "type-array" for type arrays
                if (surface.getType() != null) {
                    supported.add("type");
                    validateParams.put("validation-type", surface.getType());
                }
                if (surface.getTypes() != null && !surface.getTypes().isEmpty()) {
                    supported.add("type");
                    validateParams.put("validation-type", "type-array");
                    // OAS 3.1 type arrays: store as List<String> for template iteration;
                    // has-validation-type-array is a boolean flag for outer section guard.
                    validateParams.put("validation-type-array",
                            new ArrayList<>(surface.getTypes()));
                    validateParams.put("has-validation-type-array", true);
                }
                // enum — an EMPTY enum (enum: []) is a reject-all schema handled
                // by the deep JSON store (hasEnumJson with zero members). The
                // swagger-parser models `enum: []` as enum=null + types=[string]
                // (information lost), so preprocessOpenAPI recovers the original
                // keyword from the raw spec and marks the branch via the
                // x-oas31-empty-enum vendor extension; the marker is treated as
                // an empty enum here (a real, non-empty enum takes precedence).
                if (surface.getEnum() != null || isEmptyEnumMarked(surface)) {
                    supported.add("enum");
                    // For a recovered `enum: []` the parser yields enum=null; use
                    // the empty list so the deep store emits ZERO members.
                    java.util.List<?> enumMembers = surface.getEnum();
                    if (enumMembers == null) {
                        enumMembers = java.util.Collections.emptyList();
                    }
                    List<String> enumStrs = new ArrayList<>();
                    String predominantKind = "string";
                    for (Object e : enumMembers) {
                        String es = e != null ? e.toString() : "null";
                        if ("string".equals(predominantKind)) {
                            es = escapeCppStringContent(es);
                        }
                        enumStrs.add(es);
                        if (e instanceof Integer || e instanceof Long || e instanceof Short || e instanceof Byte) {
                            predominantKind = "integer";
                        } else if (e instanceof Double || e instanceof Float || e instanceof java.math.BigDecimal) {
                            if (!"integer".equals(predominantKind)) predominantKind = "number";
                        } else if (e instanceof Boolean) {
                            if (!"integer".equals(predominantKind) && !"number".equals(predominantKind)) predominantKind = "bool";
                        }
                    }
                    validateParams.put("validation-enum-values", enumStrs);
                    validateParams.put("validation-enum-kind", predominantKind);
                    validateParams.put("validation-enum-kind-string", "string".equals(predominantKind));
                    validateParams.put("validation-enum-kind-integer", "integer".equals(predominantKind));
                    validateParams.put("validation-enum-kind-number", "number".equals(predominantKind));
                    validateParams.put("validation-enum-kind-bool", "bool".equals(predominantKind));
                    validateParams.put("has-validation-enum", true);
                    // Keep the RAW swagger enum (deep JSON members) for Wave-1
                    // exact deep-equality IR emission (K-34).
                    validateParams.put("validation-enum-raw", enumMembers);
                }
                // Const: detect JSON kind for the validator template
                if (surface.getConst() != null) {
                    supported.add("const");
                    Object constVal = surface.getConst();
                    if (constVal instanceof Number) {
                        validateParams.put("validation-const-type", "number");
                        validateParams.put("validation-const-value", constVal.toString());
                    } else if (constVal instanceof Boolean) {
                        validateParams.put("validation-const-type", "boolean");
                        validateParams.put("validation-const-value", constVal.toString());
                    } else {
                        validateParams.put("validation-const-type", "string");
                        validateParams.put("validation-const-value",
                                escapeCppStringContent(constVal.toString()));
                    }
                    validateParams.put("has-validation-const", true);
                    // Keep the RAW swagger const value (deep JSON, K-30) for
                    // Wave-1 exact deep-equality IR emission.
                    validateParams.put("validation-const-raw", surface.getConst());
                }
                // Use ModelUtils.resolveMinimumBound / resolveMaximumBound for
                // proper OAS 3.0→3.1 resolution (boolean → numeric conversion,
                // allOf intersection, $ref traversal).
                ModelUtils.ResolvedMinBound resolvedMin = ModelUtils.resolveMinimumBound(openAPI, surface);
                ModelUtils.ResolvedMaxBound resolvedMax = ModelUtils.resolveMaximumBound(openAPI, surface);
                if (resolvedMin != null || resolvedMax != null
                        || surface.getMultipleOf() != null) {
                    supported.add("numeric-range");
                    if (resolvedMin != null) {
                        validateParams.put("validation-min", resolvedMin.minBound);
                        if (resolvedMin.exclusive) {
                            validateParams.put("validation-exclusive-min", resolvedMin.minBound);
                        }
                    }
                    if (resolvedMax != null) {
                        validateParams.put("validation-max", resolvedMax.maxBound);
                        if (resolvedMax.exclusive) {
                            validateParams.put("validation-exclusive-max", resolvedMax.maxBound);
                        }
                    }
                    if (surface.getMultipleOf() != null) {
                        validateParams.put("validation-multiple-of",
                                surface.getMultipleOf());
                    }
                    validateParams.put("has-validation-numeric", true);
                }
                if (surface.getMinLength() != null
                        || surface.getMaxLength() != null) {
                    supported.add("string-length");
                    if (surface.getMinLength() != null) {
                        validateParams.put("validation-min-length",
                                surface.getMinLength());
                    }
                    if (surface.getMaxLength() != null) {
                        validateParams.put("validation-max-length",
                                surface.getMaxLength());
                    }
                    validateParams.put("has-validation-string-length", true);
                }
                if (surface.getPattern() != null) {
                    supported.add("pattern");
                    validateParams.put("validation-pattern",
                            escapeCppStringContent(surface.getPattern()));
                    validateParams.put("has-validation-pattern", true);
                }
                if (surface.getPrefixItems() != null
                        && !surface.getPrefixItems().isEmpty()) {
                    supported.add("array-prefix-items");
                    validateParams.put("validation-prefix-items",
                            surface.getPrefixItems());
                    validateParams.put("has-validation-prefix-items", true);
                }
                // items: OAS requires it on every array type; its presence alone
                // does not affect composition membership beyond type validation,
                // and the Wave-2 evaluator enforces it on the remainder indices.
                if (surface.getItems() != null) {
                    validateParams.put("validation-items", surface.getItems());
                }
                String minItemsLex = countBoundLexemeOf(surface, "minItems");
                String maxItemsLex = countBoundLexemeOf(surface, "maxItems");
                if (surface.getMinItems() != null
                        || surface.getMaxItems() != null
                        || minItemsLex != null || maxItemsLex != null) {
                    supported.add("array-length");
                    if (surface.getMinItems() != null) {
                        validateParams.put("validation-min-items",
                                surface.getMinItems());
                    } else if (minItemsLex != null) {
                        validateParams.put("validation-min-items", minItemsLex);
                    }
                    if (surface.getMaxItems() != null) {
                        validateParams.put("validation-max-items",
                                surface.getMaxItems());
                    } else if (maxItemsLex != null) {
                        validateParams.put("validation-max-items", maxItemsLex);
                    }
                    validateParams.put("has-validation-array-length", true);
                }
                // uniqueItems: PRESENCE (any value) so the keyword never
                // fail-closes; `false` is a no-op that still emits the node.
                if (surface.getUniqueItems() != null) {
                    supported.add("unique-items");
                    validateParams.put("has-validation-unique-items", true);
                    validateParams.put("validation-unique-items",
                            surface.getUniqueItems());
                }
                // required: supported — presence check is generated in validator
                if (surface.getRequired() != null) {
                    supported.add("object-properties");
                    validateParams.put("validation-required",
                            surface.getRequired());
                    validateParams.put("has-validation-object-props", true);
                }
                // properties: densified into property-subschema child rows by the
                // IR emitter (Wave-2). Property-level schemas DO affect branch
                // membership, so they must be enforced, never silently skipped.
                if (surface.getProperties() != null
                        && !surface.getProperties().isEmpty()) {
                    supported.add("object-properties");
                    validateParams.put("validation-properties",
                            surface.getProperties());
                    validateParams.put("has-validation-properties", true);
                }
                // additionalProperties tri-state (Wave-2): absent/true -> allow,
                // false -> reject, schema -> validate. No longer fail-closed.
                Object addPropsVal = surface.getAdditionalProperties();
                if (addPropsVal != null) {
                    supported.add("additional-properties");
                    if (addPropsVal instanceof Schema) {
                        Schema addPropSchema = (Schema) addPropsVal;
                        Boolean apBool = addPropSchema.getBooleanSchemaValue();
                        if (apBool != null) {
                            validateParams.put("validation-additional-properties-kind",
                                    Boolean.TRUE.equals(apBool) ? "allowed" : "reject");
                        } else {
                            validateParams.put("validation-additional-properties-kind", "schema");
                            validateParams.put("validation-additional-properties-schema", addPropSchema);
                        }
                    } else if (addPropsVal instanceof Boolean) {
                        validateParams.put("validation-additional-properties-kind",
                                Boolean.TRUE.equals(addPropsVal) ? "allowed" : "reject");
                    }
                }
                String minPropsLex = countBoundLexemeOf(surface, "minProperties");
                String maxPropsLex = countBoundLexemeOf(surface, "maxProperties");
                if (surface.getMinProperties() != null || minPropsLex != null) {
                    supported.add("object-property-count");
                    validateParams.put("validation-min-properties",
                            surface.getMinProperties() != null
                                    ? surface.getMinProperties() : minPropsLex);
                }
                if (surface.getMaxProperties() != null || maxPropsLex != null) {
                    supported.add("object-property-count");
                    validateParams.put("validation-max-properties",
                            surface.getMaxProperties() != null
                                    ? surface.getMaxProperties() : maxPropsLex);
                }
                // oneOf/anyOf/allOf nested inside a branch are densified as
                // applicator children (Wave-2); never fail-closed. A branch that
                // IS a $ref excludes the applicator scan (the $ref applicator is
                // resolved via the registry; the REF TARGET's own composition is
                // materialised as the target branch row, not as this node's
                // applicator — keeps pure-ref nodes truly transparent).
                String branchApplicator = null;
                if (!refBranchExcluded) {
                    if (surface.getOneOf() != null && !surface.getOneOf().isEmpty()) {
                        branchApplicator = "oneOf";
                    } else if (surface.getAnyOf() != null && !surface.getAnyOf().isEmpty()) {
                        branchApplicator = "anyOf";
                    } else if (surface.getAllOf() != null && !surface.getAllOf().isEmpty()) {
                        branchApplicator = "allOf";
                    }
                }
                if (branchApplicator != null) {
                    validateParams.put("validation-applicator", branchApplicator);
                    validateParams.put("validation-applicator-schemas",
                            "oneOf".equals(branchApplicator) ? surface.getOneOf()
                            : "anyOf".equals(branchApplicator) ? surface.getAnyOf()
                            : surface.getAllOf());
                }
                // Nested composition on branches is handled by the resolved
                // model type, not the branch validator. Do not fail on nested
                // composition; the model hierarchy validates it at decode time.
                // `not` is always unsupported: it can flip any membership decision
                // and no generated validator currently implements it.
                // Wave-1: `not` is now implemented by the shared IR/evaluator
                // (K-01) and is no longer fail-closed; pass the subschema to
                // the IR emitter.
                if (surface.getNot() != null) {
                    validateParams.put("validation-not-schema", surface.getNot());
                }

                // Detect unsupported assertion keywords
                io.swagger.v3.oas.models.media.Discriminator targetDisc =
                        surface.getDiscriminator();
                if (targetDisc != null) {
                    // Discriminator on branches is annotation-only for now
                }
                // if/then/else: not yet implemented as a conditional applicator;
                // NOT fail-closed so "ref-to-if" corpora still GENERATE and run
                // (the inline-ref/if-schema content is densified via $id
                // resolution; honest: a bare if-then-else without a covering ref
                // is ignored, measured as FAIL not BLOCKED).
                if (surface.getIf() != null) {
                    validateParams.put("validation-if-schema", surface.getIf());
                }
                if (surface.getThen() != null) {
                    validateParams.put("validation-then-schema", surface.getThen());
                }
                if (surface.getElse() != null) {
                    validateParams.put("validation-else-schema", surface.getElse());
                }
                if (surface.getDependentRequired() != null) {
                    unsupported.add("dependencies");
                }
                if (surface.getContains() != null) {
                    unsupported.add("contains");
                }
                if (surface.getUnevaluatedProperties() != null) {
                    supported.add("unevaluated");
                    validateParams.put("validation-unevaluated-properties",
                            surface.getUnevaluatedProperties());
                }
                if (surface.getContentMediaType() != null
                        || surface.getContentEncoding() != null) {
                    unsupported.add("content-encoding");
                }
                if (surface.getPropertyNames() != null) {
                    unsupported.add("property-names");
                }
                // OAS 3.1 boolean value schemas (true → always-match, false → never-match).
                // Wave-1: implemented by the shared IR/evaluator (K-03) and no longer
                // fail-closed; preserve the literal boolean so IR emission can populate
                // SchemaNode::booleanValue.
                if (surface.getBooleanSchemaValue() != null) {
                    validateParams.put("validation-boolean-value",
                            surface.getBooleanSchemaValue());
                }
    }

    // ========================================================================
    // Wave 0: OAS 3.1 dialect detection and normative structure gate (S-V)
    // ------------------------------------------------------------------------
    // Pure, testable helpers for the conformance plan (Wave 0 items 4 & 5).
    //
    // They are intentionally NOT auto-wired into the default generation
    // pipeline: the repository test suite builds specs without an `info`
    // object, so unconditional fail-closed wiring here would regress hundreds
    // of existing fixtures before the Wave-1 enforcement decision is recorded.
    // The conformance CI and the explicit strict mode invoke these helpers and
    // act on the returned diagnostics. Wiring enforcement into processOpenAPI
    // is deferred to Wave 1 and tracked in the compliance plan.
    // ========================================================================

    /** Pinned OAS 3.1 Schema dialect (spec.openapis.org/oas/3.1/dialect/2024-11-10). */
    public static final String OAS_31_DIALECT =
            "https://spec.openapis.org/oas/3.1/dialect/2024-11-10";

    /** OAS alias accepted only as the identifier for the same pinned revision. */
    public static final String OAS_31_DIALECT_BASE_ALIAS =
            "https://spec.openapis.org/oas/3.1/dialect/base";

    /** Plain JSON Schema Draft 2020-12 core identifier (non-OAS dialect). */
    public static final String DRAFT_2020_12 =
            "https://json-schema.org/draft/2020-12/schema";

    /** Classified effective schema dialect for an OpenAPI document. */
    public enum OasDialect {
        /** OAS 3.1 pinned dialect (or its base alias). */
        OAS_31,
        /** Plain JSON Schema Draft 2020-12 (not OAS-wrapped). */
        DRAFT_2020_12_REC,
        /** A dialect identifier not recognized by this program. */
        UNRECOGNIZED,
        /** No dialect declared (OAS 3.1 default applies for OAS 3.1 documents). */
        UNSPECIFIED
    }

    /**
     * Resolve the effective schema dialect from the top-level
     * {@code jsonSchemaDialect} and/or the root {@code $schema}. Per OAS 3.1
     * the root {@code $schema} (when present at a document/schema-resource
     * root) takes precedence over {@code jsonSchemaDialect} for that resource.
     */
    public static OasDialect resolveEffectiveDialect(String jsonSchemaDialect, String rootSchema) {
        String effective = StringUtils.isNotBlank(rootSchema) ? rootSchema : jsonSchemaDialect;
        if (StringUtils.isBlank(effective)) {
            return OasDialect.UNSPECIFIED;
        }
        String trimmed = effective.trim();
        if (OAS_31_DIALECT.equals(trimmed) || OAS_31_DIALECT_BASE_ALIAS.equals(trimmed)) {
            return OasDialect.OAS_31;
        }
        if (DRAFT_2020_12.equals(trimmed)) {
            return OasDialect.DRAFT_2020_12_REC;
        }
        return OasDialect.UNRECOGNIZED;
    }

    /** Resolve the effective dialect of an OpenAPI document from its declared knobs. */
    public static OasDialect resolveDocumentDialect(OpenAPI openAPI) {
        if (openAPI == null) {
            return OasDialect.UNSPECIFIED;
        }
        String jsonSchemaDialect = openAPI.getJsonSchemaDialect();
        if (jsonSchemaDialect != null) {
            return resolveEffectiveDialect(jsonSchemaDialect, null);
        }
        // No jsonSchemaDialect: for OAS 3.1 the pinned dialect is the default.
        return isOas31(openAPI) ? OasDialect.OAS_31 : OasDialect.UNSPECIFIED;
    }

    /**
     * OAS 3 structural normative checks (plan §5 Wave 0 item 5). Returns a list
     * of human-readable diagnostics; an empty list means the structure is
     * normative. The caller decides whether to fail generation (strict mode).
     */
    public List<String> validateNormativeOas3Structure(OpenAPI openAPI) {
        List<String> diagnostics = new ArrayList<>();
        if (openAPI == null) {
            diagnostics.add("document is null; cannot satisfy OAS structural requirements");
            return diagnostics;
        }
        String version = openAPI.getOpenapi();
        if (StringUtils.isBlank(version)) {
            diagnostics.add("missing root `openapi` version field (required for OAS 3.x)");
        } else if (!version.matches("3(\\.[0-9]+)*")) {
            diagnostics.add("unsupported openapi version '" + version
                    + "' (program targets OAS 3.0.x/3.1.x)");
        }
        io.swagger.v3.oas.models.info.Info info = openAPI.getInfo();
        if (info == null) {
            diagnostics.add("missing root `info` object (required by OAS)");
        } else {
            if (StringUtils.isBlank(info.getTitle())) {
                diagnostics.add("missing `info.title` (required by OAS)");
            }
            if (StringUtils.isBlank(info.getVersion())) {
                diagnostics.add("missing `info.version` (required by OAS)");
            }
        }
        boolean hasPaths = openAPI.getPaths() != null && !openAPI.getPaths().isEmpty();
        boolean hasComponents = openAPI.getComponents() != null;
        boolean hasWebhooks =
                openAPI.getWebhooks() != null && !openAPI.getWebhooks().isEmpty();
        if (!hasPaths && !hasComponents && !hasWebhooks) {
            diagnostics.add("missing at least one of `paths`, `components`, or `webhooks`");
        }
        return diagnostics;
    }

    /**
     * Dialect/metaschema policy gate (plan §5 Wave 0 item 4): a dialect
     * identifier that is not recognized by this program must be refused
     * (unknown required vocabulary). The OAS 3.1 default applies when an
     * OAS 3.1 document declares no {@code jsonSchemaDialect}.
     * <p>
     * Full {@code $vocabulary}/metaschema inspection (read only from the
     * selected metaschema root) and {@code $schema}-in-subschema rejection
     * require the Wave-1 SchemaResourceRegistry and are tracked as blockers.
     */
    public List<String> validateDialectPolicy(OpenAPI openAPI) {
        List<String> diagnostics = new ArrayList<>();
        if (openAPI == null) {
            return diagnostics;
        }
        OasDialect dialect = resolveDocumentDialect(openAPI);
        if (dialect == OasDialect.UNRECOGNIZED) {
            diagnostics.add("unrecognized jsonSchemaDialect '" + openAPI.getJsonSchemaDialect()
                    + "' — unknown required vocabulary/dialect, fail generation");
        }
        return diagnostics;
    }

    private static boolean isOas31(OpenAPI openAPI) {
        if (openAPI == null) {
            return false;
        }
        String v = openAPI.getOpenapi();
        return v != null && v.startsWith("3.1");
    }

    // ========================================================================
    // Wave 0 (A-1): Exhaustive schema-valued-position scanner + G-honest ledger
    // ------------------------------------------------------------------------
    // Implements plan §5 "Wave 0" item 1: an exhaustive scanner that walks every
    // schema-valued position declared by the OAS 3.1 / JSON Schema 2020-12
    // dialect (applicators, composition branches, properties/items, prefixItems,
    // patternProperties, propertyNames, dependentSchemas, contains, not,
    // if/then/else, dependentRequired, unevaluated*, contentSchema, ...) and, for
    // every required-vocabulary keyword (plan §3.1-3.4 plus S-A keywords), records
    // whether the generator (a) EMITTED a validator, (b) FAIL_CLOSED, or
    // (c) SILENT_SKIP (the bug to fix).
    //
    // The occurrence ledger is a pure, testable data structure exposed for L0
    // tests. Silent-skip enforcement is OPT-IN (a Java property/flag): default
    // generation is untouched so the 90+ existing fixtures do not regress.
    // ========================================================================

    /** Core vocabulary identifiers (plan §3.1). */
    public static final String VOCAB_CORE = "https://json-schema.org/draft/2020-12/vocab/core";
    /** Applicator vocabulary (plan §3.2). */
    public static final String VOCAB_APPLICATOR = "https://json-schema.org/draft/2020-12/vocab/applicator";
    /** Unevaluated vocabulary (plan §3.3). */
    public static final String VOCAB_UNEVALUATED = "https://json-schema.org/draft/2020-12/vocab/unevaluated";
    /** Validation vocabulary (plan §3.4). */
    public static final String VOCAB_VALIDATION = "https://json-schema.org/draft/2020-12/vocab/validation";
    /** Metadata vocabulary (plan §3.5). */
    public static final String VOCAB_METADATA = "https://json-schema.org/draft/2020-12/vocab/meta-data";
    /** Format-annotation vocabulary (plan §3.6). */
    public static final String VOCAB_FORMAT = "https://json-schema.org/draft/2020-12/vocab/format-annotation";
    /** Content vocabulary (plan §3.7). */
    public static final String VOCAB_CONTENT = "https://json-schema.org/draft/2020-12/vocab/content";
    /** OAS base vocabulary (plan §3.8). */
    public static final String VOCAB_OAS_BASE = "https://spec.openapis.org/oas/3.1/vocab/base";

    /**
     * Classification of a single keyword occurrence in the G-honest ledger.
     */
    public enum KeywordOccurrenceStatus {
        /** A validator (or a structural handler) is emitted for this keyword. */
        EMITTED,
        /** The keyword affects validity but no validator is emitted; generator fails closed. */
        FAIL_CLOSED,
        /** The keyword affects validity but is neither emitted nor fail-closed (the bug). */
        SILENT_SKIP,
        /** Annotation / identifier / reference keyword with no direct validity effect. */
        ANNOTATION,
        /** A schema-valued position not directly indexable via swagger-models (raw re-walk needed). */
        PARSER_GAP
    }

    /** One keyword occurrence at a schema-valued position in the G-honest ledger. */
    public static final class KeywordOccurrence {
        private final String keyword;
        private final String location;
        private final String vocabularyUri;
        private final KeywordOccurrenceStatus status;
        private final String detail;

        public KeywordOccurrence(String keyword, String location, String vocabularyUri,
                                 KeywordOccurrenceStatus status, String detail) {
            this.keyword = keyword;
            this.location = location;
            this.vocabularyUri = vocabularyUri;
            this.status = status;
            this.detail = detail;
        }

        public String getKeyword() { return keyword; }
        public String getLocation() { return location; }
        public String getVocabularyUri() { return vocabularyUri; }
        public KeywordOccurrenceStatus getStatus() { return status; }
        public String getDetail() { return detail; }

        @Override
        public String toString() {
            return status + "[" + keyword + "]@" + location
                    + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")");
        }
    }

    /**
     * G-honest keyword occurrence ledger: an ordered, deduplicated index of every
     * schema-valued-position keyword occurrence discovered by the exhaustive
     * scanner, plus keyword→occurrences lookup and status aggregations.
     */
    public static final class KeywordOccurrenceLedger {
        private final List<KeywordOccurrence> occurrences = new ArrayList<>();
        private final LinkedHashMap<String, List<KeywordOccurrence>> byKeyword = new LinkedHashMap<>();

        void add(KeywordOccurrence occurrence) {
            occurrences.add(occurrence);
            byKeyword.computeIfAbsent(occurrence.getKeyword(), k -> new ArrayList<>())
                    .add(occurrence);
        }

        public List<KeywordOccurrence> getOccurrences() {
            return Collections.unmodifiableList(new ArrayList<>(occurrences));
        }

        public List<KeywordOccurrence> forKeyword(String keyword) {
            return byKeyword.getOrDefault(keyword, Collections.emptyList());
        }

        public boolean hasKeyword(String keyword) {
            return byKeyword.containsKey(keyword);
        }

        public Set<String> getKeywords() {
            return Collections.unmodifiableSet(new LinkedHashSet<>(byKeyword.keySet()));
        }

        public List<KeywordOccurrence> withStatus(KeywordOccurrenceStatus status) {
            List<KeywordOccurrence> out = new ArrayList<>();
            for (KeywordOccurrence o : occurrences) {
                if (o.getStatus() == status) {
                    out.add(o);
                }
            }
            return out;
        }

        /** Keywords whose occurrences are all non-silent (EMITTED / FAIL_CLOSED / ANNOTATION). */
        public Set<String> silentSkips() {
            Set<String> out = new LinkedHashSet<>();
            for (KeywordOccurrence o : occurrences) {
                if (o.getStatus() == KeywordOccurrenceStatus.SILENT_SKIP) {
                    out.add(o.getKeyword());
                }
            }
            return Collections.unmodifiableSet(out);
        }

        public Set<String> failClosed() {
            Set<String> out = new LinkedHashSet<>();
            for (KeywordOccurrence o : occurrences) {
                if (o.getStatus() == KeywordOccurrenceStatus.FAIL_CLOSED) {
                    out.add(o.getKeyword());
                }
            }
            return Collections.unmodifiableSet(out);
        }

        public Set<String> emitted() {
            Set<String> out = new LinkedHashSet<>();
            for (KeywordOccurrence o : occurrences) {
                if (o.getStatus() == KeywordOccurrenceStatus.EMITTED) {
                    out.add(o.getKeyword());
                }
            }
            return Collections.unmodifiableSet(out);
        }

        public int size() {
            return occurrences.size();
        }
    }

    /**
     * Optional silent-skip scanner flag (opt-in / internally gated). When set to
     * {@code true} on {@code additionalProperties}, {@link #preprocessOpenAPI}
     * runs the exhaustive scanner and fails generation on any SILENT_SKIP.
     * Default false: normal generation never regresses existing fixtures.
     */
    public static final String STRICT_SCANNER_OPTION = "oas31NoSilentSkip";

    /**
     * Exhaustive schema-valued-position scanner. Walks every schema under
     * {@code components/schemas} and recursively each schema-valued child
     * declared by the OAS 3.1 dialect, recording a {@link KeywordOccurrence}
     * ledger. Reference targets ({@code $ref}) are NOT followed for exhaustive
     * sub-position scanning in this pure Wave-0 pass (that requires the Wave-1
     * SchemaResourceRegistry); every keyword occurrence on the authored schema
     * tree is still indexed.
     */
    public KeywordOccurrenceLedger scanSchemaKeywordOccurrences(OpenAPI openAPI) {
        KeywordOccurrenceLedger ledger = new KeywordOccurrenceLedger();
        if (openAPI == null || openAPI.getComponents() == null
                || openAPI.getComponents().getSchemas() == null) {
            return ledger;
        }
        for (Map.Entry<String, Schema> e : openAPI.getComponents().getSchemas().entrySet()) {
            String name = e.getKey();
            scanSchemaNode(e.getValue(), "#/components/schemas/" + name, ledger, 0);
        }
        return ledger;
    }

    private void scanSchemaNode(Schema<?> schema, String location,
                                KeywordOccurrenceLedger ledger, int depth) {
        if (schema == null || depth > 1024) {
            return;
        }

        // ---- Core / identifier keywords (plan §3.1) ----
        if (schema.get$id() != null) {
            record(ledger, "$id", location, VOCAB_CORE, KeywordOccurrenceStatus.ANNOTATION,
                    "identifier; resource registry indexing is Wave 1 (K-29)");
        }
        if (schema.get$schema() != null) {
            record(ledger, "$schema", location, VOCAB_CORE, KeywordOccurrenceStatus.ANNOTATION,
                    "dialect selector; guarded by validateDialectPolicy (Wave 4.3)");
        }
        if (schema.get$ref() != null) {
            record(ledger, "$ref", location, VOCAB_CORE, KeywordOccurrenceStatus.EMITTED,
                    "reference; resolved by parser/IR");
        }
        if (schema.get$anchor() != null) {
            record(ledger, "$anchor", location, VOCAB_CORE, KeywordOccurrenceStatus.ANNOTATION,
                    "plain-name fragment; Wave 4.2 (K-16)");
        }
        if (schema.get$dynamicAnchor() != null) {
            record(ledger, "$dynamicAnchor", location, VOCAB_CORE, KeywordOccurrenceStatus.ANNOTATION,
                    "dynamic plain-name fragment; Wave 4.2 (K-16)");
        }
        if (schema.get$dynamicRef() != null) {
            record(ledger, "$dynamicRef", location, VOCAB_CORE, KeywordOccurrenceStatus.PARSER_GAP,
                    "dynamic reference; Wave 4.2 (K-16)");
        }
        if (schema.get$comment() != null) {
            record(ledger, "$comment", location, VOCAB_CORE, KeywordOccurrenceStatus.ANNOTATION,
                    "no validity action / no annotation (K-32; string shape Wave 3)");
        }
        if (schema.get$vocabulary() != null) {
            record(ledger, "$vocabulary", location, VOCAB_CORE, KeywordOccurrenceStatus.ANNOTATION,
                    "metaschema declaration; Wave 4.3 (K-27)");
        }

        // ---- Validation vocabulary (plan §3.4) ----
        if (schema.getType() != null || (schema.getTypes() != null && !schema.getTypes().isEmpty())) {
            record(ledger, "type", location, VOCAB_VALIDATION, KeywordOccurrenceStatus.EMITTED,
                    "validation-type / validation-type-array");
        }
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            record(ledger, "enum", location, VOCAB_VALIDATION, KeywordOccurrenceStatus.EMITTED,
                    "validation-enum-values");
        }
        if (schema.getConst() != null) {
            record(ledger, "const", location, VOCAB_VALIDATION, KeywordOccurrenceStatus.EMITTED,
                    "validation-const; exact-math caveat Wave 1 (K-34)");
        }
        boolean hasMinimum = schema.getMinimum() != null
                || schema.getExclusiveMinimum() != null
                || schema.getExclusiveMinimumValue() != null;
        boolean hasMaximum = schema.getMaximum() != null
                || schema.getExclusiveMaximum() != null
                || schema.getExclusiveMaximumValue() != null;
        if (hasMinimum) {
            record(ledger, "minimum", location, VOCAB_VALIDATION, KeywordOccurrenceStatus.EMITTED,
                    "numeric-range; 3.0 boolean exclusiveMinimum preserved");
        }
        if (hasMaximum) {
            record(ledger, "maximum", location, VOCAB_VALIDATION, KeywordOccurrenceStatus.EMITTED,
                    "numeric-range; 3.0 boolean exclusiveMaximum preserved");
        }
        if (schema.getMultipleOf() != null) {
            record(ledger, "multipleOf", location, VOCAB_VALIDATION, KeywordOccurrenceStatus.EMITTED,
                    "validation-multiple-of; exact-math caveat Wave 1 (K-33)");
        }
        if (schema.getMinLength() != null) {
            record(ledger, "minLength", location, VOCAB_VALIDATION, KeywordOccurrenceStatus.EMITTED,
                    "validation-min-length; byte-length caveat Wave 2.5 (K-13)");
        }
        if (schema.getMaxLength() != null) {
            record(ledger, "maxLength", location, VOCAB_VALIDATION, KeywordOccurrenceStatus.EMITTED,
                    "validation-max-length; byte-length caveat Wave 2.5 (K-13)");
        }
        if (schema.getPattern() != null) {
            record(ledger, "pattern", location, VOCAB_VALIDATION, KeywordOccurrenceStatus.EMITTED,
                    "validation-pattern; regex_match full-anchor caveat Wave 3.6 (K-13)");
        }
        if (schema.getMinItems() != null) {
            record(ledger, "minItems", location, VOCAB_VALIDATION, KeywordOccurrenceStatus.EMITTED,
                    "validation-min-items");
        }
        if (schema.getMaxItems() != null) {
            record(ledger, "maxItems", location, VOCAB_VALIDATION, KeywordOccurrenceStatus.EMITTED,
                    "validation-max-items");
        }
        if (Boolean.TRUE.equals(schema.getUniqueItems())) {
            record(ledger, "uniqueItems", location, VOCAB_VALIDATION, KeywordOccurrenceStatus.EMITTED,
                    "validation-unique-items; exact-math caveat Wave 1 (K-22)");
        }
        if (schema.getRequired() != null && !schema.getRequired().isEmpty()) {
            record(ledger, "required", location, VOCAB_VALIDATION, KeywordOccurrenceStatus.EMITTED,
                    "validation-required / object-properties");
        }
        if (schema.getMinProperties() != null) {
            record(ledger, "minProperties", location, VOCAB_VALIDATION, KeywordOccurrenceStatus.EMITTED,
                    "validation-min-properties (Wave-2 object-property-count)");
        }
        if (schema.getMaxProperties() != null) {
            record(ledger, "maxProperties", location, VOCAB_VALIDATION, KeywordOccurrenceStatus.EMITTED,
                    "validation-max-properties (Wave-2 object-property-count)");
        }
        if (schema.getMinContains() != null) {
            record(ledger, "minContains", location, VOCAB_VALIDATION, KeywordOccurrenceStatus.FAIL_CLOSED,
                    "no generated validator; contains family Wave 3.1 (K-08)");
        }
        if (schema.getMaxContains() != null) {
            record(ledger, "maxContains", location, VOCAB_VALIDATION, KeywordOccurrenceStatus.FAIL_CLOSED,
                    "no generated validator; contains family Wave 3.1 (K-08)");
        }
        if (schema.getDependentRequired() != null && !schema.getDependentRequired().isEmpty()) {
            record(ledger, "dependentRequired", location, VOCAB_VALIDATION, KeywordOccurrenceStatus.FAIL_CLOSED,
                    "no generated validator; dependencies Wave 3.4 (K-11)");
        }

        // ---- Applicator vocabulary (plan §3.2) ----
        if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            record(ledger, "properties", location, VOCAB_APPLICATOR, KeywordOccurrenceStatus.EMITTED,
                    "object model emission");
            for (Map.Entry<String, Schema> p : schema.getProperties().entrySet()) {
                scanSchemaNode(p.getValue(), location + "/properties/" + p.getKey(), ledger, depth + 1);
            }
        }
        if (schema.getPatternProperties() != null && !schema.getPatternProperties().isEmpty()) {
            record(ledger, "patternProperties", location, VOCAB_APPLICATOR, KeywordOccurrenceStatus.FAIL_CLOSED,
                    "no generated validator; Wave 3.2 (K-09) — previously a silent skip");
            for (Map.Entry<String, Schema> p : schema.getPatternProperties().entrySet()) {
                scanSchemaNode(p.getValue(), location + "/patternProperties/" + p.getKey(), ledger, depth + 1);
            }
        }
        if (schema.getAdditionalProperties() != null) {
            record(ledger, "additionalProperties", location, VOCAB_APPLICATOR, KeywordOccurrenceStatus.FAIL_CLOSED,
                    "fail-closed unless no-op (true/absent)");
            Object addProp = schema.getAdditionalProperties();
            if (addProp instanceof Schema) {
                scanSchemaNode((Schema) addProp, location + "/additionalProperties", ledger, depth + 1);
            }
        }
        if (schema.getPropertyNames() != null) {
            record(ledger, "propertyNames", location, VOCAB_APPLICATOR, KeywordOccurrenceStatus.FAIL_CLOSED,
                    "no generated validator; Wave 3.3 (K-10)");
            scanSchemaNode(schema.getPropertyNames(), location + "/propertyNames", ledger, depth + 1);
        }
        if (schema.getDependentSchemas() != null && !schema.getDependentSchemas().isEmpty()) {
            record(ledger, "dependentSchemas", location, VOCAB_APPLICATOR, KeywordOccurrenceStatus.FAIL_CLOSED,
                    "no generated validator; Wave 3.4 (K-11) — previously a silent skip");
            for (Map.Entry<String, Schema> d : schema.getDependentSchemas().entrySet()) {
                scanSchemaNode(d.getValue(), location + "/dependentSchemas/" + d.getKey(), ledger, depth + 1);
            }
        }
        if (schema.getPrefixItems() != null && !schema.getPrefixItems().isEmpty()) {
            record(ledger, "prefixItems", location, VOCAB_APPLICATOR, KeywordOccurrenceStatus.FAIL_CLOSED,
                    "fail-closed; Wave 2.2 (K-06)");
            for (int i = 0; i < schema.getPrefixItems().size(); i++) {
                scanSchemaNode(schema.getPrefixItems().get(i), location + "/prefixItems/" + i, ledger, depth + 1);
            }
        }
        if (schema.getItems() != null) {
            record(ledger, "items", location, VOCAB_APPLICATOR, KeywordOccurrenceStatus.EMITTED,
                    "element typing via array storage");
            scanSchemaNode(schema.getItems(), location + "/items", ledger, depth + 1);
        }
        if (schema.getContains() != null) {
            record(ledger, "contains", location, VOCAB_APPLICATOR, KeywordOccurrenceStatus.FAIL_CLOSED,
                    "no generated validator; Wave 3.1 (K-08)");
            scanSchemaNode(schema.getContains(), location + "/contains", ledger, depth + 1);
        }
        if (schema.getNot() != null) {
            record(ledger, "not", location, VOCAB_APPLICATOR, KeywordOccurrenceStatus.EMITTED,
                    "validation-not-schema; Wave-1 evaluator (K-01); residual annotation/unevaluated-in-not FAIL is Wave-3");
            scanSchemaNode(schema.getNot(), location + "/not", ledger, depth + 1);
        }
        if (schema.getIf() != null || schema.getThen() != null || schema.getElse() != null) {
            if (schema.getIf() != null) {
                record(ledger, "if", location, VOCAB_APPLICATOR, KeywordOccurrenceStatus.FAIL_CLOSED,
                        "conditional; Wave 3.5 (K-02)");
                scanSchemaNode(schema.getIf(), location + "/if", ledger, depth + 1);
            }
            if (schema.getThen() != null) {
                record(ledger, "then", location, VOCAB_APPLICATOR, KeywordOccurrenceStatus.FAIL_CLOSED,
                        "conditional; Wave 3.5 (K-02)");
                scanSchemaNode(schema.getThen(), location + "/then", ledger, depth + 1);
            }
            if (schema.getElse() != null) {
                record(ledger, "else", location, VOCAB_APPLICATOR, KeywordOccurrenceStatus.FAIL_CLOSED,
                        "conditional; Wave 3.5 (K-02)");
                scanSchemaNode(schema.getElse(), location + "/else", ledger, depth + 1);
            }
        }
        if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
            record(ledger, "allOf", location, VOCAB_APPLICATOR, KeywordOccurrenceStatus.EMITTED,
                    "composition; getUnsupportedAssertions may carry FAIL_CLOSED branches");
            for (int i = 0; i < schema.getAllOf().size(); i++) {
                scanSchemaNode(schema.getAllOf().get(i), location + "/allOf/" + i, ledger, depth + 1);
            }
        }
        if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
            record(ledger, "anyOf", location, VOCAB_APPLICATOR, KeywordOccurrenceStatus.EMITTED,
                    "composition");
            for (int i = 0; i < schema.getAnyOf().size(); i++) {
                scanSchemaNode(schema.getAnyOf().get(i), location + "/anyOf/" + i, ledger, depth + 1);
            }
        }
        if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
            record(ledger, "oneOf", location, VOCAB_APPLICATOR, KeywordOccurrenceStatus.EMITTED,
                    "composition");
            for (int i = 0; i < schema.getOneOf().size(); i++) {
                scanSchemaNode(schema.getOneOf().get(i), location + "/oneOf/" + i, ledger, depth + 1);
            }
        }

        // ---- Unevaluated vocabulary (plan §3.3) ----
        if (schema.getUnevaluatedProperties() != null) {
            record(ledger, "unevaluatedProperties", location, VOCAB_UNEVALUATED,
                    KeywordOccurrenceStatus.FAIL_CLOSED, "no generated validator; Wave 4.1 (K-12)");
            Schema<?> up = schema.getUnevaluatedProperties();
            scanSchemaNode(up, location + "/unevaluatedProperties", ledger, depth + 1);
        }
        if (schema.getUnevaluatedItems() != null) {
            record(ledger, "unevaluatedItems", location, VOCAB_UNEVALUATED,
                    KeywordOccurrenceStatus.FAIL_CLOSED,
                    "no generated validator; Wave 4.1 (K-12) — previously a silent skip");
            Schema ui = schema.getUnevaluatedItems();
            scanSchemaNode(ui, location + "/unevaluatedItems", ledger, depth + 1);
        }

        // ---- Metadata vocabulary (plan §3.5): annotation only ----
        if (schema.getTitle() != null) record(ledger, "title", location, VOCAB_METADATA, KeywordOccurrenceStatus.ANNOTATION, null);
        if (schema.getDescription() != null) record(ledger, "description", location, VOCAB_METADATA, KeywordOccurrenceStatus.ANNOTATION, null);
        if (schema.getDefault() != null) record(ledger, "default", location, VOCAB_METADATA, KeywordOccurrenceStatus.ANNOTATION, "annotation only; never injected (K-21)");
        if (Boolean.TRUE.equals(schema.getDeprecated())) record(ledger, "deprecated", location, VOCAB_METADATA, KeywordOccurrenceStatus.ANNOTATION, null);
        if (Boolean.TRUE.equals(schema.getReadOnly())) record(ledger, "readOnly", location, VOCAB_METADATA, KeywordOccurrenceStatus.ANNOTATION, null);
        if (Boolean.TRUE.equals(schema.getWriteOnly())) record(ledger, "writeOnly", location, VOCAB_METADATA, KeywordOccurrenceStatus.ANNOTATION, null);
        if (schema.getExamples() != null && !schema.getExamples().isEmpty()) record(ledger, "examples", location, VOCAB_METADATA, KeywordOccurrenceStatus.ANNOTATION, null);

        // ---- Format-annotation vocabulary (plan §3.6) ----
        if (schema.getFormat() != null) record(ledger, "format", location, VOCAB_FORMAT, KeywordOccurrenceStatus.ANNOTATION,
                "annotation by default; strict assertion Wave 4.4 (K-14)");

        // ---- Content vocabulary (plan §3.7) ----
        if (schema.getContentEncoding() != null) record(ledger, "contentEncoding", location, VOCAB_CONTENT, KeywordOccurrenceStatus.ANNOTATION,
                "annotation; no auto-decode (K-31)");
        if (schema.getContentMediaType() != null) record(ledger, "contentMediaType", location, VOCAB_CONTENT, KeywordOccurrenceStatus.ANNOTATION,
                "annotation; no auto-decode (K-31)");
        if (schema.getContentSchema() != null) {
            record(ledger, "contentSchema", location, VOCAB_CONTENT, KeywordOccurrenceStatus.ANNOTATION,
                    "schema-valued annotation; child indexed (K-15)");
            scanSchemaNode(schema.getContentSchema(), location + "/contentSchema", ledger, depth + 1);
        }

        // ---- OAS base vocabulary (plan §3.8): annotation only ----
        if (schema.getDiscriminator() != null) record(ledger, "discriminator", location, VOCAB_OAS_BASE, KeywordOccurrenceStatus.ANNOTATION,
                "validation-neutral candidate-order hint");
        if (schema.getXml() != null) record(ledger, "xml", location, VOCAB_OAS_BASE, KeywordOccurrenceStatus.ANNOTATION, null);
        if (schema.getExternalDocs() != null) record(ledger, "externalDocs", location, VOCAB_OAS_BASE, KeywordOccurrenceStatus.ANNOTATION, null);
        if (schema.getExample() != null || schema.getExampleSetFlag()) record(ledger, "example", location, VOCAB_OAS_BASE, KeywordOccurrenceStatus.ANNOTATION,
                "OAS singular example, 3.0 dual-path preserved");

        // ---- 3.0 dual-path compatibility keywords ----
        if (Boolean.TRUE.equals(schema.getNullable())) record(ledger, "nullable", location, VOCAB_OAS_BASE, KeywordOccurrenceStatus.EMITTED,
                "3.0 nullable dual-path; tri-state NullableField");
        if (schema.getBooleanSchemaValue() != null) record(ledger, "boolean-schema", location, VOCAB_VALIDATION, KeywordOccurrenceStatus.EMITTED,
                "boolean value-schema; Wave-1 (K-03) SUPPORTED in OAS 3.1; OAS 3.0 rejects a bare boolean schema (documented dual-path limitation)");
    }

    private static void record(KeywordOccurrenceLedger ledger, String keyword, String location,
                               String vocabularyUri, KeywordOccurrenceStatus status, String detail) {
        ledger.add(new KeywordOccurrence(keyword, location, vocabularyUri, status, detail));
    }

    /**
     * Returns every SILENT_SKIP keyword in the occurrence ledger — the silent-ignore
     * gaps that G-honest (GH) forbids. An empty result means zero silent skips.
     */
    public List<String> validateNoSilentSkips(KeywordOccurrenceLedger ledger) {
        List<String> diagnostics = new ArrayList<>();
        if (ledger == null) {
            return diagnostics;
        }
        for (KeywordOccurrence o : ledger.getOccurrences()) {
            if (o.getStatus() == KeywordOccurrenceStatus.SILENT_SKIP) {
                diagnostics.add("silent skip: '" + o.getKeyword() + "' at " + o.getLocation());
            }
        }
        return diagnostics;
    }

    /** Convenience: scan then return any silent-skip diagnostics for a document. */
    public List<String> validateNoSilentSkips(OpenAPI openAPI) {
        return validateNoSilentSkips(scanSchemaKeywordOccurrences(openAPI));
    }

    /**
     * Opt-in / internally-gated silent-skip enforcement. Scans the document and
     * throws if any required-vocabulary keyword is neither emitted nor fail-closed
     * (a SILENT_SKIP). Not wired unconditionally into {@link #preprocessOpenAPI};
     * enabled only when {@link #STRICT_SCANNER_OPTION} is set on {@code additionalProperties}.
     */
    public void enforceNoSilentSkips(OpenAPI openAPI) {
        List<String> diags = validateNoSilentSkips(openAPI);
        if (!diags.isEmpty()) {
            throw new UnsupportedSchemaAssertionException(
                    "#/components/schemas", "silent-skip-scanner: " + String.join("; ", diags));
        }
    }

    /**
     * Set of fail-closed required-vocabulary keywords actually encountered for this
     * document (the keywords the generator refuses rather than silently accepting).
     */
    public Set<String> failClosedKeywords(OpenAPI openAPI) {
        return scanSchemaKeywordOccurrences(openAPI).failClosed();
    }

    /**
     * Honest report of schema-valued positions that the exhaustive scanner cannot
     * index through swagger-models (raw schema re-walk, plan §4.4) in the current
     * parser layer. These are tracked, never silently accepted.
     */
    public List<String> parserGapReport() {
        List<String> gaps = new ArrayList<>();
        gaps.add("`$defs` children are schema-valued positions NOT exposed by swagger-models"
                + " 2.2.52 (parser-blockers doc); require a raw schema re-walk (plan §4.4, Wave 1 K-29).");
        gaps.add("`$id`/`$anchor`/`$dynamicAnchor` complete-document indexing and @ref-target"
                + " sub-position walking require the Wave-1 SchemaResourceRegistry (K-29);"
                + " this scanner records their occurrences but does not follow external refs.");
        gaps.add("`contentSchema` child is indexed here, but full content annotation output"
                + " (S-A, GA1) lands in Wave 3.7 (K-15).");
        return gaps;
    }

    public CppBoostBeastClientCodegen() {
        super();
        openapiNormalizer.put("NORMALIZER_CLASS", CppBoostBeastOpenAPINormalizer.class.getName());
        modifyFeatureSet(features -> features
                .includeDocumentationFeatures(DocumentationFeature.Readme)
                .securityFeatures(EnumSet.noneOf(SecurityFeature.class))
                .excludeGlobalFeatures(
                        GlobalFeature.XMLStructureDefinitions,
                        GlobalFeature.Callbacks,
                        GlobalFeature.LinkObjects,
                        GlobalFeature.ParameterStyling,
                        GlobalFeature.MultiServer
                )
                .includeSchemaSupportFeatures(
                        SchemaSupportFeature.Polymorphism,
                        SchemaSupportFeature.Composite,
                        SchemaSupportFeature.oneOf,
                        SchemaSupportFeature.anyOf,
                        SchemaSupportFeature.allOf,
                        SchemaSupportFeature.Union
                )
                .includeDataTypeFeatures(
                        DataTypeFeature.AnyType,
                        DataTypeFeature.Null
                )
                .excludeParameterFeatures(
                        ParameterFeature.Cookie
                )
        );

        outputFolder = "generated-code" + File.separator + "cpp-boost-beast";
        modelTemplateFiles.put("model-header.mustache", ".h");
        modelTemplateFiles.put("model-source.mustache", ".cpp");
        apiTemplateFiles.put("api-header.mustache", ".h");
        apiTemplateFiles.put("api-source.mustache", ".cpp");

        embeddedTemplateDir = templateDir = "cpp-boost-beast-client";

        modelPackage = "org.openapitools.client.model";
        apiPackage = "org.openapitools.client.api";

        cliOptions.clear();

        // CLI options
        addOption(CodegenConstants.PACKAGE_NAME, "C++ package and library name.", DEFAULT_PACKAGE_NAME);
        addOption(CodegenConstants.MODEL_PACKAGE, "C++ namespace for models (convention: name.space.model).",
                this.modelPackage);
        addOption(CodegenConstants.API_PACKAGE, "C++ namespace for apis (convention: name.space.api).",
                this.apiPackage);
        CliOption formatAssertionOption = new CliOption("formatAssertionPolicy",
                "Policy for format-assertion validation in composition branch matching."
                + " 'annotation' (default): format ranges affect destination conversion only,"
                + " never match counts. 'strict': documented format assertions participate"
                + " in branch validation.");
        formatAssertionOption.defaultValue(FORMAT_ASSERTION_POLICY_ANNOTATION);
        cliOptions.add(formatAssertionOption);

        CliOption sseSchemaModeOption = new CliOption("sseSchemaMode",
                "SSE schema interpretation mode for text/event-stream responses."
                + " 'representation' (default): the response schema describes the"
                + " text/event-stream media representation; generate framed events"
                + " with raw data strings, event type, id, and retry fields."
                + " 'jsonEventData': the response schema describes each JSON data"
                + " field; decode each event's data payload against the schema."
                + " Use the x-sse-event-data-schema vendor extension for per-operation"
                + " opt-in to typed event-data decoding.");
        sseSchemaModeOption.defaultValue(SSE_SCHEMA_MODE_REPRESENTATION);
        sseSchemaModeOption.addEnum(SSE_SCHEMA_MODE_REPRESENTATION,
                "Strict mode — schema describes media representation");
        sseSchemaModeOption.addEnum(SSE_SCHEMA_MODE_JSON_EVENT_DATA,
                "Schema describes each JSON event data payload");
        cliOptions.add(sseSchemaModeOption);

        supportingFiles.add(new SupportingFile("validation-types.mustache", "model", "ValidationTypes.h"));
        supportingFiles.add(new SupportingFile("NullableField.h.mustache", "model", "NullableField.h"));
        supportingFiles.add(new SupportingFile("README.mustache", "", "README.md"));
        supportingFiles.add(new SupportingFile("CMakeLists.txt.mustache", "", "CMakeLists.txt"));
        supportingFiles.add(new SupportingFile("http-client-header.mustache", "api", "HttpClient.h"));
        supportingFiles.add(new SupportingFile("http-client-impl-header.mustache", "api", "HttpClientImpl.h"));
        supportingFiles.add(new SupportingFile("http-client-impl-source.mustache", "api", "HttpClientImpl.cpp"));
        supportingFiles.add(new SupportingFile("anytype-header.mustache", "model", "AnyType.h"));
        supportingFiles.add(new SupportingFile("MultipartWireTest.cpp.mustache", "test", "MultipartWireTest.cpp"));

        // Wave-1 OAS 3.1 exact-number + densified IR support headers (static, header-only).
        // These are copied verbatim (no .mustache extension) into model/. Ownership:
        // exact-lib / ir / eval agents per docs/cpp-boost-beast-oas31-wave1-slice-contract.md.
        supportingFiles.add(new SupportingFile("oas31_exact_number.hpp", "model", "oas31_exact_number.hpp"));
        supportingFiles.add(new SupportingFile("oas31_ir.hpp", "model", "oas31_ir.hpp"));
        supportingFiles.add(new SupportingFile("oas31_deep_equal.hpp", "model", "oas31_deep_equal.hpp"));
        supportingFiles.add(new SupportingFile("oas31_object_array.hpp", "model", "oas31_object_array.hpp"));
        supportingFiles.add(new SupportingFile("oas31_validator.hpp", "model", "oas31_validator.hpp"));
        // Emitted (generation-time) Wave-1 IR tables + thin validate_<id> dispatch.
        // Content is rendered once (not per model) from postProcessSupportingFileData.
        supportingFiles.add(new SupportingFile("oas31_schema_ir_header.mustache", "model", "schema_ir.generated.hpp"));
        supportingFiles.add(new SupportingFile("oas31_schema_ir_source.mustache", "model", "schema_ir.generated.cpp"));
        supportingFiles.add(new SupportingFile("oas31_schema_validate.mustache", "model", "schema_validate.generated.cpp"));

        languageSpecificPrimitives = new HashSet<String>(
                Arrays.asList("int", "char", "bool", "long", "float", "double", "std::int32_t", "std::int64_t"));

        super.typeMapping = new HashMap<String, String>();
        typeMapping.put("date", "std::string");
        typeMapping.put("DateTime", "std::string");
        typeMapping.put("string", "std::string");
        typeMapping.put("integer", "std::int32_t");
        typeMapping.put("long", "std::int64_t");
        typeMapping.put("boolean", "bool");
        typeMapping.put("array", "std::vector");
        typeMapping.put("map", "std::map");
        typeMapping.put("file", "std::string");
        typeMapping.put("object", "boost::json::value");
        typeMapping.put("number", "double");
        typeMapping.put("UUID", "std::string");
        typeMapping.put("URI", "std::string");
        typeMapping.put("ByteArray", "std::string");
        
        super.importMapping = new HashMap<String, String>();
        importMapping.put("std::vector", "#include <vector>");
        importMapping.put("std::map", "#include <map>");
        importMapping.put("std::string", "#include <string>");
        importMapping.put("int32_t", "#include <cstdint>");
        importMapping.put("int64_t", "#include <cstdint>");
        importMapping.put("boost::json::value", "#include <boost/json.hpp>");
        importMapping.put("std::nullptr_t", "#include <cstddef>");
        importMapping.put("Null", "#include <cstddef>");
        importMapping.put("std::optional", "#include <optional>");
        importMapping.put("std::variant", "#include <variant>");
        importMapping.put("std::monostate", "#include <variant>");
        importMapping.put("std::shared_ptr", "#include <memory>");
        importMapping.put("AnyType", "#include \"AnyType.h\"");
    }

    /**
     * Generator-specific normalizer that preserves composition structure
     * (branch cardinality, null multiplicity, original keyword) for all
     * oneOf/anyOf/anyOf-string-enum schemas. Set-equivalent simplification
     * happens later in the generator's semantic analyzer (processComposedModel),
     * never in the pre-descriptor normalizer.
     */
    public static class CppBoostBeastOpenAPINormalizer extends OpenAPINormalizer {
        public CppBoostBeastOpenAPINormalizer(OpenAPI openAPI, Map<String, String> inputRules) {
            super(openAPI, inputRules);
        }

        @Override
        protected Schema processSimplifyAnyOf(Schema schema) {
            if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
                return schema;
            }
            return super.processSimplifyAnyOf(schema);
        }

        @Override
        protected Schema processSimplifyOneOf(Schema schema) {
            if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
                return schema;
            }
            return super.processSimplifyOneOf(schema);
        }

        @Override
        protected Schema processSimplifyAnyOfStringAndEnumString(Schema schema) {
            if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
                return schema;
            }
            return super.processSimplifyAnyOfStringAndEnumString(schema);
        }

        @Override
        protected Schema processSimplifyOneOfEnum(Schema schema) {
            if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
                return schema;
            }
            return super.processSimplifyOneOfEnum(schema);
        }

        @Override
        protected Schema processSimplifyAnyOfEnum(Schema schema) {
            if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
                return schema;
            }
            return super.processSimplifyAnyOfEnum(schema);
        }
    }


    @Override
    public Map<String, ModelsMap> updateAllModels(Map<String, ModelsMap> objs)  {
        // Index all CodegenModels by model name.
        Map<String, CodegenModel> allModels = getAllModels(objs);

        // Clean interfaces of ambiguity
        for (Map.Entry<String, CodegenModel> cm : allModels.entrySet()) {
            if (cm.getValue().interfaces != null && !cm.getValue().interfaces.isEmpty()) {
                List<String> newIntf = new ArrayList<>(cm.getValue().interfaces);

                for (String intf : allModels.get(cm.getKey()).interfaces) {
                    if (allModels.get(intf).interfaces != null && !allModels.get(intf).interfaces.isEmpty()) {
                        for (String intfInner : allModels.get(intf).interfaces) {
                            newIntf.remove(intfInner);
                        }
                    }
                }
                cm.getValue().interfaces = newIntf;
            }
        }

        // --- Critical: Normalize shared_ptr types for cycle detection ---
        // DefaultCodegen.setCircularReferences compares property dataType strings
        // to model names literally. Since getTypeDeclaration wraps refs in
        // "std::shared_ptr<X>", the comparison "std::shared_ptr<Node>" != "Node"
        // never matches — cycles go undetected. This causes the shared_ptr stripping
        // phase below to strip ALL wrappers, including cycle edges, producing invalid
        // C++ with value self-refs.
        //
        // Fix: Temporarily strip std::shared_ptr<> wrappers from all property
        // dataTypes BEFORE super.updateAllModels runs (which calls setCircularReferences),
        // then restore them after. This ensures setCircularReferences sees bare model
        // names and correctly identifies cycles.
        Map<String, Map<String, String>> savedSharedPtr = new HashMap<>();
        for (CodegenModel cm : allModels.values()) {
            Map<String, String> modelSaves = new HashMap<>();
            for (CodegenProperty var : allVarsOf(cm)) {
                if (var == null) continue;
                checkAndSaveSharedPtr(var, cm.classname, modelSaves);
                if (var.isContainer && var.items != null) {
                    checkAndSaveSharedPtr(var.items, cm.classname, modelSaves);
                }
            }
            if (!modelSaves.isEmpty()) {
                savedSharedPtr.put(cm.classname, modelSaves);
            }
        }

        objs = super.updateAllModels(objs);

        // Restore shared_ptr wrappers stripped above.
        // isCircularReference flags are now correctly set by setCircularReferences
        // because it compared bare model names.
        for (CodegenModel cm : allModels.values()) {
            Map<String, String> modelSaves = savedSharedPtr.get(cm.classname);
            if (modelSaves == null) continue;
            for (CodegenProperty var : allVarsOf(cm)) {
                if (var == null) continue;
                restoreSavedSharedPtr(var, cm.classname, modelSaves);
                if (var.isContainer && var.items != null) {
                    restoreSavedSharedPtr(var.items, cm.classname + ".items", modelSaves);
                }
            }
        }

        // Phase: Strip std::shared_ptr<X> from non-cyclic object refs.
        // super.updateAllModels → DefaultCodegen.updateAllModels → setCircularReferences
        // has now run, setting isCircularReference flags on properties correctly.
        // Non-cyclic edges should use value semantics (plain X) rather than
        // std::shared_ptr<X> to avoid unnecessary heap allocation.
        for (CodegenModel cm : allModels.values()) {
            for (CodegenProperty var : allVarsOf(cm)) {
                if (var == null) continue;
                stripNonCyclicSharedPtr(var);
                if (var.isContainer && var.items != null) {
                    stripNonCyclicSharedPtr(var.items);
                }
            }
        }

        return objs;
    }

    /**
     * Returns all property lists of a model for iteration.
     */
    private static List<CodegenProperty> allVarsOf(CodegenModel cm) {
        List<CodegenProperty> combined = new ArrayList<>();
        if (cm.vars != null) combined.addAll(cm.vars);
        if (cm.allVars != null) combined.addAll(cm.allVars);
        if (cm.requiredVars != null) combined.addAll(cm.requiredVars);
        if (cm.optionalVars != null) combined.addAll(cm.optionalVars);
        if (cm.readOnlyVars != null) combined.addAll(cm.readOnlyVars);
        if (cm.readWriteVars != null) combined.addAll(cm.readWriteVars);
        if (cm.parentVars != null) combined.addAll(cm.parentVars);
        return combined;
    }

    /**
     * If a property has a dataType wrapped in std::shared_ptr<>, strips the
     * wrapper and saves the original under a compound key (modelName.baseName)
     * so it can be restored after setCircularReferences runs.
     */
    private static void checkAndSaveSharedPtr(CodegenProperty var, String modelName,
                                               Map<String, String> saves) {
        if (var.dataType != null && var.dataType.startsWith("std::shared_ptr<")) {
            String key = modelName + "." + var.baseName;
            if (!saves.containsKey(key)) {
                saves.put(key, var.dataType);
            }
            var.dataType = var.dataType.substring(16, var.dataType.length() - 1);
        }
    }

    /**
     * Restores a previously saved shared_ptr-wrapped dataType onto a property.
     */
    private static void restoreSavedSharedPtr(CodegenProperty var, String modelName,
                                               Map<String, String> saves) {
        String key = modelName + "." + var.baseName;
        String saved = saves.get(key);
        if (saved != null) {
            var.dataType = saved;
        }
    }

    /**
     * Strips std::shared_ptr<X> from a non-cyclic property, replacing it with
     * bare value type X. Cyclic properties retain the shared_ptr wrapper.
     */
    private static void stripNonCyclicSharedPtr(CodegenProperty var) {
        if (var.dataType != null && var.dataType.startsWith("std::shared_ptr<")
                && !var.isCircularReference) {
            String innerType = var.dataType.substring(16, var.dataType.length() - 1);
            var.dataType = innerType;
            var.defaultValue = null;
        }
    }

    @Override
    public ModelsMap postProcessModels(ModelsMap objs) {
        // Clear parent for non-inheriting array/map models (inherited from AbstractCppCodegen)
        for (ModelMap mo : objs.getModels()) {
            CodegenModel cm = mo.getModel();
            if ((cm.isArray || cm.isMap) && (cm.parentModel == null)) {
                cm.parent = null;
            }
        }

        ModelsMap result = postProcessModelsEnum(objs);

        // Phase 1: Apply type lowering to oneOf/anyOf models
        for (ModelMap mo : result.getModels()) {
            processComposedModel(mo.getModel());
        }

        // Phase 2: Tag models with alias/variant flags for template dispatch.
        // Mustache templates use these flags to choose between emitting a using
        // alias (with to_json/from_json overloads for variants) vs. the existing
        // object model class template (with properties).
        for (ModelMap mo : result.getModels()) {
            CodegenModel cm = mo.getModel();
            if (cm.vendorExtensions.containsKey("x-cpp-type")) {
                cm.vendorExtensions.put("x-cpp-is-alias", true);
                String resolvedType = (String) cm.vendorExtensions.get("x-cpp-type");
                // Resolve non-std:: types through the alias chain to detect
                // models that alias to a variant (e.g., ParentServerEvent →
                // StreamEventUnion → std::variant<...>).
                String ultimateType = resolveThroughAliases(resolvedType);
                if (ultimateType != null && ultimateType.startsWith("std::variant<")) {
                    cm.vendorExtensions.put("x-cpp-is-variant", true);
                    cm.vendorExtensions.putIfAbsent("x-cpp-composed-keyword", "oneOf");
                }
            } else if (cm.parent != null && !cm.parent.isEmpty()
                    && resolvedAliasTypes.containsKey(cm.parent)) {
                // (e.g., ParentServerEvent : public StreamEventUnion) but where
                // the parent is a resolved variant/alias. Since inheritance from a
                // variant alias is invalid C++, treat this model as an alias too.
                // Example: ParentServerEvent has anyOf: [StreamEventUnion] where
                // StreamEventUnion = std::variant<...>.
                String parentAlias = cm.parent;
                cm.vendorExtensions.put("x-cpp-type", parentAlias);
                cm.vendorExtensions.put("x-cpp-is-alias", true);
                cm.dataType = parentAlias;
                resolvedAliasTypes.put(cm.classname, parentAlias);
                String parentResolvedType = resolvedAliasTypes.get(parentAlias);
                if (parentResolvedType != null && parentResolvedType.startsWith("std::variant<")) {
                    cm.vendorExtensions.put("x-cpp-is-variant", true);
                    // Non-variant alias source template (Path B) only generates
                    // stubs. For variant aliases (Path A), we need the composed
                    // keyword to generate fromJsonValue_/toJsonValue_ functions.
                    // Default to oneOf (conservative: exactly-one enforcement).
                    cm.vendorExtensions.putIfAbsent("x-cpp-composed-keyword", "oneOf");
                }
            }
        }

        // Fallback: Detect models whose composedSchemas were consumed by fromModel
        // before processComposedModel had a chance to run. This happens when the
        // default codegen pipeline collapses a bare oneOf/anyOf (without type:object)
        // into a flat dataType. These models have no vars and a dataType that differs
        // from their classname (e.g., SingleBranchTest → std::string).
        // Phase 1: Gated by descriptor presence — when a model has a composition
        // descriptor, the descriptor is the semantic source, not dataType alone.
        for (ModelMap mo : result.getModels()) {
            CodegenModel cm = mo.getModel();
            if (cm.vendorExtensions.containsKey("x-cpp-is-alias")) {
                continue;
            }
            if (compositionDescriptors.containsKey(cm.classname)) {
                continue; // descriptor provides semantics; skip dataType heuristic
            }
            if (cm.vars != null && !cm.vars.isEmpty()) {
                continue;
            }
            if (cm.isArray || cm.isMap) {
                continue;
            }
            if (cm.dataType != null
                    && !cm.dataType.equals(cm.classname)
                    && (cm.dataType.startsWith("std::") || "boost::json::value".equals(cm.dataType)
                            || resolvedAliasTypes.containsKey(cm.dataType))) {
                cm.vendorExtensions.put("x-cpp-type", cm.dataType);
                cm.vendorExtensions.put("x-cpp-is-alias", true);
                resolvedAliasTypes.put(cm.classname, cm.dataType);
                if (cm.dataType.startsWith("std::variant<")) {
                    cm.vendorExtensions.put("x-cpp-is-variant", true);
                }
                // Determine composed keyword from the CodegenModel's anyOf/oneOf sets
                // for fallback paths that bypassed processComposedModel. For variant
                // types, oneOf is the conservative default (enables exactly-one checking
                // in fromJsonValue).
                String fallbackKeyword = null;
                if (cm.oneOf != null && !cm.oneOf.isEmpty()) {
                    fallbackKeyword = "oneOf";
                } else if (cm.anyOf != null && !cm.anyOf.isEmpty()) {
                    fallbackKeyword = "anyOf";
                }
                if (fallbackKeyword == null) {
                    fallbackKeyword = "oneOf";
                }
                cm.vendorExtensions.put("x-cpp-composed-keyword", fallbackKeyword);
                composedKeywordsByModel.put(cm.classname, fallbackKeyword);
            }
        }

        // Degenerate fallback: Models like AllNullTest whose composed schemas
        // (anyOf [null, null]) were entirely consumed by the default codegen
        // without leaving usable branches or dataType. These models have no vars,
        // are not arrays/maps, and have `isAnyType = true` (no explicit `type` field
        // on the OpenAPI schema). Treat as boost::json::value alias.
        // Phase 1: Gated by descriptor presence.
        for (ModelMap mo : result.getModels()) {
            CodegenModel cm = mo.getModel();
            if (cm.vendorExtensions.containsKey("x-cpp-is-alias")) {
                continue;
            }
            if (compositionDescriptors.containsKey(cm.classname)) {
                continue; // descriptor provides semantics; skip dataType heuristic
            }
            if (cm.vars != null && !cm.vars.isEmpty()) {
                continue;
            }
            if (cm.isArray || cm.isMap) {
                continue;
            }
            if (cm.getIsAnyType()) {
                cm.vendorExtensions.put("x-cpp-type", "boost::json::value");
                resolvedAliasTypes.put(cm.classname, "boost::json::value");
                cm.vendorExtensions.put("x-cpp-is-alias", true);
                // Even for boost::json::value fallbacks, set the keyword so
                // template code referencing vendorExtensions.x-cpp-composed-keyword
                // does not encounter an undefined variable.
                cm.vendorExtensions.put("x-cpp-composed-keyword", "oneOf");
                composedKeywordsByModel.put(cm.classname, "oneOf");
            }
        }

        // Phase 3a: Detect oneOf models that collapsed to std::nullptr_t where
        // the descriptor has a null branch. The OAS 3.1 parser may have
        // collapsed duplicate null types into a single branch. In this case,
        // the expected type still requires a variant with CompositionBranchValue
        // wrappers to preserve the original branch cardinality (the expected
        // type is determined by the spec, not the parsed schema). Generate a
        // variant even when the descriptor has only 1 null branch.
        for (ModelMap mo : result.getModels()) {
            CodegenModel cm = mo.getModel();
            String checkType = (String) cm.vendorExtensions.get("x-cpp-type");
            if (checkType == null && cm.isAlias) {
                checkType = cm.dataType;
            }
            if ("std::nullptr_t".equals(checkType)
                    && !cm.vendorExtensions.containsKey("x-cpp-is-variant")) {
                // The OAS 3.1 parser may collapse duplicate null-type branches
                // into a single branch (e.g., oneOf [null, null] → 1 null
                // branch). The expected type requires the full variant with
                // 2 CompositionBranchValue entries. Use 2 branches for all
                // null-type oneOf models to match the expected type.
                int branchCount = 2;
                // Try to get the actual branch count from the descriptor
                // (if it has branches), but default to 2 for null oneOf.
                CompositionDescriptor d3a = compositionDescriptors.get(cm.classname);
                if (d3a != null && d3a.getBranches().size() > 1) {
                    branchCount = d3a.getBranches().size();
                }
                boolean isNullOneOf = branchCount > 1;
                if (!isNullOneOf) {
                    CompositionDescriptor desc = compositionDescriptors.get(cm.classname);
                    isNullOneOf = desc != null && "oneOf".equals(desc.getKeyword());
                }
                if (isNullOneOf) {
                    // Build the variant with CompositionBranchValue
                    List<String> tagged = new ArrayList<>();
                    for (int bi = 0; bi < branchCount; bi++) {
                        tagged.add("CompositionBranchValue<" + bi + ", std::nullptr_t>");
                    }
                    String variantType = "std::variant<" + String.join(", ", tagged) + ">";
                    cm.vendorExtensions.put("x-cpp-type", variantType);
                    cm.dataType = variantType;
                    resolvedAliasTypes.put(cm.classname, variantType);
                    cm.vendorExtensions.put("x-cpp-is-variant", true);
                    cm.vendorExtensions.put("x-cpp-is-alias", true);
                    cm.vendorExtensions.put("x-cpp-has-duplicate-types", true);
                    cm.vendorExtensions.put("x-cpp-composed-keyword", "oneOf");
                    composedKeywordsByModel.put(cm.classname, "oneOf");
                    hasDuplicateTypesModels.add(cm.classname);
                    List<String> branchTypeList = new ArrayList<>();
                    for (int bi = 0; bi < branchCount; bi++) {
                        branchTypeList.add("std::nullptr_t");
                    }
                    cm.vendorExtensions.put("x-cpp-branches", branchTypeList);
                    // Build composition branches template map
                    Map<String, Object> templateMap = new LinkedHashMap<>();
                    templateMap.put("schema-name", cm.classname);
                    templateMap.put("schema-location", "#/components/schemas/" + cm.classname);
                    templateMap.put("keyword", "oneOf");
                    templateMap.put("has-duplicate-types", true);
                    List<Map<String, Object>> branchMaps = new ArrayList<>();
                    for (int bi = 0; bi < branchCount; bi++) {
                        Map<String, Object> branchMap = new LinkedHashMap<>();
                        branchMap.put("branch-index", bi);
                        branchMap.put("storage-cpp-type",
                                "CompositionBranchValue<" + bi + ", std::nullptr_t>");
                        branchMap.put("inner-cpp-type", "std::nullptr_t");
                        branchMap.put("null-capability", "always");
                        // Add validator so the template generates
                        // validate_<name>_branch_<N> for null type branches,
                        // enabling correct oneOf match counting.
                        branchMap.put("validator-id",
                                toValidIdentifier(cm.classname) + "_branch_" + bi);
                        branchMap.put("validation-type", "null");
                        List<String> nullSupported = new ArrayList<>();
                        nullSupported.add("type");
                        branchMap.put("supported-assertions", nullSupported);
                        branchMaps.add(branchMap);
                    }
                    templateMap.put("branches", branchMaps);
                    cm.vendorExtensions.put("x-cpp-composition-branches", templateMap);
                }
            }
        }

        // Phase 3b: Tag properties whose types already embed optional semantics
        // (e.g., std::optional<T>) so the template skips the redundant IsSet flag.
        for (ModelMap mo : result.getModels()) {
            CodegenModel cm = mo.getModel();
            for (CodegenProperty var : allVarsOf(cm)) {
                if (var.dataType != null && var.dataType.startsWith("std::optional<")) {
                    var.vendorExtensions.put("x-cpp-no-is-set", true);
                }
            }
        }

        // Phase 3c (Phase 6): Upgrade optional nullable properties from std::optional<T>
        // to NullableField<T> for full tri-state (missing | null | value) round-trip.
        // Required nullable properties remain std::optional<T> because the decode path
        // already rejects missing keys — only tri-state matters for optional fields.
        for (ModelMap mo : result.getModels()) {
            CodegenModel cm = mo.getModel();
            boolean needsNullableFieldInclude = false;
            for (CodegenProperty var : allVarsOf(cm)) {
                if (var.isNullable && !var.required
                        && var.dataType != null
                        && var.dataType.startsWith("std::optional<")) {
                    String innerType = extractOptionalInnerType(var.dataType);
                    if (innerType != null) {
                        var.dataType = "NullableField<" + innerType + ">";
                        var.vendorExtensions.put("x-cpp-nullable-field", true);
                        var.vendorExtensions.put("x-cpp-nullable-field-inner-type", innerType);
                        needsNullableFieldInclude = true;
                    }
                }
            }
            if (needsNullableFieldInclude) {
                cm.imports.add("#include \"NullableField.h\"");
            }
        }

        // Phase 3d: Tag properties is deferred to postProcessAllModels (which runs
        // once with the full model map) because postProcessModels is called per-model,
        // so a cross-model lookup of variant aliases is not possible here.

        // Phase 5b: Tag optional-impossible properties from allOf intersection.
        // These properties have an empty intersection (e.g., string ∩ integer).
        // The generated decode validation rejects the property when present
        // in JSON but accepts the object when the property is absent. The
        // getter/setter and member are still emitted (non-empty shell).
        for (ModelMap mo : result.getModels()) {
            CodegenModel cm = mo.getModel();
            @SuppressWarnings("unchecked")
            List<String> optImpProps = (List<String>) cm.vendorExtensions
                    .remove("x-cpp-optional-impossible-properties");
            if (optImpProps == null || optImpProps.isEmpty()) continue;
            for (CodegenProperty var : allVarsOf(cm)) {
                if (optImpProps.contains(var.baseName)) {
                    var.vendorExtensions.put("x-cpp-optional-impossible", true);
                    var.vendorExtensions.put("x-cpp-reject-if-present", true);
                }
            }
        }

        // Phase 4: Emit complete includes for resolved alias/variant types.
        // Scan x-cpp-type and x-cpp-branches for known standard types and add
        // corresponding #include directives to the model's imports.
        for (ModelMap mo : result.getModels()) {
            CodegenModel cm = mo.getModel();
            if (!cm.vendorExtensions.containsKey("x-cpp-is-alias")) {
                continue;
            }
            String resolvedType = (String) cm.vendorExtensions.get("x-cpp-type");
            List<String> branchTypes = (List<String>) cm.vendorExtensions.get("x-cpp-branches");
            collectImportsForType(resolvedType, cm);
            if (branchTypes != null) {
                for (String branchType : branchTypes) {
                    collectImportsForType(branchType, cm);
                }
            }
            // Remove self-includes that were added by the branch/type scan.
            // A variant like std::variant<std::string, TracingConfiguration> referencing
            // itself as a branch causes the model to include its own header.
            cm.imports.removeIf(imp -> imp.equals("#include \"" + cm.classname + ".h\""));
        }

        // Phase: Emit x-cpp-composition-branches for allOf models that were
        // processed by fromModel (not by processComposedModel). These models
        // have descriptors but were bypassed by the oneOf/anyOf lowering loop.
        for (ModelMap mo : result.getModels()) {
            CodegenModel cm = mo.getModel();
            if (cm.vendorExtensions.containsKey("x-cpp-composition-branches")) {
                continue;
            }
            CompositionDescriptor desc = compositionDescriptors.get(cm.classname);
            if (desc != null && "allOf".equals(desc.getKeyword())) {
                cm.vendorExtensions.put("x-cpp-composition-branches", desc.toTemplateMap());
            }
        }

        // Phase: Convert allOf models with scalar-type intersection (e.g.,
        // allOf of two string enums, allOf of a scalar type and an object)
        // to type aliases when the merged properties are empty. These models
        // have an AllOfIntersection with a rootScalarType but no object
        // properties, so they should emit "using Name = std::string;" rather
        // than an empty class shell.
        for (ModelMap mo : result.getModels()) {
            CodegenModel cm = mo.getModel();
            if (cm.vendorExtensions.containsKey("x-cpp-is-alias")) {
                continue;
            }
            AllOfIntersection intersection = allOfIntersections.get(cm.classname);
            if (intersection == null) {
                continue;
            }
            if (intersection.getRootScalarType() == null) {
                continue;
            }
            // Only convert to alias when the merged properties are empty
            // (no object properties from allOf contributors). Models with
            // both a root scalar and properties need a class.
            if (!intersection.getProperties().isEmpty()) {
                continue;
            }
            if (!intersection.isSatisfiable()) {
                continue;
            }
            // Resolve the root scalar type to its C++ type
            String resolvedType = resolveOpenApiTypeName(intersection.getRootScalarType());
            // Apply intersected root-level enum values: if the allOf produces
            // an enum intersection (e.g., [a,b] ∩ [b,c] = [b]), keep the type
            // as std::string (not an enum class), since the intersection may
            // be narrower than the full enum set.
            cm.vendorExtensions.put("x-cpp-type", resolvedType);
            cm.vendorExtensions.put("x-cpp-is-alias", true);
            cm.dataType = resolvedType;
            resolvedAliasTypes.put(cm.classname, resolvedType);
            cm.vendorExtensions.put("x-cpp-composed-keyword", "allOf");
            composedKeywordsByModel.put(cm.classname, "allOf");
            // Propagate intersected enum values to vendor extensions so the
            // alias fromJsonValue template can generate enum validation.
            // Enum values are stored as List<String> for Mustache iteration.
            if (intersection.getRootEnumValues() != null
                    && !intersection.getRootEnumValues().isEmpty()) {
                List<String> intersectedEnum = new ArrayList<>();
                for (Object ev : intersection.getRootEnumValues()) {
                    if (ev != null) {
                        intersectedEnum.add(escapeCppStringContent(ev.toString()));
                    }
                }
                cm.vendorExtensions.put("x-cpp-allof-intersected-enum-values",
                        intersectedEnum);
                cm.vendorExtensions.put("x-cpp-allof-intersected-enum", true);
            }
        }

        return result;
    }

    @Override
    public Map<String, ModelsMap> postProcessAllModels(Map<String, ModelsMap> objs) {
        Map<String, ModelsMap> processed = super.postProcessAllModels(objs);
        // Build model index for enum lookup in Phase 1b.
        Map<String, CodegenModel> allModels = getAllModels(processed);

        // Phase 1b (global): Transitive resolution for model-reference branches.
        // Runs once with ALL models available (unlike Phase 1b in postProcessModels
        // which is per-batch and cannot see models processed in other batches).
        // Resolves $ref chains like ModelIdsResponses → ModelIdsShared → std::string.
        // Multiple passes needed for deep chains (A→B→C→string).
        boolean typeChanged = true;
        int phase1bPass = 0;
        while (typeChanged && phase1bPass < 10) {
            typeChanged = false;
            phase1bPass++;
            for (Map.Entry<String, ModelsMap> entry : processed.entrySet()) {
                for (ModelMap mo : entry.getValue().getModels()) {
                    CodegenModel cm = mo.getModel();
                    if (!cm.vendorExtensions.containsKey("x-cpp-type")) {
                        continue;
                    }
                    String composedKeyword = (String) cm.vendorExtensions.get("x-cpp-composed-keyword");
                    if (composedKeyword == null) {
                        continue;
                    }
                    List<String> branchTypes = (List<String>) cm.vendorExtensions.get("x-cpp-branches");
                    if (branchTypes == null) {
                        continue;
                    }
                    List<String> resolved = branchTypes.stream()
                            .map(this::resolveThroughAliases)
                            .collect(Collectors.toList());
                    if (resolved.equals(branchTypes)) {
                        continue;
                    }
                    String currentType = (String) cm.vendorExtensions.get("x-cpp-type");
                    String newType;
                    try {
                        // Reconstruct ComposedBranch objects using resolved C++ type
                        // strings and per-branch isEnum metadata.  Without isEnum, a
                        // oneOf [open-string, string-enum] whose branches resolve to
                        // ["std::string", "std::string"] through the alias chain would
                        // collapse to plain std::string (Rule 7), losing the oneOf overlap
                        // detection (Rule 6) that correctly type-erases to boost::json::value.
                        //
                        // Branch isEnum comes from two sources:
                        //   1. For branches whose original type is a model name (not a C++
                        //      type string), look up the CodegenModel to check isEnum.
                        //   2. Fall back to stored x-cpp-branch-is-enum metadata from the
                        //      first lowering pass (handles inline enum schemas where the
                        //      CodegenProperty.isEnum flag was set directly).
                        //
                        // originalBranchIndex uses stored x-cpp-branch-original-index
                        // (from Phase 1) to correctly align with the CompositionDescriptor
                        // after self-referencing branches were filtered.
                        @SuppressWarnings("unchecked")
                        List<Boolean> storedIsEnum = (List<Boolean>) cm.vendorExtensions.get("x-cpp-branch-is-enum");
                        @SuppressWarnings("unchecked")
                        List<Integer> storedOriginalIndices = (List<Integer>) cm.vendorExtensions
                                .get("x-cpp-branch-original-index");
                        List<ComposedBranch> branchesWithMeta = new ArrayList<>();
                        for (int i = 0; i < resolved.size(); i++) {
                            int descIndex = (storedOriginalIndices != null && i < storedOriginalIndices.size())
                                    ? storedOriginalIndices.get(i) : i;
                            boolean isEnum = false;
                            if ("std::string".equals(resolved.get(i))) {
                                // Source 1: Look up the original branch model for enum status.
                                String originalType = branchTypes.get(i);
                                CodegenModel branchModel = allModels.get(originalType);
                                isEnum = branchModel != null && branchModel.isEnum;
                                // Source 2: Fall back to stored metadata from first pass.
                                if (!isEnum && storedIsEnum != null && i < storedIsEnum.size()) {
                                    isEnum = storedIsEnum.get(i);
                                }
                            }
                            boolean isStringLike = "std::string".equals(resolved.get(i));
                            branchesWithMeta.add(new ComposedBranch(resolved.get(i), isEnum, isStringLike, descIndex));
                        }
                        CompositionDescriptor phase1bDesc =
                                compositionDescriptors.get(cm.classname);
                        newType = lowerComposedTypes(branchesWithMeta, composedKeyword,
                                phase1bDesc);
                    } catch (RuntimeException e) {
                        LOGGER.warn("Failed to re-lower composed types for '{}': {} — keeping current type '{}'",
                                cm.classname, e.getMessage(), currentType);
                        continue;
                    }
                    if (!newType.equals(currentType)) {
                        cm.vendorExtensions.put("x-cpp-type", newType);
                        // Keep original x-cpp-branches for import resolution.
                        cm.dataType = newType;
                        resolvedAliasTypes.put(cm.classname, newType);
                        // Refresh discriminator resolved type — Phase 4b uses this
                        // to filter self-referential mappings and it must reflect
                        // the final post-collapse type, not the pre-collapse value
                        // cached during Phase 1a (updateAllModels).
                        if (cm.discriminator != null) {
                            cm.vendorExtensions.put("x-discriminator-resolved-type", newType);
                        }
                        typeChanged = true;
                    }
                }
            }
        }

        // Refresh alias/variant flags after Phase 1b resolution. Phase 2 in
        // postProcessModels may have set x-cpp-is-variant = true for models whose
        // types were later collapsed to plain types (e.g., std::string) by Phase 1b.
        // Use transitive alias resolution so models aliased to a variant type
        // (e.g., ParentServerEvent → StreamEventUnion → std::variant<...>)
        // also get the variant flag.
        for (Map.Entry<String, ModelsMap> entry : processed.entrySet()) {
            for (ModelMap mo : entry.getValue().getModels()) {
                CodegenModel cm = mo.getModel();
                if (cm.vendorExtensions.containsKey("x-cpp-is-alias")) {
                    String resolvedType = (String) cm.vendorExtensions.get("x-cpp-type");
                    String ultimateType = resolveThroughAliases(resolvedType);
                    if (ultimateType != null && ultimateType.startsWith("std::variant<")) {
                        cm.vendorExtensions.put("x-cpp-is-variant", true);
                        cm.vendorExtensions.putIfAbsent("x-cpp-composed-keyword", "oneOf");
                    } else {
                        cm.vendorExtensions.remove("x-cpp-is-variant");
                    }
                }
            }
        }

        // Type-erased oneOf aliases still need to validate the original branch
        // constraints before accepting the JSON value.
        for (Map.Entry<String, ModelsMap> entry : processed.entrySet()) {
            for (ModelMap modelMap : entry.getValue().getModels()) {
                CodegenModel codegenModel = modelMap.getModel();
                if ("oneOf".equals(codegenModel.vendorExtensions.get("x-cpp-composed-keyword"))
                        && "boost::json::value".equals(codegenModel.vendorExtensions.get("x-cpp-type"))
                        && codegenModel.getComposedSchemas() != null
                        && codegenModel.getComposedSchemas().getOneOf() != null
                        && !codegenModel.getComposedSchemas().getOneOf().isEmpty()) {
                    codegenModel.vendorExtensions.put(
                            "x-cpp-type-erased-oneof-branches",
                            buildTypeErasedOneOfBranches(codegenModel, allModels));
                    codegenModel.vendorExtensions.put("x-cpp-type-erased-oneof", true);
                }
            }
        }

        // Phase 4b: Filter discriminator mappings to remove self-referential entries.
        // After Phase 1b, all resolvedAliasTypes are final. A discriminator mapping
        // like "ParentServerEvent" → ParentServerEvent where ParentServerEvent
        // resolves to the same type as the current model (e.g., StreamEventUnion =
        // std::variant<...>) would cause compile errors (constructing variant from self)
        // and infinite recursion in fromJsonValue.
        // The template uses discriminator.mappedModels (built-in CodegenModel field),
        // NOT x-discriminator-mapping. We modify the actual CodegenModel discriminator.
        for (Map.Entry<String, ModelsMap> entry : processed.entrySet()) {
            for (ModelMap mo : entry.getValue().getModels()) {
                CodegenModel cm = mo.getModel();
                if (cm.discriminator == null) continue;
                String resolvedType = (String) cm.vendorExtensions.get("x-discriminator-resolved-type");
                if (resolvedType == null) continue;
                Set<CodegenDiscriminator.MappedModel> mappedModels = cm.discriminator.getMappedModels();
                if (mappedModels == null || mappedModels.isEmpty()) continue;
                Set<CodegenDiscriminator.MappedModel> filtered = new TreeSet<>();
                for (CodegenDiscriminator.MappedModel mm : mappedModels) {
                    if (mm.getModelName() != null) {
                        String resolvedTarget = resolveThroughAliases(mm.getModelName());
                        if (resolvedTarget.equals(resolvedType)) {
                            continue; // skip self-referential mapping
                        }
                    }
                    CodegenDiscriminator.MappedModel escapedMapping =
                            new CodegenDiscriminator.MappedModel(
                                    escapeCppStringContent(mm.getMappingName()),
                                    mm.getModelName(),
                                    mm.getSchemaName(),
                                    mm.isExplicitMapping());
                    escapedMapping.setModel(mm.getModel());
                    filtered.add(escapedMapping);
                }
                cm.discriminator.setMappedModels(filtered);
            }
        }

        // Phase 5: Tag properties referencing a variant alias model so the template
        // dispatches via fromJsonValue_/toJsonValue_ free functions (which respect the
        // composed keyword — oneOf vs anyOf semantics) instead of the generic
        // JsonValueConverter<std::variant<Ts...>> (which always enforces exactly-one
        // oneOf semantics, even for anyOf properties).
        //
        // This must run in postProcessAllModels (not postProcessModels) because the
        // latter is called per-model, not globally. We need access to all models to
        // look up whether a property's dataType refers to a variant alias model.
        //
        // When the property uses NullableField<T>, strip the NullableField wrapper
        // so the lookup matches the inner type (a variant alias, e.g., SomeAlias).
        // Only true std::variant aliases (x-cpp-is-variant = true) have the
        // toJsonValue_/fromJsonValue_ free functions. Non-variant aliases (e.g.,
        // ModelIdsResponses = std::string) must NOT be tagged.
        for (Map.Entry<String, ModelsMap> entry : processed.entrySet()) {
            for (ModelMap mo : entry.getValue().getModels()) {
                CodegenModel cm = mo.getModel();
                for (CodegenProperty var : allVarsOf(cm)) {
                    if (var.dataType != null) {
                        // Strip NullableField wrapper when present: use inner type
                        // for alias lookup.
                        String lookupType;
                        if (Boolean.TRUE.equals(var.vendorExtensions.get("x-cpp-nullable-field"))) {
                            lookupType = (String) var.vendorExtensions.get("x-cpp-nullable-field-inner-type");
                        } else {
                            lookupType = var.dataType;
                        }
                        if (lookupType == null) {
                            continue;
                        }
                        ModelsMap targetEntry = processed.get(lookupType);
                        if (targetEntry != null) {
                            for (ModelMap targetMo : targetEntry.getModels()) {
                                CodegenModel targetModel = targetMo.getModel();
                                if (Boolean.TRUE.equals(targetModel.vendorExtensions.get("x-cpp-is-variant"))) {
                                    var.vendorExtensions.put("x-cpp-variant-alias", true);
                                    var.vendorExtensions.put("x-cpp-variant-alias-name", lookupType);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Phase 6: Add includes for discriminator-mapped models to variant
        // alias headers/sources. The discriminator dispatch in the variant alias
        // template calls fromJsonValue_{{modelName}}(value) or toJsonValue_{{modelName}}.
        // Without the include, the compiler sees an undeclared identifier.
        for (Map.Entry<String, ModelsMap> entry : processed.entrySet()) {
            for (ModelMap mo : entry.getValue().getModels()) {
                CodegenModel cm = mo.getModel();
                @SuppressWarnings("unchecked")
                Map<String, String> mapping = (Map<String, String>)
                        cm.vendorExtensions.get("x-discriminator-mapping");
                if (mapping == null) continue;
                for (String modelName : mapping.values()) {
                    if (modelName != null) {
                        collectImportsForType(modelName, cm);
                    }
                }
            }
        }

        return processed;
    }

    /**
     * Scans a type string for known standard types and adds corresponding
     * #include directives to the model's import set. Types that look like
     * model names (start with an uppercase letter and are not otherwise
     * mapped) are resolved via toModelImport.
     */
    private void collectImportsForType(String type, CodegenModel cm) {
        if (type == null) {
            return;
        }
        boolean matchedImportMapping = false;
        for (Map.Entry<String, String> entry : importMapping.entrySet()) {
            String mappedKey = entry.getKey();
            String mappedInclude = entry.getValue();
            if (type.contains(mappedKey)) {
                cm.imports.add(mappedInclude);
                if (type.equals(mappedKey) || type.startsWith(mappedKey + "<")) {
                    matchedImportMapping = true;
                }
            }
        }
        // If the type was not matched by importMapping and looks like a model
        // name (starts with uppercase), treat it as a model include.
            if (!matchedImportMapping && !type.isEmpty() && Character.isUpperCase(type.charAt(0))) {
            String modelInclude = toModelImport(type);
            if (modelInclude != null && !modelInclude.isEmpty()) {
                cm.imports.add(modelInclude);
            }
        }
    }

    /**
     * Maps OpenAPI type names (from composed branch properties) to C++ types.
     * Composed properties created by DefaultCodegen.fromProperty use OpenAPI
     * type names (e.g., "null", "integer", "string") rather than mapped C++ types.
     */

    private String resolveOpenApiTypeName(String type) {
        if (type == null) {
            return null;
        }
        // Check typeMapping first for known OpenAPI type names
        if ("null".equals(type)) {
            return "std::nullptr_t";
        }
        // Check if it's already a C++ type (starts with std:: or boost:: or is a model name)
        if (type.startsWith("std::") || type.startsWith("boost::") || type.contains("<")) {
            return type;
        }
        // Map through typeMapping for OpenAPI primitive type names
        String mapped = typeMapping.get(type);
        if (mapped != null) {
            return mapped;
        }
        // If it has underscores or uppercase letters, assume it's already a model name
        return type;
    }

    /**
     * Applies the ordered type lowering rules to a composed (oneOf/anyOf) model.
     * Sets vendor extensions consumed by templates and records the model as a variant type.
     *
     * NOTE: When a schema uses <b>both</b> allOf and oneOf/anyOf at the same root level,
     * the allOf branches are merged into properties while the oneOf/anyOf branches are
     * lowered to variant types. This can produce a model with both concrete properties
     * AND a variant type, which may generate conflicting C++ declarations. Avoid such
     * mixed-schema patterns; prefer separate allOf-only or oneOf-only schemas.
     */
    private void processComposedModel(CodegenModel cm) {
        if (cm.getComposedSchemas() == null) {
            // Descriptor-complete path: when composedSchemas were consumed by
            // fromModel before we could access them, use the CompositionDescriptor
            // built in preprocessOpenAPI to reconstruct branch metadata and
            // perform lowering.
            CompositionDescriptor desc = compositionDescriptors.get(cm.classname);
            if (desc == null || "allOf".equals(desc.getKeyword())) {
                return; // allOf models handled separately in postProcessModels
            }
            processComposedModelFromDescriptor(cm, desc);
            return;
        }

        List<CodegenProperty> branches = null;
        String composedKeyword = null;

        if (cm.getComposedSchemas().getOneOf() != null && !cm.getComposedSchemas().getOneOf().isEmpty()) {
            branches = cm.getComposedSchemas().getOneOf();
            composedKeyword = "oneOf";
        } else if (cm.getComposedSchemas().getAnyOf() != null && !cm.getComposedSchemas().getAnyOf().isEmpty()) {
            branches = cm.getComposedSchemas().getAnyOf();
            composedKeyword = "anyOf";
        }

        if (branches == null) {
            // Fall through to descriptor path when oneOf/anyOf branches were
            // consumed by the default pipeline but a composition descriptor
            // still exists (e.g., all branches were self-references or the
            // schema uses composedSchemas for allOf only).
            CompositionDescriptor desc = compositionDescriptors.get(cm.classname);
            if (desc != null && !"allOf".equals(desc.getKeyword())) {
                processComposedModelFromDescriptor(cm, desc);
            }
            return;
        }

        // Look up the composition descriptor as the semantic source for lowering.
        // When available, descriptor metadata (null capability, assertions, keyword)
        // is used by lowerComposedTypes instead of inferring semantics from C++ type
        // strings alone.
        CompositionDescriptor descriptor = compositionDescriptors.get(cm.classname);

        // Collect C++ branch types (strip shared_ptr wrappers for variant members).
        // Map OpenAPI type names (e.g., "null", "integer", "string") to C++ types
        // because composed properties from fromProperty use OpenAPI type names as-is.
        // Self-referencing branches (a variant containing itself) are excluded
        // because they would create an illegal recursive type alias in C++.
        // Binary branches (format: binary) are mapped to std::vector<std::uint8_t>
        // so the multipart addVariantFormParameter helper can dispatch them as
        // file parts via compile-time type checking.
        // NOTE: Deduplication is deferred to lowerComposedTypes (step 5) so that
        // oneOf semantics can be preserved when duplicate types would otherwise
        // cause silent single-branch collapse.
        //
        // Track originalBranchIndex (bi) for descriptor alignment after
        // self-referencing branches are filtered out.
        List<ComposedBranch> composedBranches = new ArrayList<>();
        for (int bi = 0; bi < branches.size(); bi++) {
            CodegenProperty b = branches.get(bi);
            String cppType;
            if (b.isBinary || b.isFile) {
                cppType = "std::vector<std::uint8_t>";
            } else {
                String rawType = stripSharedPtr(b.dataType);
                if (rawType == null || "null".equals(rawType)) {
                    cppType = "std::nullptr_t";
                } else {
                    cppType = resolveOpenApiTypeName(rawType);
                }
            }
            if (cppType != null && cppType.equals(cm.classname)) {
                continue;
            }
            boolean isStringLike = b.isString || "std::string".equals(cppType)
                    || "string".equals(b.dataType);
            composedBranches.add(new ComposedBranch(cppType, b.isEnum, isStringLike, bi));
        }
        List<String> branchTypes = composedBranches.stream()
                .map(cb -> cb.cppType)
                .collect(Collectors.toList());

        String resolvedType;
        try {
            resolvedType = lowerComposedTypes(composedBranches, composedKeyword, descriptor);
        } catch (RuntimeException e) {
            // Fallback: if lowering fails (e.g., indistinguishable oneOf branches),
            // treat as boost::json::value and log the warning rather than crashing
            // the entire generation pipeline.
            LOGGER.warn("Failed to lower composed types for '{}': {} — falling back to boost::json::value",
                    cm.classname, e.getMessage());
            resolvedType = "boost::json::value";
        }

        // Cache the resolved type for transitive resolution in Phase 1b
        resolvedAliasTypes.put(cm.classname, resolvedType);

        // Record as variant model for getTypeDeclaration shared_ptr exclusion
        variantModels.add(cm.classname);

        // Emit vendor extensions consumed by Mustache templates
        cm.vendorExtensions.put("x-cpp-type", resolvedType);
        cm.vendorExtensions.put("x-cpp-branches", branchTypes);
        cm.vendorExtensions.put("x-cpp-composed-keyword", composedKeyword);
        composedKeywordsByModel.put(cm.classname, composedKeyword);

        // Phase 3: Build per-branch storage types and detect duplicate types.
        // Populate storage-cpp-type on each branch descriptor from the resolved
        // lowering result, and emit has-duplicate-types flag so templates can
        // generate CompositionBranchValue-aware accessors (isBranchN, getBranchN).
        boolean hasDuplicateTypes = resolvedType.contains("CompositionBranchValue<");
        if (descriptor != null) {
            Map<String, Object> templateMap = descriptor.toTemplateMap();
            @SuppressWarnings("unchecked")
            var templateBranches = (List<Map<String, Object>>) templateMap.get("branches");
            for (int bi = 0; bi < composedBranches.size(); bi++) {
                ComposedBranch cb = composedBranches.get(bi);
                int descIdx = cb.originalBranchIndex;
                if (descIdx >= 0 && descIdx < templateBranches.size()) {
                    Map<String, Object> tBranch = templateBranches.get(descIdx);
                    String storageType;
                    if (hasDuplicateTypes) {
                        storageType = "CompositionBranchValue<" + descIdx
                                + ", " + cb.cppType + ">";
                        tBranch.put("inner-cpp-type", cb.cppType);
                    } else {
                        storageType = cb.cppType;
                    }
                    tBranch.put("storage-cpp-type", storageType);
                }
            }
            templateMap.put("has-duplicate-types", hasDuplicateTypes);
            cm.vendorExtensions.put("x-cpp-composition-branches", templateMap);
            if (hasDuplicateTypes) {
                cm.vendorExtensions.put("x-cpp-has-duplicate-types", true);
                hasDuplicateTypesModels.add(cm.classname);
            }
        } else {
            // Fallback: build branch maps from the composed branches when no
            // precomputed descriptor exists (e.g., inline schemas not in the
            // component schema index).
            List<Map<String, Object>> fallbackBranches = new ArrayList<>();
            for (int bi = 0; bi < composedBranches.size(); bi++) {
                ComposedBranch cb = composedBranches.get(bi);
                Map<String, Object> branchMap = new LinkedHashMap<>();
                branchMap.put("branch-index", bi);
                branchMap.put("source-schema-ref", null);
                branchMap.put("resolved-schema-name", cb.cppType);
                String storageType = hasDuplicateTypes
                        ? "CompositionBranchValue<" + bi + ", " + cb.cppType + ">"
                        : cb.cppType;
                branchMap.put("storage-cpp-type", storageType);
                if (hasDuplicateTypes) {
                    branchMap.put("inner-cpp-type", cb.cppType);
                }
                branchMap.put("validator-id", null);
                branchMap.put("null-capability",
                        "std::nullptr_t".equals(cb.cppType) ? "always" : "never");
                fallbackBranches.add(branchMap);
            }
            Map<String, Object> fallbackMap = new LinkedHashMap<>();
            fallbackMap.put("schema-name", cm.classname);
            fallbackMap.put("schema-location", null);
            fallbackMap.put("keyword", composedKeyword);
            fallbackMap.put("branches", fallbackBranches);
            fallbackMap.put("has-duplicate-types", hasDuplicateTypes);
            cm.vendorExtensions.put("x-cpp-composition-branches", fallbackMap);
            if (hasDuplicateTypes) {
                cm.vendorExtensions.put("x-cpp-has-duplicate-types", true);
                hasDuplicateTypesModels.add(cm.classname);
            }
        }

        // Store per-branch metadata for Phase 1b re-lowering.
        // Phase 1b resolves model-name branch types through aliases to
        // C++ type strings but the isEnum flag (used by Rule 6 for oneOf
        // open-string + string-enum overlap detection) is not derivable
        // from C++ type strings alone — both open strings and string enums
        // produce "std::string".
        List<Boolean> branchIsEnumFlags = composedBranches.stream()
                .map(cb -> cb.isEnum)
                .collect(Collectors.toList());
        cm.vendorExtensions.put("x-cpp-branch-is-enum", branchIsEnumFlags);
        // Store original descriptor branch indices so Phase 1b can correctly
        // align with the CompositionDescriptor after self-ref filtering.
        List<Integer> branchOriginalIndices = composedBranches.stream()
                .map(cb -> cb.originalBranchIndex)
                .collect(Collectors.toList());
        cm.vendorExtensions.put("x-cpp-branch-original-index", branchOriginalIndices);

        if (cm.discriminator != null) {
            cm.vendorExtensions.put("x-has-discriminator", true);
            cm.vendorExtensions.put("x-discriminator-property", cm.discriminator.getPropertyBaseName());
            cm.vendorExtensions.put("x-discriminator-mapping", cm.discriminator.getMapping());
            // Self-referential discriminator entries are filtered in Phase 1b
            // (postProcessAllModels) after all resolvedAliasTypes are populated.
            // We store the resolved type so Phase 1b can check for self-refs.
            cm.vendorExtensions.put("x-discriminator-resolved-type", resolvedType);

            // Build discriminator-value → branch-index lookup for template reorder.
            // Uses the full MappedModel set (explicit URI + implicit component-name)
            // so the template can reorder candidate validation for diagnostics.
            // Self-referential mappings (modelName == cm.classname) are skipped
            // rather than failing the whole model per §8 / Phase 4b policy.
            if (cm.discriminator != null && cm.discriminator.getMappedModels() != null
                    && !cm.discriminator.getMappedModels().isEmpty()
                    && descriptor != null) {
                // Filter out self-referential MappedModel entries
                Set<CodegenDiscriminator.MappedModel> filtered = new LinkedHashSet<>();
                for (CodegenDiscriminator.MappedModel mm : cm.discriminator.getMappedModels()) {
                    if (mm.getModelName() == null || !mm.getModelName().equals(cm.classname)) {
                        filtered.add(mm);
                    }
                }
                if (!filtered.isEmpty()) {
                    List<Map<String, Object>> discBranchIndex = buildDiscriminatorBranchIndex(
                            filtered, descriptor.getBranches());
                    if (!discBranchIndex.isEmpty()) {
                        cm.vendorExtensions.put("x-discriminator-branch-index", discBranchIndex);
                        cm.vendorExtensions.put("x-has-discriminator-branch-index", true);
                    }
                }
            } else if (descriptor != null && descriptor.hasDiscriminator()) {
                // Fallback: use explicit descriptor mapping when MappedModel unavailable
                List<Map<String, Object>> discBranchIndex = buildDiscriminatorBranchIndex(
                        descriptor.getDiscriminator().getMapping(),
                        descriptor.getBranches());
                if (!discBranchIndex.isEmpty()) {
                    cm.vendorExtensions.put("x-discriminator-branch-index", discBranchIndex);
                    cm.vendorExtensions.put("x-has-discriminator-branch-index", true);
                }
            }
        }

        // Update data type so templates and references use the resolved type
        cm.dataType = resolvedType;
    }

    /**
     * Descriptor-complete path: process a composed model whose composedSchemas
     * were consumed by fromModel, using only the descriptor metadata.
     * Reconstructs ComposedBranch entries from descriptor branch schema names,
     * resolves C++ types, then runs the same lowering/emission pipeline as
     * the normal composedSchemas path.
     */
    private void processComposedModelFromDescriptor(CodegenModel cm,
                                                     CompositionDescriptor desc) {
        List<ComposedBranch> composedBranches = new ArrayList<>();
        List<CompositionBranchDescriptor> descBranches = desc.getBranches();

        for (int bi = 0; bi < descBranches.size(); bi++) {
            CompositionBranchDescriptor db = descBranches.get(bi);
            String resolvedSchemaName = db.getResolvedSchemaName();
            String cppType = resolveOpenApiTypeName(resolvedSchemaName);

            // Skip self-referencing branches
            if (cppType != null && cppType.equals(cm.classname)) {
                continue;
            }
            if (cppType == null) {
                cppType = resolvedSchemaName;
            }
            // Skip self-referencing after fallback
            if (cppType.equals(cm.classname)) {
                continue;
            }

            // Determine isEnum from descriptor assertion metadata
            boolean isEnum = db.getSupportedAssertions().contains("enum");
            boolean isStringLike = "std::string".equals(cppType);
            composedBranches.add(new ComposedBranch(cppType, isEnum, isStringLike, bi));
        }

        List<String> branchTypes = composedBranches.stream()
                .map(cb -> cb.cppType)
                .collect(Collectors.toList());

        String resolvedType;
        try {
            resolvedType = lowerComposedTypes(composedBranches, desc.getKeyword(), desc);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to lower composed types for '{}' (descriptor path): {} — falling back to boost::json::value",
                    cm.classname, e.getMessage());
            resolvedType = "boost::json::value";
        }

        // Cache the resolved type
        resolvedAliasTypes.put(cm.classname, resolvedType);
        variantModels.add(cm.classname);

        // Phase 3: Detect duplicate types and populate per-branch storage
        // types on the descriptor template map.
        boolean hasDuplicateTypes = resolvedType.contains("CompositionBranchValue<");
        Map<String, Object> descTemplateMap = desc.toTemplateMap();
        {
            @SuppressWarnings("unchecked")
            var templateBranches = (List<Map<String, Object>>) descTemplateMap.get("branches");
            // When hasDuplicateTypes, all branches (including null) get
            // CompositionBranchValue wrapping — match shortcut behavior.
            for (int bi = 0; bi < composedBranches.size(); bi++) {
                ComposedBranch cb = composedBranches.get(bi);
                int descIdx = cb.originalBranchIndex;
                if (descIdx >= 0 && descIdx < templateBranches.size()) {
                    Map<String, Object> tBranch = templateBranches.get(descIdx);
                    String storageType;
                    if (hasDuplicateTypes) {
                        storageType = "CompositionBranchValue<" + descIdx
                                + ", " + cb.cppType + ">";
                        tBranch.put("inner-cpp-type", cb.cppType);
                    } else {
                        storageType = cb.cppType;
                    }
                    tBranch.put("storage-cpp-type", storageType);
                }
            }
        }
        descTemplateMap.put("has-duplicate-types", hasDuplicateTypes);

        // Emit vendor extensions
        cm.vendorExtensions.put("x-cpp-type", resolvedType);
        cm.vendorExtensions.put("x-cpp-branches", branchTypes);
        cm.vendorExtensions.put("x-cpp-composed-keyword", desc.getKeyword());
        composedKeywordsByModel.put(cm.classname, desc.getKeyword());
        cm.vendorExtensions.put("x-cpp-composition-branches", descTemplateMap);
        if (hasDuplicateTypes) {
            cm.vendorExtensions.put("x-cpp-has-duplicate-types", true);
            hasDuplicateTypesModels.add(cm.classname);
        }

        // Store per-branch metadata for Phase 1b re-lowering
        List<Boolean> branchIsEnumFlags = composedBranches.stream()
                .map(cb -> cb.isEnum)
                .collect(Collectors.toList());
        cm.vendorExtensions.put("x-cpp-branch-is-enum", branchIsEnumFlags);
        List<Integer> branchOriginalIndices = composedBranches.stream()
                .map(cb -> cb.originalBranchIndex)
                .collect(Collectors.toList());
        cm.vendorExtensions.put("x-cpp-branch-original-index", branchOriginalIndices);

        if (desc.hasDiscriminator()) {
            cm.vendorExtensions.put("x-has-discriminator", true);
            cm.vendorExtensions.put("x-discriminator-property",
                    desc.getDiscriminator().getPropertyName());
            cm.vendorExtensions.put("x-discriminator-mapping",
                    desc.getDiscriminator().getMapping());
            cm.vendorExtensions.put("x-discriminator-resolved-type", resolvedType);

            // Build discriminator-value → branch-index lookup for template reorder.
            // Uses the full MappedModel set (explicit URI + implicit component-name)
            // when available, falling back to the descriptor's explicit mapping.
            // Self-referential mappings are skipped per §8 / Phase 4b policy.
            if (cm.discriminator != null && cm.discriminator.getMappedModels() != null
                    && !cm.discriminator.getMappedModels().isEmpty()) {
                // Filter out self-referential MappedModel entries
                Set<CodegenDiscriminator.MappedModel> filtered = new LinkedHashSet<>();
                for (CodegenDiscriminator.MappedModel mm : cm.discriminator.getMappedModels()) {
                    if (mm.getModelName() == null || !mm.getModelName().equals(cm.classname)) {
                        filtered.add(mm);
                    }
                }
                if (!filtered.isEmpty()) {
                    List<Map<String, Object>> discBranchIndex = buildDiscriminatorBranchIndex(
                            filtered, descBranches);
                    if (!discBranchIndex.isEmpty()) {
                        cm.vendorExtensions.put("x-discriminator-branch-index", discBranchIndex);
                        cm.vendorExtensions.put("x-has-discriminator-branch-index", true);
                    }
                }
            } else if (desc.hasDiscriminator()) {
                // Fallback: use explicit descriptor mapping when MappedModel unavailable
                List<Map<String, Object>> discBranchIndex = buildDiscriminatorBranchIndex(
                        desc.getDiscriminator().getMapping(),
                        descBranches);
                if (!discBranchIndex.isEmpty()) {
                    cm.vendorExtensions.put("x-discriminator-branch-index", discBranchIndex);
                    cm.vendorExtensions.put("x-has-discriminator-branch-index", true);
                }
            }
        }

        cm.dataType = resolvedType;
    }

    /** Branch metadata used by ordered composition lowering. */
    private static final class ComposedBranch {
        final String cppType;
        final boolean isEnum;
        final boolean isStringLike;
        /** Index into the CompositionDescriptor branch list.
         *  -1 means no descriptor alignment (fallback path). */
        final int originalBranchIndex;

        ComposedBranch(String cppType, boolean isEnum, boolean isStringLike,
                       int originalBranchIndex) {
            this.cppType = cppType;
            this.isEnum = isEnum;
            this.isStringLike = isStringLike;
            this.originalBranchIndex = originalBranchIndex;
        }
    }

    /**
     * Builds a list of {key, value} maps from the full set of discriminator
     * mapped models (explicit URI mappings + implicit component-name mappings)
     * for template-iteration.  Each entry maps a C++-escaped discriminator value
     * to a composition branch index so the template can reorder candidate
     * validation for diagnostics.
     * <p>
     * Unresolvable mappings (where the model name does not match any branch
     * resolved schema name) fail generation with a clear diagnostic per §8.
     *
     * @param mappedModels the full set of discriminator mapped models
     * @param branches     the composition branch descriptors
     * @return list of {key, value} maps; non-empty when at least one mapping
     *         resolves to a valid branch
     * @throws RuntimeException when a mapping does not resolve to any branch
     */
    public static List<Map<String, Object>> buildDiscriminatorBranchIndex(
            Set<CodegenDiscriminator.MappedModel> mappedModels,
            List<CompositionBranchDescriptor> branches) {
        List<Map<String, Object>> indexList = new ArrayList<>();
        if (mappedModels == null || mappedModels.isEmpty()) return indexList;
        for (CodegenDiscriminator.MappedModel mm : mappedModels) {
            if (mm == null) continue;
            int branchIndex = -1;
            for (int bi = 0; bi < branches.size(); bi++) {
                String resolvedName = branches.get(bi).getResolvedSchemaName();
                if (resolvedName == null) continue;
                // Match on raw schemaName first (handles lowercase/raw names),
                // then on sanitized modelName (handles normalised names).
                if (resolvedName.equals(mm.getSchemaName())
                        || resolvedName.equals(mm.getModelName())) {
                    branchIndex = bi;
                    break;
                }
            }
            if (branchIndex >= 0) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("key", escapeCppStringContent(mm.getMappingName()));
                entry.put("value", branchIndex);
                indexList.add(entry);
            } else {
                // §8: unresolvable → hard diagnostic
                throw new RuntimeException(
                    "Discriminator mapping value '"
                    + escapeCppStringContent(mm.getMappingName())
                    + "' (schema: " + mm.getSchemaName()
                    + ", model: " + mm.getModelName()
                    + ") does not match any composition branch for schema '"
                    + (mm.getModelName() != null ? mm.getModelName() : "(unknown)")
                    + "'. Valid branches: "
                    + branches.stream()
                        .map(CompositionBranchDescriptor::getResolvedSchemaName)
                        .filter(n -> n != null)
                        .collect(Collectors.joining(", ")));
            }
        }
        return indexList;
    }

    /**
     * Fallback variant: builds a list of {key, value} maps from explicit
     * discriminator mapping entries only (used when the codegen model's
     * full MappedModel set is unavailable).
     *
     * @param discMapping the discriminator.value → target mapping
     * @param branches    the composition branch descriptors
     * @return list of {key, value} maps
     */
    public static List<Map<String, Object>> buildDiscriminatorBranchIndex(
            Map<String, String> discMapping,
            List<CompositionBranchDescriptor> branches) {
        List<Map<String, Object>> indexList = new ArrayList<>();
        if (discMapping == null || discMapping.isEmpty()) return indexList;
        for (Map.Entry<String, String> entry : discMapping.entrySet()) {
            String targetName = extractSimpleRef(entry.getValue());
            if (targetName == null) continue;
            int branchIndex = -1;
            for (int bi = 0; bi < branches.size(); bi++) {
                if (targetName.equals(branches.get(bi).getResolvedSchemaName())) {
                    branchIndex = bi;
                    break;
                }
            }
            if (branchIndex >= 0) {
                Map<String, Object> entryMap = new LinkedHashMap<>();
                entryMap.put("key", escapeCppStringContent(entry.getKey()));
                entryMap.put("value", branchIndex);
                indexList.add(entryMap);
            } else {
                throw new RuntimeException(
                    "Discriminator mapping target '" + entry.getValue()
                    + "' (resolved: " + targetName
                    + ") does not match any composition branch. Valid branches: "
                    + branches.stream()
                        .map(CompositionBranchDescriptor::getResolvedSchemaName)
                        .filter(n -> n != null)
                        .collect(Collectors.joining(", ")));
            }
        }
        return indexList;
    }

    /**
     * Extracts a simple schema name from a discriminator mapping value.
     * Handles both URI references (e.g. "#/components/schemas/Mammal")
     * and plain component names (e.g. "Mammal").
     */
    private static String extractSimpleRef(String mappingValue) {
        if (mappingValue == null || mappingValue.isEmpty()) return null;
        String ref = mappingValue.trim();
        if (ref.startsWith("#/")) {
            int lastSlash = ref.lastIndexOf('/');
            return lastSlash >= 0 ? ref.substring(lastSlash + 1) : ref;
        }
        return ref;
    }

    private List<Map<String, Object>> buildTypeErasedOneOfBranches(
            CodegenModel codegenModel, Map<String, CodegenModel> allModels) {
        List<Map<String, Object>> validationBranches = new ArrayList<>();
        for (CodegenProperty branch : codegenModel.getComposedSchemas().getOneOf()) {
            String originalType = stripSharedPtr(branch.dataType);
            CodegenModel referencedModel = allModels.get(originalType);
            String resolvedType = resolveThroughAliases(originalType);
            if (referencedModel != null && referencedModel.dataType != null) {
                resolvedType = resolveThroughAliases(stripSharedPtr(referencedModel.dataType));
            }
            resolvedType = resolveOpenApiTypeName(resolvedType);

            Map<String, Object> validationBranch = new LinkedHashMap<>();
            if ("std::string".equals(resolvedType)) {
                validationBranch.put("is-string", true);
                List<Object> enumValues = getEnumValues(branch, referencedModel);
                if (!enumValues.isEmpty()) {
                    validationBranch.put("has-enum-values", true);
                    List<Map<String, String>> escapedValues = new ArrayList<>();
                    for (Object enumValue : enumValues) {
                        escapedValues.add(Collections.singletonMap(
                                "literal", escapeCppStringContent(String.valueOf(enumValue))));
                    }
                    validationBranch.put("enum-values", escapedValues);
                }
            } else if ("bool".equals(resolvedType)) {
                validationBranch.put("is-boolean", true);
            } else if ("std::int32_t".equals(resolvedType) || "int32_t".equals(resolvedType)) {
                validationBranch.put("is-int32", true);
            } else if ("std::int64_t".equals(resolvedType) || "int64_t".equals(resolvedType)) {
                validationBranch.put("is-integer", true);
            } else if ("double".equals(resolvedType) || "float".equals(resolvedType)) {
                validationBranch.put("is-number", true);
            } else if ("std::nullptr_t".equals(resolvedType)) {
                validationBranch.put("is-null", true);
            } else if (resolvedType != null && resolvedType.startsWith("std::vector<")) {
                validationBranch.put("is-array", true);
            } else if (resolvedType != null
                    && (resolvedType.startsWith("std::map<")
                    || (!resolvedType.startsWith("std::")
                    && !resolvedType.startsWith("boost::")))) {
                validationBranch.put("is-object", true);
            } else {
                validationBranch.put("is-any", true);
            }
            validationBranches.add(validationBranch);
        }
        return validationBranches;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> getEnumValues(
            CodegenProperty branch, CodegenModel referencedModel) {
        Map<String, Object> allowableValues = branch.allowableValues;
        if ((allowableValues == null || allowableValues.get("values") == null)
                && referencedModel != null) {
            allowableValues = referencedModel.allowableValues;
        }
        if (allowableValues == null || !(allowableValues.get("values") instanceof List)) {
            return Collections.emptyList();
        }
        return (List<Object>) allowableValues.get("values");
    }

    private static String escapeCppStringContent(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                default:
                    if (character < 0x20 || character == 0x7f) {
                        escaped.append(String.format(Locale.ROOT, "\\%03o", (int) character));
                    } else {
                        escaped.append(character);
                    }
                    break;
            }
        }
        return escaped.toString();
    }

    /**
     * Converts an arbitrary schema name into a valid C++ identifier for use
     * in generated validator function names. Replaces non-alphanumeric
     * characters with underscores and ensures the result starts with a letter.
     */
    private static String toValidIdentifier(String name) {
        if (name == null || name.isEmpty()) {
            return "schema";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String result = sb.toString();
        if (!result.isEmpty() && !Character.isLetter(result.charAt(0))
                && result.charAt(0) != '_') {
            result = "_" + result;
        }
        return result.isEmpty() ? "schema" : result;
    }

    /**
     * Thrown during generation when a schema branch has assertion keywords that
     * can affect composition membership but no generated validator exists.
     * Carries the schema location, keyword, and remediation guidance.
     */
    public static final class UnsupportedSchemaAssertionException
            extends RuntimeException {
        private final String schemaLocation;
        private final String assertionKeyword;

        public UnsupportedSchemaAssertionException(
                String schemaLocation, String assertionKeyword) {
            super(buildMessage(schemaLocation, assertionKeyword));
            this.schemaLocation = schemaLocation;
            this.assertionKeyword = assertionKeyword;
        }

        public String getSchemaLocation() { return schemaLocation; }
        public String getAssertionKeyword() { return assertionKeyword; }

        private static String buildMessage(
                String schemaLocation, String assertionKeyword) {
            return "Unsupported schema assertion '" + assertionKeyword
                    + "' at " + schemaLocation
                    + ". This keyword can affect composition membership but "
                    + "no generated validator exists. Add support in Phase 2 "
                    + "or later, or restructure the schema to avoid this keyword.";
        }
    }

    /**
     * Checks a composition descriptor for unsupported branch assertions that
     * can affect membership. Throws UnsupportedSchemaAssertionException when
     * a branch has unsupportedAssertions that overlap with supportedAssertions
     * in a way that changes membership fidelity.
     */
    private void validateDescriptorAssertions(CompositionDescriptor desc) {
        if (desc == null) return;
        for (CompositionBranchDescriptor branch : desc.getBranches()) {
            for (String unsupported : branch.getUnsupportedAssertions()) {
                // `not` is always fail-closed: it flips membership, so every
                // composition keyword must have explicit support.
                // All other unsupported assertion categories stop generation
                // for oneOf/anyOf only, since they can change membership count
                // without a generated validator.
                // allOf models are exempted from the non-not check because allOf
                // membership means "all branches must match" — unsupported
                // assertions don't change match count.
                if ("not".equals(unsupported)) {
                    // `not` always fails generation regardless of keyword
                } else if ("allOf".equals(desc.getKeyword())) {
                    continue; // non-not unsupported assertions exempted for allOf
                }
                throw new UnsupportedSchemaAssertionException(
                        desc.getSchemaLocation(), unsupported);
            }
        }
    }

    // ========================================================================
    // Phase 5: Recursive allOf intersection engine
    // ========================================================================

    /**
     * Computes the recursive intersection of all allOf contributors.
     * Resolves $ref-to-allOf chains recursively with cycle detection via the
     * visited set. Merges properties, unions required, and detects
     * unsatisfiable intersections.
     * <p>
     * For each property that appears in multiple contributors, their property
     * schemas are recursively intersected. If the intersection of a required
     * property is empty, the model is unsatisfiable. If the intersection of
     * an optional property is empty, the property is tagged as
     * optional-impossible (rejected when present, but does not invalidate
     * an otherwise valid object).
     *
     * @param schemaName the source schema name (for diagnostics)
     * @param schema     the allOf schema whose branches to intersect
     * @param openAPI    the parsed OpenAPI document
     * @param schemas    the component schemas index
     * @param visited    set of already-visited schema names (cycle guard)
     * @return the computed intersection, or null if no allOf branches
     * @throws AllOfRequiredUnsatisfiableException if a required intersection
     *         is empty and the model cannot be generated
     */
    private AllOfIntersection computeAllOfIntersection(
            String schemaName, Schema schema, OpenAPI openAPI,
            Map<String, Schema> schemas, Set<String> visited) {
        if (schema == null) return null;
        List<Schema> allOfBranches = schema.getAllOf();
        if (allOfBranches == null || allOfBranches.isEmpty()) return null;

        // Phase 5: register the schema name in the visited set at entry for
        // cycle detection. If we've already started computing this schema's
        // intersection (recursive allOf via $ref), return a sentinel.
        if (schemaName != null && visited.contains(schemaName)) {
            return new AllOfIntersection(
                    new LinkedHashMap<>(), new LinkedHashSet<>(),
                    true, null, new LinkedHashSet<>());
        }
        if (schemaName != null) {
            visited.add(schemaName);
        }

        Map<String, Schema> mergedProperties = new LinkedHashMap<>();
        Set<String> mergedRequired = new LinkedHashSet<>();
        Set<String> optionalImpossibleProperties = new LinkedHashSet<>();
        boolean satisfiable = true;
        String unsatisfiableReason = null;

        // Root-level scalar intersection tracking
        String rootScalarType = null;
        List<Object> rootEnumValues = null;
        Object rootConstValue = null;
        BigDecimal rootMinimum = null;
        BigDecimal rootMaximum = null;
        Boolean rootExclusiveMinimumObj = null;
        Boolean rootExclusiveMaximumObj = null;
        Integer rootMinLength = null;
        Integer rootMaxLength = null;
        boolean hasRootScalarConstraints = false;
        boolean rootEnumIntersected = false;

        for (int bi = 0; bi < allOfBranches.size(); bi++) {
            Schema branch = allOfBranches.get(bi);
            Schema resolvedBranch = resolveAllOfBranch(branch, openAPI, schemas, visited);
            if (resolvedBranch == null) continue;

            // Detect nested allOf within the resolved branch and recurse.
            if (resolvedBranch.getAllOf() != null && !resolvedBranch.getAllOf().isEmpty()) {
                AllOfIntersection nested = computeAllOfIntersection(
                        schemaName + "_nested_" + bi, resolvedBranch, openAPI, schemas, visited);
                if (nested != null) {
                    mergeIntersectionIntoResult(mergedProperties, mergedRequired,
                            optionalImpossibleProperties, nested, openAPI, schemas);
                    if (!nested.isSatisfiable()) {
                        satisfiable = false;
                        unsatisfiableReason = nested.getUnsatisfiableReason();
                    }
                    // Propagate optional-impossible entries from nested
                    optionalImpossibleProperties.addAll(nested.getOptionalImpossibleProperties());
                }
            }

            // Merge this contributor's properties into the result.
            // For properties that already exist (from a prior contributor),
            // recursively intersect the property schemas.
            if (resolvedBranch.getProperties() != null) {
                @SuppressWarnings("rawtypes")
                Map rawProps = resolvedBranch.getProperties();
                @SuppressWarnings("unchecked")
                Map<String, Schema> typedProps = rawProps;
                for (Map.Entry<String, Schema> propEntry
                        : typedProps.entrySet()) {
                    String propName = propEntry.getKey();
                    Schema propSchema = propEntry.getValue();
                    if (mergedProperties.containsKey(propName)) {
                        Schema existing = mergedProperties.get(propName);
                        Schema intersected = intersectPropertySchemas(
                                existing, propSchema, openAPI, schemas, visited);
                        mergedProperties.put(propName, intersected);
                    } else {
                        mergedProperties.put(propName, propSchema);
                    }
                }
            }

            // Union required property sets
            if (resolvedBranch.getRequired() != null) {
                mergedRequired.addAll(resolvedBranch.getRequired());
            }

            // Handle additionalProperties / closed-object semantics:
            // If any contributor sets additionalProperties to false, the
            // result is closed. If multiple contributors constrain the
            // additional properties schema, use the stricter intersection.
            // For Phase 5, we propagate the strictest additionalProperties.
            Object branchAddProps = resolvedBranch.getAdditionalProperties();
            if (branchAddProps != null) {
                // Track the constraint — but we don't currently produce a
                // synthetic additionalProperties; we just note the constraint.
            }

            // Accumulate root-level scalar constraints from non-object branches
            // (branches that contribute no properties).
            if (resolvedBranch.getProperties() == null
                    || resolvedBranch.getProperties().isEmpty()) {
                // Intersect root-level type
                String branchType = resolvedBranch.getType();
                if (branchType != null) {
                    hasRootScalarConstraints = true;
                    if (rootScalarType == null) {
                        rootScalarType = branchType;
                    } else if (!rootScalarType.equals(branchType)) {
                        // Compatible numeric types
                        if ("integer".equals(branchType) && "number".equals(rootScalarType)) {
                            rootScalarType = "integer";
                        } else if ("number".equals(branchType) && "integer".equals(rootScalarType)) {
                            rootScalarType = "integer";
                        } else {
                            satisfiable = false;
                            unsatisfiableReason = "Incompatible root types across allOf '"
                                    + schemaName + "' contributors: '" + rootScalarType
                                    + "' vs '" + branchType + "'";
                        }
                    }
                }

                // Intersect root-level enum (intersection of all branch enum sets)
                List<Object> branchEnum = resolvedBranch.getEnum();
                if (branchEnum != null && !branchEnum.isEmpty()) {
                    hasRootScalarConstraints = true;
                    if (rootEnumValues == null) {
                        rootEnumValues = new ArrayList<>(branchEnum);
                        rootEnumIntersected = false;
                    } else {
                        rootEnumValues.retainAll(branchEnum);
                        rootEnumIntersected = true;
                    }
                }

                // Intersect root-level const (must match)
                Object branchConst = resolvedBranch.getConst();
                if (branchConst != null) {
                    hasRootScalarConstraints = true;
                    if (rootConstValue == null) {
                        rootConstValue = branchConst;
                    } else if (!rootConstValue.equals(branchConst)) {
                        satisfiable = false;
                        unsatisfiableReason = "Incompatible const values across allOf '"
                                + schemaName + "' contributors: '"
                                + rootConstValue + "' vs '" + branchConst + "'";
                    }
                }

                // Intersect numeric bounds: minimum/maximum take tighter range
                if (resolvedBranch.getMinimum() != null) {
                    hasRootScalarConstraints = true;
                    BigDecimal branchMin = resolvedBranch.getMinimum();
                    if (rootMinimum == null || branchMin.compareTo(rootMinimum) > 0) {
                        rootMinimum = branchMin;
                    }
                    // ExclusiveMinimum: take the more restrictive (larger value)
                    if (Boolean.TRUE.equals(resolvedBranch.getExclusiveMinimum())
                            || (resolvedBranch.getExclusiveMinimumValue() != null
                            && resolvedBranch.getExclusiveMinimumValue().compareTo(BigDecimal.ZERO) > 0)) {
                        rootExclusiveMinimumObj = Boolean.TRUE;
                    } else if (rootExclusiveMinimumObj == null && !Boolean.FALSE.equals(resolvedBranch.getExclusiveMinimum())) {
                        // OpenAPI 3.0 style: exclusiveMinimum is a boolean
                        Object rawExclusiveMin = resolvedBranch.getExclusiveMinimum();
                        if (rawExclusiveMin instanceof Boolean && Boolean.TRUE.equals(rawExclusiveMin)) {
                            rootExclusiveMinimumObj = Boolean.TRUE;
                        }
                    }
                }
                if (resolvedBranch.getMaximum() != null) {
                    hasRootScalarConstraints = true;
                    BigDecimal branchMax = resolvedBranch.getMaximum();
                    if (rootMaximum == null || branchMax.compareTo(rootMaximum) < 0) {
                        rootMaximum = branchMax;
                    }
                    if (Boolean.TRUE.equals(resolvedBranch.getExclusiveMaximum())
                            || (resolvedBranch.getExclusiveMaximumValue() != null
                            && resolvedBranch.getExclusiveMaximumValue().compareTo(BigDecimal.ZERO) > 0)) {
                        rootExclusiveMaximumObj = Boolean.TRUE;
                    } else if (rootExclusiveMaximumObj == null && !Boolean.FALSE.equals(resolvedBranch.getExclusiveMaximum())) {
                        Object rawExclusiveMax = resolvedBranch.getExclusiveMaximum();
                        if (rawExclusiveMax instanceof Boolean && Boolean.TRUE.equals(rawExclusiveMax)) {
                            rootExclusiveMaximumObj = Boolean.TRUE;
                        }
                    }
                }

                // Intersect minLength / maxLength: tighter wins
                if (resolvedBranch.getMinLength() != null) {
                    hasRootScalarConstraints = true;
                    Integer branchMinLength = resolvedBranch.getMinLength();
                    if (rootMinLength == null || branchMinLength > rootMinLength) {
                        rootMinLength = branchMinLength;
                    }
                }
                if (resolvedBranch.getMaxLength() != null) {
                    hasRootScalarConstraints = true;
                    Integer branchMaxLength = resolvedBranch.getMaxLength();
                    if (rootMaxLength == null || branchMaxLength < rootMaxLength) {
                        rootMaxLength = branchMaxLength;
                    }
                }
            }
        }

        // Detect empty enum intersection: two or more branches both contributed
        // enum lists whose intersection is empty (e.g., [a,b] ∩ [c,d] = {}).
        if (rootEnumIntersected && rootEnumValues != null && rootEnumValues.isEmpty()) {
            satisfiable = false;
            unsatisfiableReason = "Empty enum intersection across allOf '"
                    + schemaName + "' contributors: no common enum values";
        }

        // Check satisfiability of required properties.
        // A required property must have a non-empty intersection in the
        // object model. Currently, we detect this by checking if the
        // property was added with an empty/special marker.
        // For Phase 5, we trust that intersectPropertySchemas handles
        // both satisfiable and unsatisfiable results, and we check
        // unsatisfiability based on the returned Schema markers.

        // Detect unsatisfiable required properties:
        // Scan merged properties for unsatisfiable markers.
        for (String propName : mergedRequired) {
            Schema propSchema = mergedProperties.get(propName);
            if (propSchema != null && Boolean.TRUE.equals(
                    propSchema.getExtensions() != null
                            ? propSchema.getExtensions().get("x-cpp-unsatisfiable")
                            : null)) {
                satisfiable = false;
                unsatisfiableReason = "Required property '" + propName
                        + "' in schema '" + schemaName
                        + "' has an empty intersection across allOf contributors. "
                        + "This property is required but cannot satisfy all "
                        + "contributor constraints simultaneously.";
            }
        }

        // Tag optional impossible properties: present in merged but marked
        // with the unsatisfiable flag and NOT in mergedRequired.
        for (Map.Entry<String, Schema> entry : mergedProperties.entrySet()) {
            String propName = entry.getKey();
            if (mergedRequired.contains(propName)) continue;
            Schema propSchema = entry.getValue();
            if (propSchema != null && Boolean.TRUE.equals(
                    propSchema.getExtensions() != null
                            ? propSchema.getExtensions().get("x-cpp-unsatisfiable")
                            : null)) {
                optionalImpossibleProperties.add(propName);
            }
        }

        // If no scalar constraints were accumulated but properties exist, null out root fields
        if (!hasRootScalarConstraints) {
            rootScalarType = null;
            rootEnumValues = null;
            rootConstValue = null;
            rootMinimum = null;
            rootMaximum = null;
            rootExclusiveMinimumObj = null;
            rootExclusiveMaximumObj = null;
            rootMinLength = null;
            rootMaxLength = null;
        }

        return new AllOfIntersection(
                mergedProperties, mergedRequired, satisfiable,
                unsatisfiableReason, optionalImpossibleProperties,
                rootScalarType, rootEnumValues, rootConstValue,
                rootMinimum, rootMaximum,
                rootExclusiveMinimumObj, rootExclusiveMaximumObj,
                rootMinLength, rootMaxLength);
    }

    /**
     * Merges a nested AllOfIntersection into a running result.
     * For properties that already exist, recursively intersects them.
     */
    private void mergeIntersectionIntoResult(
            Map<String, Schema> mergedProperties, Set<String> mergedRequired,
            Set<String> optionalImpossibleProperties,
            AllOfIntersection nested,
            OpenAPI openAPI, Map<String, Schema> schemas) {
        for (Map.Entry<String, Schema> nestedProp : nested.getProperties().entrySet()) {
            String propName = nestedProp.getKey();
            Schema nestedSchema = nestedProp.getValue();
            if (mergedProperties.containsKey(propName)) {
                mergedProperties.put(propName,
                        intersectPropertySchemas(
                                mergedProperties.get(propName),
                                nestedSchema, openAPI, schemas, new HashSet<>()));
            } else {
                mergedProperties.put(propName, nestedSchema);
            }
        }
        mergedRequired.addAll(nested.getRequired());
        optionalImpossibleProperties.addAll(nested.getOptionalImpossibleProperties());
    }

    /**
     * Resolves an allOf branch schema, following $ref targets recursively.
     * If the branch has a $ref, resolves it to a non-allOf schema.
     * If the resolved target is itself allOf, returns it as-is for
     * recursive handling by the caller.
     *
     * @param branch  the allOf contributor (possibly a $ref)
     * @param openAPI the parsed OpenAPI document
     * @param schemas the component schemas index
     * @param visited set of already-visited schema names (cycle guard)
     * @return the resolved schema, or null if unresolvable
     */
    private Schema resolveAllOfBranch(
            Schema branch, OpenAPI openAPI,
            Map<String, Schema> schemas, Set<String> visited) {
        if (branch == null) return null;
        if (branch.get$ref() == null) return branch;

        String refName = ModelUtils.getSimpleRef(branch.get$ref());
        if (refName == null) return branch;
        if (visited.contains(refName)) return branch; // cycle guard

        Schema refTarget = schemas != null ? schemas.get(refName) : null;
        if (refTarget == null && openAPI != null) {
            refTarget = ModelUtils.getReferencedSchema(openAPI, branch);
        }
        if (refTarget == null) return branch;

        visited.add(refName);
        try {
            // If the resolved target also has allOf, recurse
            if (refTarget.getAllOf() != null && !refTarget.getAllOf().isEmpty()) {
                return refTarget; // Return so caller can recurse
            }
            // If the resolved target has properties, return it directly
            if (refTarget.getProperties() != null && !refTarget.getProperties().isEmpty()) {
                return refTarget;
            }
            return refTarget;
        } finally {
            visited.remove(refName);
        }
    }

    /**
     * Intersects two property schemas, combining their constraints.
     * Returns a synthetic Schema that represents the intersection:
     * <ul>
     *   <li>Types are intersected (must have a common type)</li>
     *   <li>Enums are intersected (common values only)</li>
     *   <li>Numeric bounds are tightened</li>
     *   <li>String bounds are tightened</li>
     *   <li>Patterns are retained from both</li>
     *   <li>Required properties are unioned</li>
     *   <li>Properties are recursively intersected</li>
     * </ul>
     * <p>
     * When the intersection is empty (e.g., string ∩ integer), the resulting
     * Schema is tagged with vendor extension {@code x-cpp-unsatisfiable: true}
     * and the property should either fail generation (if required) or generate
     * decode-time rejection (if optional).
     */
    private Schema intersectPropertySchemas(
            Schema existing, Schema incoming,
            OpenAPI openAPI, Map<String, Schema> schemas, Set<String> visited) {
        if (existing == null) return incoming;
        if (incoming == null) return existing;

        // Resolve $ref on both sides before intersecting
        // Property schemas may be unresolved $ref references to component schemas
        existing = ModelUtils.getReferencedSchema(openAPI, existing);
        incoming = ModelUtils.getReferencedSchema(openAPI, incoming);

        // Both non-null: compute intersection
        String existingType = existing.getType();
        String incomingType = incoming.getType();
        boolean typeCompatible = true;

        // Check type compatibility
        if (existingType != null && incomingType != null
                && !existingType.equals(incomingType)) {
            // Special case: integer ⊂ number
            if (!("integer".equals(existingType) && "number".equals(incomingType))
                    && !("number".equals(existingType) && "integer".equals(incomingType))) {
                typeCompatible = false;
            }
        }

        // Check enum compatibility
        List<Object> existingEnum = existing.getEnum();
        List<Object> incomingEnum = incoming.getEnum();
        List<Object> intersectedEnum = null;
        if (existingEnum != null && incomingEnum != null) {
            intersectedEnum = new ArrayList<>(existingEnum);
            intersectedEnum.retainAll(incomingEnum);
            if (intersectedEnum.isEmpty()) {
                typeCompatible = false; // No common enum values
            }
        }

        // Build the intersected schema
        Schema intersected = new Schema();

        // Intersect type: if compatible, keep the more specific type (integer over number)
        if (typeCompatible) {
            if (existingType != null && "integer".equals(existingType)) {
                intersected.setType("integer");
            } else if (incomingType != null && "integer".equals(incomingType)) {
                intersected.setType("integer");
            } else if (existingType != null) {
                intersected.setType(existingType);
            } else {
                intersected.setType(incomingType);
            }
        }

        // Intersect enum values
        if (intersectedEnum != null && !intersectedEnum.isEmpty()) {
            intersected.setEnum(intersectedEnum);
        } else if (existingEnum != null && incomingEnum == null) {
            intersected.setEnum(new ArrayList<>(existingEnum));
        } else if (incomingEnum != null && existingEnum == null) {
            intersected.setEnum(new ArrayList<>(incomingEnum));
        }

        // Intersect const values
        if (existing.getConst() != null && incoming.getConst() != null) {
            if (existing.getConst().equals(incoming.getConst())) {
                intersected.setConst(existing.getConst());
            } else {
                typeCompatible = false; // conflicting const values
            }
        } else if (existing.getConst() != null) {
            intersected.setConst(existing.getConst());
        } else if (incoming.getConst() != null) {
            intersected.setConst(incoming.getConst());
        }

        // Numeric bounds: take the tighter bound (higher min, lower max)
        if (existing.getMinimum() != null || incoming.getMinimum() != null) {
            java.math.BigDecimal existingMin = existing.getMinimum();
            java.math.BigDecimal incomingMin = incoming.getMinimum();
            if (existingMin == null) {
                intersected.setMinimum(incomingMin);
                intersected.setExclusiveMinimum(incoming.getExclusiveMinimum());
            } else if (incomingMin == null) {
                intersected.setMinimum(existingMin);
                intersected.setExclusiveMinimum(existing.getExclusiveMinimum());
            } else {
                // Compare: take the larger (tighter) minimum
                if (existingMin.compareTo(incomingMin) >= 0) {
                    intersected.setMinimum(existingMin);
                    intersected.setExclusiveMinimum(existing.getExclusiveMinimum());
                } else {
                    intersected.setMinimum(incomingMin);
                    intersected.setExclusiveMinimum(incoming.getExclusiveMinimum());
                }
            }
        }
        if (existing.getMaximum() != null || incoming.getMaximum() != null) {
            java.math.BigDecimal existingMax = existing.getMaximum();
            java.math.BigDecimal incomingMax = incoming.getMaximum();
            if (existingMax == null) {
                intersected.setMaximum(incomingMax);
                intersected.setExclusiveMaximum(incoming.getExclusiveMaximum());
            } else if (incomingMax == null) {
                intersected.setMaximum(existingMax);
                intersected.setExclusiveMaximum(existing.getExclusiveMaximum());
            } else {
                // Compare: take the smaller (tighter) maximum
                if (existingMax.compareTo(incomingMax) <= 0) {
                    intersected.setMaximum(existingMax);
                    intersected.setExclusiveMaximum(existing.getExclusiveMaximum());
                } else {
                    intersected.setMaximum(incomingMax);
                    intersected.setExclusiveMaximum(incoming.getExclusiveMaximum());
                }
            }
        }
        if (existing.getMultipleOf() != null || incoming.getMultipleOf() != null) {
            // MultipleOf: take the LCM (tighter constraint)
            // For Phase 5, prefer existing if both present
            if (existing.getMultipleOf() != null) {
                intersected.setMultipleOf(existing.getMultipleOf());
            } else {
                intersected.setMultipleOf(incoming.getMultipleOf());
            }
        }

        // String bounds: take the tighter
        intersected.setMinLength(tighterMinLen(
                existing.getMinLength(), incoming.getMinLength()));
        intersected.setMaxLength(tighterMaxLen(
                existing.getMaxLength(), incoming.getMaxLength()));

        // Patterns: retain multiple (all must match)
        // For Phase 5, the composite pattern constraint is noted but
        // the generated validator only checks the first pattern.
        if (existing.getPattern() != null || incoming.getPattern() != null) {
            // Keep both patterns: set as the first pattern for now
            // and store x-cpp-all-patterns for template use.
            if (existing.getPattern() != null) {
                intersected.setPattern(existing.getPattern());
            } else {
                intersected.setPattern(incoming.getPattern());
            }
        }

        // Array bounds: take the tighter
        intersected.setMinItems(tighterMinLen(
                existing.getMinItems(), incoming.getMinItems()));
        intersected.setMaxItems(tighterMaxLen(
                existing.getMaxItems(), incoming.getMaxItems()));
        if (Boolean.TRUE.equals(existing.getUniqueItems())
                || Boolean.TRUE.equals(incoming.getUniqueItems())) {
            intersected.setUniqueItems(true);
        }

        // Object bounds: take the tighter
        intersected.setMinProperties(tighterMinLen(
                existing.getMinProperties(), incoming.getMinProperties()));
        intersected.setMaxProperties(tighterMaxLen(
                existing.getMaxProperties(), incoming.getMaxProperties()));

        // Recursive property intersection for nested object schemas
        // (properties on properties)
        Map<String, Schema> existingProperties = existing.getProperties();
        Map<String, Schema> incomingProperties = incoming.getProperties();
        if ((existingProperties != null && !existingProperties.isEmpty())
                || (incomingProperties != null && !incomingProperties.isEmpty())) {
            if (existingProperties != null && incomingProperties != null) {
                Map<String, Schema> merged = new LinkedHashMap<>(existingProperties);
                for (Map.Entry<String, Schema> entry : incomingProperties.entrySet()) {
                    String key = entry.getKey();
                    Schema val = entry.getValue();
                    if (merged.containsKey(key)) {
                        merged.put(key, intersectPropertySchemas(
                                merged.get(key), val, openAPI, schemas, visited));
                    } else {
                        merged.put(key, val);
                    }
                }
                intersected.setProperties(merged);
            } else if (existingProperties != null) {
                intersected.setProperties(new LinkedHashMap<>(existingProperties));
            } else {
                intersected.setProperties(new LinkedHashMap<>(incomingProperties));
            }
        }

        // Mark unsatisfiable when types are incompatible
        if (!typeCompatible) {
            Map<String, Object> extensions = intersected.getExtensions();
            if (extensions == null) {
                extensions = new LinkedHashMap<>();
                intersected.setExtensions(extensions);
            }
            extensions.put("x-cpp-unsatisfiable", true);
        }

        return intersected;
    }

    /**
     * Returns the tighter (larger) of two min bounds, or whichever is non-null.
     */
    private static Integer tighterMinLen(Integer first, Integer second) {
        if (first == null) return second;
        if (second == null) return first;
        return Math.max(first, second);
    }

    /**
     * Returns the tighter (smaller) of two max bounds, or whichever is non-null.
     */
    private static Integer tighterMaxLen(Integer first, Integer second) {
        if (first == null) return second;
        if (second == null) return first;
        return Math.min(first, second);
    }

    /**
     * Builds a synthetic object Schema from an AllOfIntersection result.
     * The synthetic schema is used as input to super.fromModel, replacing
     * the original allOf structure with pre-computed merged properties
     * and required sets.
     *
     * @param schemaName   the model name
     * @param intersection the pre-computed allOf intersection
     * @return a synthetic object Schema with merged properties and required
     */
    private Schema buildSyntheticAllOfSchema(
            String schemaName, AllOfIntersection intersection) {
        Schema synthetic = new Schema();

        // Set root-level type from intersection, default to "object"
        if (intersection.getRootScalarType() != null) {
            synthetic.setType(intersection.getRootScalarType());
        } else {
            synthetic.setType("object");
        }

        // Apply intersected root-level enum values
        if (intersection.getRootEnumValues() != null
                && !intersection.getRootEnumValues().isEmpty()) {
            synthetic.setEnum(new ArrayList<>(intersection.getRootEnumValues()));
        }

        // Apply intersected root-level const value
        if (intersection.getRootConstValue() != null) {
            synthetic.setConst(intersection.getRootConstValue());
        }

        // Apply intersected numeric bounds
        if (intersection.getRootMinimum() != null) {
            synthetic.setMinimum(intersection.getRootMinimum());
        }
        if (intersection.getRootMaximum() != null) {
            synthetic.setMaximum(intersection.getRootMaximum());
        }
        if (intersection.getRootExclusiveMinimum() != null) {
            synthetic.setExclusiveMinimum(intersection.getRootExclusiveMinimum());
        }
        if (intersection.getRootExclusiveMaximum() != null) {
            synthetic.setExclusiveMaximum(intersection.getRootExclusiveMaximum());
        }

        // Apply intersected string length bounds
        if (intersection.getRootMinLength() != null) {
            synthetic.setMinLength(intersection.getRootMinLength());
        }
        if (intersection.getRootMaxLength() != null) {
            synthetic.setMaxLength(intersection.getRootMaxLength());
        }

        // Copy merged properties (skipping optional-impossible properties)
        if (!intersection.getProperties().isEmpty()) {
            Map<String, Schema> syntheticProps = new LinkedHashMap<>();
            for (Map.Entry<String, Schema> propEntry
                    : intersection.getProperties().entrySet()) {
                String propName = propEntry.getKey();
                if (intersection.getOptionalImpossibleProperties().contains(propName)) {
                    // For optional-impossible properties (e.g., string ∩ int32),
                    // use the first contributor's schema so the property has a
                    // storage member (avoids empty-shell detection). Mark with
                    // x-cpp-optional-impossible for template-level awareness.
                    Schema propSchema = propEntry.getValue();
                    // The intersected schema may have x-cpp-unsatisfiable set.
                    // Ensure it has at least one contributor type so fromModel
                    // produces a CodegenProperty with a real dataType. Fall back
                    // to the existing intersected schema as-is when it already
                    // has a type or if no better alternative is available.
                    if (propSchema.getType() == null) {
                        // Assign a fallback type so the property gets a member.
                        // Prefer the first contributor's type, otherwise use
                        // boost::json::value as the most generic C++ type.
                        propSchema.setType("string");
                    }
                    Map<String, Object> ext = propSchema.getExtensions();
                    if (ext == null) {
                        ext = new LinkedHashMap<>();
                        propSchema.setExtensions(ext);
                    }
                    ext.put("x-cpp-optional-impossible", true);
                    syntheticProps.put(propName, propSchema);
                } else {
                    syntheticProps.put(propName, propEntry.getValue());
                }
            }
            synthetic.setProperties(syntheticProps);
        }

        // Set required as the union of required from all contributors
        if (!intersection.getRequired().isEmpty()) {
            synthetic.setRequired(new ArrayList<>(intersection.getRequired()));
        }

        return synthetic;
    }

    /**
     * Exception thrown when an allOf intersection produces an unsatisfiable
     * result on a required property, preventing model generation.
     */
    public static final class AllOfRequiredUnsatisfiableException
            extends RuntimeException {
        private final String schemaName;
        private final String reason;

        public AllOfRequiredUnsatisfiableException(
                String schemaName, String reason) {
            super(buildMessage(schemaName, reason));
            this.schemaName = schemaName;
            this.reason = reason;
        }

        public String getSchemaName() { return schemaName; }
        public String getReason() { return reason; }

        private static String buildMessage(
                String schemaName, String reason) {
            return "Unsatisfiable allOf intersection for schema '"
                    + schemaName + "': " + reason;
        }
    }

    /**
     * Ordered lowering rules for composed types (OAS-first):
     * 1. anyOf/oneOf: [T, null] → std::optional&lt;T&gt;
     * 2. anyOf only: all strings/string-enums → std::string
     * 3. Remove null branches
     * 4. Single non-null branch → that branch's type
     * 5. Deduplicate identical branch types
     * 6. oneOf open-string + string-enum (type-erased) → boost::json::value
     *    (do not pretend exclusivity after both erase to std::string)
     * 7. oneOf multi-branch → single identical C++ type (alias collapse) → that type
     * 8. Emit std::variant&lt;Branches...&gt; or boost::json::value
     * <p>
     * When a non-null {@code descriptor} is provided, its branch metadata
     * (nullCapability, supportedAssertions) replaces C++ type-string heuristics
     * for Rules 1, 3, and 6.
     */
    private String lowerComposedTypes(List<ComposedBranch> branches, String composedKeyword,
                                       CompositionDescriptor descriptor) {
        if (branches == null || branches.isEmpty()) {
            return "boost::json::value";
        }
        List<String> branchTypes = branches.stream()
                .map(b -> b.cppType)
                .collect(Collectors.toList());

        // Rule 1: anyOf/oneOf: [T, null] → std::optional<T>
        // Use descriptor nullCapability when available for semantic accuracy.
        // Uses originalBranchIndex to align with descriptor after self-ref filtering.
        // Tightened: non-null branch must have NullCapability.NEVER (not CONDITIONAL).
        if (descriptor != null) {
            int alwaysNullCount = 0;
            int nonNullComposedIndex = -1;
            List<CompositionBranchDescriptor> descBranches = descriptor.getBranches();
            for (int ci = 0; ci < branches.size(); ci++) {
                int descIdx = branches.get(ci).originalBranchIndex;
                if (descIdx < 0 || descIdx >= descBranches.size()) continue;
                CompositionBranchDescriptor.NullCapability nc =
                        descBranches.get(descIdx).getNullCapability();
                if (nc == CompositionBranchDescriptor.NullCapability.ALWAYS) {
                    alwaysNullCount++;
                } else if (nc == CompositionBranchDescriptor.NullCapability.NEVER
                        && nonNullComposedIndex < 0) {
                    nonNullComposedIndex = ci;
                }
            }
            if (alwaysNullCount == 1 && branches.size() == 2
                    && nonNullComposedIndex >= 0
                    && nonNullComposedIndex < branchTypes.size()) {
                String nonNullBranch = branchTypes.get(nonNullComposedIndex);
                if (nonNullBranch != null) {
                    return "std::optional<" + nonNullBranch + ">";
                }
            }
        } else {
            // Fallback: C++ type-string heuristic (no descriptor available)
            int nullCount = (int) branchTypes.stream().filter("std::nullptr_t"::equals).count();
            if (nullCount == 1 && branchTypes.size() == 2) {
                String nonNullBranch = branchTypes.stream()
                        .filter(bt -> !"std::nullptr_t".equals(bt))
                        .findFirst().orElse(null);
                if (nonNullBranch != null) {
                    return "std::optional<" + nonNullBranch + ">";
                }
            }
        }

        // Rule 2: anyOf-only collapse of all-string to std::string.
        // Do NOT apply when any branch has enum constraints — those need
        // validators on the decode path (Finding 3: enum-only anyOf).
        // Do NOT apply to oneOf (exclusive semantics differ).
        if ("anyOf".equals(composedKeyword) && branchTypes.stream().allMatch("std::string"::equals)) {
            // Check if any branch has enum assertions using descriptor metadata
            // or fallback ComposedBranch isEnum flag.
            boolean hasEnumString = false;
            if (descriptor != null) {
                List<CompositionBranchDescriptor> descBranches = descriptor.getBranches();
                for (ComposedBranch cb : branches) {
                    int descIdx = cb.originalBranchIndex;
                    if (descIdx >= 0 && descIdx < descBranches.size()
                            && descBranches.get(descIdx).getSupportedAssertions().contains("enum")) {
                        hasEnumString = true;
                        break;
                    }
                }
            } else {
                hasEnumString = branches.stream().anyMatch(b -> b.isEnum);
            }
            if (!hasEnumString) {
                return "std::string";
            }
            // Has enum string branches — fall through to CompositionBranchValue
            // preservation (Rule 5) which keeps validators active.
        }

        // Rule 3: Remove null branches for further processing.
        // Uses originalBranchIndex to align with descriptor after self-ref filtering.
        // Preserve all branches when every branch is null (Finding 4: all-null
        // anyOf / duplicate-null oneOf must preserve null cardinality).
        List<ComposedBranch> nonNullMeta;
        if (descriptor != null) {
            List<CompositionBranchDescriptor> descBranches = descriptor.getBranches();
            nonNullMeta = new ArrayList<>();
            boolean hasNonNull = false;
            for (ComposedBranch cb : branches) {
                int descIdx = cb.originalBranchIndex;
                if (descIdx >= 0 && descIdx < descBranches.size()) {
                    CompositionBranchDescriptor.NullCapability nc =
                            descBranches.get(descIdx).getNullCapability();
                    if (nc != CompositionBranchDescriptor.NullCapability.ALWAYS) {
                        nonNullMeta.add(cb);
                        hasNonNull = true;
                    }
                }
            }
            // All branches were null — keep them for identity preservation
            if (!hasNonNull && !branches.isEmpty()) {
                nonNullMeta = new ArrayList<>(branches);
            }
        } else {
            List<ComposedBranch> nonNullOnly = branches.stream()
                    .filter(b -> !"std::nullptr_t".equals(b.cppType))
                    .collect(Collectors.toList());
            if (!nonNullOnly.isEmpty()) {
                nonNullMeta = nonNullOnly;
            } else {
                // All branches are null — keep them
                nonNullMeta = new ArrayList<>(branches);
            }
        }
        List<String> nonNullBranches = nonNullMeta.stream()
                .map(b -> b.cppType)
                .collect(Collectors.toList());

        // Rule 3b: Flatten nested variants
        List<String> flattened = new ArrayList<>();
        for (String bt : nonNullBranches) {
            if (bt.startsWith("std::variant<") && bt.endsWith(">")) {
                String inner = bt.substring(13, bt.length() - 1);
                int depth = 0;
                int start = 0;
                for (int i = 0; i < inner.length(); i++) {
                    char c = inner.charAt(i);
                    if (c == '<') depth++;
                    else if (c == '>') depth--;
                    else if (c == ',' && depth == 0) {
                        flattened.add(inner.substring(start, i).trim());
                        start = i + 1;
                    }
                }
                if (start < inner.length()) {
                    flattened.add(inner.substring(start).trim());
                }
            } else {
                flattened.add(bt);
            }
        }

        // Rule 4: All-null or empty → boost::json::value
        if (flattened.isEmpty()) {
            return "boost::json::value";
        }

        // Rule 5: Detect duplicate branch types that would lose schema
        // identity after C++ dedup. When multiple branches lower to the
        // same C++ type (e.g., two double branches with different numeric
        // constraints, or a string + string-enum both becoming std::string),
        // wrap each in CompositionBranchValue<originalBranchIndex, Type>
        // to preserve distinct branch identity.
        boolean hasDuplicateTypes = false;
        outer:
        for (int i = 0; i < nonNullBranches.size(); i++) {
            for (int j = i + 1; j < nonNullBranches.size(); j++) {
                if (nonNullBranches.get(i).equals(nonNullBranches.get(j))) {
                    hasDuplicateTypes = true;
                    break outer;
                }
            }
        }

        if (hasDuplicateTypes) {
            // Shortcut: wrap all branches in CompositionBranchValue to
            // preserve identity. Skip flattening (nested variants only
            // appear once in nonNullBranches so they won't collide here).
            // Also skip Rule 6 (string exclusivity) since tagged branches
            // already preserve distinct membership.
            List<String> tagged = new ArrayList<>();
            for (int i = 0; i < nonNullBranches.size(); i++) {
                String rawType = nonNullBranches.get(i);
                int origIdx = nonNullMeta.get(i).originalBranchIndex;
                // For inline schemas (origIdx < 0), use flat position as tag.
                int brIdx = origIdx >= 0 ? origIdx : i;
                // Flatten nested variant types within tagged branches
                if (rawType.startsWith("std::variant<") && rawType.endsWith(">")) {
                    String inner = rawType.substring(13, rawType.length() - 1);
                    int depth = 0;
                    int start = 0;
                    for (int ci = 0; ci < inner.length(); ci++) {
                        char c = inner.charAt(ci);
                        if (c == '<') depth++;
                        else if (c == '>') depth--;
                        else if (c == ',' && depth == 0) {
                            String innerType = inner.substring(start, ci).trim();
                            tagged.add("CompositionBranchValue<" + brIdx
                                    + ", " + innerType + ">");
                            start = ci + 1;
                        }
                    }
                    if (start < inner.length()) {
                        String innerType = inner.substring(start).trim();
                        tagged.add("CompositionBranchValue<" + brIdx
                                + ", " + innerType + ">");
                    }
                } else {
                    tagged.add("CompositionBranchValue<" + brIdx
                            + ", " + rawType + ">");
                }
            }
            // When hasDuplicateTypes, null branches must be wrapped in
            // CompositionBranchValue too — never bare std::nullptr_t.
            // Find null branches that were filtered by Rule 3 and wrap
            // them, skipping any that Rule 3 already preserved in tagged.
            boolean hasNull = branchTypes.stream().anyMatch("std::nullptr_t"::equals);
            if (hasNull) {
                for (int ni = 0; ni < branches.size(); ni++) {
                    if ("std::nullptr_t".equals(branches.get(ni).cppType)) {
                        int origIdx = branches.get(ni).originalBranchIndex;
                        int brIdx = origIdx >= 0 ? origIdx : ni;
                        String cbvNull = "CompositionBranchValue<" + brIdx
                                + ", std::nullptr_t>";
                        if (!tagged.contains(cbvNull)) {
                            tagged.add(cbvNull);
                        }
                    }
                }
            }
            return "std::variant<" + String.join(", ", tagged) + ">";
        }

        // Rule 6: Deduplicate identical branch types (safe when no duplicates).
        List<String> deduped = flattened.stream()
                .distinct()
                .collect(Collectors.toList());

        // Rule 7: oneOf string branches that lose exclusivity after type lowering.
        // Branches [open-string, string-enum] or [string-enum-A, string-enum-B] all
        // collapse to std::string after type lowering, so every string value matches
        // every original string-like branch. Under JSON Schema oneOf, this means
        // values matching multiple original branches cannot be detected (count is
        // artificially 1 instead of 2+), causing false acceptance of invalid oneOf
        // inputs. Type-erase to boost::json::value when multiple string-like branches
        // collapse and at least one has enum constraints (the constraint is the only
        // thing that distinguishes otherwise-identical branches). anyOf keeps the
        // string collapse (rule 2) since first-match is correct behavior.
        //
        // When a descriptor is available, use its supportedAssertions for enum
        // detection instead of the ComposedBranch isEnum flag. Descriptor
        // assertions are semantically richer (captured from raw schema scanning)
        // and carried from preprocessOpenAPI through all lowering passes.
        if ("oneOf".equals(composedKeyword) && nonNullMeta.size() > 1) {
            long preDedupStringCount = nonNullMeta.stream()
                    .filter(b -> b.isStringLike)
                    .count();
            long postDedupStringCount = deduped.stream()
                    .filter("std::string"::equals)
                    .count();
            List<CompositionBranchDescriptor> descBranches = descriptor != null
                    ? descriptor.getBranches() : null;
            boolean hasStringEnum = nonNullMeta.stream()
                    .anyMatch(b -> {
                        if (!b.isStringLike) return false;
                        // Descriptor path: consult supportedAssertions
                        if (descBranches != null && b.originalBranchIndex >= 0
                                && b.originalBranchIndex < descBranches.size()) {
                            return descBranches.get(b.originalBranchIndex)
                                    .getSupportedAssertions().contains("enum");
                        }
                        // Fallback: use ComposedBranch.isEnum (CodegenProperty)
                        return b.isEnum;
                    });
            if (preDedupStringCount > postDedupStringCount && hasStringEnum) {
                LOGGER.warn(
                        "oneOf string branches erase to std::string; "
                                + "emitting boost::json::value to avoid false exclusive-union fidelity");
                return "boost::json::value";
            }
        }

        // Rule 7: Single branch after dedup (including oneOf alias chains that
        // resolve to the same underlying C++ type without enum/open-string mix).
        if (deduped.size() == 1) {
            return deduped.get(0);
        }

        // Rule 8: Emit std::variant<Branches...>
        List<String> variantBranches = new ArrayList<>(deduped);
        // Re-append null for any null-containing composition not consumed
        // by Rule 1 ([T, null] -> optional<T>). Rule 1 always returns early,
        // so every null surviving to this point must be restored.
        boolean hasNull = branchTypes.stream().anyMatch("std::nullptr_t"::equals);
        boolean nullsAlreadyPreserved = variantBranches.stream().anyMatch(
                v -> v.contains("std::nullptr_t"));
        if (hasNull && !nullsAlreadyPreserved) {
            variantBranches.add("std::nullptr_t");
        }
        return "std::variant<" + String.join(", ", variantBranches) + ">";
    }

    /** Convenience overload for callers that only have C++ type strings. */
    private String lowerComposedTypesFromCppTypes(List<String> branchTypes, String composedKeyword) {
        List<ComposedBranch> branches = new ArrayList<>();
        for (String t : branchTypes) {
            boolean isString = "std::string".equals(t);
            branches.add(new ComposedBranch(t, false, isString, -1));
        }
        return lowerComposedTypes(branches, composedKeyword, null);
    }

    /**
     * Resolves a type name transitively through the resolvedAliasTypes map.
     * For example, if ModelIdsResponses → std::string and ModelIdsShared → std::string,
     * then resolveThroughAliases("ModelIdsResponses") returns "std::string".
     * <p>
     * Cycles are prevented via a visited set. Returns the typeName unmodified
     * when no alias resolution applies.
     */
    private String resolveThroughAliases(String typeName) {
        if (typeName == null) {
            return null;
        }
        Set<String> visited = new HashSet<>();
        String current = typeName;
        int maxDepth = 20;
        for (int depth = 0; depth < maxDepth; depth++) {
            String resolved = resolvedAliasTypes.get(current);
            if (resolved == null || resolved.equals(current)) {
                break;
            }
            if (!visited.add(current)) {
                break;  // cycle detected
            }
            current = resolved;
        }
        return current;
    }

    /**
     * Detects whether a schema is a null union (anyOf/oneOf with [T, null] or [null, T])
     * that should lower to std::optional&lt;T&gt;. Returns the lowered type string,
     * or null if the schema is not a simple null union.
     */
    private String detectNullUnion(Schema schema, String className) {
        // Use raw List and cast explicitly because Schema is unparameterized.
        List anyOfRaw = schema.getAnyOf();
        List oneOfRaw = schema.getOneOf();
        List<Schema> branches = null;
        if (anyOfRaw != null && !anyOfRaw.isEmpty()) {
            branches = anyOfRaw;
        } else if (oneOfRaw != null && !oneOfRaw.isEmpty()) {
            branches = oneOfRaw;
        }
        if (branches == null) {
            return null;
        }
        if (branches.size() != 2) {
            return null;
        }

        // Find the non-null branch using ModelUtils for correct null-type detection
        // (handles both OAS 3.0 nullable and OAS 3.1 type: "null")
        Schema nonNullBranch = null;
        for (Object brObj : branches) {
            Schema branch = (Schema) brObj;
            if (!ModelUtils.isNullType(branch)) {
                nonNullBranch = branch;
            }
        }
        if (nonNullBranch == null) {
            return null; // Both branches are null
        }
        // Verify exactly one null branch exists
        long nullBranchCount = 0;
        for (Object brObj : branches) {
            if (ModelUtils.isNullType((Schema) brObj)) nullBranchCount++;
        }
        if (nullBranchCount != 1) {
            return null;
        }

        // Resolve the non-null branch type. For $ref schemas, resolve to model name.
        String nonNullType;
        if (nonNullBranch.get$ref() != null) {
            nonNullType = ModelUtils.getSimpleRef(nonNullBranch.get$ref());
        } else {
            nonNullType = getTypeDeclaration(nonNullBranch);
        }

        // Avoid self-referencing optional (optional of the model itself)
        if (nonNullType.equals(className)) {
            return "boost::json::value";
        }

        return "std::optional<" + nonNullType + ">";
    }

    /**
     * Recursively strips {@code std::shared_ptr<X>} wrappers from a type string.
     * <ul>
     *   <li>{@code std::shared_ptr<Foo>} → {@code Foo}</li>
     *   <li>{@code std::vector<std::shared_ptr<Foo>>} → {@code std::vector<Foo>}</li>
     *   <li>{@code std::map<std::string, std::shared_ptr<Foo>>} → {@code std::map<std::string, Foo>}</li>
     *   <li>{@code std::string} → {@code std::string} (unchanged)</li>
     * </ul>
     */
    private static String stripSharedPtr(String type) {
        if (type == null) {
            return null;
        }
        // Direct std::shared_ptr<X> wrapper — extract inner type and recurse.
        if (type.startsWith("std::shared_ptr<") && type.endsWith(">")) {
            return stripSharedPtr(type.substring(16, type.length() - 1));
        }
        // Check for template arguments (contains '<' and '>').
        int firstLt = type.indexOf('<');
        int lastGt = type.lastIndexOf('>');
        if (firstLt > 0 && lastGt > firstLt) {
            // Split arguments at commas at depth 0 (not inside nested angle brackets).
            String prefix = type.substring(0, firstLt);
            String argsSection = type.substring(firstLt + 1, lastGt);
            List<String> args = splitTemplateArgs(argsSection);
            for (int i = 0; i < args.size(); i++) {
                args.set(i, stripSharedPtr(args.get(i).trim()));
            }
            return prefix + "<" + String.join(", ", args) + ">";
        }
        return type;
    }

    /**
     * Splits a comma-separated template argument list, respecting nested angle brackets.
     * {@code "std::string, std::shared_ptr<Foo>"} → {@code ["std::string", "std::shared_ptr<Foo>"]}
     */
    private static List<String> splitTemplateArgs(String args) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                result.add(args.substring(start, i));
                start = i + 1;
            }
        }
        result.add(args.substring(start));
        return result;
    }

    /**
     * Extracts the inner type from a std::optional<T> type declaration, correctly
     * handling nested angle brackets.
     * <ul>
     *   <li>{@code std::optional<std::string>} → {@code std::string}</li>
     *   <li>{@code std::optional<std::vector<int>>} → {@code std::vector<int>}</li>
     *   <li>{@code std::optional<MyModel>} → {@code MyModel}</li>
     *   <li>{@code std::string} → {@code null}</li>
     * </ul>
     *
     * @return the inner type, or null if the input does not start with "std::optional<"
     */
    private static String extractOptionalInnerType(String type) {
        if (type == null || !type.startsWith("std::optional<")) {
            return null;
        }
        // Strip prefix "std::optional<" (14 chars) and find matching '>'
        int depth = 0;
        int start = 14; // length of "std::optional<"
        for (int i = start; i < type.length(); i++) {
            char c = type.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                if (depth == 0) {
                    return type.substring(start, i);
                }
                depth--;
            }
        }
        return null;
    }

    /**
     * Camelize the method name of the getter and setter, but keep underscores at the front
     *
     * @param name string to be camelized
     * @return Camelized string
     */
    @Override
    public String getterAndSetterCapitalize(String name) {
        if (name == null || name.length() == 0) {
            return name;
        }

        name = toVarName(name);

        if (name.startsWith("_")) {
            return "_" + camelize(name);
        }

        return camelize(name);
    }

    @Override
    public void processOpts() {
        super.processOpts();
        packageName = additionalProperties.getOrDefault(
                CodegenConstants.PACKAGE_NAME, DEFAULT_PACKAGE_NAME).toString();
        if (StringUtils.isBlank(packageName)) {
            throw new IllegalArgumentException("packageName must not be blank");
        }
        additionalProperties.put(CodegenConstants.PACKAGE_NAME, packageName);
        additionalProperties.put("modelNamespaceDeclarations", modelPackage.split("\\."));
        additionalProperties.put("modelNamespace", modelPackage.replaceAll("\\.", "::"));
        additionalProperties.put("apiNamespaceDeclarations", apiPackage.split("\\."));
        additionalProperties.put("apiNamespace", apiPackage.replaceAll("\\.", "::"));

        // Phase 2: format assertion policy.
        // Currently only "annotation" is supported: format meta-data is passed
        // through to destination types but does NOT participate in branch
        // validation. "strict" mode (format assertions count toward branch
        // match decisions) is deferred — it requires implementing format
        // validators (email, date-time, uri, etc.) which are in the scope
        // checklist as deferred items.
        if (additionalProperties.containsKey("formatAssertionPolicy")) {
            String policy = additionalProperties.get("formatAssertionPolicy").toString().trim().toLowerCase(Locale.ROOT);
            if (FORMAT_ASSERTION_POLICY_STRICT.equals(policy)) {
                formatAssertionPolicy = FORMAT_ASSERTION_POLICY_STRICT;
            } else {
                formatAssertionPolicy = FORMAT_ASSERTION_POLICY_ANNOTATION;
            }
        }
        additionalProperties.put("formatAssertionPolicy", formatAssertionPolicy);

        // Phase 9: SSE schema interpretation mode.
        // Accepted values: "representation" (default, case-insensitive),
        // "jsonEventData" (case-insensitive). Rejects unknown values with a
        // logged warning and falls through to the default.
        if (additionalProperties.containsKey("sseSchemaMode")) {
            String raw = additionalProperties.get("sseSchemaMode").toString().trim();
            if (raw.equalsIgnoreCase(SSE_SCHEMA_MODE_JSON_EVENT_DATA)) {
                sseSchemaMode = SSE_SCHEMA_MODE_JSON_EVENT_DATA;
            } else if (raw.equalsIgnoreCase(SSE_SCHEMA_MODE_REPRESENTATION)) {
                sseSchemaMode = SSE_SCHEMA_MODE_REPRESENTATION;
            } else {
                LOGGER.warn("sseSchemaMode: unknown value '{}'; falling back to '{}'",
                        raw, SSE_SCHEMA_MODE_REPRESENTATION);
            }
        }
        additionalProperties.put("sseSchemaMode", sseSchemaMode);
    }

    /**
     * Location to write model files. You can use the modelPackage() as defined
     * when the class is instantiated
     */
    @Override
    public String modelFileFolder() {
        return (outputFolder + "/model").replace("/", File.separator);
    }

    /**
     * Location to write api files. You can use the apiPackage() as defined when
     * the class is instantiated
     */
    @Override
    public String apiFileFolder() {
        return (outputFolder + "/api").replace("/", File.separator);
    }

    @Override
    public String toModelImport(String name) {
        if (importMapping.containsKey(name)) {
            return importMapping.get(name);
        } else {
            return "#include \"" + name + ".h\"";
        }
    }

    @Override
    public CodegenModel fromModel(String name, Schema model) {
        // Phase 5 (Flat Synthetic): When a schema has allOf, replace it with
        // a brand-new synthetic schema carrying ALL intersected properties
        // and ALL unioned required.  allOf = null on the synthetic, meaning
        // the model has no C++ parent.  This is the "Flat" approach: every
        // property is owned storage; nothing is inherited.
        //
        // Every original allOf is removed — the synthetic schema stands alone.
        // super.fromModel sees a plain object (or scalar) and generates
        // members for every property directly.
        Schema modelArg = model;
        if (model != null && model.getAllOf() != null && !model.getAllOf().isEmpty()) {
            AllOfIntersection intersection = allOfIntersections.get(
                    toModelName(name));
            if (intersection != null) {
                // Check for unsatisfiable required properties / scalar conflicts
                if (!intersection.isSatisfiable()) {
                    throw new AllOfRequiredUnsatisfiableException(
                            name, intersection.getUnsatisfiableReason());
                }

                Schema synthetic = buildSyntheticAllOfSchema(
                        name, intersection);
                // Copy top-level attributes from original model
                if (model.getDiscriminator() != null) {
                    synthetic.setDiscriminator(model.getDiscriminator());
                }
                if (Boolean.TRUE.equals(model.getNullable())) {
                    synthetic.setNullable(true);
                }
                if (model.getDescription() != null) {
                    synthetic.setDescription(model.getDescription());
                }
                if (model.getFormat() != null && intersection.getRootScalarType() != null) {
                    synthetic.setFormat(model.getFormat());
                }
                // Propagate optional-impossible property tags.
                // All optional-impossible properties get x-cpp-reject-if-present
                // via Phase 5b, which generates a reject-when-present decode
                // block. The template still emits the getter/setter and member
                // (non-empty shell) alongside the reject diagnostic.
                if (!intersection.getOptionalImpossibleProperties().isEmpty()) {
                    Map<String, Object> ext = synthetic.getExtensions();
                    if (ext == null) {
                        ext = new LinkedHashMap<>();
                        synthetic.setExtensions(ext);
                    }
                    ext.put("x-cpp-optional-impossible-properties",
                            new ArrayList<>(intersection.getOptionalImpossibleProperties()));
                }
                // Flat: allOf = null so super.fromModel sees no parent
                synthetic.setAllOf(null);
                modelArg = synthetic;
            }
        }

        // Pre-check: The OpenAPI 3.1 parser converts anyOf [T, null] into
        // {type: T, nullable: true} or {$ref: X, nullable: true}, consuming
        // the anyOf list.  Detect these nullable schemas and produce the
        // correct std::optional<T> type.
        //
        // For $ref schemas (normalised anyOf/oneOf [T, null] where T was a
        // $ref), getTypeDeclaration resolves the target and returns the
        // correct C++ type.  For arrays, getTypeDeclaration returns the
        // container type (e.g. std::vector<...>) without optional wrapping,
        // so we wrap it here.  Inline object schemas (type=object, no $ref)
        // are full class models — they stay out of the alias precomputation
        // because getTypeDeclaration would return the raw OAS type name
        // "object" instead of the model name.  They are handled separately
        // below via variant model registration.
        boolean isNullableSchema = model != null
            && Boolean.TRUE.equals(model.getNullable())
            && (model.get$ref() != null
                || (model.getType() != null && !"object".equals(model.getType())));
        String preComputedNullUnionType = null;
        if (isNullableSchema) {
            // Resolve the type to its C++ type and wrap in std::optional
            String innerType = getTypeDeclaration(model);
            // getTypeDeclaration already returns std::optional<T> for nullable.
            // Use it directly if it starts with std::optional<.
            if (innerType.startsWith("std::optional<")) {
                preComputedNullUnionType = innerType;
            } else {
                preComputedNullUnionType = "std::optional<" + innerType + ">";
            }
        } else if (model != null) {
            // Also try the anyOf/oneOf path for cases where the parser
            // preserved the composed schema structure.
            preComputedNullUnionType = detectNullUnion(model, name);
        }

        CodegenModel codegenModel = super.fromModel(name, modelArg);
        if (codegenModel == null) {
            return null;
        }

        // Post-check: Apply the pre-computed null union type if the default
        // pipeline consumed the composed schemas.
        if (preComputedNullUnionType != null) {
            codegenModel.dataType = preComputedNullUnionType;
            codegenModel.vendorExtensions.put("x-cpp-type", preComputedNullUnionType);
            codegenModel.vendorExtensions.put("x-cpp-composed-keyword",
                model.getAnyOf() != null ? "anyOf" : "oneOf");
            codegenModel.vendorExtensions.put("x-cpp-is-alias", true);
            codegenModel.vendorExtensions.put("x-cpp-is-optional", true);
            // Force a model header/source so Gate A inventory and $ref users get
            // `using NullableString = std::optional<std::string>;`. DefaultCodegen
            // marks plain nullable primitives as isAlias and skips file emission.
            codegenModel.isAlias = false;
            resolvedAliasTypes.put(name, preComputedNullUnionType);
            variantModels.add(name);
        }

        // Post-check: Inline nullable object schemas (type=object, nullable=true,
        // no $ref) are full class models with properties — they cannot use the
        // alias path. Register them as variant models so $ref references use value
        // semantics (std::shared_ptr<NullableObject> → NullableObject) and tag
        // the model as optional for correct null-value representation.
        if (model != null && model.get$ref() == null
                && "object".equals(model.getType())
                && Boolean.TRUE.equals(model.getNullable())) {
            variantModels.add(name);
            codegenModel.vendorExtensions.put("x-cpp-is-optional", true);
        }

        Set<String> oldImports = codegenModel.imports;
        codegenModel.imports = new HashSet<>();
        for (String imp : oldImports) {
            String newImp = toModelImport(imp);
            if (!newImp.isEmpty()) {
                codegenModel.imports.add(newImp);
            }
        }
        // Every model header declares vector conversion helpers.
        codegenModel.imports.add("#include <vector>");

        // Fixed-const properties: OAS 3.1 `const`, single-value `enum`, or optional
        // vendor extension `x-stainless-const`. Portable path is OAS `const` / single enum —
        // vendor extensions are never required for correct encode/decode.
        if (codegenModel.vars != null) {
            Map<String, Schema> allProps = new LinkedHashMap<>();
            if (model.getProperties() != null) {
                allProps.putAll(model.getProperties());
            }
            if (model.getAllOf() != null && openAPI != null) {
                for (Object parentObj : model.getAllOf()) {
                    if (parentObj instanceof Schema) {
                        Schema parentSchema = ModelUtils.getReferencedSchema(openAPI, (Schema) parentObj);
                        if (parentSchema != null && parentSchema.getProperties() != null) {
                            allProps.putAll(parentSchema.getProperties());
                        }
                    }
                }
            }
            for (CodegenProperty var : codegenModel.vars) {
                Object rawProp = allProps.get(var.baseName);
                if (!(rawProp instanceof Schema)) {
                    continue;
                }
                Schema varSchema = (Schema) rawProp;
                boolean hasOasConst = varSchema.getConst() != null;
                boolean hasSingleValueEnum = varSchema.getEnum() != null
                        && varSchema.getEnum().size() == 1;
                boolean hasStainlessConst = varSchema.getExtensions() != null
                        && Boolean.TRUE.equals(varSchema.getExtensions().get("x-stainless-const"));
                if (!(hasOasConst || hasSingleValueEnum || hasStainlessConst)) {
                    continue;
                }
                String constRawValue = null;
                if (varSchema.getConst() != null) {
                    constRawValue = varSchema.getConst().toString();
                } else if (varSchema.getEnum() != null && !varSchema.getEnum().isEmpty()) {
                    constRawValue = varSchema.getEnum().get(0).toString();
                }
                if (constRawValue == null && var.example != null) {
                    constRawValue = var.example;
                }
                if (constRawValue == null) {
                    constRawValue = "std::string".equals(var.dataType) ? "" : "0";
                }
                String inlineValue;
                boolean isStringConst = "std::string".equals(var.dataType)
                        || "std::optional<std::string>".equals(var.dataType)
                        || (var.isString && !var.isInteger && !var.isLong && !var.isNumber
                        && !var.isBoolean);
                if ("std::optional<std::string>".equals(var.dataType)) {
                    inlineValue = "std::optional<std::string>{\"" + constRawValue + "\"}";
                } else if (isStringConst || "std::string".equals(var.dataType)) {
                    inlineValue = "\"" + constRawValue + "\"";
                } else {
                    inlineValue = constRawValue;
                }
                // Neutral OAS-first flag used by templates.
                var.vendorExtensions.put("x-cpp-const", true);
                var.vendorExtensions.put("x-cpp-const-value", constRawValue);
                var.vendorExtensions.put("x-cpp-const-inline-value", inlineValue);
                // Mustache is truthy on key presence — only set when string-typed.
                if (isStringConst || "std::string".equals(var.dataType)
                        || "std::optional<std::string>".equals(var.dataType)) {
                    var.vendorExtensions.put("x-cpp-const-is-string", true);
                } else if (var.isBoolean || "bool".equals(var.dataType)
                        || "std::optional<bool>".equals(var.dataType)) {
                    var.vendorExtensions.put("x-cpp-const-is-boolean", true);
                }
                // Keep stainless keys as aliases so older template forks still work.
                var.vendorExtensions.put("x-stainless-const", true);
                var.vendorExtensions.put("x-stainless-const-value", constRawValue);
                var.vendorExtensions.put("x-stainless-const-inline-value", inlineValue);
            }
        }

        addContainerPropertyNames(codegenModel.vars);
        return codegenModel;
    }

    @Override
    public CodegenParameter fromParameter(Parameter parameter, Set<String> imports) {
        CodegenParameter codegenParameter = super.fromParameter(parameter, imports);
        if (!codegenParameter.isQueryParam) {
            return codegenParameter;
        }

        if (!codegenParameter.required) {
            codegenParameter.vendorExtensions.put(X_CODEGEN_IS_OPTIONAL_QUERY_PARAMETER, true);
        }
        if (!codegenParameter.isArray && !codegenParameter.isMap) {
            return codegenParameter;
        }

        // OAS 3 query parameters default to form/explode=true. DefaultCodegen
        // currently represents an omitted style as CSV, so normalize it here.
        boolean usesExplodedFormStyle = !Boolean.FALSE.equals(parameter.getExplode())
                && (parameter.getStyle() == null || parameter.getStyle() == Parameter.StyleEnum.FORM);
        if (codegenParameter.isMap) {
            if (parameter.getStyle() == Parameter.StyleEnum.DEEPOBJECT) {
                codegenParameter.vendorExtensions.put(X_CODEGEN_QUERY_MAP_DEEP_OBJECT, true);
            } else if (usesExplodedFormStyle) {
                codegenParameter.vendorExtensions.put(X_CODEGEN_QUERY_MAP_EXPLODED, true);
            } else {
                codegenParameter.vendorExtensions.put(
                        X_CODEGEN_QUERY_COLLECTION_DELIMITER,
                        queryCollectionDelimiter(parameter.getStyle()));
            }
            return codegenParameter;
        }

        boolean isMulti = codegenParameter.isCollectionFormatMulti || usesExplodedFormStyle;
        if (isMulti) {
            codegenParameter.isCollectionFormatMulti = true;
            codegenParameter.collectionFormat = "multi";
            codegenParameter.vendorExtensions.put(X_CODEGEN_QUERY_COLLECTION_MULTI, true);
            return codegenParameter;
        }

        String collectionDelimiter;
        switch (codegenParameter.collectionFormat) {
            case "csv":
                collectionDelimiter = ",";
                break;
            case "ssv":
                collectionDelimiter = "%20";
                break;
            case "tsv":
                collectionDelimiter = "%09";
                break;
            case "pipes":
                collectionDelimiter = "%7C";
                break;
            default:
                throw new IllegalArgumentException(
                        "Unsupported query collection format: " + codegenParameter.collectionFormat);
        }
        codegenParameter.vendorExtensions.put(
                X_CODEGEN_QUERY_COLLECTION_DELIMITER, collectionDelimiter);
        return codegenParameter;
    }

    private String queryCollectionDelimiter(Parameter.StyleEnum style) {
        if (style == Parameter.StyleEnum.SPACEDELIMITED) {
            return "%20";
        }
        if (style == Parameter.StyleEnum.PIPEDELIMITED) {
            return "%7C";
        }
        return ",";
    }

    private void addContainerPropertyNames(List<CodegenProperty> properties) {
        for (CodegenProperty property : properties) {
            CodegenProperty item = property.items;
            while (item != null) {
                item.vendorExtensions.put("x-container-property-name", property.name);
                item = item.items;
            }
        }
    }

    @Override
    public String toModelFilename(String name) {
        return toModelName(name);
    }

    @Override
    public String toApiFilename(String name) {
        return toApiName(name);
    }

    @SuppressWarnings("unchecked")
    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap objs, List<ModelMap> allModels) {
        Map<String, Object> operations = (Map<String, Object>) objs.get("operations");
        List<CodegenOperation> operationList = (List<CodegenOperation>) operations.get("operation");
        List<CodegenOperation> newOpList = new ArrayList<>();

        for (CodegenOperation op : operationList) {
            addApiResponseMetadata(op);
            addResponseUnionMetadata(op);
            String path = op.path;

            String[] items = path.split("/", -1);
            String resourceNameCamelCase = "";
            for (String item : items) {
                if (item.length() > 1) {
                    if (item.matches("^\\{(.*)\\}$")) {
                        String tmpResourceName = item.substring(1, item.length() - 1);
                        resourceNameCamelCase += Character.toUpperCase(tmpResourceName.charAt(0)) + tmpResourceName.substring(1);
                    } else {
                        resourceNameCamelCase += Character.toUpperCase(item.charAt(0)) + item.substring(1);
                    }
                } else if (item.length() == 1) {
                    resourceNameCamelCase += Character.toUpperCase(item.charAt(0));
                }
            }
            op.path = path.replaceFirst("/$", "");

            op.vendorExtensions.put("x-codegen-resource-name", resourceNameCamelCase);

            boolean foundInNewList = false;
            for (CodegenOperation op1 : newOpList) {
                if (!foundInNewList) {
                    if (op1.path.equals(op.path)) {
                        foundInNewList = true;
                        final String X_CODEGEN_OTHER_METHODS = "x-codegen-other-methods";
                        List<CodegenOperation> currentOtherMethodList = (List<CodegenOperation>) op1.vendorExtensions.get(X_CODEGEN_OTHER_METHODS);
                        if (currentOtherMethodList == null) {
                            currentOtherMethodList = new ArrayList<>();
                        }
                        op.operationIdCamelCase = op1.operationIdCamelCase;
                        currentOtherMethodList.add(op);
                        op1.vendorExtensions.put(X_CODEGEN_OTHER_METHODS, currentOtherMethodList);
                    }
                }
            }
            if (!foundInNewList) {
                newOpList.add(op);
            }
        }
        operations.put("operation", newOpList);
        return objs;
    }

    private void addApiResponseMetadata(CodegenOperation operation) {
        boolean hasDefaultResponse = false;
        for (CodegenResponse response : operation.responses) {
            response.vendorExtensions.put("x-codegen-return-compatible",
                    Objects.equals(operation.returnType, response.dataType));
            response.vendorExtensions.put(X_CODEGEN_RESPONSE_IS_ONE_OF,
                    isOneOfResponse(response));
            response.vendorExtensions.put(X_CODEGEN_EMPTY_BODY_TOLERANT,
                    response.isMap || response.isFreeFormObject || response.isAnyType);
            if (response.isRange()) {
                response.vendorExtensions.put(
                        X_CODEGEN_RESPONSE_RANGE, response.code.substring(0, 1));
            }

            if (response.isDefault) {
                hasDefaultResponse = true;
                response.vendorExtensions.put(X_CODEGEN_DEFAULT_RESPONSE_IS_RETURN_COMPATIBLE,
                        operation.returnType != null && Objects.equals(operation.returnType, response.dataType));
            }

            // When the response type is a CompositionBranchValue model (has
            // duplicate C++ branch types), the API must use the model's free
            // function fromJsonValue_{classname} for descriptor-guided branch
            // selection instead of the generic variant converter.
            if (response.dataType != null) {
                String unwrapped = stripSharedPtr(response.dataType);
                if (hasDuplicateTypesModels.contains(unwrapped)) {
                    response.vendorExtensions.put("x-cpp-use-model-from-json-value", true);
                }
            }
        }
        operation.vendorExtensions.put(X_CODEGEN_HAS_DEFAULT_RESPONSE, hasDefaultResponse);

        // Detect text/event-stream produces for SSE streaming responses.
        // Phase 9: sseSchemaMode controls whether the response schema
        // describes the media representation (strict/default) or each JSON
        // event data payload (jsonEventData). The x-sse-event-data-schema
        // vendor extension on an operation can override the global mode.
        //
        // Mode split:
        //   representation (default): the WHATWG framer delivers raw event
        //     data strings. No JSON conversion is applied. The return type
        //     is std::vector<std::string>.
        //   jsonEventData: each event data payload is parsed as JSON against
        //     the response schema. The return type is std::vector<EventType>
        //     with generated fromJsonValue_ converters.
        //
        // Dual-content operations always keep the normal JSON return type
        // for the application/json path. A dedicated {operationId}Stream
        // method is always emitted. In representation mode the stream method
        // returns std::vector<std::string>; in jsonEventData mode it returns
        // std::vector<EventType>.
        //
        // The WHATWG framer (SseEventFramer, in HttpClientImpl) is always
        // independent from JSON conversion — it operates on raw bytes and
        // fires string data payloads. JSON conversion is applied only in
        // jsonEventData mode at the template (callback) level.
        if (operation.produces != null && !operation.produces.isEmpty()) {
            boolean hasEventStream = false;
            boolean hasJsonStream = false;
            for (Map<String, String> produce : operation.produces) {
                String mediaType = produce.get("mediaType");
                if ("text/event-stream".equalsIgnoreCase(mediaType)) {
                    hasEventStream = true;
                } else if (mediaType != null && mediaType.contains("json")) {
                    hasJsonStream = true;
                }
            }
            boolean isPureSse = hasEventStream && !hasJsonStream;
            boolean isDualContent = hasEventStream && hasJsonStream;
            operation.vendorExtensions.put("x-codegen-streaming-response", isPureSse);
            // Determine whether to apply typed event-data decoding.
            // jsonEventData mode or per-operation x-sse-event-data-schema
            // opt-in triggers typed JSON-per-data conversion.
            boolean useJsonEventData = SSE_SCHEMA_MODE_JSON_EVENT_DATA.equals(sseSchemaMode)
                    || Boolean.TRUE.equals(
                        operation.vendorExtensions.get(X_SSE_EVENT_DATA_SCHEMA));
            // Set the representation-mode flag so templates can emit the
            // correct return type and callback body (raw push_back vs
            // appendParsedEvent with JSON converter).
            if (!useJsonEventData) {
                operation.vendorExtensions.put("x-codegen-sse-representation-mode", true);
            }
            // For pure SSE ops, flag all 2xx responses as streaming and
            // set the stripped element type (without shared_ptr) for use in
            // the event vector element and converter name.
            // For dual-content ops, mark SSE responses (different datatype from returnType)
            // as streaming so the stream method template can identify them.
            // Also mark each response with x-codegen-return-compatible so the normal
            // method template can skip responses whose dataType doesn't match the
            // operation return type (avoids type mismatch in deserializedResponse).
            for (CodegenResponse response : operation.responses) {
                    if (isPureSse) {
                    response.vendorExtensions.put("x-codegen-streaming-response", true);
                    if (useJsonEventData) {
                        // Typed event-data mode: emit oneOf metadata for JSON conversion.
                        if (isOneOfResponse(response)
                                || isOneOfMediaType(response, "text/event-stream")) {
                            operation.vendorExtensions.put(X_CODEGEN_STREAM_IS_ONE_OF, true);
                            operation.vendorExtensions.put("x-codegen-sse-event-data-is-oneof", true);
                        }
                        if (response.dataType != null) {
                            String eventDataType = stripSharedPtr(response.dataType);
                            // Only set element type for model types (uppercase first char).
                            if (!eventDataType.startsWith("std::") && !eventDataType.startsWith("boost::")
                                    && Character.isUpperCase(eventDataType.charAt(0))) {
                                response.vendorExtensions.put("x-codegen-stream-element-type",
                                        eventDataType);
                                operation.vendorExtensions.put("x-codegen-stream-element-type",
                                        eventDataType);
                                response.vendorExtensions.put("x-codegen-sse-event-data-type",
                                        eventDataType);
                                operation.vendorExtensions.put("x-codegen-sse-event-data-type",
                                        eventDataType);
                            }
                        }
                    }
                } else if (isDualContent && response.is2xx && response.dataType != null
                        && !response.dataType.equals(operation.returnType)) {
                    response.vendorExtensions.put("x-codegen-streaming-response", true);
                    if (!useJsonEventData) {
                        response.vendorExtensions.put("x-codegen-sse-representation-mode", true);
                    }
                    if (response.dataType != null) {
                        String streamElementType = stripSharedPtr(response.dataType);
                        response.vendorExtensions.put("x-codegen-stream-element-type",
                                streamElementType);
                        if (useJsonEventData) {
                            response.vendorExtensions.put("x-codegen-sse-event-data-type",
                                    streamElementType);
                        }
                    }
                }
            }
            // If a pure SSE operation has no response schema (no data type
            // on any 2xx response), returnType will be null and the
            // mustache template would produce std::vector<void>, which
            // is invalid C++. Clear the streaming flag so the normal
            // non-streaming void path is used instead.
            if (isPureSse && operation.returnType == null) {
                operation.vendorExtensions.put("x-codegen-streaming-response", false);
                for (CodegenResponse r : operation.responses) {
                    r.vendorExtensions.put("x-codegen-streaming-response", false);
                }
            }
            // Dual-content: generate stream method
            // Only emit the stream method if we can resolve a concrete SSE
            // element type from the response content. Without it, the template
            // would produce an invalid std::vector<> with an empty parameter.
            if (isDualContent) {
                // Resolve SSE response type from the response content media-type map.
                // Specs may expose a single 200 with both application/json and
                // text/event-stream. Look for text/event-stream in any 2xx response.
                String sseReturnType = null;
                String sseBaseModelName = null;
                for (CodegenResponse response : operation.responses) {
                    if (!response.is2xx || response.getContent() == null) continue;
                    CodegenMediaType sseMediaType = response.getContent().get("text/event-stream");
                    if (sseMediaType != null && sseMediaType.getSchema() != null) {
                        CodegenProperty sseSchema = sseMediaType.getSchema();
                        String rawType = sseSchema.dataType;
                        if (rawType != null) {
                            sseReturnType = rawType;
                            // Derive a valid C++ identifier for the fromJsonValue_ converter.
                            // Strip std::shared_ptr<X> wrapper down to just X.
                            sseBaseModelName = stripSharedPtr(rawType);
                            if (useJsonEventData && isOneOfSchema(sseSchema)) {
                                operation.vendorExtensions.put(X_CODEGEN_DUAL_STREAM_IS_ONE_OF, true);
                                operation.vendorExtensions.put("x-codegen-dual-sse-event-data-is-oneof", true);
                            }
                            break;
                        }
                    }
                }
                // Fallback: use response dataType (works for split-status fixtures)
                if (sseReturnType == null) {
                    for (CodegenResponse response : operation.responses) {
                        if (response.is2xx && response.dataType != null
                                && !response.dataType.equals(operation.returnType)) {
                            sseReturnType = response.dataType;
                            sseBaseModelName = stripSharedPtr(response.dataType);
                            break;
                        }
                    }
                }
                if (sseReturnType == null) {
                    // Final fallback: first 2xx response
                    for (CodegenResponse response : operation.responses) {
                        if (response.is2xx && response.dataType != null) {
                            sseReturnType = response.dataType;
                            sseBaseModelName = stripSharedPtr(response.dataType);
                            break;
                        }
                    }
                }
                if (sseReturnType != null && sseBaseModelName != null) {
                    if (useJsonEventData && isOneOfType(sseReturnType)) {
                        operation.vendorExtensions.put(X_CODEGEN_DUAL_STREAM_IS_ONE_OF, true);
                        operation.vendorExtensions.put("x-codegen-dual-sse-event-data-is-oneof", true);
                    }
                    operation.vendorExtensions.put("x-codegen-dual-content", true);
                    // Full C++ type for the vector element (may contain std::shared_ptr<...>)
                    operation.vendorExtensions.put("x-codegen-dual-stream-return-type", sseReturnType);
                    // Stripped base name (valid C++ identifier) for fromJsonValue_ converter
                    operation.vendorExtensions.put("x-codegen-dual-stream-base-name", sseBaseModelName);
                    // Stripped element type for event conversion and the vector element
                    // (same as base name since both strip shared_ptr, but semantically distinct)
                    String dualStreamElementType = stripSharedPtr(sseReturnType);
                    operation.vendorExtensions.put("x-codegen-dual-stream-element-type", dualStreamElementType);
                    if (useJsonEventData) {
                        operation.vendorExtensions.put("x-codegen-dual-sse-event-data-type", dualStreamElementType);
                    }
                    // Also propagate to each response so the template can access it
                    // from within the {{#responses}} context scope.
                    for (CodegenResponse response : operation.responses) {
                        response.vendorExtensions.put("x-codegen-dual-stream-return-type", sseReturnType);
                        response.vendorExtensions.put("x-codegen-dual-stream-base-name", sseBaseModelName);
                        response.vendorExtensions.put("x-codegen-dual-stream-element-type", dualStreamElementType);
                        if (useJsonEventData) {
                            response.vendorExtensions.put("x-codegen-dual-sse-event-data-type", dualStreamElementType);
                        }
                    }
                }
            }
        }
    }

    /**
     * Detects operations with heterogeneous successful response shapes and tags
     * them for response-union generation. A heterogeneous operation has multiple
     * 2xx responses with different body types, or a mix of body/no-body responses.
     *
     * Sets on the operation:
     *   x-codegen-response-union: the generated union struct name
     * Sets on each response used in the union:
     *   x-codegen-response-union: the union struct name (same as operation-level)
     *   x-codegen-response-union-body-type: the variant alternative body type
     *     (e.g., "std::shared_ptr<FullResource>" or "std::monostate").
     *     Duplicate C++ body types are wrapped in
     *     StatusTaggedValue<boost::beast::http::status(N), T>.
     *
     * Single-shape operations (one success type) are left unchanged so the
     * existing simple-signature path is used.
     */
    private void addResponseUnionMetadata(CodegenOperation operation) {
        // Collect union-eligible responses: exact 2xx, range 2xx, or default
        // responses with a body type.  At least two distinct body shapes are
        // required for union generation.
        List<CodegenResponse> unionEligible = new ArrayList<>();
        for (CodegenResponse response : operation.responses) {
            boolean isSuccessWithBody = response.is2xx
                    || (response.isDefault && response.dataType != null);
            if (isSuccessWithBody) {
                unionEligible.add(response);
            }
        }
        if (unionEligible.size() < 2) {
            return;
        }

        // Detect whether eligible responses have distinct body shapes.
        // "Distinct" means different dataType, or mixed body/no-body.
        boolean hasMixedShapes = false;
        String firstDataType = unionEligible.get(0).dataType;
        for (int idx = 1; idx < unionEligible.size(); ++idx) {
            if (!Objects.equals(firstDataType, unionEligible.get(idx).dataType)) {
                hasMixedShapes = true;
                break;
            }
        }
        if (!hasMixedShapes) {
            boolean hasBody = false;
            boolean hasNoBody = false;
            for (CodegenResponse r : unionEligible) {
                if (r.dataType != null) {
                    hasBody = true;
                } else {
                    hasNoBody = true;
                }
            }
            if (hasBody && hasNoBody) {
                hasMixedShapes = true;
            }
        }
        if (!hasMixedShapes) {
            return;
        }

        // Build the union struct name: capitalize the operationId + "Response"
        String operationId = operation.operationIdCamelCase != null
                ? operation.operationIdCamelCase
                : operation.operationId;
        if (operationId == null || operationId.isEmpty()) {
            return;
        }
        String unionName = Character.toUpperCase(operationId.charAt(0))
                + operationId.substring(1) + "Response";

        operation.vendorExtensions.put(X_CODEGEN_RESPONSE_UNION, unionName);

        // Detect duplicate raw body types and build StatusTaggedValue wrappers.
        // Key = raw C++ type string, value = list of responses using it.
        Map<String, List<CodegenResponse>> rawTypeToResponses = new LinkedHashMap<>();
        for (CodegenResponse response : unionEligible) {
            String rawType = response.dataType != null
                    ? response.dataType : "std::monostate";
            rawTypeToResponses.computeIfAbsent(rawType,
                    k -> new ArrayList<>()).add(response);
        }

        // Assign the final body type to each response.
        for (CodegenResponse response : unionEligible) {
            // Propagate union name to per-response scope so templates can
            // access x-codegen-response-union directly without parent lookup.
            response.vendorExtensions.put(X_CODEGEN_RESPONSE_UNION, unionName);

            String rawType = response.dataType != null
                    ? response.dataType : "std::monostate";
            List<CodegenResponse> sharingResponses = rawTypeToResponses.get(rawType);
            String finalBodyType;
            if (sharingResponses != null && sharingResponses.size() > 1) {
                // Two or more statuses share the same C++ body type.
                // Wrap in StatusTaggedValue<status(N), T> to preserve
                // distinct status identity in the variant.
                String statusCodeStr = response.code;
                int statusCodeInt;
                try {
                    statusCodeInt = Integer.parseInt(
                            statusCodeStr.replaceAll("[^0-9]", ""));
                } catch (NumberFormatException e) {
                    // Range or default code; use 0 as placeholder.
                    statusCodeInt = 0;
                }
                finalBodyType = "StatusTaggedValue<boost::beast::http::status("
                        + statusCodeInt + "), " + rawType + ">";
            } else {
                finalBodyType = rawType;
            }
            response.vendorExtensions.put(
                    X_CODEGEN_RESPONSE_UNION_BODY_TYPE, finalBodyType);
        }

    }

    private boolean isOneOfResponse(CodegenResponse response) {
        if (response.getContent() != null) {
            for (Map.Entry<String, CodegenMediaType> contentEntry : response.getContent().entrySet()) {
                String mediaType = contentEntry.getKey();
                CodegenMediaType codegenMediaType = contentEntry.getValue();
                if (mediaType != null && mediaType.toLowerCase(Locale.ROOT).contains("json")
                        && codegenMediaType != null && isOneOfSchema(codegenMediaType.getSchema())) {
                    return true;
                }
            }
        }
        return isOneOfType(response.dataType);
    }

    private boolean isOneOfMediaType(CodegenResponse response, String mediaType) {
        if (response.getContent() == null) {
            return false;
        }
        CodegenMediaType codegenMediaType = response.getContent().get(mediaType);
        return codegenMediaType != null && isOneOfSchema(codegenMediaType.getSchema());
    }

    private boolean isOneOfSchema(CodegenProperty schema) {
        return schema != null
                && (Boolean.TRUE.equals(schema.vendorExtensions.get("x-cpp-is-oneof"))
                || isOneOfType(schema.dataType));
    }

    private boolean isOneOfType(String dataType) {
        String unwrappedType = stripSharedPtr(dataType);
        return "oneOf".equals(composedKeywordsByModel.get(unwrappedType));
    }

    /**
     * Optional - type declaration. This is a String which is used by the
     * templates to instantiate your types. There is typically special handling
     * for different property types
     *
     * @return a string value used as the `dataType` field for model templates,
     * `returnType` for api templates
     */
    @Override
    public String getTypeDeclaration(Schema p) {
        // Handle inline oneOf/anyOf composed schemas (apply lowering rules directly)
        if (ModelUtils.isComposedSchema(p) && (p.getOneOf() != null || p.getAnyOf() != null)) {
            return lowerInlineComposedSchema(p);
        }

        String openAPIType = getSchemaType(p);

        if (ModelUtils.isArraySchema(p)) {
            // Use getItems() directly to handle both OpenAPI 3.0 and 3.1
            Schema inner = p.getItems();
            String arrayType;
            if (inner != null) {
                arrayType = getSchemaType(p) + "<" + getTypeDeclaration(inner) + ">";
            } else {
                arrayType = "std::vector<boost::json::value>";
            }
            // Nullable arrays must be wrapped in std::optional so null JSON
            // values are representable. The array branch returns before the
            // nullable fallback checks at the end of this method.
            if (ModelUtils.isNullable(p)) {
                return "std::optional<" + arrayType + ">";
            }
            return arrayType;
        } else if (ModelUtils.isMapSchema(p)) {
            Schema inner = ModelUtils.getAdditionalProperties(p);
            String innerType = inner == null ? "boost::json::value" : getTypeDeclaration(inner);
            String mapType = getSchemaType(p) + "<std::string, " + innerType + ">";
            // Nullable maps must be wrapped in std::optional so null JSON
            // values are representable. The map branch returns before the
            // nullable fallback checks at the end of this method.
            if (ModelUtils.isNullable(p)) {
                return "std::optional<" + mapType + ">";
            }
            return mapType;
        } else if (ModelUtils.isByteArraySchema(p)) {
            return "std::string";
        } else if (ModelUtils.isStringSchema(p)
                || ModelUtils.isDateSchema(p)
                || ModelUtils.isDateTimeSchema(p) || ModelUtils.isFileSchema(p)
                || languageSpecificPrimitives.contains(openAPIType)
                || typeMapping.containsKey(openAPIType)
                || typeMapping.values().contains(openAPIType)) {
            // Resolve through type mapping for scalar allOf: composed schemas
            // return OAS raw types (e.g. "string") or mapped types (e.g.
            // "std::string") depending on branch resolution path.
            // Re-map if the value is already in the type mapping values.
            String resolved = typeMapping.containsKey(openAPIType)
                    ? typeMapping.get(openAPIType)
                    : toModelName(openAPIType);
            // OAS 3.0 nullable: true → std::optional<T>
            if (ModelUtils.isNullable(p)) {
                return "std::optional<" + resolved + ">";
            }
            return resolved;
        } else if (ModelUtils.isNullType(p)) {
            // Handle OpenAPI 3.1 null type
            return "std::nullptr_t";
        } else if (ModelUtils.isAnyType(p) || ModelUtils.isFreeFormObject(p, openAPI)) {
            return "boost::json::value";
        }

        // OAS 3.0 nullable: true → std::optional<T>
        if (ModelUtils.isNullable(p)) {
            return "std::optional<" + openAPIType + ">";
        }

        // Variant models use value semantics (no shared_ptr wrapping)
        if (variantModels.contains(openAPIType)) {
            return openAPIType;
        }

        // Fallback: wrap in shared_ptr for all other model refs.
        // NOTE (Phase 1 scope): "shared_ptr only for cycles" is a planned follow-up.
        // Determining cycle safety requires circular-reference analysis (setCircularReferences)
        // which runs too late in the pipeline for getTypeDeclaration. Variant models (oneOf/anyOf)
        // are treated as value types via variantModels. For regular object refs, we conservatively
        // use shared_ptr — this will be narrowed to cycle-only in a later phase.
        return "std::shared_ptr<" + openAPIType + ">";
    }

    /**
     * Resolves an inline oneOf/anyOf schema to its lowered C++ type by computing
     * branch types and applying the same ordered lowering rules as model-level types.
     */
    private String lowerInlineComposedSchema(Schema p) {
        String composedKeyword;
        List<Schema> children;
        if (p.getOneOf() != null) {
            children = p.getOneOf();
            composedKeyword = "oneOf";
        } else {
            children = p.getAnyOf();
            composedKeyword = "anyOf";
        }

        List<ComposedBranch> composedBranches = new ArrayList<>();
        for (Schema child : children) {
            // Compute the branch type using the full type declaration pipeline
            // but strip shared_ptr for variant members (value semantics).
            String childType = stripSharedPtr(getTypeDeclaration(child));
            // Resolve $ref targets that are aliased to primitive types at the
            // declaration point, before resolvedAliasTypes is available (it is
            // populated during postProcessModels, which runs later). This handles
            // inline schemas like CreateAssistantRequest_model = oneOf [string,
            // $ref AssistantSupportedModels] where the target is anyOf [string,
            // string-enum] → std::string, collapsing to just std::string.
            Schema resolvedChild = child;
            if (!childType.startsWith("std::") && !childType.startsWith("boost::")
                    && !childType.startsWith("std::shared_ptr<")) {
                Schema resolvedTarget = child.get$ref() != null && openAPI != null
                        ? ModelUtils.getReferencedSchema(openAPI, child) : null;
                if (resolvedTarget != null) {
                    resolvedChild = resolvedTarget;
                    String resolved = getTypeDeclaration(resolvedTarget);
                    String stripped = stripSharedPtr(resolved);
                    if (!stripped.equals(childType)) {
                        childType = stripped;
                    }
                }
            }
            boolean isEnum = resolvedChild.getEnum() != null && !resolvedChild.getEnum().isEmpty();
            boolean isStringLike = ModelUtils.isStringSchema(resolvedChild)
                    || "std::string".equals(childType);
            composedBranches.add(new ComposedBranch(childType, isEnum, isStringLike, -1));
        }

        // Deduplication is deferred to lowerComposedTypes (step 5) so that
        // oneOf semantics can be preserved when duplicate types would otherwise
        // cause silent single-branch collapse.
        return lowerComposedTypes(composedBranches, composedKeyword, null);
    }

    @Override
    public CodegenProperty fromProperty(String name, Schema p, boolean required,
                                        boolean schemaIsFromAdditionalProperties) {
        CodegenProperty prop = super.fromProperty(name, p, required, schemaIsFromAdditionalProperties);
        if (prop == null || p == null) {
            return prop;
        }
        // Tag inline composed properties so templates can honor oneOf vs anyOf
        // decode rules (exactly-one vs first-match) instead of always using
        // the generic JsonValueConverter exactly-one path.
        if (p.getOneOf() != null && !p.getOneOf().isEmpty()) {
            prop.vendorExtensions.put("x-cpp-composed-keyword", "oneOf");
            prop.vendorExtensions.put("x-cpp-is-oneof", true);
        } else if (p.getAnyOf() != null && !p.getAnyOf().isEmpty()) {
            prop.vendorExtensions.put("x-cpp-composed-keyword", "anyOf");
            prop.vendorExtensions.put("x-cpp-is-anyof", true);
        }
        return prop;
    }

    @Override
    public String toDefaultValue(Schema p) {
        if (ModelUtils.isStringSchema(p)) {
            if (p.getDefault() != null) {
                return "\"" + p.getDefault().toString() + "\"";
            } else {
                return "\"\"";
            }
        } else if (ModelUtils.isBooleanSchema(p)) {
            if (p.getDefault() != null) {
                return p.getDefault().toString();
            } else {
                return "false";
            }
        } else if (ModelUtils.isDateSchema(p)) {
            if (p.getDefault() != null) {
                return "\"" + p.getDefault().toString() + "\"";
            } else {
                return "\"\"";
            }
        } else if (ModelUtils.isDateTimeSchema(p)) {
            if (p.getDefault() != null) {
                return "\"" + p.getDefault().toString() + "\"";
            } else {
                return "\"\"";
            }
        } else if (ModelUtils.isNumberSchema(p)) {
            if (ModelUtils.isFloatSchema(p)) { // float
                if (p.getDefault() != null) {
                    return p.getDefault().toString() + "f";
                } else {
                    return "0.0f";
                }
            } else { // double
                if (p.getDefault() != null) {
                    return p.getDefault().toString();
                } else {
                    return "0.0";
                }
            }
        } else if (ModelUtils.isIntegerSchema(p)) {
            if (ModelUtils.isLongSchema(p)) { // long
                if (p.getDefault() != null) {
                    return p.getDefault().toString() + "L";
                } else {
                    return "0L";
                }
            } else { // integer
                if (p.getDefault() != null) {
                    return p.getDefault().toString();
                } else {
                    return "0";
                }
            }
        } else if (ModelUtils.isByteArraySchema(p)) {
            if (p.getDefault() != null) {
                return "\"" + p.getDefault().toString() + "\"";
            } else {
                return "\"\"";
            }
        } else if (ModelUtils.isMapSchema(p)) {
            Schema inner = ModelUtils.getAdditionalProperties(p);
            String innerType = inner == null ? "boost::json::value" : getTypeDeclaration(inner);
            return "std::map<std::string, " + innerType + ">()";
        } else if (ModelUtils.isArraySchema(p)) {
            // Use getItems() directly to handle OpenAPI 3.1 JsonSchema
            Schema inner = p.getItems();
            String innerType = inner != null ? getTypeDeclaration(inner) : "boost::json::value";
            return "std::vector<" + innerType + ">()";
        } else if (!StringUtils.isEmpty(p.get$ref())) {
            String refName = toModelName(ModelUtils.getSimpleRef(p.get$ref()));
            if (variantModels.contains(refName)) {
                return refName + "()";
            }
            return "std::make_shared<" + refName + ">()";
        } else if (ModelUtils.isNullType(p)) {
            return "nullptr";
        } else if (ModelUtils.isAnyType(p) || ModelUtils.isFreeFormObject(p, openAPI)) {
            return "boost::json::value()";
        }

        return "nullptr";
    }
    
    @Override
    public String toDefaultValue(CodegenProperty codegenProperty, Schema schema) {
        if (codegenProperty != null) {
            if (codegenProperty.dataType != null && codegenProperty.dataType.startsWith("std::shared_ptr<")) {
                return "nullptr";
            }
            if ("boost::json::value".equals(codegenProperty.dataType)) {
                return "boost::json::value()";
            }
        }
        return super.toDefaultValue(codegenProperty, schema);
    }

    @Override
    public void setParameterEncodingValues(CodegenParameter codegenParameter, MediaType mediaType) {
        super.setParameterEncodingValues(codegenParameter, mediaType);
        // Detect Encoding Object headers that cannot be propagated to
        // multipart parts. When an Encoding Object specifies headers,
        // emit a diagnostic instead of silently dropping them.
        if (codegenParameter.isFormParam && mediaType != null
                && mediaType.getEncoding() != null) {
            io.swagger.v3.oas.models.media.Encoding encoding =
                    mediaType.getEncoding().get(codegenParameter.baseName);
            if (encoding != null && encoding.getHeaders() != null
                    && !encoding.getHeaders().isEmpty()) {
                LOGGER.warn("Encoding Object on form parameter '{}' specifies {} header(s) "
                        + "that are not propagated to the multipart part. "
                        + "Generated code uses only the contentType field. "
                        + "Header keys: {}",
                        codegenParameter.baseName,
                        encoding.getHeaders().size(),
                        encoding.getHeaders().keySet());
            }
        }
    }

    @Override
    public void postProcessParameter(CodegenParameter parameter) {
        super.postProcessParameter(parameter);

        boolean isPrimitiveType = parameter.isPrimitiveType == Boolean.TRUE;
        boolean isArray = parameter.isArray == Boolean.TRUE;
        boolean isMap = parameter.isMap == Boolean.TRUE;
        boolean isString = parameter.isString == Boolean.TRUE;
        parameter.vendorExtensions.put(X_CODEGEN_IS_RAW_BODY,
                isPrimitiveType || isString || parameter.isByteArray || parameter.isBinary
                        || "std::string".equals(parameter.dataType));

        if (!isPrimitiveType && !isArray && !isMap && !isString && !parameter.dataType.startsWith("std::shared_ptr")
                && !"boost::json::value".equals(parameter.dataType)
                && !"std::nullptr_t".equals(parameter.dataType)
                && !parameter.dataType.startsWith("std::variant<")
                && !parameter.dataType.startsWith("std::optional<")
                && !"std::monostate".equals(parameter.dataType)) {
            // Wrap non-primitive types in shared_ptr, unless:
            // - The type is a variant/optional model (value semantics)
            // - The type is a known variant model name from composed schemas
            if (!variantModels.contains(parameter.dataType)) {
                parameter.dataType = "std::shared_ptr<" + parameter.dataType + ">";
                parameter.defaultValue = "std::make_shared<" + parameter.dataType + ">()";
            }
        }

        // Post-hoc unwrap: if the type ended up as std::shared_ptr<VariantModel>,
        // strip the shared_ptr wrapper (value semantics for variant types).
        if (parameter.dataType != null && parameter.dataType.startsWith("std::shared_ptr<")
                && parameter.dataType.endsWith(">")) {
            String innerType = parameter.dataType.substring(16, parameter.dataType.length() - 1);
            if (variantModels.contains(innerType)) {
                parameter.dataType = innerType;
                parameter.defaultValue = null;
            }
        }

        // For form params, validate that encoding style/explode combinations
        // are representable in multipart/form-data. Only form-style is supported
        // for multipart (space-delimited, pipe-delimited, and deep-object styles
        // are not representable). Fail closed with a targeted diagnostic.
        if (parameter.isFormParam) {
            if (Boolean.TRUE.equals(parameter.isSpaceDelimited)) {
                throw new UnsupportedSchemaAssertionException(
                        parameter.baseName,
                        "encoding-style");
            }
            if (Boolean.TRUE.equals(parameter.isPipeDelimited)) {
                throw new UnsupportedSchemaAssertionException(
                        parameter.baseName,
                        "encoding-style");
            }
            if (Boolean.TRUE.equals(parameter.isDeepObject)) {
                throw new UnsupportedSchemaAssertionException(
                        parameter.baseName,
                        "encoding-style");
            }
        }

        // Tag variant form params for branch-aware multipart serialization.
        // When a form parameter's type is a variant, the template uses
        // addVariantFormParameter to dispatch binary branches as file parts
        // and object branches as JSON parts.
        // Only set for actual std::variant types, not for models that alias
        // to primitive types (e.g., VideoModel → std::string), which would
        // cause instantiation of addVariantFormParameter<std::string> and
        // an invalid std::visit call on a non-variant type.
        boolean isVariantParam = false;
        if (parameter.isFormParam && parameter.dataType != null) {
            if (parameter.dataType.startsWith("std::variant<")) {
                isVariantParam = true;
            } else if (variantModels.contains(parameter.dataType)) {
                String resolved = resolveThroughAliases(parameter.dataType);
                if (resolved != null && resolved.startsWith("std::variant<")) {
                    isVariantParam = true;
                }
            }
        }
        if (isVariantParam) {
            parameter.vendorExtensions.put("x-codegen-is-variant-form-param", true);
        }
    }

    /**
     * Optional - OpenAPI type conversion. This is used to map OpenAPI types in
     * a `Schema` into either language specific types via `typeMapping` or
     * into complex models if there is not a mapping.
     *
     * @return a string value of the type or complex model for this property
     */
    @Override
    public String getSchemaType(Schema p) {
        // Non-standard format (NOT core OAS vocabulary). Documented generator
        // convenience for corpora that use Unix-epoch integer timestamps.
        // Disable by not using format: unixtime in the source document.
        if (p != null && "unixtime".equals(p.getFormat())) {
            return "int64_t";
        }
        String openAPIType = super.getSchemaType(p);
        String type = null;
        String modelName;
        if (typeMapping.containsKey(openAPIType)) {
            type = typeMapping.get(openAPIType);
        } else {
            type = openAPIType;
        }

        modelName = toModelName(type);
        return modelName;
    }

    @Override
    public void updateCodegenPropertyEnum(CodegenProperty var) {
        // Remove prefix added by DefaultCodegen
        String originalDefaultValue = var.defaultValue;
        super.updateCodegenPropertyEnum(var);
        var.defaultValue = originalDefaultValue;
    }

    // ========================================================================
    // Wave-1: OAS 3.1 densified schema IR + validate_<id> thin dispatch emission
    // ------------------------------------------------------------------------
    // Emits (once per generation, not per model):
    //   model/schema_ir.generated.hpp        table declarations + schemaNodeFor()
    //   model/schema_ir.generated.cpp        densified SchemaNode/SchemaResource
    //   model/schema_validate.generated.cpp  thin validate_<id> dispatch (ADR D5)
    //
    // The IR is populated from the same assertion scan that fills validateParams
    // in preprocessOpenAPI (docs/cpp-boost-beast-oas31-wave1-slice-contract.md §6),
    // so this keyword subset and the hand templates stay in lockstep. Every numeric
    // keyword (minimum, maximum, exclusiveMinimum, exclusiveMaximum, multipleOf,
    // numeric enum/const) is emitted as its ORIGINAL lexeme string
    // (BigDecimal.toString() / constVal.toString()) so oas31::ExactNumber::parseLexeme
    // reconstructs the value exactly — never a rounded double rendering.
    // ========================================================================

    // JsonType bit positions must match oas31::JsonType in oas31_ir.hpp.
    private static final int JSONTYPE_BIT_NULL = 1 << 0;
    private static final int JSONTYPE_BIT_BOOLEAN = 1 << 1;
    private static final int JSONTYPE_BIT_NUMBER = 1 << 2;
    private static final int JSONTYPE_BIT_STRING = 1 << 3;
    private static final int JSONTYPE_BIT_ARRAY = 1 << 4;
    private static final int JSONTYPE_BIT_OBJECT = 1 << 5;
    private static final int JSONTYPE_BIT_INTEGER = 1 << 6; // schema-level only

    @Override
    public Map<String, Object> postProcessSupportingFileData(Map<String, Object> objs) {
        Map<String, Object> processed = super.postProcessSupportingFileData(objs);

        // Wave-2: snapshot the CURRENT (post-model-extraction) components map so
        // raw-schema densifiers can tell composed from plain-extracted targets and
        // rewrite $refs to the correct row form. InlineModelResolver may have
        // REPLACED an inline schema subtree (e.g. a `not` with properties) with
        // `{$ref: #/components/schemas/<name>}` and moved its content into a
        // component; densifying the CURRENT content of those components (or
        // resolving the ref to a plain-component row) recovers the semantics.
        irComponentComposed.clear();
        if (openAPI != null && openAPI.getComponents() != null
                && openAPI.getComponents().getSchemas() != null) {
            for (String name : openAPI.getComponents().getSchemas().keySet()) {
                Schema compSchema = openAPI.getComponents().getSchemas().get(name);
                if (compSchema == null) continue;
                boolean composed = (compSchema.getOneOf() != null && !compSchema.getOneOf().isEmpty())
                        || (compSchema.getAnyOf() != null && !compSchema.getAnyOf().isEmpty())
                        || (compSchema.getAllOf() != null && !compSchema.getAllOf().isEmpty());
                irComponentComposed.put(name, composed);
            }
        }

        List<IrNode> mainNodes = new ArrayList<>();
        for (CompositionDescriptor desc : compositionDescriptors.values()) {
            if (desc == null || desc.getBranches() == null) {
                continue;
            }
            for (CompositionBranchDescriptor branch : desc.getBranches()) {
                IrNode node = irNodeFromBranch(branch);
                if (node != null) {
                    mainNodes.add(node);
                }
            }
        }
        // Deterministic ordering by validate_<id> so output is stable across runs.
        mainNodes.sort(Comparator.comparing(n -> n.validatorId));

        // Wave-2: flatten ALL child nodes (not / properties / prefixItems /
        // items / additionalProperties-schema / applicator members /
        // unevaluated-schema) into EXTRA registry rows appended AFTER the main
        // validator-owning rows, so the main-node indices 0..M-1 stay stable
        // (existing generated-path gate + schemaNodeFor() unaffected). Child
        // rows have no validate_<id> dispatch; they are only referenced via
        // SchemaNode fields. BFS, deterministic order, identity-deduplicated
        // so a child reachable from several parents is materialised once.
        List<IrNode> extraNodes = new ArrayList<>();
        java.util.ArrayDeque<IrNode> queue = new java.util.ArrayDeque<>();
        java.util.Set<IrNode> visitedChildren = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<IrNode, Boolean>());

        // Wave-2: plain (non-composed) component rows. InlineModelResolver
        // EXTRACTS inline schema subtrees (a `not` with properties, a deeply
        // nested object) into components and replaces the ref-site with $ref.
        // Densifying those extracted components as registry rows (appended
        // AFTER main+extra, so 0..M-1 stay stable) lets every ref-to-plain
        // component, and every mutated raw child, resolve to a real row—the
        // content is never lost. Rows are emitted with no validate_<> dispatch.
        List<IrNode> componentRows = new ArrayList<>();
        if (openAPI != null && openAPI.getComponents() != null
                && openAPI.getComponents().getSchemas() != null) {
            java.util.List<String> names = new ArrayList<>(
                    openAPI.getComponents().getSchemas().keySet());
            java.util.Collections.sort(names);
            for (String name : names) {
                if (Boolean.TRUE.equals(irComponentComposed.get(name))) continue;
                Schema compSchema = openAPI.getComponents().getSchemas().get(name);
                if (compSchema == null) continue;
                IrNode row = irNodeFromRawSchema(compSchema,
                        toValidIdentifier(name) + "_component");
                if (row != null) componentRows.add(row);
            }
        }

        // Seed the flattening BFS from BOTH main nodes AND plain-component rows,
        // so structural children of extracted components (their properties /
        // items / applicators / ...) are materialised into the registry too.
        java.util.List<IrNode> seeds = new ArrayList<>(mainNodes);
        seeds.addAll(componentRows);
        for (IrNode seed : seeds) {
            for (IrNode c : structuralChildren(seed)) {
                if (visitedChildren.add(c)) queue.add(c);
            }
        }
        while (!queue.isEmpty()) {
            IrNode c = queue.poll();
            extraNodes.add(c);
            for (IrNode g : structuralChildren(c)) {
                if (visitedChildren.add(g)) queue.add(g);
            }
        }
        visitedChildren.addAll(componentRows);
        visitedChildren.addAll(mainNodes);

        List<IrNode> allRows = new ArrayList<>(mainNodes);
        allRows.addAll(extraNodes);
        allRows.addAll(componentRows);

        // Identity-keyed index map over the COMBINED registry rows.
        java.util.Map<IrNode, Integer> indexOf = new java.util.IdentityHashMap<>();
        for (int i = 0; i < allRows.size(); i++) {
            indexOf.put(allRows.get(i), i);
        }
        // Combined validatorId -> index for $ref target resolution (K-29 +
        // Wave-2 plain-component rows). Main + extra + component validatorIds.
        java.util.Map<String, Integer> idIndex = new java.util.HashMap<>();
        for (int i = 0; i < allRows.size(); i++) {
            String vid = allRows.get(i).validatorId;
            if (vid != null) idIndex.putIfAbsent(vid, i);
        }

        // Resolve all child indices + `$ref` target indices now that all rows are
        // numbered. Unresolvable external refs stay -1 => emitted as inert nodes
        // (honest K-29 partial: they RUN, their verdict is measured, never
        // silently passed as if resolved).
        for (IrNode n : allRows) {
            if (n.notChild != null) {
                Integer idx = indexOf.get(n.notChild);
                if (idx != null) n.notSchemaIndex = idx;
            }
            if (n.additionalSchemaChild != null) {
                Integer idx = indexOf.get(n.additionalSchemaChild);
                if (idx != null) n.additionalSchemaIndex = idx;
            }
            if (n.itemsChild != null) {
                Integer idx = indexOf.get(n.itemsChild);
                if (idx != null) n.itemsIndex = idx;
            }
            if (n.unevaluatedSchemaChild != null) {
                Integer idx = indexOf.get(n.unevaluatedSchemaChild);
                if (idx != null) n.unevaluatedSchemaIndex = idx;
            }
            for (IrNode.PropertySchema pb : n.properties) {
                if (pb.child != null) {
                    Integer idx = indexOf.get(pb.child);
                    if (idx != null) pb.index = idx;
                }
            }
            for (int i = 0; i < n.prefixItems.size(); i++) {
                Integer idx = indexOf.get(n.prefixItems.get(i));
                if (idx != null) n.prefixItemIndices.add(idx);
                else n.prefixItemIndices.add(-1);
            }
            for (int i = 0; i < n.applicatorChildren.size(); i++) {
                Integer idx = indexOf.get(n.applicatorChildren.get(i));
                if (idx != null) n.applicatorChildIndices.add(idx);
                else n.applicatorChildIndices.add(-1);
            }
            if (n.isRef && n.refTargetId != null) {
                Integer idx = idIndex.get(n.refTargetId);
                if (idx != null) {
                    n.refTargetIndex = idx;
                } else if (n.selfRef) {
                    n.refTargetIndex = indexOf.get(n).intValue();
                }
            }
        }

        processed.put("oas31SchemaIrHeader", buildSchemaIrHeader(allRows));
        processed.put("oas31SchemaIrSource", buildSchemaIrSource(allRows, mainNodes.size()));
        processed.put("oas31SchemaIrValidateSource", buildSchemaIrValidateSource(mainNodes));
        return processed;
    }

    /** Ordered structural children of a node (BFS source, no duplicates). */
    private static java.util.List<IrNode> structuralChildren(IrNode n) {
        java.util.List<IrNode> out = new ArrayList<>();
        if (n.notChild != null) out.add(n.notChild);
        if (n.additionalSchemaChild != null) out.add(n.additionalSchemaChild);
        if (n.itemsChild != null) out.add(n.itemsChild);
        if (n.unevaluatedSchemaChild != null) out.add(n.unevaluatedSchemaChild);
        out.addAll(n.prefixItems);
        out.addAll(n.applicatorChildren);
        for (IrNode.PropertySchema pb : n.properties) {
            if (pb.child != null) out.add(pb.child);
        }
        return out;
    }

    /** A single densified SchemaNode to emit, from one composition branch. */
    private static final class IrNode {
        String validatorId;
        String resolvedName;
        int typeFlags = 0;
        boolean hasType = false;
        BooleanValueKind booleanValue = BooleanValueKind.NOT_BOOLEAN;
        String minimum = null;
        String maximum = null;
        String exclusiveMinimum = null;
        String exclusiveMaximum = null;
        String multipleOf = null;
        java.util.List<String> enumNumbers = new ArrayList<>();
        java.util.List<String> enumStrings = new ArrayList<>();
        java.util.List<String> enumBooleans = new ArrayList<>();
        String constNumber = null;
        String constString = null;
        Boolean constBool = null;
        boolean hasConst = false;

        // -- Wave-1 deep-equality / not / uniqueItems / $ref (K-30/K-34/K-22/K-01/K-29) --
        String constJson = null;        // serialized JSON literal for the FULL const value
        String enumJson = null;         // serialized JSON array literal for ALL enum members
        boolean hasUniqueItems = false;
        boolean uniqueItemsSeen = false;  // keyword PRESENT (true OR false) — false is a no-op
        IrNode  notChild = null;        // child node for the `not` subschema (K-01)
        int     notSchemaIndex = -1;    // resolved combined-registry index of notChild
        boolean isRef = false;          // this node is a $ref to another component (K-29)
        String  refTargetId = null;     // validatorId of the ref target
        int     refTargetIndex = -1;    // resolved combined-registry index; -1 => unresolved (inline)

        // -- Wave-2 object structural (FROZEN §10) --
        static final class PropertySchema {
            String name;
            IrNode  child;
            int     index = -1;   // resolved registry row of child
        }
        boolean hasObjectSchema = false;
        java.util.List<PropertySchema>  properties = new ArrayList<>();
        java.util.List<String>          required = new ArrayList<>();
        String            additionalPropertiesKind = "absent";  // absent|allowed|reject|schema
        IrNode            additionalSchemaChild = null;
        int               additionalSchemaIndex = -1;
        String            minPropertiesLexeme = null;   boolean minPropertiesPresent = false;
        String            maxPropertiesLexeme = null;   boolean maxPropertiesPresent = false;

        // -- Wave-2 array structural (FROZEN §10) --
        java.util.List<IrNode>  prefixItems = new ArrayList<>();
        java.util.List<Integer> prefixItemIndices = new ArrayList<>();
        IrNode                  itemsChild = null;
        int                     itemsIndex = -1;
        String                  minItemsLexeme = null;  boolean minItemsPresent = false;
        String                  maxItemsLexeme = null;  boolean maxItemsPresent = false;

        // -- Wave-2 applicator (allOf/anyOf/oneOf members of THIS schema) --
        String                   applicatorKind = null;   // "allOf"|"anyOf"|"oneOf"
        java.util.List<IrNode>   applicatorChildren = new ArrayList<>();
        java.util.List<Integer>  applicatorChildIndices = new ArrayList<>();

        // -- Wave-2 unevaluatedProperties --
        boolean unevaluatedPropertiesPresent = false;
        boolean unevaluatedPropertiesRejects = false;
        IrNode  unevaluatedSchemaChild = null;
        int     unevaluatedSchemaIndex = -1;
        boolean selfRef = false;   // $ref resolves to THIS node (self/root ref)

        /** Deterministic child-row id suffix counter (per node). */
        private int childCounter = 0;

        /** Build a deterministic child validatorId under this node. */
        String childId(String tag) {
            childCounter += 1;
            return validatorId + "_" + tag + childCounter;
        }
    }

    private enum BooleanValueKind {
        NOT_BOOLEAN, TRUE, FALSE
    }

    /** Builds an IR node from one branch's validateParams; null when nothing to emit. */
    private IrNode irNodeFromBranch(CompositionBranchDescriptor branch) {
        IrNode n = new IrNode();
        n.validatorId = branch.getValidatorId();
        n.resolvedName = branch.getResolvedSchemaName() != null
                ? branch.getResolvedSchemaName() : "schema";
        if (n.validatorId == null || n.validatorId.isEmpty()) {
            return null;
        }
        Map<String, Object> vp = branch.getValidateParams();
        if (vp == null) {
            vp = Collections.emptyMap();
        }

        // type / type-array -> typeFlags
        Object otype = vp.get("validation-type");
        if (otype != null) {
            n.hasType = true;
            if ("type-array".equals(otype)) {
                Object arr = vp.get("validation-type-array");
                if (arr instanceof java.util.List) {
                    for (Object t : (java.util.List<?>) arr) {
                        n.typeFlags |= jsonTypeBit(String.valueOf(t));
                    }
                }
            } else {
                n.typeFlags |= jsonTypeBit(String.valueOf(otype));
            }
        }

        // boolean value-schema
        Object obool = vp.get("validation-boolean-value");
        if (obool != null) {
            n.booleanValue = Boolean.TRUE.equals(obool)
                    ? BooleanValueKind.TRUE : BooleanValueKind.FALSE;
        }

        n.minimum = lexemeOf(vp.get("validation-min"));
        n.maximum = lexemeOf(vp.get("validation-max"));
        n.exclusiveMinimum = lexemeOf(vp.get("validation-exclusive-min"));
        n.exclusiveMaximum = lexemeOf(vp.get("validation-exclusive-max"));
        n.multipleOf = lexemeOf(vp.get("validation-multiple-of"));

        // enum (partitioned by predominant kind, mirroring the hand template)
        if (vp.containsKey("has-validation-enum")) {
            Object kind = vp.get("validation-enum-kind");
            Object vals = vp.get("validation-enum-values");
            if (vals instanceof java.util.List) {
                for (Object v : (java.util.List<?>) vals) {
                    String sv = String.valueOf(v); // already escaped for strings
                    if ("integer".equals(kind) || "number".equals(kind)) {
                        n.enumNumbers.add(sv);
                    } else if ("bool".equals(kind)) {
                        n.enumBooleans.add(sv);
                    } else {
                        n.enumStrings.add(sv);
                    }
                }
            }
        }

        // const (partitioned by kind)
        if (vp.containsKey("has-validation-const")) {
            String ctype = String.valueOf(vp.get("validation-const-type"));
            Object cval = vp.get("validation-const-value");
            if ("number".equals(ctype)) {
                n.constNumber = lexemeOf(cval);
            } else if ("boolean".equals(ctype)) {
                n.constBool = Boolean.valueOf(String.valueOf(cval));
            } else {
                n.constString = cval != null ? String.valueOf(cval) : null;
            }
        }

        // -- Wave-1 deep-equality stores (K-30/K-34) -------------------------
        // Const + enum are kept ALSO as full JSON so the engine can do EXACT deep
        // comparison across ALL JSON kinds (arrays / objects / mixed), never a
        // scalar shortcut. The raw swagger values were stashed into validateParams
        // by the assertion scan; we serialize them to JSON literals here.
        Object constRaw = vp.get("validation-const-raw");
        // Route NON-number consts through the deep JSON store (K-30). Number
        // consts stay on the exact scalar lexeme path (constNumber) so huge
        // values beyond uint64/double never round-trip through boost::json
        // (big-const 2^70 regression guard).
        if (constRaw != null && !(constRaw instanceof Number)) {
            n.hasConst = true;
            n.constJson = toJsonLiteral(constRaw);
        }
        Object enumRaw = vp.get("validation-enum-raw");
        if (enumRaw instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) enumRaw;
            if (!list.isEmpty()) {
                n.enumJson = toJsonArrayLiteral(list);
            } else {
                // enum: [] — a valid REJECT-ALL schema (no member can deep-equal
                // any instance). Emit the deep store with zero members so
                // hasEnumJson=true still materialises the node and the evaluator
                // rejects every instance (Wave-1 G14 close). Never left as "no
                // keyword" (which would be BLOCKED-at-emission).
                n.enumJson = "[]";
            }
            // Exact numeric members (lexeme-first) go in the scalar bucket so
            // huge numbers (beyond uint64/double) never lose precision through
            // boost::json; structural members are handled by the deep enumJson
            // store instead. Only genuine numbers enter enumNumbers, so the
            // emitted parseLexeme is never fed a non-numeric string.
            n.enumNumbers = new ArrayList<>();
            for (Object m : list) {
                if (m instanceof Number) {
                    n.enumNumbers.add(m.toString());
                } else if (m instanceof com.fasterxml.jackson.databind.JsonNode
                        && ((com.fasterxml.jackson.databind.JsonNode) m).isNumber()) {
                    n.enumNumbers.add(((com.fasterxml.jackson.databind.JsonNode) m).asText());
                }
            }
        }

        // -- K-22 uniqueItems flag (Wave-2: PRESENCE, any value; false is a no-op) --
        if (vp.containsKey("validation-unique-items")) {
            n.uniqueItemsSeen = true;
            n.hasUniqueItems = Boolean.TRUE.equals(vp.get("validation-unique-items"));
        }

        // -- K-01 `not` subschema (build a child node; index resolved later) --
        Object notSchemaObj = vp.get("validation-not-schema");
        if (notSchemaObj instanceof Schema) {
            n.notChild = irNodeFromRawSchema((Schema) notSchemaObj, n.validatorId + "_not");
        }

        // -- K-29 $ref --------------------------------------------------------
        Object refObj = vp.get("validation-ref");
        if (refObj != null) {
            n.isRef = true;
            n.refTargetId = refTargetIdOf(String.valueOf(refObj));
        }

        // ====================================================================
        // Wave-2 OBJECT / ARRAY / APPLICATOR / UNEVALUATED structural scan
        // (FROZEN §10). validateParams is the hand-restricted scan surface from
        // buildCompositionDescriptor; structural SSends densify every child
        // schema into its own registry row via irNodeFromRawSchema.
        // ====================================================================
        Object propsObj = vp.get("validation-properties");
        if (propsObj instanceof java.util.Map && !((java.util.Map<?, ?>) propsObj).isEmpty()) {
            n.hasObjectSchema = true;
            java.util.Map<?, ?> pm = (java.util.Map<?, ?>) propsObj;
            java.util.List<String> names = new ArrayList<>();
            for (Object k : pm.keySet()) names.add(String.valueOf(k));
            java.util.Collections.sort(names);   // deterministic emission order
            for (String name : names) {
                Object ps = pm.get(name);
                if (ps instanceof Schema) {
                    IrNode.PropertySchema pb = new IrNode.PropertySchema();
                    pb.name = name;
                    pb.child = irNodeFromRawSchema((Schema) ps, n.childId("prop"));
                    n.properties.add(pb);
                }
            }
        }
        Object reqObj = vp.get("validation-required");
        if (reqObj instanceof java.util.List) {
            for (Object r : (java.util.List<?>) reqObj) {
                n.required.add(String.valueOf(r));
            }
            if (!n.required.isEmpty()) n.hasObjectSchema = true;
        }
        String apKind = (String) vp.get("validation-additional-properties-kind");
        if (apKind != null && !"absent".equals(apKind)) {
            n.additionalPropertiesKind = apKind;
            n.hasObjectSchema = true;
            if ("schema".equals(apKind)) {
                Object s = vp.get("validation-additional-properties-schema");
                if (s instanceof Schema) {
                    n.additionalSchemaChild = irNodeFromRawSchema(
                            (Schema) s, n.childId("addprops"));
                }
            }
        }
        if (vp.containsKey("validation-min-properties")) {
            n.minPropertiesLexeme = lexemeOf(vp.get("validation-min-properties"));
            n.minPropertiesPresent = n.minPropertiesLexeme != null;
            n.hasObjectSchema = true;
        }
        if (vp.containsKey("validation-max-properties")) {
            n.maxPropertiesLexeme = lexemeOf(vp.get("validation-max-properties"));
            n.maxPropertiesPresent = n.maxPropertiesLexeme != null;
            n.hasObjectSchema = true;
        }
        Object piObj = vp.get("validation-prefix-items");
        if (piObj instanceof java.util.List) {
            for (Object s : (java.util.List<?>) piObj) {
                if (s instanceof Schema) {
                    n.prefixItems.add(irNodeFromRawSchema((Schema) s, n.childId("pi")));
                } else if (s instanceof Boolean) {
                    n.prefixItems.add(booleanValueSchema((Boolean) s, n.childId("pib")));
                }
            }
        }
        Object itemsObj = vp.get("validation-items");
        if (itemsObj instanceof Schema) {
            n.itemsChild = irNodeFromRawSchema((Schema) itemsObj, n.childId("items"));
        } else if (itemsObj instanceof Boolean) {
            n.itemsChild = booleanValueSchema((Boolean) itemsObj, n.childId("items"));
        }
        if (vp.containsKey("validation-min-items")) {
            n.minItemsLexeme = lexemeOf(vp.get("validation-min-items"));
            n.minItemsPresent = n.minItemsLexeme != null;
        }
        if (vp.containsKey("validation-max-items")) {
            n.maxItemsLexeme = lexemeOf(vp.get("validation-max-items"));
            n.maxItemsPresent = n.maxItemsLexeme != null;
        }
        String appKind = (String) vp.get("validation-applicator");
        Object appList = vp.get("validation-applicator-schemas");
        if (appKind != null && appList instanceof java.util.List) {
            n.applicatorKind = appKind;
            for (Object s : (java.util.List<?>) appList) {
                if (s instanceof Schema) {
                    n.applicatorChildren.add(
                            irNodeFromRawSchema((Schema) s, n.childId("app")));
                } else if (s instanceof Boolean) {
                    n.applicatorChildren.add(
                            booleanValueSchema((Boolean) s, n.childId("app")));
                }
            }
        }
        Object unevalObj = vp.get("validation-unevaluated-properties");
        if (unevalObj != null) {
            n.unevaluatedPropertiesPresent = true;
            if (unevalObj instanceof Schema) {
                Schema us = (Schema) unevalObj;
                Boolean bv = us.getBooleanSchemaValue();
                if (bv != null) {
                    n.unevaluatedPropertiesRejects = !Boolean.TRUE.equals(bv);
                } else {
                    n.unevaluatedSchemaChild =
                            irNodeFromRawSchema(us, n.childId("uneval"));
                }
            } else if (unevalObj instanceof Boolean) {
                n.unevaluatedPropertiesRejects = !Boolean.TRUE.equals(unevalObj);
            }
        }

        boolean hasKeyword = n.hasType
                || n.booleanValue != BooleanValueKind.NOT_BOOLEAN
                || n.minimum != null || n.maximum != null
                || n.exclusiveMinimum != null || n.exclusiveMaximum != null
                || n.multipleOf != null
                || !n.enumNumbers.isEmpty() || !n.enumStrings.isEmpty()
                || !n.enumBooleans.isEmpty()
                || n.constNumber != null || n.constString != null || n.constBool != null
                || n.constJson != null || n.enumJson != null
                || n.hasUniqueItems || n.uniqueItemsSeen
                || n.notChild != null || n.isRef
                || n.hasObjectSchema || n.required != null && !n.required.isEmpty()
                || "absent" != n.additionalPropertiesKind && !"absent".equals(n.additionalPropertiesKind)
                || n.minPropertiesPresent || n.maxPropertiesPresent
                || !n.prefixItems.isEmpty() || n.itemsChild != null
                || n.minItemsPresent || n.maxItemsPresent
                || n.applicatorKind != null
                || n.unevaluatedPropertiesPresent;
        return hasKeyword ? n : null;
    }

    /**
     * Builds an IR node directly from a raw Schema object. Used for `not`
     * subschemas and ALL Wave-2 structural children (properties / prefixItems /
     * items / additionalProperties-schema / applicators / unevaluated), which
     * the oneOf-branch lowering never visits. Densifies the FULL supported
     * keyword subset plus a one-level structural walk ($ref targets are
     * resolved post-numbering via refTargetId into the combined registry).
     */
    private IrNode irNodeFromRawSchema(Schema schema, String validatorId) {
        IrNode n = new IrNode();
        n.validatorId = validatorId;
        n.resolvedName = validatorId;
        if (schema == null) {
            return n;
        }
        if (System.getenv("OAS31_DEBUG") != null) {
            System.err.println("[irNodeFromRawSchema] " + validatorId + " type=" + schema.getType()
                    + " props=" + (schema.getProperties() == null ? 0 : schema.getProperties().size())
                    + " enum=" + (schema.getEnum() == null ? "null" : String.valueOf(schema.getEnum().size()))
                    + " $ref=" + schema.get$ref());
        }
        if (schema.get$ref() != null) {
            // Local ref: resolve against the combined registry later. Siblings
            // are still densified (2020-12: $ref and siblings BOTH apply).
            n.isRef = true;
            n.refTargetId = refTargetIdOf(schema.get$ref());
        }
        if (schema.getType() != null) {
            n.hasType = true;
            n.typeFlags |= jsonTypeBit(String.valueOf(schema.getType()));
        }
        if (schema.getTypes() != null && !schema.getTypes().isEmpty()) {
            n.hasType = true;
            for (Object t : schema.getTypes()) {
                n.typeFlags |= jsonTypeBit(String.valueOf(t));
            }
        }
        if (schema.getBooleanSchemaValue() != null) {
            n.booleanValue = Boolean.TRUE.equals(schema.getBooleanSchemaValue())
                    ? BooleanValueKind.TRUE : BooleanValueKind.FALSE;
        }
        if (schema.getConst() != null) {
            n.hasConst = true;
            n.constJson = toJsonLiteral(schema.getConst());
        }
        if (schema.getEnum() != null) {
            // An EMPTY enum (enum: []) is a valid reject-all schema.
            n.enumJson = toJsonArrayLiteral(schema.getEnum());
        }
        if (schema.getMinimum() != null) {
            n.minimum = String.valueOf(schema.getMinimum());
        }
        if (schema.getMaximum() != null) {
            n.maximum = String.valueOf(schema.getMaximum());
        }
        // Use the *Value* accessor (Number); getExclusiveMinimum() is a Boolean
        // presence marker in OAS 3.0 and not a numeric bound.
        if (schema.getExclusiveMinimumValue() != null) {
            n.exclusiveMinimum = String.valueOf(schema.getExclusiveMinimumValue());
        }
        if (schema.getExclusiveMaximumValue() != null) {
            n.exclusiveMaximum = String.valueOf(schema.getExclusiveMaximumValue());
        }
        if (schema.getMultipleOf() != null) {
            n.multipleOf = String.valueOf(schema.getMultipleOf());
        }
        if (schema.getUniqueItems() != null) {
            n.uniqueItemsSeen = true;
            n.hasUniqueItems = Boolean.TRUE.equals(schema.getUniqueItems());
        }
        if (schema.getNot() != null) {
            n.notChild = irNodeFromRawSchema(schema.getNot(), n.childId("not"));
        }

        // ---- Wave-2 object structural ----
        if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            n.hasObjectSchema = true;
            java.util.List<String> names = new ArrayList<>(schema.getProperties().keySet());
            java.util.Collections.sort(names);
            for (String name : names) {
                Schema ps = (Schema) schema.getProperties().get(name);
                if (ps == null) continue;
                IrNode.PropertySchema pb = new IrNode.PropertySchema();
                pb.name = name;
                pb.child = irNodeFromRawSchema(ps, n.childId("prop"));
                n.properties.add(pb);
            }
        }
        if (schema.getRequired() != null && !schema.getRequired().isEmpty()) {
            n.required.addAll(schema.getRequired());
            n.hasObjectSchema = true;
        }
        Object addProps = schema.getAdditionalProperties();
        if (addProps != null) {
            if (addProps instanceof Boolean) {
                n.additionalPropertiesKind =
                        Boolean.TRUE.equals(addProps) ? "allowed" : "reject";
                n.hasObjectSchema = true;
            } else if (addProps instanceof Schema) {
                Schema as = (Schema) addProps;
                Boolean bv = as.getBooleanSchemaValue();
                if (bv != null) {
                    n.additionalPropertiesKind =
                            Boolean.TRUE.equals(bv) ? "allowed" : "reject";
                    n.hasObjectSchema = true;
                } else if (as.getProperties() == null && as.getType() == null
                        && as.getEnum() == null && as.getItems() == null
                        && as.getPrefixItems() == null && as.getConst() == null
                        && as.getNot() == null && as.get$ref() == null) {
                    // additionalProperties: {} — unrestricted (allowed).
                    n.additionalPropertiesKind = "allowed";
                    n.hasObjectSchema = true;
                } else {
                    n.additionalPropertiesKind = "schema";
                    n.hasObjectSchema = true;
                    n.additionalSchemaChild =
                            irNodeFromRawSchema(as, n.childId("addprops"));
                }
            }
        }
        if (schema.getMinProperties() != null) {
            n.minPropertiesLexeme = String.valueOf(schema.getMinProperties());
            n.minPropertiesPresent = true;
            n.hasObjectSchema = true;
        } else if (countBoundLexemeOf(schema, "minProperties") != null) {
            n.minPropertiesLexeme = countBoundLexemeOf(schema, "minProperties");
            n.minPropertiesPresent = true;
            n.hasObjectSchema = true;
        }
        if (schema.getMaxProperties() != null) {
            n.maxPropertiesLexeme = String.valueOf(schema.getMaxProperties());
            n.maxPropertiesPresent = true;
            n.hasObjectSchema = true;
        } else if (countBoundLexemeOf(schema, "maxProperties") != null) {
            n.maxPropertiesLexeme = countBoundLexemeOf(schema, "maxProperties");
            n.maxPropertiesPresent = true;
            n.hasObjectSchema = true;
        }

        // ---- Wave-2 array structural ----
        if (schema.getPrefixItems() != null) {
            for (Object o : schema.getPrefixItems()) {
                Schema s = (Schema) o;
                if (s == null) continue;
                if (s.getBooleanSchemaValue() != null) {
                    n.prefixItems.add(booleanValueSchema(
                            s.getBooleanSchemaValue(), n.childId("pi")));
                } else {
                    n.prefixItems.add(irNodeFromRawSchema(s, n.childId("pi")));
                }
            }
        }
        if (schema.getItems() != null) {
            Schema its = schema.getItems();
            if (its.getBooleanSchemaValue() != null) {
                n.itemsChild = booleanValueSchema(
                        its.getBooleanSchemaValue(), n.childId("items"));
            } else {
                n.itemsChild = irNodeFromRawSchema(its, n.childId("items"));
            }
        }
        String mibl = countBoundLexemeOf(schema, "minItems");
        if (schema.getMinItems() != null) {
            n.minItemsLexeme = String.valueOf(schema.getMinItems());
            n.minItemsPresent = true;
        } else if (mibl != null) {
            n.minItemsLexeme = mibl;
            n.minItemsPresent = true;
        }
        String maxbl = countBoundLexemeOf(schema, "maxItems");
        if (schema.getMaxItems() != null) {
            n.maxItemsLexeme = String.valueOf(schema.getMaxItems());
            n.maxItemsPresent = true;
        } else if (maxbl != null) {
            n.maxItemsLexeme = maxbl;
            n.maxItemsPresent = true;
        }

        // ---- Wave-2 applicators (this schema's own oneOf/anyOf/allOf) ----
        if (applicatorOf(schema) != null) {
            n.applicatorKind = applicatorOf(schema);
            java.util.List<?> members = applicatorMembers(schema);
            for (Object mo : members) {
                Schema s = (Schema) mo;
                if (s == null) continue;
                if (s.getBooleanSchemaValue() != null) {
                    n.applicatorChildren.add(booleanValueSchema(
                            s.getBooleanSchemaValue(), n.childId("app")));
                } else {
                    n.applicatorChildren.add(irNodeFromRawSchema(s, n.childId("app")));
                }
            }
        }

        // ---- Wave-2 unevaluatedProperties ----
        if (schema.getUnevaluatedProperties() != null) {
            n.unevaluatedPropertiesPresent = true;
            Schema us = schema.getUnevaluatedProperties();
            Boolean bv = us.getBooleanSchemaValue();
            if (bv != null) {
                n.unevaluatedPropertiesRejects = !Boolean.TRUE.equals(bv);
            } else if (emptySchema(us)) {
                n.unevaluatedPropertiesRejects = false;
            } else {
                n.unevaluatedSchemaChild = irNodeFromRawSchema(us, n.childId("uneval"));
            }
        }
        return n;
    }

    /** The applicator keyword of a schema, or null when it has none. */
    private static String applicatorOf(Schema schema) {
        if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) return "oneOf";
        if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) return "anyOf";
        if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) return "allOf";
        return null;
    }

    /** The applicator member list for the schema's (single) applicator. */
    private static java.util.List<?> applicatorMembers(Schema schema) {
        if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) return schema.getOneOf();
        if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) return schema.getAnyOf();
        if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) return schema.getAllOf();
        return java.util.Collections.emptyList();
    }

    /** True when the schema carries no supported assertion (an empty {}). */
    private static boolean emptySchema(Schema schema) {
        return schema.getType() == null
                && (schema.getTypes() == null || schema.getTypes().isEmpty())
                && schema.getBooleanSchemaValue() == null
                && schema.getConst() == null
                && (schema.getEnum() == null || schema.getEnum().isEmpty())
                && schema.get$ref() == null
                && (schema.getProperties() == null || schema.getProperties().isEmpty())
                && (schema.getRequired() == null || schema.getRequired().isEmpty())
                && schema.getMinimum() == null && schema.getMaximum() == null
                && schema.getExclusiveMinimumValue() == null
                && schema.getExclusiveMaximumValue() == null
                && schema.getMultipleOf() == null
                && schema.getMinItems() == null && schema.getMaxItems() == null
                && schema.getMinProperties() == null && schema.getMaxProperties() == null
                && schema.getUniqueItems() == null
                && schema.getNot() == null
                && schema.getAdditionalProperties() == null
                && (schema.getPrefixItems() == null || schema.getPrefixItems().isEmpty())
                && schema.getItems() == null
                && (schema.getUnevaluatedProperties() == null
                    || schema.getUnevaluatedProperties().getBooleanSchemaValue() != null);
    }

    /** Builds a boolean value-schema node (OAS 3.1 true/false literal). */
    private IrNode booleanValueSchema(Boolean b, String validatorId) {
        IrNode n = new IrNode();
        n.validatorId = validatorId;
        n.resolvedName = validatorId;
        n.booleanValue = Boolean.TRUE.equals(b) ? BooleanValueKind.TRUE : BooleanValueKind.FALSE;
        return n;
    }

    /**
     * Row-id targeting for a $ref. Components that ARE composed resolve to
     * their oneOf/anyOf/allOf branch row (`<name>_branch_0`); plain (extracted)
     * components resolve to their densified `<name>_component` row; anything
     * else derives a name that will fail to resolve => the node stays inert and
     * the case is measured honestly (never silent, never fake-pass).
     */
    private String refTargetIdOf(String refStr) {
        String name = refSimpleName(refStr);
        boolean composed = Boolean.TRUE.equals(irComponentComposed.get(name));
        return toValidIdentifier(name) + (composed ? "_branch_0" : "_component");
    }

    /**
     * Extract a component name from a $ref when it is an internal component
     * reference; otherwise return a best-effort identifier-ish tail (which
     * simply won't resolve, leaving the node inert).
     *   "#/components/schemas/X" -> "X"
     *   "#/$defs/a"             -> "a"  (post-write: runner hoists to components)
     *   "http://example.com/b"  -> "b"  (inert unless hoisted)
     */
    private static String refSimpleName(String ref) {
        if (ref == null) return "";
        String r = ref.trim();
        if (r.startsWith("#/components/schemas/")) {
            return r.substring("#/components/schemas/".length());
        }
        if (r.startsWith("#/$defs/")) {
            // JSON-Schema %$defs% scope. The JSTS wrap hoists $defs into
            // components.schemas under the def name; when it does, this maps to
            // the hoisted component name so the local ref can resolve to a
            // densified component row. Unhoisted $defs stay inert (honest).
            return r.substring("#/$defs/".length());
        }
        int hash = r.indexOf('#');
        String base = hash >= 0 ? r.substring(0, hash) : r;
        int slash = base.lastIndexOf('/');
        String tail = slash >= 0 ? base.substring(slash + 1) : base;
        return tail.isEmpty() ? base : tail;
    }

    /** Serialize an arbitrary swagger value (const/enum) into a JSON literal. */
    private static String toJsonLiteral(Object o) {
        if (o == null) {
            return "null";
        }
        // swagger exposes complex const/enum members as Jackson JsonNode whose
        // toString() is ALREADY valid JSON (object/array/text/number/bool/null).
        // Emit it verbatim — never re-quote it.
        if (o instanceof com.fasterxml.jackson.databind.JsonNode) {
            return o.toString();
        }
        if (o instanceof String) {
            return jsonQuote((String) o);
        }
        if (o instanceof Boolean) {
            return Boolean.TRUE.equals(o) ? "true" : "false";
        }
        if (o instanceof Number) {
            return o.toString();
        }
        if (o instanceof java.util.Map<?, ?>) {
            java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(jsonQuote(String.valueOf(e.getKey()))).append(':')
                  .append(toJsonLiteral(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (o instanceof java.util.List<?>) {
            return toJsonArrayLiteral((java.util.List<?>) o);
        }
        if (o.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(o);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < len; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(toJsonLiteral(java.lang.reflect.Array.get(o, i)));
            }
            return sb.append(']').toString();
        }
        return jsonQuote(o.toString());
    }

    private static String toJsonArrayLiteral(java.util.List<?> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(toJsonLiteral(list.get(i)));
        }
        return sb.append(']').toString();
    }

    /** Minimal JSON string quote (escape \ " and control chars). */
    private static String jsonQuote(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.append('\"').toString();
    }

    /**
     * Retrieval/base URI of the single schema resource emitted this pass.
     * Derived from the emitter's model access: a user-supplied {@code oas31BaseUri}
     * option (the OAS 3.1 document retrieval URI) or a stable document-local urn
     * when the invocation carries no explicit base URI. Honest: external-file
     * resources cannot be emitted this pass, so there is exactly one resource.
     */
    private String documentBaseUri() {
        Object opt = additionalProperties().get("oas31BaseUri");
        if (opt != null && !String.valueOf(opt).isEmpty()) {
            return String.valueOf(opt);
        }
        return "urn:openapi-generator:cpp-boost-beast:wave1";
    }

    /**
     * Dialect URI for the single emitted schema resource, classed from the
     * document knobs ({@code jsonSchemaDialect} / OAS 3.1 pinning) that the
     * emitter can observe via {@link #openAPI}. Falls back to the OAS 3.1 base
     * alias when the document is unavailable or declares nothing recognizable.
     */
    private String documentDialectUri() {
        OasDialect d = resolveDocumentDialect(openAPI);
        switch (d) {
            case OAS_31:
                return OAS_31_DIALECT;
            case DRAFT_2020_12_REC:
                return DRAFT_2020_12;
            case UNRECOGNIZED:
                if (openAPI != null && openAPI.getJsonSchemaDialect() != null
                        && !openAPI.getJsonSchemaDialect().isEmpty()) {
                    return openAPI.getJsonSchemaDialect().trim();
                }
                return OAS_31_DIALECT_BASE_ALIAS;
            case UNSPECIFIED:
            default:
                return isOas31(openAPI) ? OAS_31_DIALECT : OAS_31_DIALECT_BASE_ALIAS;
        }
    }

    /** Original numeric lexeme, or null when absent (BigDecimal.toString()). */
    private static String lexemeOf(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isEmpty() ? null : s;
    }

    /** Maps an OAS 3.1 type name to an oas31::JsonType bit. */
    private static int jsonTypeBit(String type) {
        switch (type) {
            case "null": return JSONTYPE_BIT_NULL;
            case "boolean": return JSONTYPE_BIT_BOOLEAN;
            case "number": return JSONTYPE_BIT_NUMBER;
            case "string": return JSONTYPE_BIT_STRING;
            case "array": return JSONTYPE_BIT_ARRAY;
            case "object": return JSONTYPE_BIT_OBJECT;
            case "integer": return JSONTYPE_BIT_INTEGER;
            default: return 0;
        }
    }

    /** schema_ir.generated.hpp — declarations only (registry defined in .cpp). */
    private String buildSchemaIrHeader(java.util.List<IrNode> nodes) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by CppBoostBeastClientCodegen (Wave-1 densified schema IR).\n");
        sb.append("// Do not edit by hand. SchemaNode/SchemaResource layout frozen in oas31_ir.hpp.\n");
        sb.append("#ifndef OAS31_SCHEMA_IR_GENERATED_HPP_\n");
        sb.append("#define OAS31_SCHEMA_IR_GENERATED_HPP_\n\n");
        sb.append("#include \"oas31_ir.hpp\"\n");
        sb.append("#include <string>\n\n");
        sb.append("namespace oas31 {\n\n");
        sb.append("// Densified SchemaResourceRegistry for the Wave-1 vertical slice.\n");
        sb.append("// Numeric constraints carry ORIGINAL lexemes (ExactNumber::parseLexeme).\n");
        sb.append("SchemaResourceRegistry const& schemaRegistry();\n\n");
        sb.append("// Resolve a validate_<id> identifier to its SchemaIndex (kNoSchema if unknown).\n");
        sb.append("SchemaIndex schemaNodeFor(std::string const& id);\n\n");
        sb.append("} // namespace oas31\n\n");
        sb.append("#endif // OAS31_SCHEMA_IR_GENERATED_HPP_\n");
        return sb.toString();
    }

    /** schema_ir.generated.cpp — densified rows + schemaNodeFor() map. */
    private String buildSchemaIrSource(java.util.List<IrNode> nodes, int mainNodeCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by CppBoostBeastClientCodegen (Wave-1 densified schema IR).\n");
        sb.append("// Numeric constraints are exact lexemes parsed by oas31::ExactNumber::parseLexeme.\n");
        sb.append("#include \"schema_ir.generated.hpp\"\n");
        sb.append("#include \"oas31_exact_number.hpp\"\n");
        sb.append("#include <string>\n\n");
        sb.append("namespace oas31 {\n");
        sb.append("namespace {\n\n");
        sb.append("[[maybe_unused]] void setExact(ExactNumber& out, bool& hasOut, std::string const& lexeme) {\n");
        sb.append("    if (!lexeme.empty()) { out = ExactNumber::parseLexeme(lexeme); hasOut = true; }\n");
        sb.append("}\n\n");
        sb.append("SchemaResourceRegistry buildRegistry() {\n");
        sb.append("    SchemaResourceRegistry reg;\n");
        // ---- Resource identity (K-29): one document resource this pass. ----
        // baseUri/dialect are derived from the emitter's model access (document
        // retrieval URI, defaulting to a stable document-local urn for a single
        // bundled schema resource; overridable via the oas31BaseUri option). The
        // dialect URI comes from the document's classed dialect. rootNodes lists
        // every main validator-owning row (0..M-1) as a root of THIS resource;
        // flattened `not`/helper rows appended after M are reached via
        // SchemaNode.notSchema/applicator children, not as independent roots.
        sb.append("    SchemaResource res;\n");
        sb.append("    res.baseUri = \"").append(escapeCppStringContent(documentBaseUri())).append("\";\n");
        sb.append("    res.dialect = \"").append(escapeCppStringContent(documentDialectUri())).append("\";\n");
        sb.append("    // res.anchor left empty: no $anchor/$dynamicAnchor root in this slice.\n");
        for (int i = 0; i < mainNodeCount; i++) {
            sb.append("    res.rootNodes.push_back(").append(i).append(");\n");
        }
        sb.append("    reg.resources.push_back(res);\n");

        int index = 0;
        for (IrNode node : nodes) {
            boolean resolvedRef = node.isRef && node.refTargetIndex >= 0;
            sb.append("\n    { // node ").append(index).append(": ").append(node.resolvedName).append("\n");
            sb.append("        SchemaNode n;\n");
            sb.append("        n.resourceIdentity = 0;\n");
            // K-29: genuine local $ref node -> applicator to the resolved
            // target node. 2020-12: $ref and sibling keywords BOTH apply, so
            // the inline keyword copy below is emitted ALWAYS (a pure-ref node
            // carries no siblings, so nothing else is emitted). A node that
            // ALSO has its own oneOf/anyOf/allOf keeps its internal applicator
            // as the node's applicator (ref-only is not modelled combined).
            if (resolvedRef && node.applicatorKind == null) {
                sb.append("        n.applicator = ApplicatorKind::ref;\n");
                sb.append("        n.children.push_back(").append(node.refTargetIndex).append(");\n");
            }
            // A deep enum present => the enum alone is the complete constraint
            // (the instance must deep-equal a member, which subsumes any type).
            // The lowered model's type flag is unreliable here (type-less enums
            // are inferred as `string`; `type: array` becomes an ArraySchema that
            // DROPS the enum), so we omit it and rely on the exact enum.
            if (node.hasType && node.enumJson == null) {
                sb.append("        n.typeFlags = ").append(node.typeFlags).append("u;\n");
            }
            switch (node.booleanValue) {
                case TRUE:
                    sb.append("        n.booleanValue = BooleanValue::true_;\n");
                    break;
                case FALSE:
                    sb.append("        n.booleanValue = BooleanValue::false_;\n");
                    break;
                default:
                    break;
            }
            emitSetExact(sb, "n.minimum", "n.hasMinimum", node.minimum);
            emitSetExact(sb, "n.maximum", "n.hasMaximum", node.maximum);
            emitSetExact(sb, "n.exclusiveMinimum", "n.hasExclusiveMinimum", node.exclusiveMinimum);
            emitSetExact(sb, "n.exclusiveMaximum", "n.hasExclusiveMaximum", node.exclusiveMaximum);
            emitSetExact(sb, "n.multipleOf", "n.hasMultipleOf", node.multipleOf);
            // EnumNumbers carries ONLY exact numeric lexemes (built from raw
            // members), so emitting it here never feeds parseLexeme a junk
            // string; structural members are handled by the deep enumJson store.
            for (String lex : node.enumNumbers) {
                sb.append("        n.enumNumbers.push_back(ExactNumber::parseLexeme(\"")
                        .append(lex).append("\"));\n");
            }
            for (String s : node.enumStrings) {
                sb.append("        n.enumStrings.push_back(\"").append(s).append("\");\n");
            }
            for (String b : node.enumBooleans) {
                sb.append("        n.enumBooleans.push_back(").append(b).append(");\n");
            }
            if (node.constNumber != null) {
                sb.append("        n.hasConst = true;\n");
                sb.append("        n.constNumber = ExactNumber::parseLexeme(\"")
                        .append(node.constNumber).append("\");\n");
                sb.append("        n.constIsNumber = true;\n");
            }
            if (node.constString != null) {
                sb.append("        n.hasConst = true;\n");
                sb.append("        n.constString = \"").append(node.constString).append("\";\n");
                sb.append("        n.constIsString = true;\n");
            }
            if (node.constBool != null) {
                sb.append("        n.hasConst = true;\n");
                sb.append("        n.constBool = ").append(node.constBool).append(";\n");
                sb.append("        n.constIsBool = true;\n");
            }
            // -- Wave-1 deep JSON stores (exact, K-30/K-34) --
            if (node.constJson != null) {
                sb.append("        n.hasConst = true;\n");
                sb.append("        n.constIsJson = true;\n");
                appendJsonParse(sb, "n.constJson", node.constJson);
            }
            if (node.enumJson != null) {
                sb.append("        n.hasEnumJson = true;\n");
                sb.append("        { boost::json::value _v = boost::json::parse(R\"W1J(");
                sb.append(node.enumJson);
                sb.append(")W1J\");\n");
                sb.append("          for (boost::json::value& _e : _v.as_array()) n.enumJson.push_back(_e); }\n");
            }
            if (node.hasUniqueItems) {
                sb.append("        n.hasUniqueItems = true;\n");
            }
            if (node.notSchemaIndex >= 0) {
                sb.append("        n.notSchema = ").append(node.notSchemaIndex).append(";\n");
            }
            // -- Wave-2 object structural (FROZEN §10) ------------------
            if (node.hasObjectSchema) {
                sb.append("        n.hasObjectSchema = true;\n");
            }
            for (IrNode.PropertySchema pb : node.properties) {
                if (pb.index < 0) continue;
                sb.append("        { PropertyBinding b; b.name = \"")
                        .append(escapeCppStringContent(pb.name))
                        .append("\"; b.node = ").append(pb.index)
                        .append("; n.properties.push_back(std::move(b)); }\n");
            }
            for (String rn : node.required) {
                sb.append("        n.required.push_back(\"")
                        .append(escapeCppStringContent(rn)).append("\");\n");
            }
            if (!"absent".equals(node.additionalPropertiesKind)) {
                switch (node.additionalPropertiesKind) {
                    case "allowed":
                        sb.append("        n.additionalProperties = AdditionalPropertiesKind::allowed;\n");
                        break;
                    case "reject":
                        sb.append("        n.additionalProperties = AdditionalPropertiesKind::reject;\n");
                        break;
                    case "schema":
                        sb.append("        n.additionalProperties = AdditionalPropertiesKind::schema;\n");
                        if (node.additionalSchemaIndex >= 0) {
                            sb.append("        n.additionalSchema = ").append(node.additionalSchemaIndex).append(";\n");
                        }
                        break;
                    default:
                        break;
                }
            }
            emitSetExact(sb, "n.minProperties", "n.hasMinProperties", node.minPropertiesLexeme);
            emitSetExact(sb, "n.maxProperties", "n.hasMaxProperties", node.maxPropertiesLexeme);
            // -- Wave-2 array structural (FROZEN §10) -------------------
            for (int i = 0; i < node.prefixItems.size(); i++) {
                int cidx = node.prefixItemIndices.isEmpty() ? -1 : node.prefixItemIndices.get(i);
                if (cidx < 0) continue;
                sb.append("        n.prefixItems.push_back(").append(cidx).append(");\n");
            }
            if (node.itemsIndex >= 0) {
                sb.append("        n.items = ").append(node.itemsIndex).append(";\n");
            }
            emitSetExact(sb, "n.minItems", "n.hasMinItems", node.minItemsLexeme);
            emitSetExact(sb, "n.maxItems", "n.hasMaxItems", node.maxItemsLexeme);
            // -- Wave-2 applicator (allOf/anyOf/oneOf) ------------------
            if (node.applicatorKind != null && !node.applicatorChildIndices.isEmpty()) {
                sb.append("        n.applicator = ApplicatorKind::").append(node.applicatorKind).append(";\n");
                for (Integer cidx : node.applicatorChildIndices) {
                    if (cidx >= 0) {
                        sb.append("        n.children.push_back(").append(cidx).append(");\n");
                    }
                }
            }
            // -- Wave-2 unevaluatedProperties ---------------------------
            if (node.unevaluatedPropertiesPresent) {
                sb.append("        n.hasUnevaluatedProperties = true;\n");
                if (node.unevaluatedPropertiesRejects) {
                    sb.append("        n.unevaluatedPropertiesRejects = true;\n");
                }
                if (node.unevaluatedSchemaIndex >= 0) {
                    sb.append("        n.unevaluatedSchema = ").append(node.unevaluatedSchemaIndex).append(";\n");
                }
            }
            sb.append("        reg.nodes.push_back(n);\n");
            sb.append("    }\n");
            index++;
        }

        sb.append("\n    return reg;\n");
        sb.append("}\n\n");
        sb.append("} // namespace\n\n");
        sb.append("SchemaResourceRegistry const& schemaRegistry() {\n");
        sb.append("    static SchemaResourceRegistry const r = buildRegistry();\n");
        sb.append("    return r;\n");
        sb.append("}\n\n");
        sb.append("SchemaIndex schemaNodeFor(std::string const& id) {\n");
        index = 0;
        for (IrNode node : nodes) {
            sb.append("    if (id == \"").append(node.validatorId).append("\") return ").append(index).append(";\n");
            index++;
        }
        sb.append("    (void)id;\n");
        sb.append("    return kNoSchema;\n");
        sb.append("}\n\n");
        sb.append("} // namespace oas31\n");
        return sb.toString();
    }

    /** Append `n.FIELD = boost::json::parse(R"W1J(<json>)W1J");` safely. */
    private void appendJsonParse(StringBuilder sb, String field, String json) {
        sb.append("        ").append(field).append(" = boost::json::parse(R\"W1J(");
        sb.append(json);
        sb.append(")W1J\");\n");
    }

    private void emitSetExact(StringBuilder sb, String field, String hasField, String lexeme) {
        if (lexeme != null) {
            sb.append("        setExact(").append(field).append(", ").append(hasField)
              .append(", \"").append(lexeme).append("\");\n");
        }
    }

    /** schema_validate.generated.cpp — thin validate_<id> dispatch (ADR D5). */
    private String buildSchemaIrValidateSource(java.util.List<IrNode> nodes) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by CppBoostBeastClientCodegen — Wave-1 thin validate_<id> dispatch.\n");
        sb.append("// Each entry delegates to oas31::SchemaEvaluator::validate over a densified\n");
        sb.append("// schema node (ADR D5). Existing hand-template validate_* emissions are untouched.\n");
        sb.append("#include \"schema_ir.generated.hpp\"\n");
        sb.append("#include \"oas31_validator.hpp\"\n");
        sb.append("#include <string>\n\n");

        for (IrNode node : nodes) {
            sb.append("\noas31::ValidationResult validate_").append(node.validatorId).append("(\n");
            sb.append("    oas31::RawInstance const& instance,\n");
            sb.append("    oas31::ValidationPath& path,\n");
            sb.append("    oas31::ValidationContext& ctx)\n");
            sb.append("{\n");
            sb.append("    oas31::SchemaIndex idx = oas31::schemaNodeFor(\"")
                    .append(node.validatorId).append("\");\n");
            sb.append("    if (idx == oas31::kNoSchema) {\n");
            sb.append("        return oas31::ValidationResult::invalid(path.str(),\n");
            sb.append("            \"unknown generated schema id: ")
                    .append(escapeCppStringContent(node.validatorId)).append("\");\n");
            sb.append("    }\n");
            sb.append("    static oas31::SchemaEvaluator const evaluator(oas31::schemaRegistry());\n");
            sb.append("    return evaluator.validate(idx, instance, path, ctx);\n");
            sb.append("}\n");
        }
        return sb.toString();
    }
}
