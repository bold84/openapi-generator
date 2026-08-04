# Wave-2.5 slice: pattern / string / type-null / ref-emission (executed evidence)

Status: COMMITTED evidence for the Wave-2/2.5 completion slice. All numbers below
were produced by the real GENERATED path: OAS wrap -> real generator -> g++ ->
generated `validate_<id>` dispatch (runner `tools/jsts_genpath_slice.py`,
suite `vendor`, pinned JSTS `fb7372e8763a1417bddc65fa4c911b3e79b57b65`).
BLOCKED = did not run (generation/dispatch failure). FAIL = ran with a
verdict-mismatch. Promotion to `supported` is decided ONLY by zero-FAIL AND
zero-BLOCKED executed-path verdicts (never by hand gate scripts, never by
source/IR evidence alone).

## 1. Root causes fixed this slice

1. **K-23 type-array + `null` member (type.json g10 FAIL).** The OAS-3.1
   normalizer strips a literal `"null"` from `type: [...]` (rewrites it to
   `nullable: true`) before the branch scan, so the union bitmask lost the null
   bit. Fixed via the established pristine-raw-text recovery: `recoverPristineLiterals`
   detects a raw `"type":[...,"null",...]` token, marks the owning component and
   its composition members with `x-oas31-pristine-type-null`, and the branch
   scan restores the member into `validation-type-array` (also covering the
   lowered-to-empty `type: ["null"]` case).
2. **String length counted bytes, not code points (minLength/maxLength caveat).**
   Engine now counts UNICODE CODE POINTS (UTF-8 decode, skip continuation
   bytes). minLength.maxLength suites are zero-FAIL, including the one-emoji
   instance (= 1 code point < 2).
3. **`pattern` was anchored `regex_match`.** Engine now runs the Wave-3.6
   ECMA-262 translation early: `normalizeEcmaPattern` maps `\p{...}` letter
   classes to explicit Unicode code-point ranges (libc++ std::regex has no
   locale constructors and C-locale `[[:alpha:]]` fails on non-ASCII), searches
   UNANCHORED, and always-match-degrades on constructs the translator cannot
   cover (measured fail-open, never silent). pattern.json 12/0/0 incl. the
   `π` unanchored case.
4. **`properties`/`additionalProperties` patternProperties-interplay FAILs +
   propertyNames generation BLOCKEDs.** The Wave-2.5 engine emits
   `patternProperties` (multi-pattern apply with evaluated coverage, exempt
   from additionalProperties) and `propertyNames` (per-member-name
   subschema). Both suites are now zero-FAIL; `properties` 28/0/0,
   `additionalProperties` 21/0/0 (its group-7 generation failures are gone).
5. **G8 unevaluated-in-`not` FAIL.** `refTargetIdOf` aliased `$ref -> composed`
   to the composed schema's `_branch_0` row (the FIRST branch only). For
   `{not: {$ref G0_oneOf_not}}` where G0_oneOf_not = `anyOf:[true, {...}] +
   unevaluatedProperties:false`, the not-child degenerated to
   ref -> boolean-true, dropping both the anyOf wrapper and the
   unevaluated:false. Fixed structurally: every component (composed or plain)
   now materialises a dense `_component` wrapper row (the raw densifier emits
   applicator + members + unevaluated*), and `$ref` resolves to the full
   wrapper. not.json 40/0/0.
6. **oneOf/anyOf exactly-one / at-least-one FAILs on type-carrying ref
   branches (oneOf g1/g8, anyOf g1).** The emitter suppressed the `$ref` hop
   when the branch node carried type flags copied from its target's scan
   (`refs && !hasType` guard), so the TARGET row's oneOf/anyOf applicator
   never ran. The hop is now emitted unconditionally for resolved refs
   (2020-12: $ref + sibling keywords BOTH apply) — oneOf 27/0/0, anyOf
   18/0/0. This ALSO closed G6: the runner vendors the 2020-12 metaschema,
   which densifies into the registry, and ref.json's remote
   `https://json-schema.org/draft/2020-12/schema` cases now run against the
   real metaschema (its `minimum: 0` family rejects the invalid case).

## 2. JVM regression tests

