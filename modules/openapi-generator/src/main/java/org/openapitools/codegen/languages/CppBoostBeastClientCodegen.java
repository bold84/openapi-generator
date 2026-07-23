package org.openapitools.codegen.languages;


import io.swagger.v3.oas.models.OpenAPI;
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

            // Scan the resolved target schema for assertion keywords
            if (targetForAssertions != null) {
                // Validation type — use the resolved type name or "type-array" for type arrays
                if (targetForAssertions.getType() != null) {
                    supported.add("type");
                    validateParams.put("validation-type", targetForAssertions.getType());
                }
                if (targetForAssertions.getTypes() != null && !targetForAssertions.getTypes().isEmpty()) {
                    supported.add("type");
                    validateParams.put("validation-type", "type-array");
                    // OAS 3.1 type arrays: store as List<String> for template iteration;
                    // has-validation-type-array is a boolean flag for outer section guard.
                    validateParams.put("validation-type-array",
                            new ArrayList<>(targetForAssertions.getTypes()));
                    validateParams.put("has-validation-type-array", true);
                }
                if (targetForAssertions.getEnum() != null && !targetForAssertions.getEnum().isEmpty()) {
                    supported.add("enum");
                    List<String> enumStrs = new ArrayList<>();
                    String predominantKind = "string";
                    for (Object e : targetForAssertions.getEnum()) {
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
                    validateParams.put("has-validation-enum", true);
                }
                // Const: detect JSON kind for the validator template
                if (targetForAssertions.getConst() != null) {
                    supported.add("const");
                    Object constVal = targetForAssertions.getConst();
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
                }
                // Use ModelUtils.resolveMinimumBound / resolveMaximumBound for
                // proper OAS 3.0→3.1 resolution (boolean → numeric conversion,
                // allOf intersection, $ref traversal).
                ModelUtils.ResolvedMinBound resolvedMin = ModelUtils.resolveMinimumBound(openAPI, targetForAssertions);
                ModelUtils.ResolvedMaxBound resolvedMax = ModelUtils.resolveMaximumBound(openAPI, targetForAssertions);
                if (resolvedMin != null || resolvedMax != null
                        || targetForAssertions.getMultipleOf() != null) {
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
                    if (targetForAssertions.getMultipleOf() != null) {
                        validateParams.put("validation-multiple-of",
                                targetForAssertions.getMultipleOf());
                    }
                    validateParams.put("has-validation-numeric", true);
                }
                if (targetForAssertions.getMinLength() != null
                        || targetForAssertions.getMaxLength() != null) {
                    supported.add("string-length");
                    if (targetForAssertions.getMinLength() != null) {
                        validateParams.put("validation-min-length",
                                targetForAssertions.getMinLength());
                    }
                    if (targetForAssertions.getMaxLength() != null) {
                        validateParams.put("validation-max-length",
                                targetForAssertions.getMaxLength());
                    }
                    validateParams.put("has-validation-string-length", true);
                }
                if (targetForAssertions.getPattern() != null) {
                    supported.add("pattern");
                    validateParams.put("validation-pattern",
                            escapeCppStringContent(targetForAssertions.getPattern()));
                    validateParams.put("has-validation-pattern", true);
                }
                if (targetForAssertions.getItems() != null
                        || targetForAssertions.getPrefixItems() != null) {
                    // items/prefixItems validation affects membership but is not
                    // yet implemented in the validator template. Fail-closed for
                    // oneOf/anyOf; allOf exempted (non-not unsupported).
                    unsupported.add("array-items");
                }
                if (targetForAssertions.getMinItems() != null
                        || targetForAssertions.getMaxItems() != null) {
                    supported.add("array-length");
                    if (targetForAssertions.getMinItems() != null) {
                        validateParams.put("validation-min-items",
                                targetForAssertions.getMinItems());
                    }
                    if (targetForAssertions.getMaxItems() != null) {
                        validateParams.put("validation-max-items",
                                targetForAssertions.getMaxItems());
                    }
                    validateParams.put("has-validation-array-length", true);
                }
                if (Boolean.TRUE.equals(targetForAssertions.getUniqueItems())) {
                    supported.add("unique-items");
                    validateParams.put("has-validation-unique-items", true);
                }
                // required: supported — presence check is generated in validator
                if (targetForAssertions.getRequired() != null) {
                    supported.add("object-properties");
                    validateParams.put("validation-required",
                            targetForAssertions.getRequired());
                    validateParams.put("has-validation-object-props", true);
                }
                // properties: fail-closed — per-property validation on composition
                // branches is not implemented; object properties are validated by
                // the resolved model type, not the branch validator.
                if (targetForAssertions.getProperties() != null
                        && !targetForAssertions.getProperties().isEmpty()) {
                    unsupported.add("properties");
                }
                // additionalProperties: fail-closed unless no-op (true or absent).
                // Handles both Schema (e.g. {type: string}) and Boolean (false).
                if (targetForAssertions.getAdditionalProperties() != null) {
                    boolean hasConstraint = false;
                    if (targetForAssertions.getAdditionalProperties() instanceof Schema) {
                        Schema addProp = (Schema) targetForAssertions.getAdditionalProperties();
                        hasConstraint = Boolean.FALSE.equals(addProp.getBooleanSchemaValue())
                                || addProp.getType() != null
                                || (addProp.getEnum() != null && !addProp.getEnum().isEmpty());
                    } else if (targetForAssertions.getAdditionalProperties() instanceof Boolean) {
                        // OAS 3.0: additionalProperties: false rejects extra properties
                        hasConstraint = Boolean.FALSE.equals(
                                targetForAssertions.getAdditionalProperties());
                    }
                    if (hasConstraint) {
                        unsupported.add("additional-properties");
                    }
                }
                if (targetForAssertions.getMinProperties() != null
                        || targetForAssertions.getMaxProperties() != null) {
                    // minProperties/maxProperties affects membership but is not
                    // yet implemented in the validator template. Fail-closed.
                    unsupported.add("object-property-count");
                }
                if (targetForAssertions.getOneOf() != null
                        || targetForAssertions.getAnyOf() != null
                        || targetForAssertions.getAllOf() != null) {
                    // Nested composition is not implemented in per-branch
                    // validators. Fail-closed for oneOf/anyOf.
                    unsupported.add("composition");
                }
                // `not` is always unsupported: it can flip any membership decision
                // and no generated validator currently implements it.
                if (targetForAssertions.getNot() != null) {
                    unsupported.add("not");
                }

                // Detect unsupported assertion keywords
                io.swagger.v3.oas.models.media.Discriminator targetDisc =
                        targetForAssertions.getDiscriminator();
                if (targetDisc != null) {
                    // Discriminator on branches is annotation-only for now
                }
                if (targetForAssertions.getIf() != null
                        || targetForAssertions.getThen() != null
                        || targetForAssertions.getElse() != null) {
                    unsupported.add("conditional");
                }
                if (targetForAssertions.getDependentRequired() != null) {
                    unsupported.add("dependencies");
                }
                if (targetForAssertions.getContains() != null) {
                    unsupported.add("contains");
                }
                if (targetForAssertions.getUnevaluatedProperties() != null) {
                    unsupported.add("unevaluated");
                }
                if (targetForAssertions.getContentMediaType() != null
                        || targetForAssertions.getContentEncoding() != null) {
                    unsupported.add("content-encoding");
                }
                if (targetForAssertions.getPropertyNames() != null) {
                    unsupported.add("property-names");
                }
                // OAS 3.1 boolean value schemas (true → always-match, false → never-match)
                // affect oneOf/anyOf membership with no generated validator.
                if (targetForAssertions.getBooleanSchemaValue() != null) {
                    unsupported.add("boolean-schema");
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


        supportingFiles.add(new SupportingFile("validation-types.mustache", "model", "ValidationTypes.h"));
        supportingFiles.add(new SupportingFile("NullableField.h.mustache", "model", "NullableField.h"));
        supportingFiles.add(new SupportingFile("README.mustache", "", "README.md"));
        supportingFiles.add(new SupportingFile("CMakeLists.txt.mustache", "", "CMakeLists.txt"));
        supportingFiles.add(new SupportingFile("http-client-header.mustache", "api", "HttpClient.h"));
        supportingFiles.add(new SupportingFile("http-client-impl-header.mustache", "api", "HttpClientImpl.h"));
        supportingFiles.add(new SupportingFile("http-client-impl-source.mustache", "api", "HttpClientImpl.cpp"));
        supportingFiles.add(new SupportingFile("anytype-header.mustache", "model", "AnyType.h"));

        languageSpecificPrimitives = new HashSet<String>(
                Arrays.asList("int", "char", "bool", "long", "float", "double", "int32_t", "int64_t"));

        super.typeMapping = new HashMap<String, String>();
        typeMapping.put("date", "std::string");
        typeMapping.put("DateTime", "std::string");
        typeMapping.put("string", "std::string");
        typeMapping.put("integer", "int32_t");
        typeMapping.put("long", "int64_t");
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
        // These properties have an empty intersection (e.g., string ∩ integer)
        // and should not generate a writable member. The generated decode
        // validation rejects the property when present in JSON but accepts
        // the object when the property is absent.
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
                cppType = resolveOpenApiTypeName(stripSharedPtr(b.dataType));
            }
            if (cppType.equals(cm.classname)) {
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
                    // Skip optional impossible properties from the storage model.
                    // These are generated with decode-only validation (reject if present)
                    // but no writable member.
                    Schema rejectionSchema = new Schema();
                    rejectionSchema.setType("boolean"); // Sentinel type for template dispatch
                    Map<String, Object> ext = new LinkedHashMap<>();
                    ext.put("x-cpp-reject-if-present", true);
                    rejectionSchema.setExtensions(ext);
                    syntheticProps.put(propName, rejectionSchema);
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
                // Propagate optional-impossible property tags
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
        // When an operation produces ONLY text/event-stream, it is a pure
        // SSE endpoint and the operation-level flag is set, causing the
        // return type to be wrapped in std::vector<...>.
        // For dual-content ops (JSON + SSE), the operation-level flag is NOT
        // set (return type stays JSON). Instead, a dedicated stream method is
        // generated ({operationId}Stream) that sets Accept to text/event-stream
        // and returns std::vector<EventType> via incremental event conversion.
        // Note: Dual-content detection is driven by produces media types, not
        // by the presence of a "stream" query parameter (the parameter is
        // a client-side convention for choosing between JSON and SSE).
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
                    if (isOneOfResponse(response)
                            || isOneOfMediaType(response, "text/event-stream")) {
                        operation.vendorExtensions.put(X_CODEGEN_STREAM_IS_ONE_OF, true);
                    }
                    if (response.dataType != null) {
                        String streamElementType = stripSharedPtr(response.dataType);
                        // Only set element type for model types (uppercase first char).
                        // Primitives like std::string don't have fromJsonValue_ free
                        // functions and would produce invalid identifiers like
                        // fromJsonValue_std::string.
                        if (!streamElementType.startsWith("std::") && !streamElementType.startsWith("boost::")
                                && Character.isUpperCase(streamElementType.charAt(0))) {
                            response.vendorExtensions.put("x-codegen-stream-element-type",
                                    streamElementType);
                            // Propagate to operation level for use outside {{#responses}} scope
                            operation.vendorExtensions.put("x-codegen-stream-element-type",
                                    streamElementType);
                        }
                    }
                } else if (isDualContent && response.is2xx && response.dataType != null
                        && !response.dataType.equals(operation.returnType)) {
                    response.vendorExtensions.put("x-codegen-streaming-response", true);
                    if (response.dataType != null) {
                        String streamElementType = stripSharedPtr(response.dataType);
                        response.vendorExtensions.put("x-codegen-stream-element-type",
                                streamElementType);
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
                            if (isOneOfSchema(sseSchema)) {
                                operation.vendorExtensions.put(X_CODEGEN_DUAL_STREAM_IS_ONE_OF, true);
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
                    if (isOneOfType(sseReturnType)) {
                        operation.vendorExtensions.put(X_CODEGEN_DUAL_STREAM_IS_ONE_OF, true);
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
                    // Also propagate to each response so the template can access it
                    // from within the {{#responses}} context scope.
                    for (CodegenResponse response : operation.responses) {
                        response.vendorExtensions.put("x-codegen-dual-stream-return-type", sseReturnType);
                        response.vendorExtensions.put("x-codegen-dual-stream-base-name", sseBaseModelName);
                        response.vendorExtensions.put("x-codegen-dual-stream-element-type", dualStreamElementType);
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
     * Sets on each 2xx non-default response:
     *   x-codegen-response-union-body-type: the variant alternative body type
     *     (e.g., "std::shared_ptr<FullResource>" or "std::monostate")
     *
     * Single-shape operations (one success type) are left unchanged so the
     * existing simple-signature path is used.
     */
    private void addResponseUnionMetadata(CodegenOperation operation) {
        // Collect successful (2xx, non-default) responses.
        List<CodegenResponse> successResponses = new ArrayList<>();
        for (CodegenResponse response : operation.responses) {
            if (response.is2xx && !response.isDefault) {
                successResponses.add(response);
            }
        }
        if (successResponses.size() < 2) {
            return; // single-shape or zero: no union needed
        }

        // Detect whether the success responses have distinct shapes.
        // "Distinct" means different dataType, or mixed body/no-body.
        boolean hasMixedShapes = false;
        String firstDataType = successResponses.get(0).dataType;
        for (int idx = 1; idx < successResponses.size(); ++idx) {
            if (!Objects.equals(firstDataType, successResponses.get(idx).dataType)) {
                hasMixedShapes = true;
                break;
            }
        }
        // Also detect body/no-body mix (same dataType string but one null).
        if (!hasMixedShapes) {
            boolean hasBody = false;
            boolean hasNoBody = false;
            for (CodegenResponse r : successResponses) {
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
            return; // all success responses share the same body shape
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

        // Tag each successful response with its body type for the variant.
        // Use the response dataType directly (may be std::shared_ptr<Foo> etc.)
        // or std::monostate for no-body responses.
        for (CodegenResponse response : successResponses) {
            String bodyType = response.dataType != null
                    ? response.dataType
                    : "std::monostate";
            response.vendorExtensions.put(X_CODEGEN_RESPONSE_UNION_BODY_TYPE, bodyType);
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
}
