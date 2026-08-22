# Wave-4 slice: $dynamicRef/$dynamicAnchor dynamic scoping + unevaluated*-recovery evidence

Date: 2026-08-22
HEAD: `fbc90f175c9` (Wave-3 slice addendum 7) + local Wave-4 changes (unpushed; branch
`plan/cpp-boost-beast-oas31-full`, sits on top of unmerged upstream PR #24387 commit
`9f421b0fcef`).

## Scope

Closed the remaining generated-path ($dynamicRef/$dynamicAnchor) slice of the plan's
G-full-schema wave:

- dynamicRef.json 21 groups: the "initial-target rule" (2020-12 §8.2.3.2), dynamic
  scope resolution (outermost declaring resource wins), resource-boundary-correct
  anchor scans (same $anchor with different base URI), avoid-the-root variant,
  strict-tree.
- anchor.json: $anchor/$dynamicAnchor table registration, hoist dedupe.
- Wave-3.1 tail that had pending careers: contains/minContains/maxContains,
  dependentRequired.
- unevaluatedItems/unevaluatedProperties full files re-verified under the generated
  path (the extraction/dedupe recovery below).
- not.json: annotations inside `not` + anyOf coverage semantics.

## Verdicts (batch runner, one generation + one compile per file)

| file | PASS | FAIL | BLOCKED |
|---|---|---|---|
| not.json | 40 | 0 | 0 |
| dynamicRef.json | 44 | 0 | 0 |
| anchor.json | 8 | 0 | 0 |
| unevaluatedItems.json | 71 | 0 | 0 |
| unevaluatedProperties.json | 129 | 0 | 0 |
| contains.json | 21 | 0 | 0 |
| minContains.json | 28 | 0 | 0 |
| maxContains.json | 14 | 0 | 0 |
| dependentRequired.json | 20 | 0 | 0 |
| boolean_schema / const / enum / minimum / maximum / exclusive* / multipleOf / type | 281 | 0 | 0 |

Totals: 19 files, 656 cases, **656 PASS / 0 FAIL / 0 BLOCKED** (gen=OK everywhere).
JVM suite: 114/114 (109 CodegenTest + DynamicRefParserRetentionTest +
DependentRequiredParserRetentionTest + 3 earlier parser tests).

Evidence dirs (retained, untracked, not committed): `w4n/` (the 9-file wave-4 run),
`w4o/` (the 10-file numeric run), `w4m/` (not/defs/vocabulary run), plus earlier
`w4e`…`w4l` iterations. Each `w4x/report.json` carries per-case verdicts; batch gen
artifacts live under `w4x/gen/<file>/model/`.

## Engineering notes (what the slice had to solve)

1. **`__dynref_` name channel.** swagger-parser drops sibling vendor extensions on
   `$ref`-carrying schemas, so `$dynamicRef` was rewritten by the runner to
   `$ref: #/components/schemas/__dynref_<resid>_<anchor>` and decoded at emission
   (`dynamicRefAnchorOf`). The decoder now rejects names that are not real spec
   components (the model layer synthesises virtual `<parent>_oneOf` names) and the
   component-name set is populated BEFORE the main IR builder (previously the
   branch-path decodes ran against an empty set — dynamicRef g13 "strict-tree").

2. **Dynamic scope = every resource on the evaluation path.** Frames are pushed when
   the row's synthetic resource id differs from the current top-of-scope (resourceRoot
   rows still push re-entries). This fixes embedded-resource members whose resource
   roots are never visited (`$defs.stuff` chains in dynamicRef g20 "avoid-the-root").

3. **Initial-target eligibility follows the ref chain.** The model layer inlines
   composed-ref hops between the `__dynref_` wrapper and the anchored content row;
   `dynamicAnchorEligible` now follows up to 16 pure-ref hops.

4. **dependentRequired parser corruption.** swagger-parser MERGES the required lists
   of a multi-entry dependentRequired map into one shared list for EVERY trigger
   (pinned by `DependentRequiredParserRetentionTest`). `recoverPristineLiterals`
   gained a raw-text pass that re-reads the literal JSON object and injects
   `x-oas31-dependent-required` on the exact carrier member (raw-span member
   splitting); both IR readers prefer the extension.

5. **unevaluated*: extraction/dedupe is destructive.** The model layer extracts inline
   object subschemas into components and dedupes identical content across groups;
   both paths DROP `unevaluatedProperties/Items: false` and boolean property schemas
   are re-typed (`foo: true` → `foo: {"type":"string"}`, not.json g8). The runner now
   hoists every composed member (allOf/anyOf/oneOf) and every sub carrying an
   unevaluated* boolean into components, with an inert `x-oas31-gid` marker per group
   that defeats the cross-group content dedupe.

6. **Count-bound decimal lexemes** (`minContains: 1.0` etc.) joined the raw-literal
   recovery key list.

## Deferred (dialect wave, not this slice)

- defs.json 0:1 — validation of `$defs` content against the 2020-12 metaschema
  (`{"$defs": {"foo": {"type": 1}}}` must be invalid).
- vocabulary.json 0:2 — per-resource `$vocabulary` gating (validation keywords become
  annotations when a resource's vocabulary list omits validation).

Both need the dialect-policy machinery planned in Waves 3/4 remainder.

## Runner acceleration

`jsts_genpath_slice.py` batch mode (`--gen-mode batch`, default): all groups of a file
share one OAS doc (per-group `__g<i>` suffixed hoists + ref rewriting + the gid
markers), one generation and one compile per FILE, all cases in one driver binary;
`--workers N` (default 6) evaluates files in parallel across cores. dynamicRef.json:
~160 s serial → ~6 s batch; the 10-file numeric slice: ~500 s serial → ~12 s.
`--gen-mode serial` remains for per-group isolation debugging.