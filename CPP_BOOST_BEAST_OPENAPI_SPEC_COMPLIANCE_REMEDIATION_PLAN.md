# cpp-boost-beast-client OpenAPI Specification Compliance Remediation Plan

## Document status

- Scope: resulting branch diff from PR base `37a636f41167eb289f8ed2c5e92556d999e7fb9c`
  through reviewed head `aeef0bd60753a4b0ce2f3abe3259bf9f4d048fdd`.
- Target generator: `cpp-boost-beast-client`.
- Target language: C++17.
- Primary specifications:
  - OpenAPI 3.0.4 and 3.1.1.
  - JSON Schema Draft 2020-12 semantics embedded by OpenAPI 3.1.
  - WHATWG Server-Sent Events only where transport behavior is explicitly enabled.
- Purpose: replace structurally plausible but semantically lossy composition behavior
  with behavior that is either specification-correct or fails generation explicitly.

## Executive decision

The generator must stop claiming general `oneOf`, `anyOf`, and `allOf` support while
it validates only JSON kinds and a small subset of assertions. The remediation uses
three rules:

1. Preserve the source schema semantics until after generator-specific analysis.
2. Separate C++ storage selection from JSON Schema branch validation.
3. Fail closed when a schema assertion affects composition membership but has no
   generated validator.

The implementation remains dependency-free by generating schema-specific C++
validators. It must not add a general JSON Schema runtime dependency. If a valid
schema uses an assertion not yet supported by the generated validator, generation
must fail with the schema location, keyword, and remediation guidance instead of
silently accepting invalid wire values.

## Confirmed issues to resolve

| ID | Issue | Required result |
| --- | --- | --- |
| C-01 | `oneOf` branch types are deduplicated after constraints are erased | Match full branch schemas; require exactly one match |
| C-02 | Integer matching follows Boost storage kind instead of mathematical JSON Schema semantics | Treat `1` and `1.0` as integers when mathematically integral |
| C-03 | Discriminator dispatch changes validation outcomes | Use discriminator only as a candidate-order hint; always preserve composition outcome |
| C-04 | Different successful response shapes and body/no-body statuses cannot be returned | Generate a status-aware response union |
| C-05 | Enum-only `anyOf` and all-null compositions broaden or lose the valid value set | Preserve branch validators and null cardinality |
| C-06 | `allOf` is merged as inheritance without complete intersection semantics | Recursively intersect contributors and their assertions |
| C-07 | OAS 3.0 nullable object schemas are tagged but cannot decode JSON null | Generate a null-capable schema-level type |
| C-08 | Multipart `Encoding.contentType` and related metadata are ignored | Propagate encoding metadata to each multipart part |
| C-09 | Optional nullable properties collapse missing and explicit null | Add a tri-state field representation |
| C-10 | Typed SSE event decoding is presented as if defined by OAS | Make event-data typing an explicit convention/option |
| C-11 | Documentation says composition is fully supported | Publish an exact supported/fail-closed matrix |

## Normative behavioral rules

### Composition

- `oneOf`: validate every branch and accept only when exactly one full branch schema
  succeeds. Storage type uniqueness must never substitute for schema uniqueness.
- `anyOf`: validate branches until at least one succeeds. Storage may choose the first
  matching branch in document order, but validation metadata must remain available.
- `allOf`: the instance must independently satisfy every contributor. Flattening is
  only a representation optimization after intersection has been proven sound.
- `not`: until a generated validator exists, any composition whose result depends on
  `not` must fail generation. It must not be ignored.
- Boolean schemas in OAS 3.1: `true` matches every instance and `false` matches none.
  An OAS 3.0 document containing a boolean schema must fail parsing/generation with a
  version-specific diagnostic rather than entering the 3.1 validator path.

### Numbers

- JSON Schema has one mathematical number domain. `integer` means a number with zero
  fractional part; it is not the same as Boost JSON's `is_int64()` storage tag.
- Branch validation runs before conversion to `std::int32_t` or `std::int64_t`.
- C++ range conversion failures are representation errors, not branch non-matches.
- `format` is annotation-oriented by default in OAS 3.1. Generator mappings such as
  `int32` remain documented representation choices and must not alter composition
  match counts unless a configured format-assertion policy explicitly says so.
