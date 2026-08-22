# Wave-4 dialect slice: $schema/$vocabulary gating + official-metaschema validation

Date: 2026-08-22
HEAD: `4e17c3c3bfe` (Wave-4 dynamicRef/anchor slice) + this slice (unpushed).

## Scope

Closed the last two JSTS corpus failures plus the surrounding dialect policy:

- vocabulary.json: per-resource `$vocabulary` gating (2020-12 §8.1.2). A resource
  whose metaschema's `$vocabulary` omits the validation vocabulary runs its
  validation keywords as inert annotations: type/enum/const/ranges/lengths/
  counts/required/dependentRequired/min-maxContains bounds are skipped;
  applicators (properties, allOf/anyOf/oneOf, contains matching) and core
  boolean schemas keep working. `metaschema-optional-vocabulary` (validation
  declared true, unknown optional vocab declared false) keeps full validation.
- defs.json: validating instances against the OFFICIAL 2020-12 metaschema
  (`$ref: https://json-schema.org/draft/2020-12/schema`). The metaschema + all
  seven meta/* vocabulary metaschemas are vendored under vendor/remotes, and
  the runner's vault resolves the `https://json-schema.org/draft/2020-12/...`
  URIs (both slash and dash path forms) with $dynamicRef/"meta"-anchor
  machinery fed through the existing dynamic-scope engine.

## Verdicts (batch runner, one generation + one compile per file)

| file | PASS | FAIL | BLOCKED |
|---|---|---|---|
| defs.json | 2 | 0 | 0 |
| vocabulary.json | 5 | 0 | 0 |
| dynamicRef.json | 44 | 0 | 0 |
| anchor.json | 8 | 0 | 0 |
| unevaluatedItems.json | 71 | 0 | 0 |
| unevaluatedProperties.json | 129 | 0 | 0 |
| contains/minContains/maxContains/dependentRequired/not | 123 | 0 | 0 |
| 10-file numeric slice | 281 | 0 | 0 |

Totals: 21 files, 663 cases, **663 PASS / 0 FAIL / 0 BLOCKED** (gen=OK
everywhere). JVM suite: 114/114. Evidence: `w4v/` (11-file wave-4 run),
`w4t/` (dialect focus), `w4u/` (numeric slice), `w4s/` (diagnostic artifacts).

## Engineering notes

1. **Dialect resolution is runner-side.** The runner resolves each group's
   `$schema` (vault-only, best-effort) to the metaschema's `$vocabulary` and
   stamps `x-oas31-vocab-inert` on the resource root when the validation
   vocabulary is absent. The emitter collects the stamped rows' synthetic
   resource ids into `reg.vocabInertResources`, and the evaluator gates the
   validation-keyword blocks via the row's effective resource
   (`ctx.currentValidationRes`, maintained in `validate()` — a row inherits the
   innermost enclosing marked resource when unstamped; the object/array
   traversals save/restore it around their child validations).

2. **The metaschema's `$id`-named property binding** — meta/core declares
   `properties: {"$id": ..., "$schema": ...}`. Every runner walk that treated
   any non-None `$id` as a resource declaration misread the properties
   CONTAINER as a new resource, rebased the rewrite context to the container,
   and left every inner `$dynamicRef`/pointer raw (defs 0:1 accepted type: 1).
   All `$id` reads now require a string value.

3. **URL-form normalization** — official URIs use `/draft/2020-12/...`; the
   suite's remotes vault uses `draft2020-12/...`. The vault loader and the
   refRemote branch accept both forms and normalize to the vault path.

4. Vendored files: `vendor/remotes/draft2020-12/schema` +
   `meta/{core,applicator,unevaluated,validation,meta-data,format-annotation,content}`
   (official json-schema.org copies).

## Deferred

- `format` annotation collection, `$comment` shape-check, the S-A annotation
  collector (GA1) and content-keyword annotations: next wave-3/4 tail items
  (annotation-relevant only; the corpus verdicts are unaffected).