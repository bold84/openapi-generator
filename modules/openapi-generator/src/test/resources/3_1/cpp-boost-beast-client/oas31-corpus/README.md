# OAS 3.1 Corpus (cpp-boost-beast-client)

This directory is the **OAS-wrapped 3.1 fixture corpus** for the
`cpp-boost-beast-client` generator, driving **GS3** of
`CPP_BOOST_BEAST_OPENAPI_31_FULL_COMPLIANCE_PLAN.md`:

> `modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/oas31-corpus/**`:
> 100% of OAS-wrapped fixtures match expected generation and raw-instance
> validation outcomes. Include 3.1.0/3.1.1/3.1.2 parity, default
> `jsonSchemaDialect`, resource-root overrides, and embedded/external resource
> dialect changes. Typed decode/encode belongs to GM1–GM3, not this gate.

## Purpose

Unlike a bare JSON Schema Test Suite vault, every file here is a complete
**OpenAPI 3.1 document** (an OAD) so that generation, dialect detection, and
schema evaluation are exercised through the real OAS entry point the generator
accepts. Each fixture pairs:

1. **the OAS 3.1 specification** — the Schema Objects, dialect declarations,
   and resource structure the generator must parse and honour; and
2. **the expected outcomes** — in the accompanying comments: the C++ types the
   generator should emit (generation contract) and the accept/reject verdicts a
   raw-instance validator must produce (S-V validity semantics).

Raw-instance verdicts belong to the **S-V / S-A validity** pipeline (GS3/GS4),
not to typed decode/encode (which is scoped to the separate M corpus and gates
GM1–GM3).

## Dialect baseline

- Normative OAS: **3.1.x** (3.1.0, 3.1.1, 3.1.2 are nominally equivalent patch
  strings; the program targets 3.1.2 while honouring all three).
- Pinned schema dialect (alias `/base`):
  `https://spec.openapis.org/oas/3.1/dialect/2024-11-10`
- Pinned underlying metaschema: **JSON Schema Draft 2020-12**.

`jsonSchemaDialect` at the OAD root is the default for all Schema Objects in
that document. If absent, the pinned OAS dialect applies. A resource-root
`$schema` overrides the OAD default for that entire resource; `$schema` in an
ordinary (non-resource-root) subschema is rejected.

## Coverage map (Wave 0 fixtures)

| File | Patch | Dialect mechanism exercised | Gap IDs |
| --- | --- | --- | --- |
| `oas31-basic.yaml` | 3.1.0 | default `jsonSchemaDialect` (no explicit override) | K-03 (partial), K-06, K-23 |
| `oas31-parity-exclusiveMinimum.yaml` | 3.1.2 | top-level `jsonSchemaDialect` set to pinned dialect | K-17, K-23 |
| `oas31-boolean-schemas.yaml` | 3.1.1 | default `jsonSchemaDialect`; boolean `true`/`false` branches | K-03 |

Three patch strings (3.1.0 / 3.1.1 / 3.1.2) are represented to prove **parity**:
the generator must treat them identically with respect to dialect resolution and
schema semantics.

Resource-root `$schema` overrides, embedded-resource dialect, external-resource
dialect, and multi-document OAD resolution are **declared scope** for the corpus
(per plan §7.6, GS3) and will be added as dedicated fixtures in the same GS3
gate alongside this Wave 0 seed set.

## Expected outcome format

Each fixture's comments use two unambiguous markers so they can be mechanically
asserted by the Gate A raw-validator augmentations:

- `# gen: <cpp type>` — the C++ type the generator is expected to emit for the
  schema (generation/inventory expectation).
- `# instance: <json> -> accept|reject` — a raw-instance validity verdict the
  compiled raw validator must reproduce (S-V proof), independent of any typed
  representation.

These markers are illustrative contracts for the Wave 0/1 harness that consumes
this corpus; they must not be silently ignored. Any keyword or dialect
declaration that affects validity but cannot be honoured must fail generation
(policy §1.5: *fail closed > partial fake*), never be silently skipped.

**Current-state vs target (important):** the outcome markers above describe the
**GS3 target (G-full-schema) semantics**, not the current generator behaviour.
Several seed keywords are currently **fail-closed** in `CppBoostBeastClientCodegen`
(e.g. boolean schemas `K-03`, `prefixItems` `K-06`) and therefore produce a
`generation_failure`, not the `accept` shown in the markers, until their
implementation waves land. Do not read the markers as a present-day support claim.

## Relationship to other suites

| Suite | Role |
| --- | --- |
| `../oas-compliance/fixtures.yaml` | Gate A composition inventory (src of `gate-a.sh`) |
| `../oas-compliance/semantic-cases.yaml` | Gate A semantic decode cases (many DEFERRED pre-Wave 0) |
| `oas31-corpus/` (this dir) | OAS-wrapped 3.1 fixtures for GS3; **Wave 0 seed** |
| `oas31-jsts/` (planned) | Pinned JSON Schema Test Suite (GS2) |