- Add `formatAssertionPolicy=annotation|strict`, defaulting to `annotation`. In strict
  mode, documented format assertions such as signed 32-bit range participate in
  branch validation; in annotation mode, range checks happen only during destination
  conversion and cannot change `oneOf`/`anyOf` match counts.

### Discriminators

- A discriminator may reorder candidate validation but must not skip validation.
- Known values must not bypass `oneOf` exactly-one counting.
- Unknown or missing values must fall back to full branch validation.
- A discriminator mismatch is an error only when the underlying schema makes it one,
  for example through `const`, `enum`, `required`, or a failed branch schema.
- Mappings must resolve to legal alternatives and preserve explicit URI resolution.

### Nullability

- OAS 3.0 `nullable: true` applies only when `type` is explicitly declared in the same
  Schema Object.
- OAS 3.1 nullability is represented by `type: "null"`, a type array containing
  `null`, or a composition containing a null-capable branch.
- Property presence and property value are separate concerns. A non-required nullable
  property has three states: missing, present-null, and present-value.
- The `std::optional<T>` null-union optimization is allowed only when it preserves the
  complete accepted set and branch cardinality.

### Responses and media types

- Every declared exact, range, or default response must be representable.
- Exact status codes take precedence over range responses, then `default`.
- A declared successful no-content response is not an exception.
- Deserialization must use the actual response `Content-Type` when available.
- Multipart property encoding must honor the OAS Encoding Object before defaults.
- OAS does not define JSON typing for each SSE `data:` field. Typed event-data parsing
  requires explicit generator configuration or a documented vendor extension.

## Target architecture

### 1. Preserve semantic schema descriptors

Add generator-private descriptors in
`CppBoostBeastClientCodegen.java` rather than reducing branches immediately to C++
type strings:

```text
CompositionDescriptor
  schemaName
  schemaLocation
  keyword: ONE_OF | ANY_OF | ALL_OF
  branches: List<CompositionBranchDescriptor>
  discriminator: optional DiscriminatorDescriptor

CompositionBranchDescriptor
  branchIndex
  sourceSchema or stable schema reference
  resolvedSchemaName
  storageCppType
  validatorId
  nullCapability: NEVER | ALWAYS | CONDITIONAL
  supportedAssertions
  unsupportedAssertions
```

Required implementation details:

- Preserve branch order.
- Resolve local and external `$ref` values with cycle detection.
- Record schema locations in JSON Pointer form for diagnostics.
- Keep the original keyword after normalizer and inline-model processing.
- Store template-safe branch maps in vendor extension
  `x-cpp-composition-branches`; do not expose Java objects directly to Mustache.
- During migration, populate both new `x-cpp-composition-branches` and existing
  `x-cpp-branches` metadata. Phase 3 switches templates to the new descriptor and
  removes the old extension after all generation paths have migrated.
- Replace `resolvedAliasTypes` as the source of semantics. It may remain a storage
  optimization index only.

### 2. Take ownership of composition normalization

Extend `CppBoostBeastOpenAPINormalizer` so this generator sees all branch schemas
needed for validation. Do not globally change other generators.

The lifecycle is explicit: `DefaultGenerator` runs normalization, then
`InlineModelResolver.flatten`, then `preprocessOpenAPI`. Therefore the custom
normalizer must prevent destructive composition simplification before descriptors
exist. After inline flattening has replaced inline children with stable component
references, `preprocessOpenAPI` builds descriptors by recursively resolving those
references. It must not try to snapshot already-consumed branches in `fromModel`.

Implementation tasks:

- Disable or bypass `SIMPLIFY_ONEOF_ANYOF` when it would erase branch cardinality,
  assertions, null multiplicity, or the original keyword.
- Override `processSimplifyOneOf`, `processSimplifyAnyOf`, and
  `processSimplifyAnyOfStringAndEnumString` so the normalizer returns the original
  composition for this generator. Set-equivalent simplification happens later in the
  generator's semantic analyzer, never in the pre-descriptor normalizer.
- Preserve inline compositions and adapt `InlineModelResolver` output rather than
  depending on default simplification.
