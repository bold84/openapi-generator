# cpp-boost-beast-client Full OpenAPI 3.1 Compliance Plan

## Document status

| Field | Value |
| --- | --- |
| Target generator | `cpp-boost-beast-client` |
| Language | C++17 |
| Primary target | **OpenAPI 3.1.x** (normative: 3.1.2; equivalently supports 3.1.0, 3.1.1, 3.1.2 patch strings) |
| Schema foundation | JSON Schema Draft **2020-12** + OAS 3.1 Schema dialect / base vocabulary |
| Baseline | Remediation plan `CPP_BOOST_BEAST_OPENAPI_SPEC_COMPLIANCE_REMEDIATION_PLAN.md` (phases 0–10 ✅) |
| Branch context | From remediation HEAD → `plan/cpp-boost-beast-oas31-full` (or equivalent) |
| Companion | OpenAPI **3.0.x** dual-path must remain correct |
| Explicitly out of scope | **OpenAPI 3.2+** (no QUERY, `itemSchema`, `$self`, device flow, etc. in this program) |
| Plan revision | **v5** — OAS **3.1 only** |

---

## 1. Compliance profiles and claims

### 1.1 Four normative profiles

OAS has no formal generator conformance class. The following profiles define the scope of compliance claims in this plan:

| Profile | Name | Meaning |
| --- | --- | --- |
| **S-V** | **Schema validity** | Every keyword in the required Core, Applicator, Validation, and Unevaluated vocabularies is implemented with correct JSON Schema 2020-12 validity semantics. Required annotation vocabularies are recognized as non-validating. Includes exact JSON Number domain evaluation (see §4). |
| **S-A** | **Schema annotation** | All annotation keywords in the OAS 3.1 Schema dialect are collected, propagated, and exposed with keyword, instance location, schema-location path, absolute schema location when different, and value(s). Failed schema objects produce no annotation output. Includes metadata, Format-Annotation, Content, unknown-keyword annotations, and the OAS base vocabulary. `$comment` is expressly not an annotation. |
| **M** | **Typed C++ mapping** | Declared and representable JSON domain is mapped to C++ types via Boost.JSON DOM (or equivalent). A separate representability gate distinguishes schema-valid-but-unmappable from schema-invalid. Typed decode/encode does not serve as a validity oracle. |
| **C** | **Outbound HTTP client** | Non-schema OAS 3.1 client behaviour: parameter serialization, security hooks, request body, response handling, servers, media type negotiation. See §3.9 and §5 Wave 5. |

**"Full OAS 3.1 schema compliance"** = S-V + S-A complete (includes exact JSON number domain).
**"OAS 3.1 JSON outbound-client profile"** = S-V + S-A + M + C complete for the media types and conventions enumerated in §3.9.

Ship profiles separately; C must not block an S-V+S-A release claim.

### 1.2 Three goal levels

| Level | ID | Definition |
| --- | --- | --- |
| Honest partial | **G-honest** | Zero silent ignore: every dialect keyword is **Supported** or **Fail-closed** with tests and matrix rows. |
| Full schema | **G-full-schema** | S-V + S-A complete: every required-vocabulary keyword and every S-A OAS keyword is **Supported**, not merely fail-closed. No required-keyword product exclusion. Full exact JSON number domain validation. |
| JSON outbound client | **G-outbound-json-client** | G-full-schema + M + C DoD for the explicitly enumerated JSON-focused outbound profile. Callbacks/webhooks are inbound metadata and do not fail generation; they are preserved with visible diagnostics. Separate inbound listener and automatic Link traversal profiles are out of scope. |

**Primary program success criterion: G-full-schema.**
G-honest is a **minimum bar after every phase**, not the end state.
G-outbound-json-client is a follow-on goal.

### 1.3 What compliance is *not*

- OpenAPI **3.2+** features (sequential `itemSchema`, `query` method, `$self`, OAuth device flow fields, etc.)
- A general JSON Schema library for arbitrary non-OAS dialects
- Typed SSE JSON-per-event as core OAS (remains optional generator convention — remediation Phase 9)
- XML binding, server stubs, or full OAuth/OIDC stacks inside the library
- Claiming **G-full-schema** while any §3 required-vocabulary keyword remains fail-closed
- Claiming **G-outbound-json-client** while lacking M or C DoD
- Marketing the JSON-focused profile as an unqualified "Full OAS 3.1 client"

### 1.4 Repository reality (acknowledged baseline limitations)

The current codebase has known gaps that Wave 0/1 must fix rather than treat the baseline as G-honest:

1. **allOf unsupported-assertion exemption**: `allOf` members with assertion keywords on composition branches are selectively processed; some fail-closed logic treats them as pass-through. A full schema-valued-position scanner is required.
2. **Current scanner misses keywords and schema locations**: Several subsection keywords are not indexed or scanned, meaning silent-skip gaps exist that are not yet tracked in the matrix.
3. **Current Gate A semantic cases are DEFERRED**: The inventory harness exists (K-18), but compiled raw-instance validation is absent.
4. **Current `pattern` uses `std::regex::regex_match` (full anchored match)** instead of `std::regex::regex_search` (unanchored search as ECMA-262 specifies). String lengths count UTF-8 bytes, not Unicode code points.
5. **Dual IR paths**: C++ `dataType` recovery paths coexist with SchemaNode IR; collapse to single IR is incomplete.

Wave 0/1 must establish G-honest by exhaustive scanner and fail-closed handling, not by declaring the current incomplete baseline as G-honest.

### 1.5 Non-negotiable constraints

| Rule | Rationale |
| --- | --- |
| **No silent skip** | Keyword that affects validity without a validator → fail generation |
| **Storage ≠ validation** | C++ types never substitute for branch membership |
| **Single IR source of truth** | One SchemaNode IR; deprecate dual recovery from C++ `dataType` |
| **Dependency-free by default** | Generated C++ validators preferred; but correctness outranks dependency-free — allow a required exact-number/regex/evaluator support library if needed |
| **C++17** | Preference; no automatic upgrade in this program |
| **3.0 dual-path** | `nullable`, boolean exclusiveMin/Max, singular schema `example` remain correct for 3.0 docs |
| **Fail closed > partial fake** | Refuse-to-generate over wrong accept |
| **Runtime proof > substring tests** | Semantics proven in C++; Java tests lock emission contracts |
| **Correctness > dependency-free** | If full compliance requires an exact-number, regex, or evaluator library, adopt it |

### 1.6 Strategic architecture

| Option | Description | Decision |
| --- | --- | --- |
| **A. Generated validators** | Expand `validate_*` emission per SchemaNode | **Primary through Wave 2** |
| **B. Embeddable schema interpreter** | IR tables + single `SchemaEvaluator` | **ADR required before or during Wave 1** if generated validators cannot support dynamic/annotation semantics |

Wave 0 may continue on the existing generated-validator foundation. **A mandatory ADR before Wave 1** chooses A-with-evaluation-context or B; it must not pre-decide that Option A remains primary through Wave 2. The decision covers dynamic scope, annotation transactions, exact numbers, generated-code size, and runtime dependencies. Escape-hatch triggers in §12 apply earlier if thresholds trip.

### 1.7 Success gates

#### S-V release (G-full-schema — schema validity)

| Gate | Requirement |
| --- | --- |
| **GS1** | Every keyword in every required vocabulary of the pinned dialect is **Supported** for every schema accepted by G-full-schema. No blanket §10 exceptions apply. Required-vocabulary semantic exclusions are zero. Optional-profile classifications do not count as support. |
| **GS2** | JSON Schema Test Suite at a pinned commit SHA: **100%** of applicable required-vocabulary tests execute and pass, with zero exclusions, skips, or unresolved harness defects. `jsts-exclusions.yaml` may identify only demonstrably inapplicable optional profiles. Harness defects are tracked separately and block GS2. Register remotes; record discovered files/cases/tests/skips/failures/crashes; self-test discovery. |
| **GS3** | `modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/oas31-corpus/**`: 100% of OAS-wrapped fixtures match expected generation and raw-instance validation outcomes. Include 3.1.0/3.1.1/3.1.2 parity, default `jsonSchemaDialect`, resource-root overrides, and embedded/external resource dialect changes. Typed decode/encode belongs to GM1–GM3, not this gate. |
| **GS4** | Gate A: inventory + compiled C++ **raw-instance validation** binary green under `-Werror`; no semantic row remains `DEFERRED`. |
| **GS5** | Petstore + multi-file 3.1 kitchen-sink sample: build + ctest green |
| **GS6** | Docs + FeatureSet + `docs/generators/cpp-boost-beast-client.md` match matrix |
| **GS7** | OpenAPI 3.0 fixture suite: no regressions |
| **GS8** | Parser capability appendix (§11): no **Blocker** items for Supported keywords. Must be exhaustive rows — no TBD/blank/unverified workaround entries. Each row must have source/parser/IR/runtime evidence IDs. |

