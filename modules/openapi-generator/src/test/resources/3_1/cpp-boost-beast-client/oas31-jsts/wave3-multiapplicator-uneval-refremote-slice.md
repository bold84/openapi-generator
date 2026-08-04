# Wave-3 Multi-Applicator + unevaluated*-Depth + refRemote Vault Slice — Evidence

Artefact ID: `wave3-multiapplicator-uneval-refremote-slice.md`
Branch: `plan/cpp-boost-beast-oas31-full` · Generator: `cpp-boost-beast-client`
Authority: EXECUTED GENERATED-path corpus only (runner `tools/jsts_genpath_slice.py`,
wrap → real generator → g++ -Werror → GENERATED `validate_<id>` dispatch). Hand gates are
smoke tests; the corpus is the promotion authority.

## 1. Implementation surface (this slice)

### 1.1 Engine — `oas31_validator.hpp` / `oas31_ir.hpp`
1. **Multi-applicator composition (2020-12):** `SchemaNode` now carries
   `allOfChildren`/`anyOfChildren`/`oneOfChildren` as three independent child vectors;
   `walkApplicators` runs allOf (all) → anyOf (≥1) → oneOf (exactly 1), each group with
   per-branch transaction snapshots. All three applicators may coexist on one schema
   (closed allOf g11 `{allOf:[{multipleOf:2}],anyOf:[{multipleOf:3}],oneOf:[{multipleOf:5}]}`).
2. **unevaluated* depth/scope:** `ValidationContext` global evaluated-sets replaced by
   STACKS (`evaluatedPropertiesStack`/`evaluatedItemsStack` with `pushLocation()`/
   `popLocation()`); nested member-value evaluations run in a fresh location so their keys
   never leak into the enclosing object's unevaluated* check (closed the nesting-depth
   FAIL class 41–43). `walkApplicators` members evaluate against the walk-ENTRY coverage
   with transactional capture (`evaluateAndCapture*`) and only SUCCESSFUL branches
   contribute coverage — new `evaluateAndCaptureValid` (failed anyOf/oneOf branches must
   never leak annotations; closed unevaluatedItems g8).
3. **if/then/else:** `ifSchema`/`thenSchema`/`elseSchema`; the guard runs under a throwaway
   capture; its annotations count ONLY when it succeeds, and only the APPLIED branch
   (then xor else) evaluates in the real context.
4. **dependentSchemas:** trigger-key → schema rows; validated in the current context when
   the key is present (annotations count for unevaluated*).
5. **unevaluatedItems:** boolean/schema form with evaluated-item positions scoped to the
   current array location; `prefixItems`/`items` insert evaluated indices under the
   current location.

### 1.2 Emitter — `CppBoostBeastClientCodegen.java`
6. Branch scan emits three independent keys (`validation-allof-schemas`,
   `validation-anyof-schemas`, `validation-oneof-schemas`) instead of a single
   `validation-applicator`; `IrNode` carries the three vectors; raw path and the composed
   `_component` wrapper path densify each applicator group; emission writes the three
   `push_back` vectors.
7. `validation-if`/`validation-then`/`validation-else`/`validation-dependent-schemas`
   scan keys; `IrNode` if/then/else children + dependentSchemas list; structuralChildren,
   index resolution and emission wired (frozen IR layout in `oas31_ir.hpp`).
8. `validation-unevaluated-items` scan key + emission (schema/boolean forms).

### 1.3 Runner — `tools/jsts_genpath_slice.py`
9. **Inline allOf-member hoisting (pass 2):** openapi-generator's InlineModelResolver FOLDS
   pure-constraint inline allOf members (`{allOf:[{maximum:30},{minimum:20}]}` → member
   emptied, constraint lost). Every inline (non-pure-$ref) allOf member is hoisted to a
   component so the branch carries a $ref that never folds.
