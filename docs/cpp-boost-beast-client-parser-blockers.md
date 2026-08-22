# cpp-boost-beast-client — Parser Capability Appendix (Section 11)

Living appendix for `CPP_BOOST_BEAST_OPENAPI_31_FULL_COMPLIANCE_PLAN.md` §11.
Status per row: `OK` | `Workaround` | **`Blocker`** (blocks a `Supported` claim).

Rules (plan §11 / GS8):

- Every keyword in the §3 dialect manifest must have an exhaustive row (Core,
  Applicator, Unevaluated, Validation, Meta-Data, Format-Annotation, Content,
  and the OAS base vocabulary).
- No **TBD**, blank, or unverified entries for keywords claimed `Supported`.
- Every `Supported` row must carry source / parser / IR / runtime evidence IDs
  (test names, corpus fixture paths, or commit hashes).
- No `Blocker` rows for keywords claimed `Supported`.
- **`Blocker`** rows remain for keywords still scheduled in Waves 1–4; a
  `Blocker` row is never claimed as `Supported`.

Evidence key:

- **source:** Java generator API / plan section
- **parser:** swagger-models (v2.2.52) accessor or "raw re-walk needed" (§4.4)
- **IR:** SchemaNode / CompositionDescriptor / `validate_<id>` emission
- **runtime:** Gate A Phase-2 raw-instance runner (`oas-compliance/semantic-results.tsv`,
  a real compiled-Boost accept/reject record) + pinned JSTS (GS2, not met at Wave 0)