#### S-A release (schema annotation) — verify alongside S-V

| Gate | Requirement |
| --- | --- |
| **GA1** | Annotation-output/application semantics suite: separate from validity tests. Every output records keyword, instance JSON Pointer, schema-location path including reference traversal, absolute schema-location URI when different, and value(s). Verify metadata, OAS base, Content, Format-Annotation, and unknown-keyword annotations. `$comment` produces no annotation output. |
| **GA2** | Evaluation-path annotation collection and branch transaction/rollback (for `unevaluated*`). |
| **GA3** | Dialect/metaschema gate: meta-validate schemas against the effective recognized metaschema where supported; reject invalid keyword shapes; read `$vocabulary` only from the selected metaschema root; refuse unknown required vocabularies and allow unknown optional vocabularies. |

#### M release (typed C++ mapping)

| Gate | Requirement |
| --- | --- |
| **GM1** | Mapping corpus distinguishes schema-invalid, schema-valid-and-representable, schema-valid-but-unrepresentable, transport parse error, and typed decode error. |
| **GM2** | Decode/encode round trips compare exact JSON mathematical values; destination range or precision failures are representation diagnostics, never schema-invalid results. |
| **GM3** | Public API documents every C++ destination domain, optional/null/presence behavior, enum/open-value policy, default policy, and raw fallback. |

#### C release (G-outbound-json-client)

| Gate | Requirement |
| --- | --- |
| **GC1** | Parameter style/explode matrix golden tests and runtime mock HTTP endpoint tests pass. See §3.9 for the full parameter/header/cookie matrix. |
| **GC2** | Security scheme metadata and pluggable credential hooks. Entries are OR alternatives; names within one entry are AND requirements; `{}` permits anonymous access; operation `security: []` removes inherited security; scope arrays follow scheme-type rules. |
| **GC3** | Callbacks/webhooks: preserve metadata with visible diagnostics; no inbound listener or automatic Link traversal. Links metadata preserved without automatic traversal. |
| **GC4** | Enumerated media types covered; requestBody `required`, normative content-map matching, Encoding Object applicability, response exact/range/default precedence, response headers, optional/no-body, unexpected-content/status policy, non-schema Reference Objects, and multi-document OAD resolution. XML wire binding remains outside the explicitly named JSON outbound profile. |
| **GC5** | GS1–GS8, GA1–GA3, and GM1–GM3 remain green. FeatureSet metadata matches the delivered C profile. |

#### Continuous (every phase)

| Gate | Requirement |
| --- | --- |
| **GH** | G-honest: zero new silent ignores; matrix row updated. Wave 0 must immediately establish G-honest globally via exhaustive schema-valued-position scanner and fail-closed handling. |

---

## 2. Baseline (post-remediation)

### 2.1 Solid foundation (do not redo)

- Composition descriptors + normalizer ownership (partial IR)
- oneOf/anyOf tagged storage + validator-first membership
- Validation-neutral discriminators
- Recursive allOf intersection (objects + scalar aliases) — **not** full nested applicator surface
- NullableField tri-state + null-capable object roots
- Status-aware response unions + Content-Type dispatch
- Multipart Encoding Object content types + wire tests
- SSE representation vs jsonEventData (convention-documented; **not** OAS 3.2 `itemSchema`)
- Fail-closed foundation for several unsupported keywords
- Mathematical integer helpers
- Gate A **inventory** harness (runtime decode still incomplete — K-18)

### 2.2 Still major work (do not overstate baseline)

| Reality | Implication |
| --- | --- |
| Object validation on composition branches still partial | Wave 2.1 is large |
| Nested composition handling is incomplete | Wave 2.3 is new capability |
| No annotation/evaluation context API | Define in Wave 1; use in Waves 3–4 |
| Dual extension / dataType recovery paths may remain | Collapse to SchemaNode IR early |
| Gate A semantics largely deferred | Wave 0 is mandatory |

### 2.3 Gap register

| Gap ID | Keyword / area | Current state | Profile |
| --- | --- | --- | --- |
| K-01 | `not` | Fail-closed | S-V |
| K-02 | `if` / `then` / `else` | Fail-closed | S-V |
| K-03 | Boolean schemas `true`/`false` | Fail-closed | S-V |
| K-04 | Full `properties` + `additionalProperties` on composition branches | Partial / fail-closed | S-V |
| K-05 | `minProperties` / `maxProperties` | Fail-closed | S-V |
| K-06 | `items` / `prefixItems` (2020-12) | Fail-closed / incomplete | S-V |
| K-07 | Nested composition on branches | Fail-closed | S-V |
| K-08 | `contains` / `minContains` / `maxContains` | Missing | S-V |
| K-09 | `patternProperties` | Missing | S-V |
| K-10 | `propertyNames` | Missing | S-V |
| K-11 | `dependentRequired` / `dependentSchemas` | Missing | S-V |
| K-12 | `unevaluatedProperties` / `unevaluatedItems` | Missing | S-V |
| K-13 | `pattern` uses `regex_match` (full anchor), byte-length strings, no Unicode | regex_match anchors, UTF-8 bytes, subset only | S-V |
| K-14 | `format` (annotation) / strict format-assertion | Deferred / not annotation-aware | S-A |
| K-15 | `contentMediaType` / `contentEncoding` / `contentSchema` as annotations | Missing / partial | S-A |
| K-16 | `$id` / `$anchor` / `$dynamicAnchor` / `$dynamicRef` / `$defs` multi-doc | Partial (parser-dependent) | S-V |
| K-17 | `jsonSchemaDialect` / `$schema` / dialect policy | Not owned end-to-end | S-V |
| K-18 | Gate A compiled raw-instance validation | Deferred / inventory only | S-V |
| K-19 | JSON Schema Test Suite CI | Absent | S-V |
| K-20 | Non-schema OAS 3.1 client surface | Partial | C |
| K-21 | `default` / `readOnly` / `writeOnly` annotation semantics + client policy | Partial / ad hoc; `default` must not silently inject values | S-A/C |
| K-22 | `uniqueItems` deep JSON mathematical (arbitrary-precision) equality | Present but may use Boost.JSON direct comparison | S-V |
| K-23 | Type-array + keyword applicability edge cases | Partial | S-V |
| K-24 | Single SchemaNode IR (eliminate dual paths) | Incomplete | M |
| K-25 | Legacy `$recursiveRef` / `$recursiveAnchor` names | Unknown keywords in Draft 2020-12; general unknown-annotation policy, never legacy recursion | S-A |
| K-26 | swagger-parser keyword fidelity | Unknown / blockers TBD | S-V |
| K-27 | `$vocabulary` policy | Unimplemented | S-V |
| K-28 | `title` / `description` / `examples` / OAS `example` annotation preservation | Partial | S-A |
| K-29 | `SchemaResourceRegistry`: resource identity, retrieval URI, base URI, anchors, dynamic anchors, embedded resources, cycles | Missing | S-V |
| K-30 | Exact JSON Number domain (arbitrary-precision base-10 evaluation) | Boost.JSON double only; no separate exact-number layer | S-V |
| K-31 | Content keywords as annotations (no auto-decode/parse) | Current partial may auto-decode | S-A |
| K-32 | `$comment` | Core reserved keyword; validate string shape, no annotation output or semantic action | S-V |
| K-33 | `multipleOf` / numeric keyword exact evaluation | Via double; no base-10 decimal-rational | S-V |
| K-34 | `const` / `enum` exact mathematical equality | Via Boost.JSON; no arbitrary-precision comparison | S-V |
| K-35 | Evaluated properties/items collection for `unevaluated*` | Missing | S-A |
| K-36 | Annotation branch transaction/rollback for `if`/then`/else` and composition | Missing | S-A |

---

## 3. Dialect keyword manifest

The OAS 3.1 Schema dialect manifest is mechanically generated from the pinned `https://spec.openapis.org/oas/3.1/dialect/2024-11-10` metaschema and its directly declared vocabularies. `/oas/3.1/dialect/base` is accepted only as the OAS alias for that pinned revision; adopting a newer dated revision requires an explicit manifest update and conformance run.