- Build the descriptor index in `preprocessOpenAPI`, after inline flattening and before
  any `fromModel` call. Add a lifecycle test that would fail if normalizer ordering
  changes.
- Add tests that compare descriptors before and after normalization.
- Remove recovery heuristics that infer semantics only from `CodegenModel.dataType`.

### 3. Generate schema-aware branch validators

Generate one validator per distinct branch schema:

```cpp
ValidationResult validate_<schema>_branch_<index>(
    boost::json::value const& instance,
    ValidationPath& path);
```

`ValidationResult` must carry success plus the most useful failure path/message.
Validators must be side-effect free. Conversion into C++ storage happens only after
composition membership is known.

Initial required assertion coverage:

- Boolean schemas.
- `type`, including type arrays and mathematical integer checks.
- `enum` and `const` for every JSON kind.
- Numeric `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, and
  `multipleOf`.
- String `minLength`, `maxLength`, and ECMA-262-compatible `pattern` handling.
- Array `items`, `prefixItems`, `minItems`, `maxItems`, and `uniqueItems`.
- Object `required`, `properties`, `additionalProperties`, `minProperties`, and
  `maxProperties`.
- Nested `$ref`, `oneOf`, `anyOf`, `allOf`, and `not`.

Keywords not implemented in the first validator release, including conditional or
unevaluated vocabularies, must be detected during generation. If they can affect a
composition match, generation fails with an `UnsupportedSchemaAssertionException`.
Ordinary non-composed models may retain the existing documented validation scope,
but they must not be used as proof of `oneOf`/`anyOf` membership unless all relevant
assertions have validators.

Avoid `std::regex` as an unqualified substitute for ECMA-262 and do not add a compiled
Boost.Regex dependency. Implement the interoperable regex subset already recommended
by JSON Schema Core, emit a generated matcher for that subset, and fail closed on any
construct outside it. Add official JSON Schema pattern cases for every accepted token
and explicit generation-failure cases for unsupported constructs.

### 4. Decouple storage from branch identity

Keep ergonomic aliases where schemas are provably disjoint after full validation.
For branches that lower to duplicate C++ types, generate tagged storage:

```cpp
template<std::size_t BranchIndex, typename ValueType>
struct CompositionBranchValue {
    ValueType value;
};
```

Example:

```cpp
using ExampleStorage = std::variant<
    CompositionBranchValue<0, double>,
    CompositionBranchValue<1, double>>;
```

Generated model APIs should provide named `isBranchN`, `getBranchN`, and factory
methods so users do not manipulate template tags directly.

Rules:

- `oneOf`: count all validators first, require exactly one, then convert and tag it.
- `anyOf`: collect or count matches, require at least one, then store the first match
  in source order.
- Encoding unwraps the tagged value without reinterpreting branch membership.
- Type-erased `boost::json::value` remains an allowed fallback only when paired with
  generated validators and a recorded selected branch. It cannot mean "accept any".

### 5. Centralize numeric matching and conversion

Add generated helpers shared by model and API response conversion:

```text
isJsonNumber(value)
isJsonInteger(value)
tryGetMathematicalInteger(value, checked destination)
convertJsonNumber<T>(value)
```

Required behavior:

- Handle Boost JSON `int64`, `uint64`, and `double` storage.
- `1` and `1.0` both satisfy `integer` and `number`.
- Non-integral doubles do not satisfy `integer`.
- Reject NaN/infinity where JSON cannot represent them.
- Check destination bounds separately from schema matching.
- Use the same helpers in `model-source.mustache` and `api-source.mustache`; remove
  duplicated branch logic.

### 6. Build a recursive `allOf` intersection engine

Replace the shallow type-conflict pre-check with a recursive intersection analysis.

Integration point: build intersections in the descriptor pass during
`preprocessOpenAPI`, before `super.fromModel` can flatten allOf into inheritance.
`fromModel` must not pass the original allOf schema to the default merge path:

- For an object intersection, build a synthetic object Schema containing the
  intersected properties/required set and pass that synthetic schema to
  `super.fromModel` as the storage-model input.
- Preserve the original `CompositionDescriptor` for generated runtime validation and
  discriminator metadata.
- For a scalar or non-object intersection, bypass object-class generation and emit a
  validated alias/wrapper model.
- Do not mutate the original OpenAPI tree; keep the synthetic schema in the
  generator-private descriptor index.

The engine must:

- Resolve `$ref`-to-`allOf` contributors recursively with a visited set.
- Union required-property sets.
- Intersect type sets, enum sets, const values, numeric bounds, string bounds, and
  array/object bounds.
- Retain multiple patterns and require all of them.
- Intersect property schemas recursively instead of allowing last-write-wins.
- Apply `additionalProperties` and closed-object semantics to properties introduced
  by other contributors.
- Detect unsatisfiable intersections.

Representation rules:

- A required property with an empty intersection makes the model unsatisfiable and
  generation fails with a deterministic diagnostic.
- An optional property with an empty intersection is effectively forbidden. Do not
  emit a writable member; emit decode validation that rejects its presence.
- A representable intersection emits one property plus the combined validator.
- An intersection not yet representable in a typed member uses validated
  `boost::json::value` or fails closed; it must not silently select one contributor.
- Generated inheritance is optional and must not be used as the semantic engine.

### 7. Introduce null-capable schema and field types

Add a generated supporting type, for example `NullableField.h.mustache`:

```text
NullableField<T>
  state: Missing | Null | Value
  isMissing()
  isNull()
  hasValue()
  value()
  setNull()
  resetMissing()