10. **remotes vault:** `http://localhost:1234/<path>` refs are resolved against the vendored
    `vendor/remotes/<path>` files (swagger-parser otherwise HTTP-fetches at parse time →
    Connection refused → generation BLOCKED). Vault subdocuments are registered as in-doc
    resources, their OWN inner refs recursively rewritten in a nested walk bound to the
    vault document (ctx object = vault root, so local pointers and bare anchors resolve
    against the vault root, never the enclosing group branch), each group operates on a
    FRESH deep copy (never leak a prior group's rewrites). Added the missing
    `vendor/remotes/draft2020-12/the-nested-id.json` resource (its `$id` is declared inside
    `nested-absolute-ref-to-string.json`'s `$defs`; the bytes are authoritative).

## 2. JVM unit tests
Java: 111 run / 0 fail (emitter surface incl. multi-applicator + unevaluated-items + if/
then/else + dependentSchemas scan keys).

## 3. Full executed corpus (46 files / 1299 cases = 1028 PASS / 28 FAIL / 243 BLOCKED)

Per-file verdicts, GENERATED path, executed against the slice build (work dir
`w3/wfull`; deltas vs the committed wave2.5 baseline 882/75/342):

```
additionalProperties.json    PASS=  21 FAIL=  0 BLOCKED=  0
allOf.json                   PASS=  30 FAIL=  0 BLOCKED=  0 <+5/-3/-2>
anchor.json                  PASS=   6 FAIL=  2 BLOCKED=  0
anyOf.json                   PASS=  18 FAIL=  0 BLOCKED=  0
boolean_schema.json          PASS=  18 FAIL=  0 BLOCKED=  0
const.json                   PASS=  54 FAIL=  0 BLOCKED=  0
contains.json                PASS=   0 FAIL=  0 BLOCKED= 21
content.json                 PASS=   0 FAIL=  0 BLOCKED= 18
default.json                 PASS=   7 FAIL=  0 BLOCKED=  0
defs.json                    PASS=   1 FAIL=  1 BLOCKED=  0
dependentRequired.json       PASS=   0 FAIL=  0 BLOCKED= 20
dependentSchemas.json        PASS=  20 FAIL=  0 BLOCKED=  0 <+18/-2/-16>
dynamicRef.json              PASS=  25 FAIL= 19 BLOCKED=  0 <+8/+3/-11>
enum.json                    PASS=  51 FAIL=  0 BLOCKED=  0
exclusiveMaximum.json        PASS=   4 FAIL=  0 BLOCKED=  0
exclusiveMinimum.json        PASS=   4 FAIL=  0 BLOCKED=  0
format.json                  PASS=   0 FAIL=  0 BLOCKED=133
if-then-else.json            PASS=  30 FAIL=  0 BLOCKED=  0 <+28/+0/-28>
infinite-loop-detection.json PASS=   2 FAIL=  0 BLOCKED=  0
items.json                   PASS=  29 FAIL=  0 BLOCKED=  0
maxContains.json             PASS=   0 FAIL=  0 BLOCKED= 14
maxItems.json                PASS=   6 FAIL=  0 BLOCKED=  0
maxLength.json               PASS=   7 FAIL=  0 BLOCKED=  0
maxProperties.json           PASS=  10 FAIL=  0 BLOCKED=  0
maximum.json                 PASS=   8 FAIL=  0 BLOCKED=  0
minContains.json             PASS=   0 FAIL=  0 BLOCKED= 28
minItems.json                PASS=   6 FAIL=  0 BLOCKED=  0
minLength.json               PASS=   7 FAIL=  0 BLOCKED=  0
minProperties.json           PASS=  10 FAIL=  0 BLOCKED=  0
minimum.json                 PASS=  11 FAIL=  0 BLOCKED=  0
multipleOf.json              PASS=  11 FAIL=  0 BLOCKED=  0
not.json                     PASS=  40 FAIL=  0 BLOCKED=  0
oneOf.json                   PASS=  27 FAIL=  0 BLOCKED=  0
pattern.json                 PASS=  12 FAIL=  0 BLOCKED=  0
patternProperties.json       PASS=  25 FAIL=  0 BLOCKED=  0
prefixItems.json             PASS=  11 FAIL=  0 BLOCKED=  0
properties.json              PASS=  28 FAIL=  0 BLOCKED=  0
propertyNames.json           PASS=  22 FAIL=  0 BLOCKED=  0
ref.json                     PASS=  79 FAIL=  0 BLOCKED=  0
refRemote.json               PASS=  31 FAIL=  0 BLOCKED=  0 <+25/-5/-20>
required.json                PASS=  18 FAIL=  0 BLOCKED=  0
type.json                    PASS=  80 FAIL=  0 BLOCKED=  0
unevaluatedItems.json        PASS=  58 FAIL=  4 BLOCKED=  9 <+35/-13/-22>
unevaluatedProperties.json   PASS= 128 FAIL=  1 BLOCKED=  0 <+27/-27/+0>
uniqueItems.json             PASS=  69 FAIL=  0 BLOCKED=  0
vocabulary.json              PASS=   4 FAIL=  1 BLOCKED=  0
```

Zero regressions vs the committed baseline (no row regressed; the only BLOCKED
count changes are improvements). Remaining FAIL groups, all owned by their own
slices: `dynamicRef.json` G0-G5/G9/G11-G20 (19; `$dynamicRef` slice),
`unevaluatedItems.json` G18/G23 (4; `$dynamicRef` + `contains` interactions),
`unevaluatedProperties.json` G21 (1; `$dynamicRef` interaction), `anchor.json`
G3 (2; `$anchor` slice), `defs.json` G0 (1), `vocabulary.json` G0 (1).

`w3/full.json` (runner report) + `w3/full.log` (execution transcript) are the
retained raw artifacts for this table.

## 4. Slice verdicts (measured, all via the GENERATED path)
| suite | before (baseline) | after (this slice) | residuals |
|---|---|---|---|
| allOf | 25/3/2 | **30/0/0** | — |
| refRemote | 6/5/20 | **31/0/0** | — |
| if-then-else | 2/0/28 | **30/0/0** | — |
| dependentSchemas | 2/2/16 | **20/0/0** | — |
| unevaluatedProperties | 101/28/0 | **128/1/0** | G21 `$dynamicRef` interaction (own slice) |
| unevaluatedItems | 23/17/31 | **58/4/9** | G18/G23 `$dynamicRef`/`contains` (own slices) |
| dynamicRef | 17/16/11 | 25/19/0 | 19 FAILs = the `$dynamicRef` slice; zero BLOCKED |

## 5. Gates
- `gate-generated-path.sh`: **39/39 GREEN** (executed after the slice).
- `gate-wave1-complete.sh`: **35/35 GREEN** (executed after the slice).
- `gate-a.sh --skip-build`: **PASS** (19 DEFERRED by design).

## 6. Full sweep + committed-HEAD reproduction
Filled post-run: see addendum (same file, §7) after the committed-HEAD rebuild.

---

## 7. Committed-HEAD reproduction (addendum)

Per the committed-state reproducibility rule, the acceptance numbers above were
re-derived from a jar built after committing HEAD:

- HEAD = `600c55f878f` (Wave-3 slice impl), working tree CLEAN (0 modified).
- `./mvnw -q -pl modules/openapi-generator-cli -am -DskipTests ... package`
  rebuilt `modules/openapi-generator-cli/target/openapi-generator-cli.jar` from
  the committed state (jar mtime after commit).
- Full 46-file corpus re-run (work dir `w3/whead`, report `w3/head.json`):
  **46 files / 1299 cases = 1028 PASS / 28 FAIL / 243 BLOCKED** — per-file
  totals AND every `gi:ci` verdict key programmatically diffed against the
  pre-commit `w3/full.json`: **zero mismatches** (bit-identical).
- Gates from committed HEAD: `gate-generated-path.sh` **39/39 GREEN**,
  `gate-wave1-complete.sh` **35/35 GREEN**; `gate-a.sh` PASS (19 DEFERRED by
  design).
- JVM: **111 run / 0 fail** (BUILD SUCCESS).