| Vocabulary URI | Required in dialect | Meta-schema |
|---|---|---|
| `https://json-schema.org/draft/2020-12/vocab/core` | `true` | `https://json-schema.org/draft/2020-12/meta/core` |
| `https://json-schema.org/draft/2020-12/vocab/applicator` | `true` | `https://json-schema.org/draft/2020-12/meta/applicator` |
| `https://json-schema.org/draft/2020-12/vocab/unevaluated` | `true` | `https://json-schema.org/draft/2020-12/meta/unevaluated` |
| `https://json-schema.org/draft/2020-12/vocab/validation` | `true` | `https://json-schema.org/draft/2020-12/meta/validation` |
| `https://json-schema.org/draft/2020-12/vocab/meta-data` | `true` | `https://json-schema.org/draft/2020-12/meta/meta-data` |
| `https://json-schema.org/draft/2020-12/vocab/format-annotation` | `true` | `https://json-schema.org/draft/2020-12/meta/format-annotation` |
| `https://json-schema.org/draft/2020-12/vocab/content` | `true` | `https://json-schema.org/draft/2020-12/meta/content` |
| `https://spec.openapis.org/oas/3.1/vocab/base` | `false` | `https://spec.openapis.org/oas/3.1/meta/2024-11-10` |

**Key implications:**
- The OAS base vocabulary is **optional** (`false`) for a generic JSON Schema evaluator, but S-A requires this OAS-aware generator to preserve and expose all four OAS base keywords. XML wire binding is a separate C-profile concern.
- Read `$vocabulary` only from the root object of the selected metaschema. Declarations are not inherited through metaschema `$ref`. An unknown vocabulary marked `true` there causes refusal.
- Unknown **optional** vocabulary → is allowed; generator may warn but must not refuse.
- Unknown individual keywords not in any recognized vocabulary → treated as **annotations** (persisted, no validity effect).
- `$vocabulary` declares vocabularies only when processing a metaschema; ignore it for declaration purposes in an ordinary schema.
- `$recursiveRef` / `$recursiveAnchor` are unknown keywords in the OAS 3.1 / Draft 2020-12 profile. They follow the general unknown-annotation policy and never activate legacy recursive semantics.

Each keyword below is listed with its vocabulary provenance. Every keyword in a `required=true` vocabulary must reach **Supported** for G-full-schema. The OAS base vocabulary is also mandatory for this program's S-A claim even though its dialect flag is `false`.

### 3.1 Core vocabulary (required)

| Keyword | Classification | Wave | Notes |
|---|---|---|---|
| `$schema` | Identifier | 4 | Allowed at a document schema-resource root and at an embedded resource root established by `$id`; prohibited in ordinary non-resource-root subschemas. The resource-root value selects the dialect for the entire resource. |
| `$id` | Identifier | 1–4 | Resolves against the current base URI and establishes a new schema-resource base. Canonical URI collisions across loaded resources are errors by default. Complete-document indexing required. |
| `$ref` | Reference | 1–4 | Resolve against the current base URI; support local/external resources and cycles. Adjacent keywords remain active under Draft 2020-12. |
| `$defs` | Reserved schema container | 1–4 | No direct evaluation result; every child is a schema-valued position that must be indexed and referenceable. |
| `$anchor` | Identifier | 4 | Plain-name fragment; unique within its schema resource. |
| `$dynamicAnchor` | Identifier | 4 | Dynamic plain-name fragment used by `$dynamicRef`. |
| `$dynamicRef` | Reference | 4 | Resolve an initial target statically, then apply the Draft 2020-12 dynamic-scope replacement rule only when its fragment names a `$dynamicAnchor`. |
| `$vocabulary` | Metaschema declaration | 4 | Read only at the selected metaschema root; unknown required vocabulary → refuse. No declaration effect in ordinary schemas. |
| `$comment` | Core reserved, non-annotation | 3 | Value must be a string. No validity action, annotation result, or standard output. Optional source retention for diagnostics is not S-A support. |
| `$recursiveRef` | Unknown keyword in 2020-12 | — | General unknown-annotation policy; no legacy recursion. |
| `$recursiveAnchor` | Unknown keyword in 2020-12 | — | General unknown-annotation policy; no legacy recursion. |

### 3.2 Applicator vocabulary (required)

| Keyword | Classification | Wave | Notes |
|---|---|---|---|
| `allOf` | Applicator (AND) | baseline + 2.3 | Every branch must validate; annotations retained only if the enclosing schema object succeeds. |
| `anyOf` | Applicator (OR) | baseline + 2.3 | Retain output from every successful branch. |
| `oneOf` | Applicator (XOR) | baseline + 2.3 | Exactly one branch; retain output from that branch only. |
| `not` | Applicator (NOT) | 1.6 | Invert subschema result and retain no subschema annotations. |
| `if` / `then` / `else` | Applicator (conditional) | 3.5 | Transactional annotation/evaluated-location behavior. |
| `properties` | Applicator | 2.1 | Everywhere, not only top-level models. |
| `patternProperties` | Applicator | 3.2 | |
| `additionalProperties` | Applicator | 2.1 | true/false/schema; contributes evaluated-property coverage. |
| `propertyNames` | Applicator | 3.3 | Applies subschema to each property name as a string instance. |
| `dependentSchemas` | Applicator | 3.4 | Schema form of property dependencies. |
| `prefixItems` | Applicator | 2.2 | Tuple validation by index. |
| `items` | Applicator | 2.2 | Applies to items after the `prefixItems` coverage. |
| `contains` | Applicator | 3.1 | Produces matching-index annotations used by `minContains`, `maxContains`, and `unevaluatedItems`. |

### 3.3 Unevaluated vocabulary (required)

| Keyword | Classification | Wave | Notes |
|---|---|---|---|
| `unevaluatedProperties` | Applicator | 4.1 | Full successful-evaluation-path semantics, not lexical sibling only. |
| `unevaluatedItems` | Applicator | 4.1 | Full successful-evaluation-path semantics. |

### 3.4 Validation vocabulary (required)

| Keyword | Classification | Wave | Notes |
|---|---|---|---|
| `type` | Assertion | 1.4 + 2.4 | Arrays of types + `null`; keyword applicability by instance type. |
| `enum` | Assertion | 1.2 | Deep JSON mathematical equality with exact numbers and all JSON kinds. |
| `const` | Assertion | 1.2 | Deep JSON mathematical equality with exact numbers and all JSON kinds. |
| `multipleOf` | Assertion | 1.2 | Exact decimal-rational evaluation; no floating tolerance. |
| `maximum` | Assertion | 1.2 | Exact number comparison. |
| `exclusiveMaximum` | Assertion | 1.2 | Exact number comparison. |
| `minimum` | Assertion | 1.2 | Exact number comparison. |
| `exclusiveMinimum` | Assertion | 1.2 | Exact number comparison. |
| `maxLength` | Assertion | 2.5 | Length counts Unicode code points, not UTF-8 bytes. |
| `minLength` | Assertion | 2.5 | Length counts Unicode code points, not UTF-8 bytes. |
| `pattern` | Assertion | 3.6 | Unanchored search with ECMAScript-compatible syntax and Unicode-aware behavior for the full accepted language. Non-BMP/code-point tests required; ICU alone is not proof of compatibility. |
| `maxItems` | Assertion | 2.5 | |
| `minItems` | Assertion | 2.5 | |
| `uniqueItems` | Assertion | 1.2 | Deep JSON mathematical equality with exact numbers. |
| `maxContains` | Assertion | 3.1 | No effect without adjacent `contains`. |
| `minContains` | Assertion | 3.1 | No effect without adjacent `contains`; default is 1. |
| `maxProperties` | Assertion | 2.1 | |
| `minProperties` | Assertion | 2.1 | |
| `required` | Assertion | 2.1 | Array of unique property names; applies only to object instances and is enforced at every schema-valued position. |
| `dependentRequired` | Assertion | 3.4 | Array-form property-name dependency. |

### 3.5 Metadata vocabulary (required)

| Keyword | Classification | Wave | Notes |
|---|---|---|---|
| `title` | Annotation | 3.0 | Preserved and exposed; no validity effect. |
| `description` | Annotation | 3.0 | Preserved and exposed; no validity effect. |
| `default` | Annotation | 3.8 | **Annotation only**. Does not silently inject missing values. Any client construction policy is separately named and tested. |
| `deprecated` | Annotation | 3.0 | Preserved; optional API-level promotion. |
| `readOnly` | Annotation | 3.8 | Annotation only. C policy: exclude from request projection and reject an explicitly set read-only value before send. Required applies only to the response projection. |
| `writeOnly` | Annotation | 3.8 | Annotation only. C policy: reject a response containing the property by default. Required applies only to the request projection. |
| `examples` | Annotation | 3.0 | JSON Schema array, distinguished from singular OAS `example`. |

### 3.6 Format-annotation vocabulary (required)

| Keyword | Classification | Wave | Notes |
|---|---|---|---|
| `format` | Annotation (default) | 4.4 | Annotation by default in the OAS dialect. Optional application checks are not equivalent to Format-Assertion. If Format-Assertion is claimed, support every format defined by that vocabulary or refuse the dialect. |

### 3.7 Content vocabulary (required)