```

Property mapping:

| Property shape | C++ representation |
| --- | --- |
| Required, non-null | `T` |
| Optional, non-null | `std::optional<T>` plus existing presence behavior |
| Required, nullable | null-capable value with `Missing` rejected |
| Optional, nullable | `NullableField<T>` |

For a schema-level OAS 3.0 nullable object, generate a non-null payload class and a
public null-capable alias, for example:

```cpp
class NullableObjectValue;
using NullableObject = std::optional<NullableObjectValue>;
```

Do not treat OAS 3.0 `$ref` siblings as nullable unless the specification version and
schema structure make that legal. OAS 3.1 null unions continue through composition
analysis.

### 8. Make discriminator dispatch validation-neutral

Change `fromJsonValue_<Schema>` as follows:

1. Read the discriminator only when the instance is an object and the property has
   the required JSON type.
2. If it resolves to a known alternative, validate that branch first for diagnostics.
3. Validate every other branch needed to compute `oneOf`/`anyOf` cardinality.
4. Apply the normal composition rule to the final match set.
5. Use the mapping only to choose candidate order or resolve an otherwise equivalent
   branch identifier.
6. Unknown or missing values use normal branch validation; they are not standalone
   errors.

Validate at generation time that mappings resolve and refer to legal alternatives.
Keep explicit URI mappings distinct from implicit component-name mappings.

### 9. Generate status-aware operation response unions

For operations with one successful response shape, preserve the existing method
signature. For heterogeneous successful responses, generate a named result:

```cpp
struct GetThingResponse {
    boost::beast::http::status status;
    std::string contentType;
    std::variant<Response200, Response201, NoContentResponse> body;
};
```

Use status-tagged branch wrappers when two statuses share the same C++ body type.
Include range and default responses where their body differs from exact responses.

Transport metadata is required for correct media-type dispatch. Add an additive
`HttpResponse` value containing status, body, and response headers. Preserve custom
client compatibility with a default adapter:

```text
HttpClient::executeWithMetadata(...)
  non-pure default -> calls legacy execute(), returns empty headers

HttpClientImpl::executeWithMetadata(...)
  returns actual status, body, and headers