`CppBoostBeastClientCodegenTest` = 109 + `CppBoostBeastClientApiCodegenTest` = 2
= **111 run / 0 fail** on this working tree (BUILD SUCCESS). Includes the new
`wave2.5EmitsStringAndPatternSurface` test (asserts the 72u type-array union
bitmask, the `2.0` minLength exact lexeme, patternProperties rows and the
propertyNames child row are all emitted) and the updated stricture pins: the
`previouslyMissedKeywordsAreFailClosedNotSilentSkip` set now excludes
patternProperties (EMITTED); `failClosedKeywordsSurfaceInLedger` no longer pins
patternProperties/propertyNames as fail-closed; `wave1CompleteEmittedIrEndToEnd`
was de-pinned from raw registry indices to shape/relationship assertions
(ref-hop blocks, root-set membership) because the combined registry grew.

## 3. Executed full corpus (46 files / 1299 cases = 882 PASS / 75 FAIL / 342 BLOCKED)

Per-file verdicts (work dir was a throwaway /tmp tree; the jar was built from
this working tree):

```
additionalProperties.json    PASS=  21 FAIL=  0 BLOCKED=  0 gen=OK
allOf.json                   PASS=  25 FAIL=  3 BLOCKED=  2 gen=OK
anchor.json                  PASS=   6 FAIL=  2 BLOCKED=  0 gen=OK
anyOf.json                   PASS=  18 FAIL=  0 BLOCKED=  0 gen=OK
boolean_schema.json          PASS=  18 FAIL=  0 BLOCKED=  0 gen=OK
const.json                   PASS=  54 FAIL=  0 BLOCKED=  0 gen=OK
contains.json                PASS=   0 FAIL=  0 BLOCKED= 21 gen=PARTIAL
content.json                 PASS=   0 FAIL=  0 BLOCKED= 18 gen=PARTIAL
default.json                 PASS=   7 FAIL=  0 BLOCKED=  0 gen=OK
defs.json                    PASS=   1 FAIL=  1 BLOCKED=  0 gen=OK
dependentRequired.json       PASS=   0 FAIL=  0 BLOCKED= 20 gen=PARTIAL
dependentSchemas.json        PASS=   2 FAIL=  2 BLOCKED= 16 gen=OK
dynamicRef.json              PASS=  17 FAIL= 16 BLOCKED= 11 gen=PARTIAL
enum.json                    PASS=  51 FAIL=  0 BLOCKED=  0 gen=OK
exclusiveMaximum.json        PASS=   4 FAIL=  0 BLOCKED=  0 gen=OK
exclusiveMinimum.json        PASS=   4 FAIL=  0 BLOCKED=  0 gen=OK
format.json                  PASS=   0 FAIL=  0 BLOCKED=133 gen=OK
if-then-else.json            PASS=   2 FAIL=  0 BLOCKED= 28 gen=OK
infinite-loop-detection.json PASS=   2 FAIL=  0 BLOCKED=  0 gen=OK
items.json                   PASS=  29 FAIL=  0 BLOCKED=  0 gen=OK
maxContains.json             PASS=   0 FAIL=  0 BLOCKED= 14 gen=PARTIAL
maxItems.json                PASS=   6 FAIL=  0 BLOCKED=  0 gen=OK
maxLength.json               PASS=   7 FAIL=  0 BLOCKED=  0 gen=OK
maxProperties.json           PASS=  10 FAIL=  0 BLOCKED=  0 gen=OK
maximum.json                 PASS=   8 FAIL=  0 BLOCKED=  0 gen=OK
minContains.json             PASS=   0 FAIL=  0 BLOCKED= 28 gen=PARTIAL
minItems.json                PASS=   6 FAIL=  0 BLOCKED=  0 gen=OK
minLength.json               PASS=   7 FAIL=  0 BLOCKED=  0 gen=OK
minProperties.json           PASS=  10 FAIL=  0 BLOCKED=  0 gen=OK
minimum.json                 PASS=  11 FAIL=  0 BLOCKED=  0 gen=OK
multipleOf.json              PASS=  11 FAIL=  0 BLOCKED=  0 gen=OK
not.json                     PASS=  40 FAIL=  0 BLOCKED=  0 gen=OK
oneOf.json                   PASS=  27 FAIL=  0 BLOCKED=  0 gen=OK
pattern.json                 PASS=  12 FAIL=  0 BLOCKED=  0 gen=OK
patternProperties.json       PASS=  25 FAIL=  0 BLOCKED=  0 gen=OK
prefixItems.json             PASS=  11 FAIL=  0 BLOCKED=  0 gen=OK
properties.json              PASS=  28 FAIL=  0 BLOCKED=  0 gen=OK
propertyNames.json           PASS=  22 FAIL=  0 BLOCKED=  0 gen=OK
ref.json                     PASS=  79 FAIL=  0 BLOCKED=  0 gen=OK
refRemote.json               PASS=   6 FAIL=  5 BLOCKED= 20 gen=PARTIAL
required.json                PASS=  18 FAIL=  0 BLOCKED=  0 gen=OK
type.json                    PASS=  80 FAIL=  0 BLOCKED=  0 gen=OK
unevaluatedItems.json        PASS=  23 FAIL= 17 BLOCKED= 31 gen=PARTIAL
unevaluatedProperties.json   PASS= 101 FAIL= 28 BLOCKED=  0 gen=OK
uniqueItems.json             PASS=  69 FAIL=  0 BLOCKED=  0 gen=OK
vocabulary.json              PASS=   4 FAIL=  1 BLOCKED=  0 gen=OK
```