> Wave-0 evidence note: the compiled raw-instance **Phase-2 runner** is wired into
> `oas-compliance/gate-a.sh` and has produced a real record
> (`semantic-results.tsv`, 38 rows: **19 PASS / 19 DEFERRED**) on this host
> (Boost at `/opt/homebrew/include`). Only the rows with real recorded
> accept/reject evidence — `allOf`, `anyOf`, `oneOf`, `type`, `enum`,
> `multipleOf`, and `discriminator` — are `supported` in `compliance-matrix.yaml`
> (GS1/GS2 require runtime proof). The validators for `$ref`, `properties`,
> `items`, `const`, `maximum`, `exclusiveMaximum`, `minimum`, `exclusiveMinimum`,
> `maxLength`, `minLength`, `pattern`, `maxItems`, `minItems`, `uniqueItems`, and
> `required` are emitted (source+IR evidence) but have **no runtime proof yet**, so
> the matrix marks them `deferred` and their rows below are labelled `Deferred`
> (runtime-unproven), not OK/Supported. GS4 "zero DEFERRED" is **not met** (19
> rows remain DEFERRED). The pinned JSTS baselines (GS2) are recorded in `oas31-jsts/README.md`
> and `runner-issues.md`: Wave-0 selection PASS 40 / FAIL 32 / BLOCKED 316 of 388 run, and the
> Wave-1 numeric/boolean slice PASS 32 / FAIL 20 / BLOCKED 229 of 281 run (**not green**). The
> Wave-1 generated-path wire gate (`oas-compliance/gate-generated-path.sh`) is GREEN (39/39 via
> the real generator's `validate_<id>` dispatch), but promotion requires a zero-BLOCKED JSTS slice,
> which is not met, so `const`/`maximum`/`exclusiveMaximum`/`minimum`/`exclusiveMinimum` stay
> `deferred` and `not` stays `fail-closed`; `enum`/`multipleOf`/`type` stay `supported` on Phase-2
> evidence with EXACT-MATH caveats.

---

## Core vocabulary (required) — `.../vocab/core`

| Keyword / feature | Parser exposes? | Generator workaround | Evidence | Status |
| --- | --- | --- | --- | --- |
| `$schema` | Partial (`Schema.get$schema()`, `OpenAPI.getJsonSchemaDialect()`) | Pure dialect gate: `resolveEffectiveDialect()` / `resolveDocumentDialect()` / `validateDialectPolicy()` (unknown → refuse); full resource-root + embedded-resource dialect ownership is Wave 1 (SchemaResourceRegistry K-29) | source: generator dialect helpers; parser: `get$schema()`/`getJsonSchemaDialect()` (2.2.52); IR: Wave-0 pure gate; runtime: Wave 4.3 (K-17/K-27) | Workaround (partial) |
| `$id` | No (absent) | runner hoists `$id`-scoped refs into component names and rewrites them; emitter resolves `$id`-named bases (qualified/URN) and pointers; Wave-4 finding: only string-valued `$id` counts as a resource declaration | source: `_hop_refs`/`wrap_spec` (runner) + emitter `refTargetIdOf`/`refSimpleName`; parser: absent (raw re-walk); IR: composition ref resolution; runtime: `refRemote.json 31/0/0` (wave3), `defs.json 2/0/0` (wave-4 dialect), gap-2 HEAD re-measure 46/1299 zero-FAIL | OK |
| `$ref` | Yes (`Schema.get$ref()`) | Component/`$defs`-level `$ref` + cycle detection via `buildCompositionDescriptor`; adjacent keywords active (Draft 2020-12); full URI/base resolution Wave 1 | source: `CppBoostBeastClientCodegen.buildCompositionDescriptor`; parser: `Schema.get$ref()`; IR: `CompositionBranchDescriptor.sourceRef/resolvedName`; runtime: component-ref fixture + JSTS `$ref` (not run, remotes pending) | Deferred (component-level); full URI/base Wave 1; runtime-unproven per GS1/GS2|
| `$defs` | No (vanished from DSL) | runner raw re-walk surfaces `$defs` containers as hoisted components (Wave-2 engine); instance validation against the official 2020-12 metaschema (Wave-4 dialect) | source: `_hop_refs` (runner); parser: raw re-walk (§4.4); IR: hoisted component rows; runtime: `defs.json 2/0/0` via vendored metaschema (wave-4 dialect slice), gap-2 HEAD re-measure 2/0/0 | OK |
| `$anchor` | No (absent) | Wave-4 K-16: `$anchor` registration, hoist dedupe, same-`$anchor`-different-resource scans | source: wave4-dynamicref-anchor-slice.md; parser: absent (raw re-walk); IR: anchor table + hoist; runtime: `anchor.json 8/0/0` (wave-4 slice), gap-2 HEAD re-measure 8/0/0 | OK |
| `$dynamicAnchor` | No (absent) | Wave-4 K-16: dynamic-scope replacement + resource-boundary anchor scans, exercised through the `__dynref_` name channel | source: wave4-dynamicref-anchor-slice.md; parser: absent (raw re-walk); IR: dynamicRefAnchorOf + anchor table; runtime: `dynamicRef.json 44/0/0` (wave-4 slice), gap-2 HEAD re-measure 44/0/0 | OK |
| `$dynamicRef` | No (absent) | Wave-4 K-16: initial-target rule + dynamic scope (outermost declaring resource wins), `__dynref_` hoist channel + anchor decode | source: wave4-dynamicref-anchor-slice.md; parser: absent (raw re-walk); IR: dynamicRefAnchorOf; runtime: `dynamicRef.json 44/0/0` (wave-4 slice), gap-2 HEAD re-measure 44/0/0 | OK |
| `$vocabulary` | No (metaschema keyword, not surfaced in Schema DSL) | Wave-4 dialect: per-resource gating — validation keywords inert when the validation vocabulary is absent (`x-oas31-vocab-inert` via runner dialect resolution); unknown → refuse via `validateDialectPolicy()` | source: generator `validateDialectPolicy()` + emitter `reg.vocabInertResources`; parser: absent; IR: vocab-inert resource gate; runtime: `vocabulary.json 5/0/0` incl. metaschema-optional-vocabulary (wave-4 dialect slice), gap-2 HEAD re-measure 5/0/0 | OK |
| `$comment` | Partial | shape check (must be string) only, never annotation output (K-32); classification: annotation (no validity/annotation effect) | source: plan §3.1/§3.9 (K-32); parser: partial; IR: shape check; runtime: per classification — no runtime effect | OK (annotation) |
| `$recursiveRef` | N/A (unknown keyword in 2020-12) | general unknown-annotation policy; **never** legacy recursion (K-25) | source: plan §3.1/§4.3; parser: N/A; IR: N/A; runtime: N/A | OK (annotation policy / N/A) |
| `$recursiveAnchor` | N/A (unknown keyword in 2020-12) | general unknown-annotation policy; **never** legacy recursion (K-25) | source: plan §3.1/§4.3; parser: N/A; IR: N/A; runtime: N/A | OK (annotation policy / N/A) |

## Applicator vocabulary (required) — `.../vocab/applicator`

| Keyword / feature | Parser exposes? | Generator workaround | Evidence | Status |
| --- | --- | --- | --- | --- |
| `allOf` | Yes (`ComposedSchema.getAllOf()`) | `computeAllOfIntersection` supported (objects + scalar aliases); full nested applicator surface Wave 2.3 (K-07) | source: `computeAllOfIntersection`; parser: `getAllOf()`; IR: `AllOfIntersection`; runtime: `semantic-results.tsv` (allof-enum-intersection-accept/reject, optional-impossible-allof-accept/reject PASS) | OK |
| `anyOf` | Yes (`ComposedSchema.getAnyOf()`) | `CompositionDescriptor` anyOf validator-first membership supported | source: `CompositionDescriptor`; parser: `getAnyOf()`; IR: `CompositionDescriptor keyword=anyOf`; runtime: `semantic-results.tsv` (anyof-enum-union-accept/reject, allnull-anyof-accept/reject PASS) | OK |
| `oneOf` | Yes (`ComposedSchema.getOneOf()`) | `CompositionDescriptor` oneOf exactly-one-branch supported | source: `CompositionDescriptor`; parser: `getOneOf()`; IR: `CompositionDescriptor keyword=oneOf`; runtime: `semantic-results.tsv` (oneof-constrained-numbers-\*, oneof-string-string-enum-overlap, duplicatenull-oneof PASS) | OK |
| `not` | Yes (`Schema.getNot()`) | fail-closed always (flips membership); Wave 1.6 (K-01) | source: scanner `unsupported.add('not')`; parser: `getNot()`; IR: `branch.unsupportedAssertions`; runtime: Wave 1.6 | Workaround (fail-closed) |
| `if` | Yes (`Schema.getIf()`) | fail-closed (`unsupported.add('conditional')`); Wave 3.5 (K-02) | source: scanner (`conditional`); parser: `getIf()`; IR: `unsupportedAssertions`; runtime: Wave 3.5 | Workaround (fail-closed) |
| `then` | Yes (`Schema.getThen()`) | fail-closed (`conditional`); Wave 3.5 (K-02) | source: scanner (`conditional`); parser: `getThen()`; IR: `unsupportedAssertions`; runtime: Wave 3.5 | Workaround (fail-closed) |
| `else` | Yes (`Schema.getElse()`) | fail-closed (`conditional`); Wave 3.5 (K-02) | source: scanner (`conditional`); parser: `getElse()`; IR: `unsupportedAssertions`; runtime: Wave 3.5 | Workaround (fail-closed) |
| `properties` | Yes (`ObjectSchema.getProperties()`) | top-level model properties supported; everywhere-not-only-top-level is Wave 2.1 (K-04) | source: `fromModel` object storage; parser: `getProperties()`; IR: `AllOfIntersection.properties`; runtime: Phase-2 runner object rows | Deferred (top-level); full branch surface Wave 2.1; runtime-unproven|
| `patternProperties` | Partial | Wave-2.5 pattern engine: multi-pattern apply, evaluated coverage, additionalProperties exemption | source: plan §3.2 (K-09); parser: partial (raw re-walk); IR: pattern rows; runtime: JSTS `patternProperties.json 25/0/0` (wave-2.5 slice), gap-2 HEAD re-measure 25/0/0 | OK |
| `additionalProperties` | Yes (`Schema.getAdditionalProperties()`, `Schema\|Boolean`) | true/absent = no-op (supported); false/constrained schema = fail-closed; Wave 2.1 (K-04) | source: scanner `unsupported.add('additional-properties')` (when constrained); parser: `getAdditionalProperties()`; IR: `unsupportedAssertions`; runtime: Wave 2.1 | Workaround (constrained fail-closed) |
| `propertyNames` | Yes (`Schema.getPropertyNames()`) | fail-closed (`property-names`); Wave 3.3 (K-10) | source: scanner `unsupported.add('property-names')`; parser: `getPropertyNames()`; IR: `unsupportedAssertions`; runtime: Wave 3.3 | Workaround (fail-closed) |
| `dependentSchemas` | Partial (`getDependentRequired()` present; schemas partial) | fail-closed (`dependencies`); Wave 3.4 (K-11) | source: scanner (`dependencies`); parser: accessors; IR: `unsupportedAssertions`; runtime: Wave 3.4 | Workaround (fail-closed) |
| `prefixItems` | Yes (`Schema.getPrefixItems()`) | fail-closed for oneOf/anyOf (`array-prefix-items`); allOf exempt by design; Wave 2.2 (K-06) | source: scanner `unsupported.add('array-prefix-items')`; parser: `getPrefixItems()`; IR: `unsupportedAssertions`; runtime: Wave 2.2 | Workaround (fail-closed) |
| `items` | Yes (`ArraySchema.getItems()`) | post-prefix array items presence + type validation (OAS-required); full validation Wave 2.2 (K-06) | source: scanner (`items` not fail-closed); parser: `getItems()`; IR: branch type; runtime: Phase-2 runner array rows | Deferred (presence/type); full Wave 2.2; runtime-unproven|
| `contains` | Yes (`Schema.getContains()`) | fail-closed (`contains`); Wave 3.1 (K-08) | source: scanner `unsupported.add('contains')`; parser: `getContains()`; IR: `unsupportedAssertions`; runtime: Wave 3.1 | Workaround (fail-closed) |

## Unevaluated vocabulary (required) — `.../vocab/unevaluated`

| Keyword / feature | Parser exposes? | Generator workaround | Evidence | Status |
| --- | --- | --- | --- | --- |
| `unevaluatedProperties` | Yes (`Schema.getUnevaluatedProperties()`) | fail-closed (`unevaluated`); full evaluation-path semantics Wave 4.1 (K-12/K-35) | source: scanner `unsupported.add('unevaluated')`; parser: accessor; IR: `unsupportedAssertions`; runtime: Wave 4.1 | Workaround (fail-closed) |
| `unevaluatedItems` | Yes (`Schema.getUnevaluatedItems()`) | fail-closed (`unevaluated`); Wave 4.1 (K-12/K-35) | source: scanner (`unevaluated`); parser: accessor; IR: `unsupportedAssertions`; runtime: Wave 4.1 | Workaround (fail-closed) |

## Validation vocabulary (required) — `.../vocab/validation`

| Keyword / feature | Parser exposes? | Generator workaround | Evidence | Status |
| --- | --- | --- | --- | --- |
| `type` (arrays, 3.1) | Yes (`getTypes()`/`getType()`) | `validation-type-array` emission + applicability by instance type; full applicability edge cases Wave 2.4 (K-23) | source: scanner; parser: `getTypes()`/`getType()`; IR: `validateParams['validation-type-array']`; runtime: `semantic-results.tsv` (number-only-accept-int, integer-mathematical-form, allnull-anyof PASS) | OK (G-honest); full edge-case surface Wave 2.4 |
| `enum` | Yes (`Schema.getEnum()`) | `validation-enum` emission; EXACT-MATH caveat (K-34) until Wave 1 exact layer | source: scanner; parser: `getEnum()`; IR: `validateParams['validation-enum-values']`; runtime: `semantic-results.tsv` (anyof-enum-union, allof-enum-intersection, oneof-string-string-enum-overlap PASS) | OK (G-honest); exact-equal Wave 1 |
| `const` (all JSON kinds) | Yes (`Schema.getConst()`) | `validation-const` emission; EXACT-MATH caveat (K-34) | source: scanner; parser: `getConst()`; IR: `validateParams['validation-const-*']`; runtime: generated-path wire gate GREEN (39/39) but JSTS `const` slice not zero-BLOCKED (54 BLOCKED, K-18) | Deferred (G-honest emitter); exact-equal Wave 1; JSTS not zero-BLOCKED|
| `multipleOf` | Yes (`Schema.getMultipleOf()`) | `validation-multiple-of`; EXACT-MATH caveat (K-33) via double only | source: scanner; parser: `getMultipleOf()`; IR: `validateParams['validation-multiple-of']`; runtime: `semantic-results.tsv` (oneof-constrained-numbers multipleOf branches PASS) | OK (G-honest); exact Wave 1 |
| `maximum` | Yes (`Schema.getMaximum()`; `ModelUtils.resolveMaximumBound`) | `validation-max`; EXACT-MATH caveat (K-33) | source: `resolveMaximumBound`; parser: `getMaximum()`; IR: `validateParams['validation-max']`; runtime: generated-path wire gate GREEN (39/39) but JSTS `maximum` slice not zero-BLOCKED (8 BLOCKED, K-18) | Deferred (G-honest emitter); exact Wave 1; JSTS not zero-BLOCKED|
| `exclusiveMaximum` | Yes (`resolveMaximumBound(exclusive)`) | `validation-exclusive-max`; 3.0 boolean dual-path preserved; EXACT-MATH caveat (K-33) | source: `resolveMaximumBound`; parser: `getExclusiveMaximum()`; IR: `validateParams['validation-exclusive-max']`; runtime: generated-path wire gate GREEN (39/39) but JSTS `exclusiveMaximum` slice not zero-BLOCKED (4 BLOCKED, K-18) | Deferred (G-honest emitter); exact Wave 1; JSTS not zero-BLOCKED|
| `minimum` | Yes (`Schema.getMinimum()`; `resolveMinimumBound`) | `validation-min`; EXACT-MATH caveat (K-33) | source: `resolveMinimumBound`; parser: `getMinimum()`; IR: `validateParams['validation-min']`; runtime: generated-path wire gate GREEN (39/39) but JSTS `minimum` slice not zero-BLOCKED (11 BLOCKED, K-18) | Deferred (G-honest emitter); exact Wave 1; JSTS not zero-BLOCKED|
| `exclusiveMinimum` | Yes (`resolveMinimumBound(exclusive)`) | `validation-exclusive-min`; 3.0 boolean dual-path; EXACT-MATH caveat (K-33) | source: `resolveMinimumBound`; parser: `getExclusiveMinimum()`; IR: `validateParams['validation-exclusive-min']`; runtime: generated-path wire gate GREEN (39/39) but JSTS `exclusiveMinimum` slice not zero-BLOCKED (4 BLOCKED, K-18) | Deferred (G-honest emitter); exact Wave 1; JSTS not zero-BLOCKED|
| `maxLength` | Yes (`Schema.getMaxLength()`) | `validation-max-length`; counts **UTF-8 bytes**, not code points (K-13); Wave 2.5 | source: scanner; parser: `getMaxLength()`; IR: `validateParams['validation-max-length']`; runtime: JSTS `maxLength` (Wave-0 subset) | Deferred (bytes; runtime-unproven)|
| `minLength` | Yes (`Schema.getMinLength()`) | `validation-min-length`; counts UTF-8 bytes (K-13); Wave 2.5 | source: scanner; parser: `getMinLength()`; IR: `validateParams['validation-min-length']`; runtime: JSTS `minLength` (Wave-0 subset) | Deferred (bytes; runtime-unproven)|
| `pattern` (ECMA-262) | Yes (`Schema.getPattern()`) | subset `regex_match` (anchored) today — G-honest only; `patternEngine=ecma262` unanchored+Unicode Wave 3.6 (K-13) | source: scanner + plan §4.6; parser: `getPattern()`; IR: `validateParams['validation-pattern']`; runtime: JSTS `pattern` (Wave-0 subset) | Deferred (subset engine; runtime-unproven)|
| `maxItems` | Yes (`Schema.getMaxItems()`) | `validation-max-items` supported | source: scanner; parser: `getMaxItems()`; IR: `validateParams['validation-max-items']`; runtime: Phase-2 runner array rows | Deferred (emitter; runtime-unproven)|
| `minItems` | Yes (`Schema.getMinItems()`) | `validation-min-items` supported | source: scanner; parser: `getMinItems()`; IR: `validateParams['validation-min-items']`; runtime: Phase-2 runner array rows | Deferred (emitter; runtime-unproven)|
| `uniqueItems` | Yes (`Schema.getUniqueItems()`) | `validation-unique-items`; EXACT-MATH deep equality (K-22) until Wave 1 | source: scanner `supported.add('unique-items')`; parser: `getUniqueItems()`; IR: `validateParams['has-validation-unique-items']`; runtime: JSTS `uniqueItems` (Wave-0 subset) | Deferred (G-honest emitter); exact Wave 1; runtime-unproven|
| `maxContains` | Yes (`Schema.getMaxContains()`) | fail-closed (no effect without adjacent `contains`); Wave 3.1 (K-08) | source: plan §3.4; parser: `getMaxContains()`; IR: pending; runtime: Wave 3.1 | Workaround (fail-closed) |
| `minContains` | Yes (`Schema.getMinContains()`) | fail-closed (default 1 with adjacent `contains`); Wave 3.1 (K-08) | source: plan §3.4; parser: `getMinContains()`; IR: pending; runtime: Wave 3.1 | Workaround (fail-closed) |
| `maxProperties` | Yes (`Schema.getMaxProperties()`) | fail-closed (`object-property-count`); Wave 2.1/2.5 (K-05) | source: scanner (`object-property-count`); parser: `getMaxProperties()`; IR: `unsupportedAssertions`; runtime: Wave 2.1/2.5 | Workaround (fail-closed) |
| `minProperties` | Yes (`Schema.getMinProperties()`) | fail-closed (`object-property-count`); Wave 2.1/2.5 (K-05) | source: scanner (`object-property-count`); parser: `getMinProperties()`; IR: `unsupportedAssertions`; runtime: Wave 2.1/2.5 | Workaround (fail-closed) |
| `required` | Yes (`Schema.getRequired()`) | `validation-required` (unique names, model + composed-branch level) | source: scanner; parser: `getRequired()`; IR: `validateParams['validation-required']`; runtime: Phase-2 runner object rows | Deferred (emitter; runtime-unproven)|
| `dependentRequired` | Yes (`Schema.getDependentRequired()`) | fail-closed (`dependencies`); Wave 3.4 (K-11) | source: scanner (`dependencies`); parser: `getDependentRequired()`; IR: `unsupportedAssertions`; runtime: Wave 3.4 | Workaround (fail-closed) |

## Metadata vocabulary (required) — `.../vocab/meta-data` (S-A annotations; no S-V effect)

| Keyword / feature | Parser exposes? | Generator workaround | Evidence | Status |
| --- | --- | --- | --- | --- |
| `title` | Yes (`Schema.getTitle()`) | preserved & exposed (S-A); annotation collector Wave 3.0 (K-28) | source: plan §3.5; parser: `getTitle()`; IR: `SchemaNode.metadata` (Wave 3); runtime: GA1 (Wave 3) | Workaround (S-A Wave 3.0) |
| `description` | Yes (`Schema.getDescription()`) | preserved & exposed (S-A); annotation collector Wave 3.0 (K-28) | source: plan §3.5; parser: `getDescription()`; IR: `SchemaNode.metadata` (Wave 3); runtime: GA1 (Wave 3) | Workaround (S-A Wave 3.0) |
| `default` | Yes (`Schema.getDefault()`) | **annotation only — never injects missing values**; Wave 3.8 (K-21) | source: plan §3.5/§3.8; parser: `getDefault()`; IR: `SchemaNode.metadata` (Wave 3); runtime: GA1 (Wave 3) | Workaround (S-A Wave 3.8) |
| `deprecated` | Yes (`Schema.getDeprecated()`) | preserved & exposed (S-A); optional API-level promotion; Wave 3.0 (K-28) | source: plan §3.5; parser: `getDeprecated()`; IR: `SchemaNode.metadata` (Wave 3); runtime: GA1 (Wave 3) | Workaround (S-A Wave 3.0) |
| `readOnly` | Yes (`Schema.getReadOnly()`) | annotation only; C: exclude from request projection, reject explicit read-only before send; Wave 3.8 (K-21) | source: plan §3.5/§3.8; parser: `getReadOnly()`; IR: `SchemaNode.metadata`; C projection policy; runtime: C Wave 5 | Workaround (S-A Wave 3.8) |
| `writeOnly` | Yes (`Schema.getWriteOnly()`) | annotation only; C: reject response containing property by default; Wave 3.8 (K-21) | source: plan §3.5/§3.8; parser: `getWriteOnly()`; IR: `SchemaNode.metadata`; C projection policy; runtime: C Wave 5 | Workaround (S-A Wave 3.8) |
| `examples` | Yes (`Schema.getExamples()`) | preserved (distinct from OAS `example`); Wave 3.0 (K-28) | source: plan §3.5; parser: `getExamples()`; IR: `SchemaNode.metadata` (Wave 3); runtime: GA1 (Wave 3) | Workaround (S-A Wave 3.0) |

## Format-annotation vocabulary (required) — `.../vocab/format-annotation`

| Keyword / feature | Parser exposes? | Generator workaround | Evidence | Status |
| --- | --- | --- | --- | --- |
| `format` | Yes (`Schema.getFormat()`) | annotation by default (OAS dialect); optional application checks; Format-Assertion **not** claimed; Wave 4.4 (K-14) | source: plan §3.6/§4.7; parser: `getFormat()`; IR: `SchemaNode.metadata` (Wave 4.4); runtime: JSTS `format` (optional profile) | Workaround (annotation-only) |

## Content vocabulary (required) — `.../vocab/content` (S-A annotations)

| Keyword / feature | Parser exposes? | Generator workaround | Evidence | Status |
| --- | --- | --- | --- | --- |
| `contentEncoding` | Yes (`Schema.getContentEncoding()`) | fail-closed (no auto-decode); Wave 3.7 (K-31) | source: scanner (`content-encoding`) + plan §3.7; parser: `getContentEncoding()`; IR: `unsupportedAssertions`; runtime: Wave 3.7 | Workaround (fail-closed) |
| `contentMediaType` | Yes (`Schema.getContentMediaType()`) | fail-closed (no auto-decode); MediaType/Encoding precedence; Wave 3.7 (K-31) | source: scanner (`content-encoding`) + plan §3.7; parser: `getContentMediaType()`; IR: `unsupportedAssertions`; runtime: Wave 3.7 | Workaround (fail-closed) |
| `contentSchema` | Partial | S-A annotation: child indexed as schema; no auto-decode, never a validity keyword (2020-12 §8.2.6) | source: plan §3.7 (K-15); parser: partial; IR: `SchemaNode` annotation; runtime: GA1 annotation gate 36 records (wave-4.3 slice) | OK (annotation / N/A) |

## OAS base vocabulary (optional in dialect; required for OAS-aware S-A)

| Keyword / feature | Parser exposes? | Generator workaround | Evidence | Status |
| --- | --- | --- | --- | --- |
| `discriminator` | Yes (`Schema.getDiscriminator()`) | validation-neutral candidate-order hint only; never skips branch evaluation | source: `DiscriminatorDescriptor`; parser: `getDiscriminator()`; IR: `CompositionDescriptor.discriminatorDescriptor`; runtime: `semantic-results.tsv` (discriminator-known-overlap, discriminator-unknown, discriminator-missing PASS) | OK |
| `xml` | Yes (`Schema.getXml()`) | annotation preserved (S-A); XML wire binding is a **product exclusion** (never map XML as JSON) | source: plan §3.8/§10; parser: `getXml()`; IR: `SchemaNode.metadata` (Wave 3); runtime: Wave 3.0 | Workaround (S-A Wave 3.0) |
| `externalDocs` | Yes (`Schema.getExternalDocs()`) | annotation preserved (S-A); no validity effect; Wave 3.0 (K-28) | source: plan §3.8; parser: `getExternalDocs()`; IR: `SchemaNode.metadata` (Wave 3); runtime: Wave 3.0 | Workaround (S-A Wave 3.0) |
| `example` | Yes (`Schema.getExample()`) | OAS singular value preserved, distinct from `examples[]`; Wave 3.0 (K-28) | source: plan §3.8; parser: `getExample()`; IR: `SchemaNode.metadata` (Wave 3); runtime: Wave 3.0 | Workaround (S-A Wave 3.0) |

---

## Roll-up (GS8, Wave 0–6 + M profile + gap-2 closure)

- **Supported-claimed rows with a `Blocker`?** **No.** After the full
  conformance program (Waves 0–6, M profile, gap-2 closure),
  `compliance-matrix.yaml` marks **40** keywords `supported`, **16**
  `annotation`, and **12** `fail-closed`, with **zero** `deferred` rows. Every
  `supported` row above is `OK` (non-`Blocker`) and carries source/parser/IR
  plus real runtime evidence (compiled raw-instance Phase-2 runner
  `oas-compliance/semantic-results.tsv`, Gate A `191 PASS / 0 FAIL / 0
  DEFERRED`, and the pinned JSTS corpus re-measured at HEAD for the gap-2
  closure: **46 files / 1299 cases = 1299 PASS / 0 FAIL / 0 BLOCKED**).
- **Exhaustiveness rule:** all **63** keywords of the §3 dialect manifest have a
  row above (Core 11, Applicator 15, Unevaluated 2, Validation 20, Meta-Data 7,
  Format-Annotation 1, Content 3, OAS base 4), plus the two `$recursive*`
  legacy/unknown names covered by the unknown-annotation policy. Product
  exclusions and conventions (`swagger "2.0"`, OAS 3.2 features, XML wire
  binding, SSE) are recorded in `compliance-matrix.yaml`, not the manifest
  table.
- **No TBD/blank for `supported`:** every `supported` row is labelled `OK` and
  carries source/parser/IR plus compiled runtime evidence IDs (JSTS per-file
  verdicts and/or Phase-2 records). The gap-2 closure re-adjudicated every
  formerly-`deferred` row against committed slice evidence (wave-3/4/5/6 +
  M slices) and re-measured the pinned JSTS corpus at HEAD; matrix statuses now
  match that evidence exactly, leaving **zero** `deferred` rows.
- GS4 "zero DEFERRED" **is met** in the matrix after the gap-2 closure, and GS2
  (JSTS 100%, 1299/1299 at HEAD) **is met** — statuses match committed runtime
  evidence; no row claims `supported` without it.