| Keyword | Classification | Wave | Notes |
|---|---|---|---|
| `contentEncoding` | Annotation | 3.7 | Automatic processing is disabled by default. If explicitly enabled at application level, decode according to this value. Decoding never changes enclosing-instance validity. |
| `contentMediaType` | Annotation | 3.7 | If application processing is enabled, interpret decoded content according to this value. A contradictory Media Type Object key or Encoding Object `contentType` takes precedence. |
| `contentSchema` | Schema-valued annotation | 3.7 | Its child is indexed as a schema. Optional decoded-content validation is a separate application result and never changes enclosing-instance validity. |

### 3.8 OAS base vocabulary (optional in dialect, REQUIRED for OAS-aware generation)

| Keyword | Classification | Wave | Notes |
|---|---|---|---|
| `discriminator` | Annotation / hint | baseline + 2.3 | Validation-neutral candidate-order hint only; never skips branch evaluation required for composition semantics. |
| `xml` | Annotation | 3.0 | Preserved and exposed for S-A. XML request/response binding remains outside C. |
| `externalDocs` | Annotation | 3.0 | Preserved; no schema validity effect. |
| `example` | Annotation | 3.0 | OAS singular value, distinguished from JSON Schema `examples`. |

### 3.9 Outbound client profile (C profile)

| Area | End-state | Notes |
|---|---|---|
| **Parameter serialization** | Supported | Query/path/header/cookie location rules; legal styles and defaults; `explode` defaults; omitted vs empty values; query-only `allowReserved` and `allowEmptyValue`; mutually exclusive `schema`/`content`; exactly one entry in parameter `content`. |
| **Path parameters** | Supported | Each template expression is backed by an effective Path Item and/or Operation path parameter, except an empty Path Item; every path parameter is `required: true`. Percent-encode unescaped `/`, `?`, and `#` in values. |
| **Header parameters** | Supported | Ignore Header Parameter definitions named `Accept`, `Content-Type`, or `Authorization`, as required by OAS. Do not percent-encode header values as URL components. |
| **Cookie parameters** | Supported | Serialized as cookies (not query string). |
| **Servers** | Supported | Select the applicable array by Operation > Path Item > OpenAPI precedence. If top-level servers are absent or empty, use implicit `/`. Resolve relative Server URLs in document context, expand variables, then append the Paths key without relative-URL resolution. Define user selection among multiple entries; OAS does not choose one. |
| **Security** | Supported | `apiKey`, `http`, `oauth2`, `openIdConnect` with `openIdConnectUrl`, and `mutualTLS` metadata plus credential hooks. Requirement entries are OR alternatives; scheme names within one non-empty entry are AND requirements. `{}` permits anonymous access; operation `security: []` removes inherited security. Enforce scope-array rules by scheme type. |
| **Callbacks** | Metadata preserve + diagnostic | **Inbound metadata** for an outbound client. Preserve with visible diagnostic. An outbound client does not need to host callbacks; do not fail generation. No inbound listener claim. |
| **Webhooks** | Metadata preserve + diagnostic | **Inbound metadata** for an outbound client. Same policy as callbacks. |
| **Links** | Metadata preserve | No automatic traversal. Preserve `operationId`/`operationRef`/parameters as annotations. |
| **RequestBody** | Supported | `required` defaults false. Content-map matching: exact concrete type > matching subtype wildcard > `*/*`; ignore media-type parameters while matching. `+json` decoding and raw-byte fallback are explicitly named client conventions, not extra OAS matching tiers. Encoding Objects apply only to multipart and form-urlencoded, with field applicability/default/ignore rules. |
| **Responses** | Supported | Exact status > range (2XX/3XX/...) > default. Response headers. Optional/no-body responses. Unexpected status policy (documented graceful handling). |
| **Reference Objects** | Supported | Multi-document OAD resolution for every legal target type. Implement Reference Object `summary`/`description` overrides and sibling handling separately from JSON Schema `$ref`; cover Path Item `$ref` semantics. |
| **Media types** | Enumerated profile | JSON, multipart, form-urlencoded, octet-stream, and text/plain; concrete `+json` types use the documented JSON decoder convention. SSE remains a convention (`sseSchemaMode`). XML and arbitrary codecs remain outside G-outbound-json-client. |
| **Runtime testing** | Required | Mock HTTP endpoint tests, not only source goldens. |
| **FeatureSet metadata** | Required | Wave 5 updates currently excluded `Callbacks`, `LinkObjects`, `ParameterStyling`, `MultiServer`, and Cookie features only when corresponding behavior and tests land. Generated docs must never lead implementation. |

---

## 4. Target architecture

### 4.0 Core principle: exact JSON Number domain

JSON Schema specifies numbers as arbitrary-precision base-10 values. Equality for `enum`, `const`, `uniqueItems` requires deep JSON mathematical equality per this domain, independent of the representation precision of any host language or library (e.g., Boost.JSON `double`).

**Architecture requirement:** Without an exact-number evaluation layer, G-full-schema (S-V) cannot ship. The architecture must provide:

1. **Exact mathematical values**: Preserve every JSON number's exact arbitrary-precision base-10 value through parsing. Retaining the source lexeme is an acceptable technique, not a semantic requirement; `1`, `1.0`, and `1e0` compare equal.
2. **Decimal-rational representation**: An exact base-10 rational type capable of arbitrary-precision comparison, multiplication, and division (for `multipleOf`).
3. **Separation from Boost.JSON DOM**: Boost.JSON can remain the transport/model DOM, but exact numeric evaluation must not depend on Boost.JSON's `double`. This is a **separate representability gate** (M profile) from the exact evaluation (S-V profile).

Without this layer, `enum`/`const`/`uniqueItems` equality and `multipleOf`/numeric assertion precision are incorrect for numbers outside `double`'s exact representable set.

### 4.1 SchemaNode IR (single source of truth)

```text
SchemaNode
  resourceId                # owning schema resource identity
  retrievalUri              # URI used to fetch the document
  canonicalUri              # canonical $id of the resource
  baseUri                   # nearest effective base URI
  dialectUri                # effective $schema / jsonSchemaDialect
  jsonPointer               # JSON Pointer location within document
  booleanValue              # optional true|false schema
  types                     # set of JSON types (3.1 type arrays)
  assertions                # validation keywords (type, enum, numeric, string length, pattern, ...)
  applicators               # properties, prefixItems, oneOf, if/then/else, ...
  refUri                    # optional unresolved/resolved $ref URI-reference + initial target
  dynamicRefUri             # optional $dynamicRef URI-reference + statically resolved initial target
  anchors                   # map: plain name → SchemaNode (via $anchor)
  dynamicAnchors            # map: dynamic name → SchemaNode (via $dynamicAnchor)
  metadata                  # title, description, default, readOnly, writeOnly, deprecated, examples, example
  childSchemas              # ordered list of subschema nodes (for keyword-by-keyword traversal)
  unknownAnnotations        # unknown keyword values retained as annotations, never traversed as schemas
```

**Rules:**

1. Build IR once after full document load (post-parse, pre-lowering).
2. Lowering, validator emission, and public decode **read only IR** (+ composition index derived from IR).
3. Remove / gate dataType-only recovery heuristics.
4. Collapse dual vendor-extension paths to IR-driven emission; temporary dual-write only during migration with parity tests.
5. **Structural hash deduplication** of SchemaNode instances is valid **only when** the semantic environment (resource identity, base URI, dialect, anchors, output identity) is identical. Otherwise, stable SchemaNode identity per schema position.
6. A single `refTarget` field is insufficient for `$dynamicRef` — must support dynamic scope stack resolution.

### 4.2 SchemaResourceRegistry

The OAS+JSON Schema specification requires **complete-document indexing and loading** before a reference can be deemed unresolvable. Implement:

```text
SchemaResourceRegistry
  documents                 # map: retrieval URI → parsed complete document
  resources                 # map: stable resource identity → SchemaResource
  uriAliases                # map: absolute retrieval/canonical/anchor URI → SchemaResource or SchemaNode

SchemaResource
  owningDocument            # parsed document that physically contains the resource
  retrievalUri              # present for document root; embedded resources inherit document provenance
  canonicalId               # $id declared in the root schema (may differ from retrievalUri)
  baseUri                   # canonical $id or inherited/retrieval base
  embeddedResources         # resources declared via $id within this document
  externalReferences        # URIs referenced but not in this document
  anchors                   # all $anchors in this resource (resolved to SchemaNode)
  dynamicAnchors            # all $dynamicAnchors in this resource
  identifierCollisions      # canonical URI collisions across all loaded resources (error by default)
  cycles                    # detected reference cycles
  dialectUri                # effective dialect
```

**Policy:**

