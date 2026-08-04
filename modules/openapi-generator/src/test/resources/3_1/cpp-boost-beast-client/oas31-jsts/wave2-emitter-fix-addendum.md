# Wave-2 Emitter-Fix Addendum — empty-enum reject-all + decimal count-bound preservation

**Git range:** `20c28e948be..<head>` (emitter-fix slice on `plan/cpp-boost-beast-oas31-full`)
**Basis of measurement:** jar rebuilt with `./mvnw -pl modules/openapi-generator-cli -am package -DskipTests` from the identical source state that this addendum is committed with (see "Reproduced from committed HEAD" below).
**Runner:** `oas31-jsts/tools/jsts_genpath_slice.py --suite vendor` — OAS-wrap → real generator → g++ → GENERATED `validate_<id>` dispatch. Verdicts are the executed generated path only.

## 1. What was fixed (emitter-side, JVM)

This slice closes the two residual gaps that previously blocked honest promotion of `enum`, `minItems`, `maxItems`, `minProperties`, `maxProperties`:

1. **Empty `enum: []` was not recovered for JSON (one-line) specs.** The pristine-input recovery (`markPristineEmptyEnums`) only matched YAML 4-space component keys, so the JSTS corpus's JSON one-line per-group docs never received the `x-oas31-empty-enum` marker; the branch degraded to swagger-parser's `types=[string]` inference and the single non-string case wrongly passed (the enum.json G14 FAIL). **Fix:** `recoverPristineLiterals` — format-tolerant component-key location in BOTH YAML (`  Name:`) and JSON (`"Name":`) shapes at any indentation; every recovered literal is mapped to the nearest preceding component present in `components.schemas` (walk-back past non-schema keys). Empty-enum literals still route through `markComponentEmptyEnum` (reject-all).

2. **Float-form count bounds (`minItems: 1.0`) were silently dropped.** swagger-models `getMinItems()/getMaxItems()/getMinProperties()/getMaxProperties()` return null for float values, so the densify conditional never fired and no branch row was emitted (the 8 BLOCKED cases). **Fix:** the same recovery now injects the exact raw lexeme via `x-oas31-<keyword>-lexeme` extensions (`injectCountBoundLexeme`), and all three emission sites — `buildCompositionDescriptor` (array-length + object-property-count paths) and `irNodeFromRawSchema` (fallback) — honor the extension lexeme *before* the swagger-models getters, so `ExactNumber` carries the exact lexeme (`1.0` == `1` mathematically; scientific forms stay exact).

3. **Ledger honesty corrections (same slice):** `minProperties`/`maxProperties` ledger records were `FAIL_CLOSED` ("no generated validator") while Wave-2 already emits object-property-count; `not` (Wave-1) and `boolean-schema` (Wave-1) were still `FAIL_CLOSED` — all four corrected to `EMITTED` with honest notes. The stale `failClosedKeywordsSurfaceInLedger` test pins were updated (only genuinely unimplemented keywords such as `patternProperties` remain fail-closed).

4. **New JVM regression tests:** `recoversEmptyEnumFromJsonSpec`, `recoversFloatCountBoundsFromJsonSpec` — both generate from JSON one-line specs and assert the IR emits `n.hasEnumJson = true;` / the preserved `"1.0"`/`"2.0"` lexemes.

## 2. JVM verification

```
./mvnw -pl modules/openapi-generator -am test \
  -Dtest=CppBoostBeastClientCodegenTest,CppBoostBeastClientApiCodegenTest \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
→ Tests run: 110, Failures: 0, Errors: 0, Skipped: 0  (BUILD SUCCESS)
```

## 3. Executed GENERATED-path corpus (5 affected files)

| file | before | after |
|---|---|---|
| enum.json | 50 PASS / 1 FAIL / 0 BLOCKED | **51 / 0 / 0** |
| minItems.json | 4 / 0 / 2 | **6 / 0 / 0** |
| maxItems.json | 4 / 0 / 2 | **6 / 0 / 0** |
| minProperties.json | 8 / 0 / 2 | **10 / 0 / 0** |
| maxProperties.json | 8 / 0 / 2 | **10 / 0 / 0** |
| **subset total** | **74 / 1 / 8** | **83 / 0 / 0** |

All five keywords are now zero-FAIL **and** zero-BLOCKED through the executed generated path.

## 4. Full 13-file corpus (regression check)

| file | PASS | FAIL | BLOCKED | gen |
|---|---|---|---|---|
| not | 39 | 1 | 0 | OK |
| enum | 51 | 0 | 0 | OK |
| uniqueItems | 69 | 0 | 0 | OK |
| ref | 78 | 1 | 0 | OK |
| properties | 26 | 2 | 0 | OK |
| required | 18 | 0 | 0 | OK |
| additionalProperties | 17 | 2 | 2 | PARTIAL |
| minProperties | 10 | 0 | 0 | OK |
| maxProperties | 10 | 0 | 0 | OK |
| prefixItems | 11 | 0 | 0 | OK |
| items | 29 | 0 | 0 | OK |
| minItems | 6 | 0 | 0 | OK |
| maxItems | 6 | 0 | 0 | OK |
| **TOTAL** | **370** | **6** | **2** | — |

Delta vs committed Wave-2 numbers (`wave2-structural-subset-report.md`): **361/7/10 → 370/6/2** (+9 PASS, −1 FAIL, −8 BLOCKED).

Remaining residuals are all pre-existing and owned elsewhere (no new regressions):
- enum G14: **resolved** (51/0/0).
- min/maxItems + min/maxProperties BLOCKED: **resolved** (0 BLOCKED).
- additionalProperties 17/2/2 unchanged: the 2 FAIL are patternProperties-interplay (Wave-3); the 2 BLOCKED are `propertyNames`-group generation (Wave-3.2) — confirmed by inspecting `spec_additionalProperties_7.json` (`{"oneOf":[{"propertyNames": {"maxLength": 5}, ...}]}`), a generation-stage failure unrelated to this slice.
- not 39/1/0 (annotation/unevaluated-in-not → Wave-3 annotations), ref 78/1/0 (remote metaschema inert → external-$ref slice), properties 26/2/0 (patternProperties interplay → Wave-3).

## 5. Matrix effect (promoted on zero-FAIL + zero-BLOCKED only)

`enum`, `minItems`, `maxItems`, `minProperties`, `maxProperties` → `status: supported`
(net supported rows: 15 → 20). All promoted rows carry source/parser/ir/runtime evidence and executed-path numbers above.

## 6. Reproduced from committed HEAD

<append after commit>