```

Generated APIs use actual `Content-Type` when available and fall back to the requested
Accept type only for legacy adapters. Status selection order is exact, range, default.
Every matched declared success returns a response branch; no matched block may fall
through to `Unexpected HTTP status code`.

### 10. Honor multipart encoding metadata

Use existing OpenAPI Generator structures before adding new ones:

- `CodegenMediaType.getEncoding()`.
- `CodegenEncoding.getContentType()`.
- `CodegenParameter.contentType`, populated by
  `DefaultCodegen.setParameterEncodingValues`.

Change `addVariantFormParameter` to receive the property's resolved encoding metadata
instead of hard-coding by C++ branch type.

Precedence:

1. Encoding Object `contentType`.
2. Applicable OAS 3.1 `contentMediaType` when not contradicted by the Encoding Object
   or enclosing Media Type Object.
3. OAS default for the property type.

Also propagate Encoding Object headers, style, and explode where supported. Fail
generation with a targeted diagnostic for encoding combinations the transport cannot
represent. Add explicit tests for `image/png`, `text/plain`, JSON objects, arrays, and
binary/object variants.

### 11. Put typed SSE behavior behind an explicit contract

Strict OAS mode must treat `text/event-stream` as a media representation, not assume
that its Schema Object describes each JSON event data field.

Add a generator option such as:

```text
sseSchemaMode=representation | jsonEventData
```

- `representation` is the strict default. Generate an SSE event stream API exposing
  event fields and raw `data` strings, or a buffered body when streaming is disabled.
- `jsonEventData` enables the current JSON-per-`data:` convention and clearly states
  that the media type schema is interpreted as the event data schema.
- Optionally support a documented vendor extension such as
  `x-sse-event-data-schema` for per-operation opt-in.

Integrate the option with the existing `x-codegen-streaming-response`,
`x-codegen-dual-content`, `x-codegen-stream-is-oneof`, and
`x-codegen-dual-stream-is-oneof` paths. During migration, keep media-type discovery
but emit typed conversion extensions only in `jsonEventData` mode; strict mode emits
raw SSE event types. Remove or rename the old oneOf stream extensions once both
templates use the mode-specific metadata.

Rename internal vendor extensions from generic "stream schema" wording to explicit
SSE event-data wording. Document handling of `event`, `id`, `retry`, BOM, comments,
multi-line data, incomplete final events, and total-body limits. Keep the WHATWG
framer independent from JSON conversion.

## Implementation phases

### ✅ Phase 0 - Lock failing behavior into regression fixtures

Files:

- `modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/oas-compliance/fixtures.yaml`
- `modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/oas-compliance/fixtures-negative.yaml`
- `modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/composed-schema-lowering.yaml`
- New OAS 3.0 nullable and multipart fixtures under
  `modules/openapi-generator/src/test/resources/3_0/cpp-boost-beast-client/`.
- `CppBoostBeastClientCodegenTest.java`.

Supplement the existing fixtures with every missing row in the acceptance matrix
below; do not replace current coverage. Tests must initially fail for the expected
reason, not merely because generated C++ does not compile.

Definition of done:

- Each confirmed issue has a focused fixture and expected semantic outcomes.
- Gate A distinguishes generation failure, compile failure, decode acceptance, decode
  rejection, and round-trip mismatch.

### ✅ Phase 1 - Semantic descriptors and normalization ownership

Implement `CompositionDescriptor`, branch descriptors, schema locations, reference
resolution, and generator-specific normalization preservation.

Definition of done:

- No lowering decision depends only on C++ type strings.
- Branch counts and assertion metadata survive normalization, inline extraction, and
  alias resolution.
- Existing large fixtures still generate, even if later runtime tests remain red.

### ✅ Phase 2 - Generated validator foundation and numeric semantics

Add validation result/path helpers, primitive assertion validators, mathematical
integer matching, and fail-closed unsupported-keyword detection.

Definition of done:

- `oneOf`/`anyOf` membership calls validators, not conversion attempts.
- Model and response conversion share the same numeric helpers.
- Unsupported membership assertions stop generation with schema-path diagnostics.

### ✅ Phase 3 - Sound oneOf/anyOf storage and null cardinality

Replace blind deduplication with tagged branches or validated type erasure. Restrict
set-equivalent optimizations to cases proven by descriptors.

Definition of done:

- Duplicate C++ branch types retain distinct schema identities.
- Enum-only `anyOf`, all-null `anyOf`, duplicate-null `oneOf`, and constrained numeric
  compositions pass the runtime matrix.
- `[T, null] -> optional<T>` runs only after a proof that it preserves semantics.

### ✅ Phase 4 - Validation-neutral discriminators

Rework mapping validation and runtime dispatch without changing composition outcomes.

Definition of done:

- Known, unknown, and missing discriminator values produce the same validity result as
  evaluating the underlying composition without a discriminator.
- Mappings improve diagnostics/performance only.

### ✅ Phase 5 - Recursive allOf intersection

Replace shallow conflict scanning and last-write behavior with recursive intersection
descriptors and combined validators.

Definition of done:

- Same-property constraints intersect correctly through nested references.
- Optional impossible properties are rejected when present but do not invalidate an
  otherwise valid object.
- Required impossible intersections fail generation as unsatisfiable.

### ✅ Phase 6 - Null-capable objects and tri-state fields

Add `NullableField<T>`, schema-level nullable object aliases, and version-aware OAS 3.0
nullable handling.

Definition of done:

- Missing, null, and value survive decode/encode round trips.
- Required nullable fields reject missing but accept explicit null.
- Nullable object roots and references accept object or null as defined.

### ✅ Phase 7 - Status-aware responses and response metadata

Add operation response unions, no-content branches, exact/range/default precedence,
and response headers/content type from transport.

Definition of done:

- All declared successful statuses return a generated result branch.
- Heterogeneous 2xx and mixed body/no-body operations never fall through as unexpected.
- Actual response content type selects the correct schema deserializer.

### ✅ Phase 8 - Multipart encoding fidelity

Propagate `CodegenEncoding`, explicit part content types, headers, and defaults through
API and transport templates.

Definition of done:

- Wire-level multipart tests assert each part's Content-Type and payload.
- Explicit Encoding Object metadata overrides defaults.

### ✅ Phase 9 - SSE contract and documentation correction

Introduce strict and typed-event modes, rename vendor extensions, and correct all
claims in generated and generator documentation.

Files:

- `README.mustache`.
- `docs/generators/cpp-boost-beast-client.md` through normal doc generation.
- `model-source.mustache` validation scope comment.
- SSE API and transport templates/tests.

Definition of done:

- No text says composition is "fully supported" without qualification.
- The support matrix states generated-validator coverage and fail-closed keywords.
- Typed SSE is identified as a convention, not core OAS behavior.

### ✅ Phase 10 - Regenerate samples and run release gates

Regenerate the checked-in Petstore sample only after templates and APIs stabilize.
Update sample tests for intentional breaking response/nullability APIs.

Definition of done:

- Generator tests, Gate A, generated C++ warning-as-error builds, runtime tests, sample
  tests, and documentation generation all pass.
- Generated sample diff contains no hand edits.

## Acceptance matrix

| Case | Schema/payload | Expected result |
| --- | --- | --- |
| oneOf constrained numbers | `multipleOf: 3` vs `multipleOf: 5`, value `2` | Reject: zero matches |
| oneOf constrained numbers | Same schema, value `9` | Accept branch 0 |
| oneOf constrained numbers | Same schema, value `15` | Reject: two matches |
| integer mathematical form | `oneOf [integer, number]`, value `1.0` | Reject: two matches |
| number only | `type: number`, value `1` | Accept |
| anyOf enum union | enums `[red]` and `[blue]`, value `green` | Reject |
| anyOf enum union | Same schema, value `blue` | Accept |
| all-null anyOf | Two null branches, value `null` | Accept |
| all-null anyOf | Two null branches, value `{}` | Reject |
| duplicate-null oneOf | Two null branches, value `null` | Reject: two matches |
| discriminator known overlap | Mapping points to A, payload matches A and B | Reject oneOf |
| discriminator unknown | Unknown value, payload structurally matches exactly A | Accept if A permits value |
| discriminator missing | Payload matches exactly one branch | Accept |
| allOf enum intersection | `[a,b]` intersect `[b,c]`, value `b` | Accept |
| allOf enum intersection | Same schema, value `a` | Reject |
| optional impossible allOf property | String and integer constraints, property missing | Accept object |
| optional impossible allOf property | Same schema, property present | Reject object |
| OAS 3.0 nullable object | Root and property values `null` | Accept and round-trip null |
| optional nullable property | Missing, null, and value inputs | Preserve all three states |
| successful response union | `200 Foo`, `201 Bar`, `204` | Return corresponding tagged branch |
| range/default response | Exact + `2XX` + default | Apply exact, range, default precedence |
| multipart encoding | Explicit `image/png` part | Emit `Content-Type: image/png` |
| multipart default | Binary part without encoding | Emit specification default |
| SSE strict mode | `text/event-stream` without event-data extension | Expose framed event/raw data contract |
| SSE typed mode | Explicit JSON event-data schema | Decode each data payload against schema |

## Test and verification strategy

### Java generator tests

Add focused tests for:

- Descriptor preservation before and after normalization.
- Semantic simplification proofs.
- Unsupported assertion diagnostics with JSON Pointer locations.
- Recursive allOf intersections and unsatisfiable detection.
- Version-aware nullable handling.
- Response-union metadata and multipart encoding propagation.

Primary command:

```sh
./mvnw -pl modules/openapi-generator -am \
  -Dtest=CppBoostBeastClientCodegenTest,CppBoostBeastClientApiCodegenTest \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