| Aspect | Behaviour |
|---|---|
| Configurable offline/network | Offline mode: pre-registered remotes only. Network: fetch on demand. |
| SSRF/security controls | `allowedSchemes` (file, https, ...), allowed domains, max depth, max-size per fetch. |
| Duplicate canonical URI | Raise an error diagnostic by default across the loaded resource corpus; never choose a target nondeterministically. |
| Dialect switches | Each resource has its own dialect. Global `jsonSchemaDialect` vs resource-root `$schema`. |
| `$schema` on root resource | Overrides `jsonSchemaDialect` for that resource. |
| `$schema` elsewhere | Prohibited in an ordinary non-resource-root subschema. An embedded resource root established by `$id` may declare its own `$schema`; the declaration scopes that entire resource. |

### 4.3 Validator API + ValidationContext

```text
struct ValidationContext {
  // Raw exact-number access (independent of typed decode)
  JsonNumber const& rawNumberValue() const;  // decimal-rational accessor

  // Instance and schema locations
  InstanceLocation  instanceLocation;    // JSON Pointer into the instance
  SchemaLocation    schemaLocation;      // JSON Pointer into the schema
  SchemaResource*   activeResource;      // current schema resource

  // Dynamic scope
  DynamicScope      dynamicScope;        // ordered schema resources traversed on the validation path

  // Guard repeated evaluation states without truncating $dynamicRef resolution.
  // Dynamic scope is part of the state; lexical location alone is not a stop condition.
  RecursionSet      recursionStates;

  // Evaluated properties/items
  std::set<std::string> evaluatedProperties;
  std::set<size_t>      evaluatedItems;

  // Annotation collection
  AnnotationStore&  annotations;         // keyword, instance path, schema path, absolute schema URI, value(s)

  // Branch transaction/rollback
  void beginBranch();                    // snapshot annotation/evaluated state
  void commitBranch();                   // merge snapshot into parent
  void rollbackBranch();                 // discard snapshot
};

ValidationResult validate_<id>(
    RawInstance const& instance,         // direct raw-instance accessor (not typed decode)
    ValidationPath&   path,
    ValidationContext& ctx);
```

**Key design points:**
- **Direct raw-instance validator** independent of typed decode/encode. The M profile (C++ typed mapping) is a separate layer; S-V/S-A must work on raw JSON instances without requiring Boost.JSON typed extraction.
- Make annotation and evaluated-location state transactional at every schema-object and applicator boundary. Commit output only when the producing schema object succeeds; discard its local and descendant output when it fails.
- Successful `allOf` retains all branches; `anyOf` retains every successful branch; successful `oneOf` retains its sole successful branch; `not` retains no subschema output. `if` retains its output only when it succeeds, and only the selected `then` or `else` branch is evaluated and eligible for output.
- `beginBranch`/`commitBranch`/`rollbackBranch` cover annotations, `evaluatedProperties`, and `evaluatedItems` together.

### 4.4 Raw parser re-walk

If swagger-parser drops schema fields during parse, the generator must re-walk the raw JSON/YAML schema nodes. The re-walk must:

1. Be **schema-position/context aware** — know whether it is inside a Schema Object vs a non-schema OAS object.
2. **Preserve document/resource context** — track `$id`, `$schema`, base URI changes.
3. Traverse every schema-valued position declared by the active dialect, regardless of keyword category, including applicators, `$defs`, and `contentSchema`.
4. **Never traverse unknown annotation payloads as schemas** unless a recognized active vocabulary explicitly declares that position schema-valued.

### 4.5 Evaluation-path semantics (Wave 4)

`unevaluatedProperties` / `unevaluatedItems` depend on **successfully evaluated** locations along the evaluation path (in-place applicators, `$ref` targets), not only lexical siblings. When `unevaluatedItems` is in scope, `contains` may need full item consideration for annotations. Implement against Core §11 and JSTS `unevaluated*.json`.

The collector tracks successful coverage produced by `properties`, `patternProperties`, `additionalProperties`, `prefixItems`, `items`, `contains`, `unevaluatedProperties`, and `unevaluatedItems`, plus successful in-place applicators and `$ref`/`$dynamicRef` evaluations. It records only instance locations to which a subschema actually applied and whose enclosing schema output is retained. All successful `anyOf` branches contribute; failed branches and failed enclosing schema objects roll back coverage.

### 4.6 Pattern engine

| Mode | Behavior |
| --- | --- |
| `patternEngine=subset` | Documented interoperable subset; unsupported valid source patterns fail generation. Eligible for G-honest only, not G-full-schema. Current `regex_match` must become unanchored search. |
| `patternEngine=ecma262` | G-full-schema path: ECMAScript-compatible syntax and unanchored Unicode-aware matching for the full accepted language, with non-BMP/code-point tests. ICU alone is not proof of ECMA-262 compatibility. |

### 4.7 Format policy

| Mode | Behavior |
| --- | --- |
| `formatAssertionPolicy=annotation` (default) | Formats never change composition match counts. Annotation-only collection. |
| `formatAssertionPolicy=strict` | Registered formats assert; `unknownFormat=error\|ignore`. If Format-Assertion is ever claimed, ALL defined formats must be supported or dialect refused. |

Strict-format priority is not required for G-full-schema release. Format annotation-only is the OAS default.

### 4.8 Ref / dialect / parser layering

| Layer | Owner |
| --- | --- |
| Parse OAS document | swagger-parser / swagger-core |
| SchemaResourceRegistry | Generator — complete-document scanning |
| IR construction | Generator — re-walk raw schema nodes if parser drops fields |
| Local `$ref` / `$defs` | Generator IR |
| `$id` / `$anchor` / `$dynamicAnchor` / `$dynamicRef` | Generator IR + Registry; blockers in §11 |
| `$vocabulary` dialect policy | Generator — inspect only the selected metaschema root; refuse unknown required vocabulary |
| Unknown dialect | Fail generation |

### 4.9 SSE (unchanged product policy)

OAS 3.1 does **not** define JSON typing per SSE `data:` field. Keep remediation behavior:

- `sseSchemaMode=representation` (default): framed events, raw data strings
- `sseSchemaMode=jsonEventData`: explicit convention for JSON-per-data
- Never market typed SSE as core OAS 3.1 schema compliance

---

## 5. Work waves

Phases preserve **GH** (G-honest: zero silent ignore) at every step. Wave 0 must immediately establish G-honest globally via exhaustive schema-valued-position scanner and fail-closed handling.

---

### Wave 0 — Foundation: harness, scanner, baseline (S-V / S-A) — **mandatory first**

1. **Exhaustive schema-valued-position scanner**: Walk every Schema Object embedding location and every schema-valued keyword declared by the effective dialect, including `$defs` and `contentSchema`. Produce a keyword occurrence ledger; known unsupported assertions/applicators fail generation everywhere, while unknown keywords follow annotation policy.
2. **Gate A raw validation binary**: extend the existing `oas-compliance/gate-a.sh` flow to generate, compile, and run C++ against `semantic-cases.yaml`. Replace every `DEFERRED` decode outcome with raw-validator accept/reject evidence. Keep typed decode and round-trip in the separate M corpus.
3. Create `modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/oas31-corpus/` with fixtures for every gap ID. Include 3.1.0/3.1.1/3.1.2 parity, default `jsonSchemaDialect`, resource-root `$schema`, embedded-resource dialect, and external-resource cases.
4. **Metaschema gate**: Add metaschema/invalid-schema tests. Verify dialect detection, required-vocabulary refusal, optional-vocabulary allowance.
5. **OAS structural normative checks**: require root `openapi` and `info`, require `info.title` and `info.version`, and require presence of at least one of `paths`, `components`, or `webhooks`; permit empty maps where OAS permits them. Add field-specific normative checks without inventing minimum entry counts.
6. Pin JSTS by commit SHA under `modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/oas31-jsts/`; register remotes and run direct and OAS-wrapped production paths.
7. Add a repository CI job named `cpp-boost-beast-oas31-conformance`; start as scheduled/nightly if necessary, but record the exact GitHub Actions workflow and promotion criteria in the conformance README.
8. Create `modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/compliance-matrix.yaml` and generate README tables from it.
9. Parser spike -> Section 11 appendix v1 (K-26, K-29). It must be exhaustive with no TBD, blank, or unverified rows.

**Explicit baseline acknowledgments to fix:**
- Current allOf unsupported-assertion exemption: fix scanning in this wave.
- Current scanner misses keywords/schema locations: exhaustively fix.
- Current `pattern` uses `regex_match` (anchor): baseline must acknowledge.
- Current string lengths use bytes: baseline must acknowledge.

**DoD:** Compiled raw-validator Gate A for current Supported keywords with zero `DEFERRED` semantic rows; exhaustive occurrence ledger; corpus + matrix + CI skeleton; baseline JSTS report; parser appendix v1; dialect/metaschema gate; GH globally.

**Exit:** No keyword phase without Wave 0 DoD.

---

### Wave 1 — IR, resources, and exact instance model (S-V / S-A / M)

#### 1.0 — Architecture ADR

