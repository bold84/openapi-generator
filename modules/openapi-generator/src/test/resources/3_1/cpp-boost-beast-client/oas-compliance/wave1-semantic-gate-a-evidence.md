# Wave-1 Gate-A Phase-2 Semantic Evidence — boolean / not / deep equality / uniqueItems / $ref

**Owner:** harness / gate-a leaf (oas-compliance/).
**Branch:** `plan/cpp-boost-beast-oas31-full`.
**Date/run:** recorded from an ACTUAL executed `gate-a.sh --skip-build` (rc=0) plus
the isolated `phase2_numeric_driver` (68/68) and both baseline/after classifier runs.

This report is the committed, reproducible evidence artifact for wiring Wave-1
raw-instance evidence into gate-a Phase-2 (per the Wave-1 slice contract §9 and
the plan Wave-1 scope). It proves that the following Wave-1 keywords now produce
**real accept/reject verdicts** (PASS) through the compiled `validate_<id> ->
SchemaEvaluator` dispatch instead of remaining DEFERRED.

**Anti-greenwash:** every number below was produced by running the commands; no
claim is carried from a structured return. The gate-a semantic classifier treats
a row as PASS **only** when the compiled driver's verdict is recorded on disk in
`semantic-resolved.tsv`. Rows not covered stay honestly DEFERRED.

---

## 1. Scope and files changed (committed)

| File | Change |
| --- | --- |
| `semantic-cases.yaml` | Added 9 new case groups / 33 raw-instance cases for K-03, K-01, K-30, K-34, K-22, K-29. |
| `phase2_numeric_gen.py` | Extended the IR emitter to the FULL Wave-1 slice: `booleanValue`, `notSchema`, `constJson`, `enumJson`, `hasUniqueItems`, `applicator=ref` + `children`, and `string/array/object` type flags. No engine/Java/jsts file touched. |
| `phase2_numeric_driver.cpp` | **unchanged** — it already dispatches every case through `oas31::validateNumeric` (ADR D5 thin dispatch) → `SchemaEvaluator`; the container/object/array payloads were already handled by generic boost parse + `RawInstance`. |
| `phase2_classify.py` | **unchanged** — the new schema names are members of `NUMERIC_SLICES`, so they are held honestly DEFERRED at baseline and flipped to PASS only by on-disk driver evidence. |
| `gate-a.sh` | **unchanged** — Step 4c already wires `run_numeric_driver` and re-runs the classifier with `BOOST_PHASE2_RESOLVED`. |

**Deliverable mapping (task):** harness/gate-a owns only
`oas-compliance/{gate-a.sh, phase2_* driver/_gen, phase2_classify.py,
semantic-cases.yaml, oas31_lexeme.hpp}`. None of the engine/Java/jsts files were edited.

---

## 2. New Wave-1 keyword cases (33 total, all DEFERRED at baseline)

| Keyword | Case group | Cases | Instances exercised |
| --- | --- | --- | --- |
| K-03 boolean value-schema `true` | `boolean-value-schema-true` | 5 | `true`, `false`, `42`, `"hello"`, `null` → all ACCEPT |
| K-03 boolean value-schema `false` | `boolean-value-schema-false` | 4 | `true`, `false`, `42`, `null` → all REJECT |
| K-01 `not` (subschema const) | `not-const-zero` | 4 | `0` reject; `1`, `-3`, `"x"` accept |
| K-01 `not` (subschema type) | `not-type-string` | 3 | `"hi"` reject; `42`, `true` accept |
| K-30 const-as-json object | `deep-const-object` | 4 | `{a:1,b:[true,null]}` accept; `{a:1.0,...}` accept (1.0==1); `{a:2,...}` / `{a:1}` reject |
| K-30 const-as-json array | `deep-const-array` | 3 | `[1,2,3]` accept; `[1.0,2,3]` accept (1.0==1); `[1,2,4]` reject |
| K-34 enum-as-json objects | `deep-enum-objects` | 3 | `{a:1}` accept; `{a:1.0}` accept (1.0==1); `{c:9}` reject |
| K-22 uniqueItems exact-number | `unique-items-exact-number` | 4 | `[1,2]` / `[1,2,3]` accept; `[1,1.0]` / `[1,2,1.0]` reject (1==1.0 duplicate) |
| K-29 local `$ref` → const:5 | `ref-const-five` | 3 | `5` accept; `6` reject; `5.0` accept |
| **Total** | | **33** | |

---

## 3. Verification (all actually executed)