### Generated C++ semantic tests

Extend Gate A to compile and run generated fixtures. Type inventory alone is not a
semantic gate. Generate a small C++ executable that loads positive and negative JSON
cases and asserts acceptance, rejection, selected branch, status branch, and
round-trip output.

Compile with:

```sh
cmake -S <generated> -B <generated>/build \
  -DCMAKE_EXPORT_COMPILE_COMMANDS=1 \
  -DCMAKE_CXX_FLAGS="-Wall -Wextra -Wpedantic -Werror"
cmake --build <generated>/build --parallel 2
ctest --test-dir <generated>/build --output-on-failure
```

### External conformance corpus

Vendor or pin the relevant subsets of the official JSON Schema Test Suite for:

- `type`.
- `enum` and `const`.
- `oneOf`, `anyOf`, `allOf`, and `not`.
- Numeric, string, array, and object assertions implemented by generated validators.

Record the upstream revision and do not silently skip cases. Unsupported vocabulary
cases must be listed as explicit expected generation failures until implemented.

### Transport/runtime tests

Extend `http_client_test.cpp` and API tests to cover:

- Actual response Content-Type propagation.
- Exact/range/default response selection.
- `200`/`201`/`204` result branches.
- Multipart part headers and explicit encoding content types.
- SSE strict versus typed-event behavior.
- Existing split BOM, CRLF, multi-line data, large-event, and body-limit behavior.