- **Mandatory before Wave 1 work begins.** Option A (generated validators) vs B (interpreter), annotation threading, dynamic scope, exact-number architecture. The ADR must address whether generated validators can support dynamic/annotation semantics before Wave 1 starts.
- Escape-hatch triggers in §12 apply if thresholds cannot be met.

#### 1.1 — SchemaResourceRegistry (K-29)

- Complete-document indexing: retrieval URI, canonical `$id`, base URI, embedded resources, anchors, dynamic anchors.
- External ref tracking; cycles; canonical URI collision errors.
- Configurable offline/network and SSRF policy.

#### 1.2 — Exact JSON Number domain (K-30, K-33, K-34, K-22)

- **Critical path.** Exact decimal-rational representation for numeric comparison, `multipleOf`, `enum`, `const`, `uniqueItems`.
- Independent of Boost.JSON `double`. Boost.JSON remains transport DOM; exact-number layer sits below validation.
- All numeric assertions use exact evaluation.

#### 1.3 — SchemaNode IR (K-24)

- Full SchemaNode with resource identity, document provenance, retrieval/canonical URI aliases, base URI, dialect, JSON Pointer, static initial reference targets, dynamic-reference URI data, anchors, and dynamic anchors.
- Single source of truth; deprecate dataType-only membership paths.

#### 1.4 — Raw-instance validator (S-V)

- Direct raw-instance validation independent of typed decode/encode.
- `ValidationContext` with exact-number access, instance/schema locations, ordered dynamic scope, and recursion states that include dynamic scope.

#### 1.5 — Boolean schemas (K-03)

- `true` / `false` validators; allowed as branches/items/additionalProperties
- OAS 3.0 + boolean schema → **fail generation** with version diagnostic

#### 1.6 — `not` (K-01)

- Invert subschema; composition branches
- FeatureSet adds `not` only when this lands

**DoD:** K-01, K-03, K-22, K-24, K-29–K-30, K-33–K-34 Supported; GS4 for these; JSTS groups advancing; ADR recorded; exact-number architecture verified against JSTS numeric tests; GH.

---

### Wave 2 — Common validation (S-V)

#### 2.1 — Full object validation (K-04, K-05)

- Recursive `properties`; `additionalProperties` true|false|schema
- `minProperties` / `maxProperties`
- Same path for decode and composition

#### 2.2 — Full array validation (K-06)

- `prefixItems` + `items` (2020-12)

#### 2.3 — Nested composition (K-07)

- Nested oneOf/anyOf/allOf/not; recursive validators

#### 2.4 — Type-array applicability + keyword applicability edge cases (K-23)

#### 2.5 — `minItems` / `maxItems` / `minLength` / `maxLength`

- String length counts Unicode code points, not UTF-8 bytes

**DoD:** K-04–K-07, K-23 Supported; GS4; JSTS validation group advancing.

---

### Wave 3 — Applicators, annotations, and content (S-V / S-A)

#### 3.0 — Annotation / evaluation collector (S-A)

- Annotation collection in ValidationContext with required output identity fields: `title`, `description`, `default`, `readOnly`, `writeOnly`, `deprecated`, `examples`, OAS `example`, `format`, Content, OAS base, and unknown annotations.
- Branch transaction/rollback for `if`/then`/else` and composition.
- `evaluatedProperties` / `evaluatedItems` tracking.

#### 3.1 — `contains` / `minContains` / `maxContains` (K-08)

#### 3.2 — `patternProperties` (K-09)

#### 3.3 — `propertyNames` (K-10)

#### 3.4 — `dependentRequired` / `dependentSchemas` (K-11)

#### 3.5 — `if` / `then` / `else` (K-02)

- Annotation hooks required for unevaluated. Branch rollback.

#### 3.6 — Pattern engine (K-13)

- Fix from `regex_match` to `regex_search` (unanchored).
- Unicode/code-point and non-BMP testing.
- Select and prove an ECMAScript-compatible Unicode-aware engine; ICU alone is insufficient evidence.

#### 3.7 — Content vocabulary as annotations (K-15, K-31)

- `contentEncoding`, `contentMediaType`, `contentSchema` as annotations. **No automatic decode/parse**.
- Optional application mapping behavior.
- OAS contradiction precedence (MediaType/Encoding over `contentMediaType`).

#### 3.8 — Metadata annotations (K-21, K-28)

- `default`, `readOnly`, `writeOnly`, `title`, `description`, `deprecated`, `examples`, OAS `example`.
- Request projection excludes `readOnly`; explicitly set read-only request values fail before send. Response decoding rejects `writeOnly` by default. Directional `required` behavior follows those projections.
- `default` never injects a missing value. Any opt-in default application is a separately named non-validation operation and is not enabled by decode.

#### 3.9 — `$comment` (K-32)

- Require a string value. It has no validity action or annotation output. Optional retention in the raw source/IR for developer diagnostics is not exposed as S-A output and requires no Mustache model emission.

**DoD:** K-02, K-08–K-11, K-13, K-15, K-21, K-28, K-31–K-32 Supported; GA1–GA3 for annotations; per-group JSTS; GH.

---

### Wave 4 — Unevaluated, dynamic refs, and dialects (S-V / S-A)

**Prerequisite:** Wave 3 annotation infrastructure is operational.

#### 4.1 — `unevaluatedProperties` / `unevaluatedItems` (K-12, K-35)

- Full evaluation-path semantics (not only lexical siblings).
- Annotation transaction/rollback for composition.
- `evaluatedProperties` / `evaluatedItems` per evaluation path.

#### 4.2 — `$anchor` / `$dynamicAnchor` / `$dynamicRef` (K-16)

- Dynamic scope records schema resources traversed along the validation path.
- Resolve the `$dynamicRef` URI-reference statically against the current base URI. If and only if the initial fragment was defined by `$dynamicAnchor`, replace the initial target with the identically named anchor in the outermost matching schema resource in dynamic scope; otherwise evaluate exactly as `$ref`.

#### 4.3 — `$id` / `$schema` dialect policy (K-17, K-27)

- `jsonSchemaDialect` is the default for Schema Objects in that OAS document; if absent, use the pinned OAS dialect `https://spec.openapis.org/oas/3.1/dialect/2024-11-10`.
- `$schema` at a document or embedded schema-resource root overrides the OAD default for the entire resource; reject `$schema` in an ordinary non-resource-root subschema.
- `$vocabulary` policy reads only the selected metaschema root; referenced metaschemas do not contribute inherited declarations. Ignore declaration behavior in ordinary schemas.
- `$recursiveRef` / `$recursiveAnchor`: general unknown-annotation policy only; no legacy behavior.
- Multi-document OAD resolution.

#### 4.4 — Format annotation (K-14)

- `format` as annotation (OAS default).
- Optional Format-Assertion: if claimed, ALL defined formats or refuse dialect.

**DoD:** K-12, K-14, K-16, K-17, K-25, K-27, K-35, K-36 closed; **GS1-GS8 and GA1-GA3** pass, producing a G-full-schema release candidate.

---

### Wave 5 — Outbound client (C profile)

| Phase | Work |
| --- | --- |
| 5.1 | Parameter serialization: location/style defaults, `explode`, `allowReserved`, `allowEmptyValue`, schema-vs-content, path required/template matching, header restrictions, cookies, exact wire bytes. |
| 5.2 | Servers: Operation/Path Item/root precedence, implicit `/`, document-relative Server URLs, variable/default/enum rules, Paths-key append semantics, and user selection among multiple entries. |
| 5.3 | Security: apiKey/http/oauth2/openIdConnect/mutualTLS metadata and credential hooks; AND/OR, `{}` anonymous alternative, operation `security: []`, inheritance, and scope-array rules. |
| 5.4 | RequestBody: `required`; exact/subtype-wildcard/`*/*` matching with parameters ignored; explicitly named `+json` decoder and unexpected-content fallback conventions; Encoding Object scope/applicability. |
| 5.5 | Responses: exact/range/default precedence, response headers, optional/no-body, unexpected status policy. |
| 5.6 | Non-schema Reference Objects and multi-doc OAD resolution. |
| 5.7 | Callbacks/webhooks: preserve metadata with visible diagnostics; no inbound listener. Links metadata preserve without automatic traversal. |
| 5.8 | Runtime mock HTTP endpoint tests (not only source goldens). |
| 5.9 | XML wire binding remains outside the JSON outbound profile; emit a targeted unsupported-codec diagnostic and never map XML as JSON. |
| 5.10 | Update FeatureSet only as behavior lands: remove exclusions for ParameterStyling, MultiServer, and Cookie when their gates pass. Keep callback/link execution features excluded while documenting metadata preservation separately. |

**DoD:** GM1–GM3, GC1–GC5, GH; G-outbound-json-client release candidate.

---

### Wave 6 — Hardening and release

