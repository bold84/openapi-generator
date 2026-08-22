# Wave-4.3 slice: S-A annotation collection (GA1)

Reproduced from committed HEAD; slice commit: `3daa02b9262`.

## Scope (plan §S-A, gate GA1)

The generated validator now COLLECTS annotation keywords as records at every
successfully-evaluated schema node's instance location:

| channel | keywords | source vocab |
|---|---|---|
| meta-data | `title`, `description`, `default`, `examples`, `deprecated`, `readOnly`, `writeOnly` | meta-data |
| format-annotation | `format` | format-annotation (Assertion=off) |
| content | `contentEncoding`, `contentMediaType`, `contentSchema` (child row) | content |
| unknown-keyword | any non-`x-oas31-*` extension | — |
| `$comment` | STRING-SHAPE check at generation; **never** an annotation output | core |

Every record carries:

- `keyword` (the schema keyword name),
- `instancePath` — RFC 6901 JSON pointer of the instance location (root = `""`),
- `schemaPath` — emitter row name (hoisted names encode the reference
  traversal, e.g. `demo_title_component_prop3`),
- `absSchemaUri` — synthetic absolute schema-location URI
  `urn:oas31:res:<dynamicResource>`,
- `value` — the keyword's value as JSON text (`true`/`false` for the
  boolean markers, `toJsonLiteral` output for `default`/`examples`/extras,
  verbatim strings for the rest; `contentSchema` carries its child's
  schema-location name — annotation-only semantics, the child is never
  evaluated against the instance).

`deprecated`/`readOnly`/`writeOnly` are OAS 3.0 legacy markers; per 2020-12
meta-data they are annotation-only. `$comment` is *shape-checked* (a non-string
value is flagged `n.annCommentShapeViolation` — the ledger never loses the
spec violation) but produces **no** annotation output. Unknown keywords ride
the extension map minus the runner's `x-oas31-*` engine channels.

## Fail-closed inversion (per §7.2, keyword now Supported)

`contentEncoding` was the LAST fail-closed keyword in the composition
descriptor gate (it was the canary used by the inverted JVM tests after the
contains family left fail-closed). Per 2020-12 §8.2.6 the content keywords are
pure annotations — they have zero validation behavior and can never affect
composition membership — so the GA1 annotation surface makes the fail-closed
stance obsolete:

- `scanSurfaceAssertions` now records `content-encoding`, `content-media-type`,
  `content-schema` as SUPPORTED (they ride `readAnnotationKeywords` into the
  IR + annotation collection).