### Full project gates

Before final delivery:

1. Run targeted Java tests.
2. Run Gate A generation, compile, and semantic runtime tests.
3. Build the generated Petstore sample warning-free.
4. Run Petstore CTest suites.
5. Run relevant formatting/checkstyle/forbidden-API checks.
6. Run documentation generation and verify no manual generated-doc drift.
7. Run `git diff --check`.

## Documentation and feature metadata

Replace broad claims with a table containing:

- Keyword.
- Type representation.
- Generated validation assertions.
- Fail-closed assertions.
- Known representation limits.
- OAS 3.0 versus 3.1 differences.

Specific changes:

- Remove `oneOf`/`anyOf`/`allOf` "fully supported" wording from
  `README.mustache` until every accepted composition is validated correctly.
- Explain that `std::variant` is storage, not proof of schema exclusivity.
- Document mathematical integer behavior and C++ destination range limits.
- Document discriminator neutrality.
- Document response-union API migration.
- Document `NullableField<T>` migration and tri-state examples.
- Document strict versus typed SSE modes.
- Regenerate `docs/generators/cpp-boost-beast-client.md`; do not edit generated
  support metadata by hand.

## Compatibility and migration

These changes are intentionally breaking where the existing API cannot represent the
specification:

- Duplicate-type compositions gain tagged branches or generated wrapper classes.
- Optional nullable properties change from `std::optional<T>` to `NullableField<T>`.
- Nullable object schemas gain payload types plus null-capable aliases.
- Heterogeneous success operations return named response unions.
- Typed SSE becomes explicit rather than automatic in strict mode.

Mitigation:

- Preserve current signatures for single-shape operations and non-nullable fields.
- Provide generated helper factories/accessors instead of exposing raw tags.
- Add a migration section with before/after C++ examples.
- If compatibility options are added, mark them transitional and test both modes.
- Do not allow a compatibility option to re-enable silent schema-invalid acceptance
  while documentation labels the mode compliant.