1. Validator dedupe; code-size audit
2. Optional fuzz
3. Sample regen + docs
4. FeatureSet accuracy
5. Migration guide (remediation -> G-full-schema -> G-outbound-json-client)
6. Conformance report; CI promotion policy

**DoD:** G-full-schema (and G-outbound-json-client if claimed) on agreed CI surface.

---

## 6. Phase checklist (orchestration markers)

| # | Phase | Wave | Profile |
| --- | --- | --- | --- |
| 0 | Foundation: scanner, harness, parser appendix | 0 | S-V / S-A |
| 1 | Architecture ADR | 1 | S-V / S-A / M |
| 2 | SchemaResourceRegistry + exact-number layer | 1 | S-V / M |
| 3 | SchemaNode IR + raw-instance validator | 1 | S-V / M |
| 4 | Boolean schemas | 1 | S-V |
| 5 | `not` | 1 | S-V |
| 6 | Full object validation | 2 | S-V |
| 7 | Full array validation | 2 | S-V |
| 8 | Nested composition | 2 | S-V |
| 9 | Type-array applicability + edge cases | 2 | S-V |
| 10 | minItems/maxItems/minLength/maxLength (Unicode code points) | 2 | S-V |
| 11 | Annotation collector + branch transaction | 3 | S-A |
| 12 | `contains` family | 3 | S-V |
| 13 | `patternProperties` + `propertyNames` | 3 | S-V |
| 14 | `dependentRequired` / `dependentSchemas` | 3 | S-V |
| 15 | `if`/`then`/`else` | 3 | S-V |
| 16 | Pattern engine (unanchored + Unicode) | 3 | S-V |
| 17 | Content vocabulary as annotations | 3 | S-A |
| 18 | Metadata annotations + direction-aware policy | 3 | S-A |
| 19 | `$comment` | 3 | S-V |
| 20 | `unevaluatedProperties` / `unevaluatedItems` | 4 | S-V |
| 21 | `$anchor` / `$dynamicAnchor` / `$dynamicRef` | 4 | S-V |
| 22 | `$id` / `$schema` / `$vocabulary` dialect policy | 4 | S-V |
| 23 | Format annotation | 4 | S-A |
| 24 | Parameter serialization matrix | 5 | C |
| 25 | Servers + variables | 5 | C |
| 26 | Pluggable security hooks | 5 | C |
| 27 | RequestBody + responses + media types | 5 | C |
| 28 | Callbacks/webhooks/links metadata preserve | 5 | C |
| 29 | Runtime mock HTTP endpoint tests + FeatureSet accuracy | 5 | M/C |
| 30 | Hardening + release | 6 | S-V/S-A/M/C |

---

## 7. Testing strategy

### 7.1 Layers

| Layer | Location | Purpose |
| --- | --- | --- |
| L0 | `CppBoostBeastClientCodegenTest` | IR, emission, fail-closed |
| L1 | Gate A / corpus C++ binaries | Runtime schema semantics via direct raw-instance validator |
| L2 | `modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/oas31-jsts/` | Pinned suite, remotes, discovery manifest, direct runner, and OAS-wrapped production-path adapter. |
| L3 | Petstore + 3.1 kitchen-sink | Integration |
| L4 | Parameter/security goldens | C profile |
| L5 | JSON Schema Test Suite (direct) | Direct JSON Schema runner: schema→parser→IR→validator corpus |
| L6 | OAS-wrapped end-to-end | Parser→IR→validator corpus via OAS document (Schema Object context) |
| L7 | Runtime mock HTTP endpoint | C profile: mock server responding per OAS operation definitions |

### 7.2 Per-keyword requirements

Per-keyword tests must differ by category:

| Category | Requirements |
| --- | --- |
| **Assertion** (`type`, `minimum`, `maxLength`, etc.) | Positive instance(s), negative instance(s), composition interaction, boundary/edge cases. Not required to pass negative validity for annotations — annotations never fail. |
| **Applicator** (`allOf`, `properties`, `prefixItems`, etc.) | Branch membership, annotation propagation, nested applicator interaction. |
| **Reference** (`$ref`, `$dynamicRef`) | Resolution, cycles, dynamic scope, base-URI interaction. |
| **Annotation** (`title`, `description`, `default`, `format`, `content*`, etc.) | Preservation, collection, output; must be ignorable without changing validity. Do not require negative validity cases for annotations. |
| **Unknown compatibility names** (`$recursiveRef`, `$recursiveAnchor`) | Process under the general unknown-keyword annotation policy; prove that no legacy recursive behavior is activated. |

Additional universal requirements:
1. Werror build
2. Matrix + exclusions update if JSTS skipped
3. Invert obsolete fail-closed tests when keyword becomes Supported

### 7.3 Annotation-output / application semantics suite (separate from validity)

A dedicated annotation test suite (GA1–GA3) tests:
- `title`, `description`, `default`, `readOnly`, `writeOnly`, `deprecated`, `examples`, OAS `example` annotation collection
- `format` annotation collection (no automatic validation)
- `contentEncoding`, `contentMediaType`, `contentSchema` annotation collection (no automatic decode/parse)
- Evaluation-path annotation propagation (composition branches, `$ref` targets)
- Schema-object/applicator transactions: all successful `anyOf` branches, all `allOf` branches, the sole successful `oneOf` branch, no `not` output, conditional selection, and enclosing-failure rollback
- `default` annotation output (not injected into instance)
- Metaschema detection and dialect policy response

### 7.4 Typed decode/encode mapping tests (M profile — not validity oracle)

M profile tests must distinguish the following outcomes, **not conflate them with schema validity**:

| Outcome | Meaning |
| --- | --- |
| **Schema-valid** | Instance passes all assertion/applicator checks (S-V) |
| **Schema-valid but unrepresentable** | Instance is valid but cannot be losslessly mapped to the selected C++ destination, such as an exact integer or decimal outside that destination's range or precision. |
| **Decode error** | C++ typed extraction fails (Boost.JSON conversion error, type mismatch in typed mapping) |
| **Round-trip** | After decode→encode, the resulting JSON is mathematically equal (exact comparison) to the original |

A C++ decode failure is **not** a schema-invalid signal. Typed mapping is a representability gate (M profile), not a validity oracle.

### 7.5 Anti-greenwash / JSTS

- No weakening assertions to pass CI
- Runtime proof required for semantic claims
- Report **per JSTS group**; Supported means 100% of applicable required-vocabulary tests
- Required-vocabulary semantic JSTS exclusions **must be zero** for G-full-schema. JSTS exclusions can only classify genuinely inapplicable optional profiles and cannot count as support.

### 7.6 OAS 3.1.0 / 3.1.1 / 3.1.2 parity fixtures

- Include schema documents and instances that exercise all three patch versions.
- Default `jsonSchemaDialect`, document/embedded-resource-root `$schema` overrides, and rejection of `$schema` in ordinary subschemas.
- Multi-document OAD: entry document + externally referenced schema documents.
- Metaschema/invalid-schema gate: verify that unknown required vocabulary triggers refusal, unknown optional vocabulary allows processing, invalid metaschema triggers diagnostic.

---

## 8. Documentation deliverables

1. Machine-generated support matrix from `compliance-matrix.yaml` (per keyword, per profile: S-V, S-A, M, C)
2. `jsts-exclusions.yaml` + conformance report (per vocabulary group; required-vocabulary exclusions zero for G-full-schema)
3. Migration guide: remediation → G-full-schema → G-outbound-json-client
4. Dialect allow-list; format/pattern policies; exact-number policy
5. SSE convention clearly non-core
6. Parser blocker appendix (Section 11), exhaustive with no TBD rows
7. Regenerate `docs/generators/cpp-boost-beast-client.md` each wave
8. Never claim full support without gate IDs and profile specification
9. Annotation-output/application semantics documentation, separate from validity
10. Exact JSON number evaluation architecture documentation
11. Contradiction-free matrix: pattern is not both Supported and an exclusion; strict format is not required for schema release; fail-closed is not counted as full support

### 8.1 Machine-readable file contracts

`compliance-matrix.yaml` lives beside the cpp-boost-beast 3.1 test resources. Each row contains `keyword`, `vocabularyUri`, `classification`, `profiles`, `status`, `acceptedSchemaDomain`, `sourceEvidence`, `parserEvidence`, `irEvidence`, `runtimeEvidence`, `jstsGroups`, and `notes`. CI rejects duplicate or missing manifest keywords, unknown statuses, missing evidence for `Supported`, and generated-documentation drift.

`jsts-exclusions.yaml` contains the pinned `suiteCommit`, `dialect`, `runnerVersion`, and bounded `exclusions[]` entries with `file`, `case`, `profile`, `classification: optional-profile`, `reason`, `owner`, and `expiry`. Required-vocabulary tests may not be excluded. Harness defects belong in a separate blocking runner-issues report. CI verifies that every optional exclusion still matches a discovered test.