1. **`bash -n gate-a.sh`** → rc=0 (syntax OK).
2. **Driver joint compile with the real engine under `-Werror`:**
   `g++ -std=c++17 -Wall -Wextra -Werror -I/opt/homebrew/include -I<res_dir> -I<build> phase2_numeric_driver.cpp boost_json_src.cpp`
   → rc=0, no warnings. Links the committed engine headers
   `oas31_ir.hpp`, `oas31_validator.hpp`, `oas31_exact_number.hpp` + this
   slice's `oas31_lexeme.hpp`.
3. **Run driver:** `phase2_numeric_driver` over 68 Wave-1 raw-instance cases
   → `__PHASE2_NUM_TOTAL__=68 __PHASE2_NUM_PASS__=68 __PHASE2_NUM_FAIL__=0`
   (evidence appended to oas-compliance/semantic-resolved.tsv, gitignored).
4. **Full gate-a:** `./gate-a.sh --skip-build` → rc=0. Summary:
   `DEFERRED before=106 -> after=19`; `Phase-2 raw-instance runner resolved 87
   DEFERRED row(s)`; `All Gate A compliance checks passed`.

### 3.1 Gate-a semantic PASS/DEFERRED — before vs after (isolated classifier runs)

Measured with the classifier over exactly the same `semantic-cases.yaml`:

| Bucket | Baseline (no evidence) | After (with `semantic-resolved.tsv`) |
| --- | --- | --- |
| New Wave-1 keyword rows (33) | **33 DEFERRED / 0 PASS** | **0 DEFERRED / 33 PASS** |
| Overall `__SEMANTIC_DEFERRED__` | 106 | 19 |

The 19 remaining DEFERRED rows are Wave-0 rows covered by neither the
`phase2_runner` nor the numeric driver (external-spec / round-trip-M /
response-dispatch / wire cases) — tracked honestly, never silently passed.

---

## 4. Per-keyword honest breakdown (PASS after, 0 remaining DEFERRED in this slice)

| Keyword | JSTS context (env) | This slice |
| --- | --- | --- |
| K-03 boolean value-schemas | boolean_schema 0/0/18 | 9 cases PASS (true ×5, false ×4) |
| K-01 `not` | not → supported | 7 cases PASS |
| K-30 const exact deep equality | const 45/9/0 | 7 cases PASS (object/array + 1 vs 1.0) |
| K-34 enum exact deep equality | enum 31/12/8 | 3 cases PASS (object-values) |
| K-22 uniqueItems | disabled until this pass | 4 cases PASS (1 vs 1.0 duplicate) |
| K-29 local `$ref` | $ref deferred → striving | 3 cases PASS (local ref → const:5) |

---

## 5. Honest limitations (NOT claimed)

- **In-depth numeric lexemes:** the exact number-lexeme is captured at the
  **root** via `oas31_lexeme.hpp::captureLeadingNumberLexeme`. Numbers nested
  inside object/array instances are viewed through the engine's
  `RawInstance::atMember/atIndex`, which carry no captured lexeme and degrade to
  `ExactNumber::fromInt/fromDouble`. For the exercised spellings (`1` vs `1.0`)
  this is still EXACT (`fromDouble(1.0) == fromInt(1)`), so `1 == 1.0` holds at
  depth; a full recursive lexeme-tree tokenizer is a later enhancement.
- **External-file `$ref` / `$anchor` cross-resource resolution / dialect
  switches** are out of scope (Wave-2+ per contract §9.4) — only **local** `$ref`
  to a same-registry node is proven here.
- **The Java-emitted `schema_ir.generated.*` end-to-end generated path** is
  owned by the engine and proven separately by `gate-wave1-complete.sh`; this
  harness builds the identical-format IR itself (documented honesty, §header
  comment of `phase2_numeric_gen.py`).
- Later waves (allOf/anyOf/oneOf full walk, string length+pattern, annotations,
  unevaluated*/$dynamicRef, contains, dependent/if-then-else) are **not** claimed.

## 6. Reproduce

```bash
cd modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/oas-compliance
./mvnw -pl modules/openapi-generator-cli -am compile package -DskipTests -q   # once, jar
./gate-a.sh --skip-build          # full pipeline => rc=0, see DEFERRED 106 -> 19
# isolated driver evidence (no jar required):
PHASE2_NUM_INC=phase2-build/cases.inc PHASE2_NUM_IR=phase2-build/ir.hpp python3 phase2_numeric_gen.py
g++ -std=c++17 -Wall -Wextra -Werror -I/opt/homebrew/include -I../../../../../main/resources/cpp-boost-beast-client \
    -Iphase2-build phase2_numeric_driver.cpp phase2-build/boost_json_src.cpp -o phase2-build/driver
./phase2-build/driver out.tsv     # => __PHASE2_NUM_TOTAL__=68 __PHASE2_NUM_PASS__=68 __PHASE2_NUM_FAIL__=0
```