## Risk register

| Risk | Mitigation |
| --- | --- |
| Normalizer erases semantics before descriptors exist | Generator-specific normalizer ownership plus descriptor-preservation tests |
| Generated validators substantially increase code size | Deduplicate validators by stable schema identity and reuse referenced validators |
| Recursive schemas recurse indefinitely during validation | Pair schema-reference cycle guards with instance-location recursion tracking |
| Pattern semantics differ from ECMA-262 | Support a documented subset and fail closed outside it |
| Arbitrary-precision JSON numbers exceed Boost/C++ storage | Separate matching from conversion and produce explicit representation errors |
| Tagged branches make APIs harder to use | Generate named factories, predicates, and accessors |
| allOf intersections become complex or unsatisfiable | Use a formal intersection descriptor and deterministic generation diagnostics |
| Response metadata breaks custom HttpClient implementations | Add a default metadata adapter and preserve legacy execute methods |
| Tri-state migration affects many models | Restrict wrapper to optional nullable fields and provide migration examples |
| External JSON Schema suite is too broad initially | Pin tested vocabularies and list unsupported cases explicitly; never silently skip |
| SSE behavior is relied on by existing users | Offer an explicit transitional typed-event mode with deprecation guidance |

## Suggested commit sequence

1. `test(cpp-boost-beast): add specification regression matrix`
2. `refactor(cpp-boost-beast): preserve composition schema descriptors`
3. `feat(cpp-boost-beast): generate branch validators`
4. `fix(cpp-boost-beast): enforce mathematical numeric semantics`
5. `fix(cpp-boost-beast): preserve oneOf and anyOf branch identity`
6. `fix(cpp-boost-beast): make discriminator dispatch validation-neutral`
7. `fix(cpp-boost-beast): implement recursive allOf intersection`
8. `feat(cpp-boost-beast): add nullable field state`
9. `feat(cpp-boost-beast): generate status-aware response unions`
10. `fix(cpp-boost-beast): honor multipart encoding metadata`
11. `refactor(cpp-boost-beast): make typed SSE explicit`
12. `docs(cpp-boost-beast): publish precise compliance scope`
13. `chore(cpp-boost-beast): regenerate samples`

Each commit must keep generated C++ compilable and include its focused tests. Do not
combine sample regeneration with semantic implementation commits.

## Definition of done

The remediation is complete only when all conditions are true:

- Every confirmed issue C-01 through C-11 has a regression test and passing outcome.
- Every row in the acceptance matrix passes through executable Gate A semantic tests.
- Composition membership is computed from branch validators, never conversion success
  or deduplicated C++ types alone.
- Discriminators do not change validity.
- Mathematical integer semantics are covered for `int64`, `uint64`, and integral
  `double` Boost JSON storage.
- `allOf` contributors are recursively intersected or generation fails closed.
- Optional nullable properties preserve missing, null, and value.
- Every declared successful response is representable and returned.
- Multipart parts honor Encoding Object metadata.
- Typed SSE is explicit and documented as a convention.
- Unsupported assertion keywords produce deterministic generation errors rather than
  false validation success.
- Generated documentation no longer overstates support.
- Targeted Maven tests, Gate A semantic tests, warning-as-error C++ builds, sample
  tests, documentation checks, and `git diff --check` all pass.

## Normative references

- OpenAPI 3.1.1: https://spec.openapis.org/oas/v3.1.1.html
- OpenAPI 3.0.4: https://spec.openapis.org/oas/v3.0.4.html
- JSON Schema Draft 2020-12 Core:
  https://json-schema.org/draft/2020-12/json-schema-core.html
- JSON Schema Draft 2020-12 Validation:
  https://json-schema.org/draft/2020-12/json-schema-validation.html
- JSON Schema composition guide:
  https://json-schema.org/understanding-json-schema/reference/combining
- WHATWG Server-Sent Events:
  https://html.spec.whatwg.org/multipage/server-sent-events.html
- Official JSON Schema Test Suite:
  https://github.com/json-schema-org/JSON-Schema-Test-Suite