---

## 9. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Code size explosion | Dedupe; ADR before Wave 1; Option B |
| unevaluated* sibling-only mistakes | Evaluation-path + JSTS; branch transaction/rollback |
| swagger-parser gaps | Section 11 blockers; context-aware raw schema re-walk |
| Pattern syntax/Unicode mismatch | G-full-schema requires the ECMAScript-compatible engine and accepted-language corpus; subset mode is G-honest only |
| Exact-number evaluation without Boost.JSON support | Adopt decimal-rational library; correctness > dependency-free |
| Scope creep (OAuth UX, XML, 3.2) | C profile + Section 10; **no 3.2 in this program** |
| Monorepo CI cost | Nightly first; promote gradually |
| Claiming full too early | GS1-GS8 + GA1-GA3 mandatory for schema claim |
| Dual IR drift | K-24 in Wave 1.3 |
| Float multipleOf / uniqueItems | Exact decimal-rational evaluation; JSTS |
| Annotation semantics insufficient for unevaluated* | ADR must evaluate early; Wave 3 architecture before Wave 4 |
| `$dynamicRef` needs initial static resolution plus dynamic scope | Resource registry + ordered traversed-resource scope + official dynamicRef corpus |
| Effort overrun | Ship G-full-schema (S-V + S-A) without C profile |

---

## 10. Product exclusions

| Item | Policy |
| --- | --- |
| **OpenAPI 3.2+** | Out of scope for this program |
| XML request/response binding | Out of scope; documented as JSON outbound profile |
| Server / stub generation | Out of scope |
| Arbitrary non-OAS JSON Schema dialects | Fail-closed unless allow-listed |
| Full OAuth/OIDC browser/device flows | Out of scope; pluggable hooks only |
| Links runtime follower | Deferred optional; metadata preserved |
| Callbacks/webhooks inbound listener | Out of scope; metadata preserved with diagnostics |
| Typed SSE as core OAS | Convention only (`sseSchemaMode`) |
| Subset-only pattern engine | Allowed only for G-honest; cannot satisfy G-full-schema |
| Replacing Boost.JSON / Beast | Out of scope; exact-number layer sits alongside, not replacing |
| inbound listener profile | Separate scope from outbound client |

**Not eligible for exclusion:** Any keyword in a required vocabulary of the pinned OAS 3.1 dialect. Required-vocabulary semantic JSTS exclusions must be zero for G-full-schema. Product exclusions qualify only the separately named M and C profiles.

---

## 11. Parser capability appendix (living)

Maintain an **exhaustive** table (corpus README or `docs/cpp-boost-beast-client-parser-blockers.md`):

| Keyword / feature | Parser exposes? | Generator workaround | Evidence IDs | Status |
| --- | --- | --- | --- | --- |
| type arrays | | | source:xxx, parser:yyy, IR:zzz, runtime:www | |
| boolean schemas | | | | |
| prefixItems | | | | |
| unevaluated* | | | | |
| $dynamicRef | | | | |
| $anchor / $dynamicAnchor | | | | |
| $vocabulary | | | | |
| const (all JSON kinds) | | | | |
| $comment | | | | |
| contentSchema | | | | |
| Additional manifest rows generated from `compliance-matrix.yaml` | | | | |

**Status per row:** `OK` | `Workaround` | **`Blocker`** (blocks Supported claim).

**GS8 rules:**
- Every keyword in the Section 3 dialect manifest must have an exhaustive row.
- No **TBD**, blank, or unverified entries allowed for keywords claimed Supported.
- Each row must have source/parser/IR/runtime evidence IDs (test case names, commit hashes, or corpus fixture paths).
- No Blocker rows for keywords claimed Supported.

---

## 12. Option B escape hatch

**Mandatory ADR before Wave 1.** Also trigger early if:

- Median generated model TU exceeds threshold (e.g. 50k LOC) on kitchen-sink specs
- Validator dedupe cannot control size after Wave 2
- Annotation threading, dynamic scope, or exact-number evaluation is clearly simpler as an interpreter
- Generated validators cannot support dynamic/annotation semantics needed for Wave 4 `unevaluated*`

If B chosen: IR tables + `SchemaEvaluator`; GS1–GS8 and GA1–GA3 unchanged.

---

## 13. Estimated effort

| Wave | Profile | Rough calendar (1 senior eng) |
| --- | --- | --- |
| 0 Foundation: scanner, harness, parser appendix | S-V / S-A | 3–5 weeks |
| 1 IR, resources, exact-number model | S-V / S-A / M | 12–18 weeks |
| 2 Common validation | S-V | 6–8 weeks |
| 3 Applicators, annotations, content | S-V / S-A | 10–14 weeks |
| 4 Unevaluated, dynamic refs, dialects | S-V / S-A | 8–12 weeks |
| 5 Outbound client | C | 8–12 weeks |
| 6 Hardening | All | 3–5 weeks |
| **G-full-schema (S-V + S-A)** | | **~9–16 months** |
| **G-outbound-json-client (S-V + S-A + M + C)** | | **~13–22 months** |

Wave 5 may overlap late Wave 3 if staffing ≥ 2. Wave 1 must not start before Wave 0 DoD + ADR.

---

## 14. Normative references

- [OpenAPI Specification 3.1.2](https://spec.openapis.org/oas/v3.1.2) - authoritative normative text; supports all 3.1.x patch versions equivalently
- [OpenAPI Specification 3.1.1](https://spec.openapis.org/oas/v3.1.1)
- [OpenAPI Specification 3.1.0](https://spec.openapis.org/oas/v3.1.0)
- [JSON Schema Core 2020-12](https://json-schema.org/draft/2020-12/json-schema-core)
- [JSON Schema Validation 2020-12](https://json-schema.org/draft/2020-12/json-schema-validation)
- [Pinned OAS 3.1 Schema dialect, 2024-11-10](https://spec.openapis.org/oas/3.1/dialect/2024-11-10)
- [Pinned OAS 3.1 base vocabulary metaschema, 2024-11-10](https://spec.openapis.org/oas/3.1/meta/2024-11-10)
- [JSON Schema Test Suite](https://github.com/json-schema-org/JSON-Schema-Test-Suite) - pin by commit SHA
- [RFC 2119 / 8174](https://tools.ietf.org/html/bcp14) - key words for use in RFCs
- [RFC 3986](https://tools.ietf.org/html/rfc3986) - URI resolution
- [RFC 6901](https://tools.ietf.org/html/rfc6901) - JSON Pointer
- [ECMA-262](https://www.ecma-international.org/publications-and-standards/standards/ecma-262/) - `pattern` regex semantics
- [Unicode Standard](https://www.unicode.org/standard/standard.html) - Unicode code point length for `minLength`/`maxLength`
- Remediation: `CPP_BOOST_BEAST_OPENAPI_SPEC_COMPLIANCE_REMEDIATION_PLAN.md`

---

## 15. Immediate next actions

1. Branch `plan/cpp-boost-beast-oas31-full` from remediation HEAD.
2. Execute **Wave 0 only**: establish the exhaustive scanner, G-honest, compiled raw-validator Gate A, JSTS pin, parser appendix v1, and dialect/metaschema gate.
3. Land the machine-readable matrix, JSTS pin, raw-validator Gate A, and exhaustive parser appendix.
4. Freeze marketing: specify S-V, S-A, M, and C profiles when claiming support; make no 3.2 claims.
5. After Wave 0 DoD, record the architecture ADR and begin Wave 1.

---

## 16. Program Definition of Done

### G-full-schema (S-V + S-A) complete (primary)

1. GS1-GS8 and GA1-GA3 pass on the agreed CI surface.
2. Every required-vocabulary keyword in Section 3 and every OAS-aware S-A keyword is **Supported**, with no required-keyword product exclusion.
3. Every applicable required-vocabulary JSTS test executes and passes; required-test exclusions, skips, and unresolved harness defects are zero.
4. Zero silent ignores (GH).
5. Matrix, JSTS exclusions, and parser appendix are current.
6. The OAS 3.0 suite is green.
7. The migration guide is published.
8. FeatureSet and generated documentation match reality.
9. The Wave 1 ADR is recorded and implemented.
10. The exact JSON number layer passes numeric, `const`, `enum`, and `uniqueItems` tests.
11. The annotation-output and application-semantics suite passes.
12. The matrix contains no support/exclusion or validation/annotation contradictions.

### G-outbound-json-client (S-V + S-A + M + C) complete (follow-on)

13. GM1-GM3 and GC1-GC5 pass.
14. Typed C++ mapping tests keep decode, encode, and round-trip separate from validity.
15. Callback/webhook metadata is preserved with visible diagnostics; no inbound listener or automatic Link traversal is claimed.

---

*End of plan (v5 - OAS 3.1 only).*
