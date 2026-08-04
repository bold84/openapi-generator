# Wave-2 Baseline Verification Evidence — cpp-boost-beast-client (VERIFY phase, label v1)

| Field | Value |
| --- | --- |
| Branch | `plan/cpp-boost-beast-oas31-full` (never reset) |
| Task | VERIFY 1 — Full regression baseline BEFORE the Wave-2 object/array JSTS-GENERATED promotion |
| Date (env) | 2026-08-04 |
| Build | `./mvnw` · Java 26 · Boost at `/opt/homebrew/include` · `g++ -std=c++17 -Wall -Wextra -Werror -fsyntax-only` |
| Checkout | HEAD `082461df8f5`; working tree holds in-flight Wave-2 engine files: modified `oas31_ir.hpp`, `oas31_validator.hpp`, `docs/cpp-boost-beast-oas31-wave1-slice-contract.md` + untracked `oas31_object_array.hpp`. Verified AS-IS; this file is the only artifact added here. |

Every claim below was **run** and the output captured verbatim. Anti-greenwash: nothing
reported green without a real command + recorded output. The committed-only promotion
authority (JSTS GENERATED corpus) is NOT re-claimed here — this is a regression gate
pre-run for the Wave-2 pass.

---

## 1. Engine headers — `-Werror` syntax-only (all five, rc=0)

Command (per header):
```
g++ -std=c++17 -Wall -Wextra -Werror -fsyntax-only -I/opt/homebrew/include <header>
```
Headers checked (resources dir `modules/openapi-generator/src/main/resources/cpp-boost-beast-client/`):
`oas31_exact_number.hpp`, `oas31_deep_equal.hpp`, `oas31_ir.hpp`, `oas31_object_array.hpp`,
`oas31_validator.hpp`.

Result: **rc=0 for all five** (zero warnings under `-Wall -Wextra -Werror`). The new
in-flight `oas31_object_array.hpp` (235 lines) is included in the checked set.

## 2. Java generator tests — CppBoostBeastClient* (count + delta)

Command:
```
./mvnw -pl modules/openapi-generator -am test \
  -Dtest=CppBoostBeastClientCodegenTest,CppBoostBeastClientApiCodegenTest \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Recorded result (surefire reports, this run's mtimes):
```
BUILD SUCCESS — 22 goals, 22 executed
CppBoostBeastClientCodegenTest: Tests run: 106, Failures: 0, Errors: 0, Skipped: 0
CppBoostBeastClientApiCodegenTest: Tests run:   2, Failures: 0, Errors: 0, Skipped: 0
```
Total **108/108 pass**. Wave-1 baseline recorded 105 (commit `9573d5d0965`); the **true delta
is +3**, all added by committed Wave-2 commit `ada7ba2a49e`
(`wave2ObjectArrayStructuralKeywordsSurfaceIntoBranchParams`,
`emitsWave2ObjectArrayStructuralIr`, `refSiblingsAndDefsRefsEmitResolutionRows`; two older
tests were renamed in the same commit, net 0 there). Source `@Test` count matches the surefire
count (106 + 2). No offset from parametrized/unexecuted tests.

## 3. Committed gates

### 3.1 `gate-a.sh --skip-build` — object/array smoke gate (Wave-2 raw-instance evidence)
```
GATE_A_RC=0
__SEMANTIC_PASS__=172
__SEMANTIC_FAIL__=0
__SEMANTIC_DEFERRED__=19
__SEMANTIC_ERRORS__=0
PASS  Phase-2 raw-instance runner resolved 172 DEFERRED row(s) with real accept/reject evidence (-Werror compile).
INFO  Wave-0 K-18 shortfall accounting: DEFERRED before=191 -> after=19.
PASS  All Gate A compliance checks passed.
```
Matches the committed `wave2-object-array-gate-a-evidence.md` (172/19), run against the
jar that embeds the in-flight engine byte-identically (verified: jar contains
`oas31_object_array.hpp` 10308 B, `oas31_validator.hpp` 33630 B, `oas31_ir.hpp` 7327 B == workspace sizes).

### 3.2 `gate-wave1-complete.sh` — Wave-1 completion gate (no regression)
```
GATE_WAVE1_RC=0
Step 1: generator jar ready (up to date)
Step 2: emitted schema_ir.generated.{hpp,cpp} + schema_validate.generated.cpp
        IR carries $ref (K-29), uniqueItems (K-22), boolean true/false (K-03),
        notSchema (K-01), deep JSON store (K-30/K-34), enum store (K-34) — all PASS
Step 3: -Werror full-artifact compile rc=0
Step 4: 35 Wave-1-complete cases via GENERATED validate_<Id> dispatch: 35 PASS, 0 FAIL
WAVE-1-COMPLETE GATE RESULT: GREEN
```
**No regression.** The Wave-1 complete engine still runs 35/35 through the real
generator + GENERATED dispatch under `-Werror`.

## 4. compliance-matrix.yaml — YAML validity + evidence audit

File: `modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/compliance-matrix.yaml`

```
yaml.safe_load OK — top-level list, 68 rows, 0 non-dict rows
status histogram: {'supported': 11, 'deferred': 22, 'annotation': 14, 'fail-closed': 21}
```
All **11 `supported` rows carry all four evidence fields** (sourceEvidence, parserEvidence,
irEvidence, runtimeEvidence): allOf, anyOf, oneOf, boolean_schema, const, multipleOf,
maximum, exclusiveMaximum, minimum, exclusiveMinimum, discriminator.

Wave-1/2 residual keywords honestly NOT promoted in the matrix (deferred / fail-closed),
consistent with the committed JSTS baseline:
`not` deferred, `enum` deferred, `uniqueItems` deferred, `ref` deferred,
`properties` deferred, `required` deferred, `items` deferred, `minItems`/`maxItems`
deferred, `additionalProperties`/`prefixItems`/`minProperties`/`maxProperties` fail-closed.

## 5. Residual baseline to fix (recorded, from committed `oas31-jsts/wave1-complete-subset-report.md`)

Verified the committed report records exactly the task's residual counts
(not re-measured here — this is the Wave-2 implementation target):
- `not.json` **36/4/0** (4 FAIL: G2 ×2 deep not:{type,properties}, G3 ×1 not:{} in properties, G8 ×1 not+annotation/unevaluated)
- `enum.json` **46/5/0** (5 FAIL: G3 ×4 object properties+required enums, G14 ×1 empty enum:[])
- `uniqueItems.json` **28/0/41** (41 BLOCKED: uniqueItems:false ×15, prefixItems/items structural ×26)
- `ref.json` **6/5/68** (68 BLOCKED: $defs/`$id`/anchor/remote-URN scopes; 5 FAIL = $ref siblings + inner-$id)

## 6. Verdict

All Wave-2-preflight gates green with zero regressions; no fixes made (verify-only).
`werrorOk=true, javaCount=108, smokeGateGreen=true, wave1GateStillGreen=true, matrixYamlOk=true, failures=[]`.