- `validateDescriptorAssertions` can no longer fire for them. No keyword
  remains fail-closed through the descriptor gate (`not` is fully emitted —
  not.json 40/0/0; the `"not".equals(unsupported)` special case is dead code
  left for the ledger's ongoing audit).
- JVM canary inversion:
  - `descriptorUnsupportedAssertionsPopulated` → asserts content-encoding is
    in the branch's supportedAssertions and absent from unsupportedAssertions
    (no exception).
  - `unsupportedAssertionOnAnyOfThrows` → `contentEncodingAnyOfNoLongerThrows`
    (preprocess succeeds; branch supported).
  - `failClosedKeywordsSurfaceInLedger` fixture extended with
    contentEncoding/contentMediaType/contentSchema; asserts none of them
    appear in `failClosedKeywords(openAPI)`.

## Batch-mode contamination fix (runner)

The batch driver (one shared OAS doc across groups, `__g<i>`-suffixed hoists)
was proven equivalent on dynamicRef only. The full-corpus batch run exposed a
second contamination class: the inline-model resolver dedupes IDENTICAL inline
boolean `oneOf` members across/between groups — `oneOf: [true, true, false]`
collapsed to `[true, false]`, silently accepting what the suite rejects
(oneOf.json 4:0 FAIL in batch vs PASS in serial).

Fix: `hoist_allof` now hoists every boolean `oneOf` member into a
group-unique object wrapper (the `x-oas31-gid` marker makes the contents
dedupe-proof): `true` → `{"x-oas31-gid": <g>}` (accept-all), `false` →
`{"x-oas31-gid": <g>, "not": true}` (reject-all via the corpus-proven not
machinery). anyOf/allOf boolean members are left untouched (duplicate accepts
are harmless there). oneOf.json batch ≡ serial afterwards.

## Changes

- `CppBoostBeastClientCodegen.java`
  - `readAnnotationKeywords(Schema, BiConsumer)` — shared collector for both
    the raw path and the composition-branch vp channel.
  - `readAnnotationVp` / `readAnnotationRaw` — materialise `IrNode` fields
    (`annTitle` … `annExtras`); `contentSchema` is densified as a child row
    (`annContentSchemaNode` → post-numbering `annContentSchemaIndex`).
  - row emission: annotation payloads + `n.sourceName` (emitter row id).
  - `$comment` shape check surfaced as `validation-ann-comment-shape-violation`.
  - content keywords: fail-closed → supported (see inversion above).
- `oas31_ir.hpp` — `SchemaNode` annotation fields + `sourceName`.
- `oas31_validator.hpp` — `collectAnnotations(node, path, ctx)` at the
  successful closure of `validateSchemaNode`; records into
  `ctx.annotations.add(Annotation{…})`; instance pointer normalised per
  RFC 6901 (`/`-prefixed); `contentSchema` record carries the child
  schema-location.
- `tools/jsts_annotation_gate.py` — new GA1 gate: demo spec carrying every
  annotation keyword (incl. a `$comment`, an unknown keyword, and a
  `contentSchema` child) through the wrap → generate → compile → run
  pipeline; asserts presence, paths, URI shape, literal values and
  `$comment` silence.
- `tools/jsts_genpath_slice.py` — boolean oneOf-member hoisting (batch-mode
  contamination fix).

## Evidence

### GA1 gate (generated artifact)

```
GA1 GATE PASS (36 annotation records, $comment silent)
```

Key records (raw binary output, `kw|instance|schema|uri|value`):

```
title|name|demo_title_component_prop2|urn:oas31:res:0|name title
contentEncoding|payload|demo_title_component_prop3|urn:oas31:res:0|base64
contentMediaType|payload|demo_title_component_prop3|urn:oas31:res:0|text/plain
contentSchema|payload|…_contentSchema|urn:oas31:res:0|…_contentSchema…
title||G0_branch_0|urn:oas31:res:0|demo title
description||G0_branch_0|urn:oas31:res:0|demo description
default||G0_branch_0|urn:oas31:res:0|{"a":1}
examples||G0_branch_0|urn:oas31:res:0|1
examples||G0_branch_0|urn:oas31:res:0|"two"
deprecated||G0_branch_0|urn:oas31:res:0|true
readOnly||G0_branch_0|urn:oas31:res:0|true
writeOnly||G0_branch_0|urn:oas31:res:0|false
format||G0_branch_0|urn:oas31:res:0|demo-format
unknownKeyword||G0_branch_0|urn:oas31:res:0|{"x":1}
default|level|G0_branch_0_prop1|urn:oas31:res:0|3
```

The root `$comment` is absent from all 36 records (silent). Nested properties
report their own locations (`/name`, `/payload`, `/level`). The unknown
keyword reports its literal JSON value. The boolean markers report
`true`/`false` JSON text.

Invocation (from the suite dir):

```
python3 tools/jsts_annotation_gate.py
```

### Full JSTS corpus (generated path, batch mode)

```
files=46 cases=1299 PASS=1299 FAIL=0 BLOCKED=0
```

- content.json: 18/18 PASS in batch mode (was 18 BLOCKED fail-closed).
- oneOf.json: 27/27 PASS in batch mode with the boolean-member hoist
  (4:0 `[true, true, false]` correctly rejects).
- Regression vs Wave-4 dialect slice (663/663 on its 21-file subset): the
  full 46-file corpus is now entirely green.

### Batch ≡ serial equivalence

oneOf.json batch vs serial: no verdict differences (`w4g5` vs `w4g3`).
Full-corpus serial equivalence run:

```
files=46 cases=1299 PASS=1299 FAIL=0 BLOCKED=0 (w4g7/report.json, serial mode)
```

Batch (w4g6) and serial (w4g7) verdicts are identical file-for-file.

### JVM (generator-side) regression

```
Tests run: 114, Failures: 0, Errors: 0, Skipped: 0
```

## Status

- [x] GA1 collected-annotation records (keyword / instancePath / schemaPath /
      absSchemaUri / value)
- [x] `$comment` shape-checked but never output
- [x] unknown-keyword annotations with literal values
- [x] content/format-annotation vocabularies as annotations
- [x] content-keyword fail-closed inversion (last fail-closed keyword gone)
- [x] batch-mode boolean oneOf-member contamination fixed
- [x] full corpus + JVM regression green at committed HEAD

## Gates

- GS1–GS8 (S-V): verdict counts unchanged for the assertion surface; the
  fail-closed inversion is the ONLY gate delta (content vocab → Supported).
- GA1 (S-A): PASS — see gate evidence above.
- GA2/GA3: engine work-tracked (GA2/GA3 transform: output side, out of
  scope for this slice — see the conformance doc).