**30 suites are zero-FAIL AND zero-BLOCKED** (the `supported` evidence):
additionalProperties, anyOf, boolean_schema, const, default (annotation row,
7/0/0), enum, exclusiveMaximum, exclusiveMinimum, infinite-loop-detection,
items, maxItems, maxLength, maxProperties, maximum, minItems, minLength,
minProperties, minimum, multipleOf, not, oneOf, pattern, patternProperties,
prefixItems, properties, propertyNames, ref, required, type, uniqueItems.

## 4. Honest residuals (all named, none greenwashed)

- allOf.json 25/3/2: 3 FAIL = mixed solo-applicator documents
  (allOf + anyOf + oneOf simultaneously; the IR models one applicator per
  node) and 2 BLOCKED = allOf g2 `{allOf:[{maximum},{minimum}]}` emits no
  `validate_G0_branch_0` (extraction edge). **allOf row DEMOTED to deferred.**
- unevaluatedProperties.json 101/28/0: emitted and fully measured; 28
  annotation-depth interplay residuals -> Wave 3/4 annotations.
- unevaluatedItems.json 23/17/31: unevaluatedItems not yet emitted.
- dynamicRef.json 17/16/11 (+ anchor 6/2/0, defs 1/1/0, vocabulary 4/1/0):
  dynamic/anchored ref resolution -> Wave 4.
- if-then-else.json 2/0/28; dependentRequired 0/0/20; dependentSchemas
  2/2/16; contains family 0/0/63; content 0/0/18; format 0/0/133
  (annotation-only; asserts off by default); refRemote 6/5/20
  (non-metaschema remote resources) -> named Wave-3/4/registry slices.

## 5. Gates (executed from this working tree)

- `gate-generated-path.sh` **39/39 GREEN**
- `gate-wave1-complete.sh` **35/35 GREEN**
- `gate-a.sh` all checks **PASS** (19 DEFERRED rows remain by design)

Integer-form YAML bounds never trigger the lexeme-injection path, so the
emitter-fix behavior is unchanged for hand-authored gates.

## 6. Matrix effect (verified post-edit)

68 rows; statuses **supported 30 / deferred 10 / annotation 14 / fail-closed 14**;
zero supported-without-evidence. Promoted this slice (executed zero-FAIL +
zero-BLOCKED): `$ref` (79/0/0), `not` (40/0/0), `properties` (28/0/0),
`additionalProperties` (21/0/0), `type` (80/0/0), `minLength` (7/0/0),
`maxLength` (7/0/0), `pattern` (12/0/0), `patternProperties` (25/0/0),
`propertyNames` (22/0/0). `unevaluatedProperties` fail-closed -> deferred
(measured 101/28/0). `allOf` supported -> deferred (full-suite 25/3/2; its
earlier row rested on Phase-2 evidence narrower than the full suite, and the
anti-greenwash rule requires the full-suite bar).
