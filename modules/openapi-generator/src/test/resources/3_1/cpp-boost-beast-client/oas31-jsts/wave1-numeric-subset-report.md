# cpp-boost-beast-client — Wave-1 JSTS Numeric/Boolean SUBSET Report (GENERATED path)

**Slice label:** `jsub` · **Owner:** jsts tooling (`oas31-jsts/`) · **Date:** 2026-08-03
**Slice:** Wave-1 numeric/boolean set (slice contract §6) against the **pinned 2020-12
JSON Schema Test Suite** (`suiteCommit` `fb7372e8763a1417bddc65fa4c911b3e79b57b65`).

> **Anti-greenwash (read first).** Every number in this report comes from a **real,
> actually-executed** run of the pipeline shown below (OAS-wrap → real generator →
> g++ compile → run). No row is estimated, extrapolated, or claimed from a
> hand-written validator. A case is **PASS** only when the **GENERATED-path** runtime
> verdict equals the suite's `valid` flag. Anything that did not run is **BLOCKED**,
> never silently passed. **Full GS2 is explicitly NOT claimed by this slice.**
>
> **Headline result:** 281 cases run; **PASS 193, FAIL 22, BLOCKED 66.**
> The **zero-BLOCKED target is NOT met** (66 BLOCKED) **and zero-FAIL is not met**
> (22 FAIL). This slice is therefore **partial support only** — see the FAIL and
> BLOCKED ledgers below. It is a material improvement over the Wave-0 decode-based
> baseline (which measured the same slice at PASS 32 / FAIL 20 / BLOCKED 229), but it
> is not a GS2 / G-full-schema claim and is not full Wave-1 fidelity.

---

## 1. What this slice proves (and does not prove)

This report is the **GENERATED-path** successor measurement for the Wave-1
numeric/boolean slice. It drives the **10 selected JSTS files** end-to-end through
the real generator's emitted artifacts — `schema_ir.generated.{hpp,cpp}` +
`schema_validate.generated.cpp` (a thin `validate_<id>_branch_n` dispatch into the
shared `SchemaEvaluator`) — exactly the ADR Option-B path proven by
`oas-compliance/gate-generated-path.sh` (39/39 green on the committed
`oas31-generated-path-regression.yaml`).

**Selected files (the exact machine-enumerable slice set — same as
`NUMERIC_BOOLEAN_SLICE_FILES` in `tools/jsts_runner.py`):**

| # | File | Required group | Cases | Keywords exercised |
| --- | --- | --- | --- | --- |
| 1 | `boolean_schema.json` | core | 18 | OAS 3.1 `true`/`false` value-schema |
| 2 | `not.json` | applicator | 40 | `not` |
| 3 | `const.json` | validation | 54 | `const` |
| 4 | `enum.json` | validation | 51 | `enum` |
| 5 | `minimum.json` | validation | 11 | `minimum` |
| 6 | `maximum.json` | validation | 8 | `maximum` |
| 7 | `exclusiveMinimum.json` | validation | 4 | `exclusiveMinimum` |
| 8 | `exclusiveMaximum.json` | validation | 4 | `exclusiveMaximum` |
| 9 | `multipleOf.json` | validation | 11 | `multipleOf` (exact `divmod`) |
| 10 | `type.json` | validation | 80 | `type` number/integer |
| **Slice total** | — | — | **281** | — |

Every file above is a required-vocabulary file (`requiredVocabSubset = true`), so the
slice is a **strict subset** of the GS2 corpus and runs through the **same
OAS-wrapped + compiled + GENERATED-dispatch path** as full GS2.

### What it does NOT prove
- It does **not** claim GS2 / G-full-schema (that requires the entire 44-file / 1292-case
  required-vocabulary corpus at 100% with zero exclusions and zero BLOCKED — **out of
  scope**, see the slice contract §0).
- It does **not** claim full Wave-1 fidelity for `const`/`enum`/`type` (see FAIL ledger:
  non-scalar const/enum values and `null`-in-`type`-array are genuine residual
  shortfalls).
- It does **not** claim `boolean_schema` or `not` support — generation **fail-closes**
  on those keywords (BLOCKED, shown below).

---

## 2. Method — OAS-wrap → generate → compile → run (GENERATED path)

The runner used is **`tools/jsts_genpath_slice.py`** (jsts-owned, committed alongside
this report). For each JSTS file, every specification group is **independently**
OAS-wrapped and driven (per-group isolation so one broken generated artifact cannot
mask siblings):

1. **OAS-wrap:** each group's schema is lowered to a single-branch `oneOf` in a
   fresh OAS 3.1 doc (`components.schemas.G0 = {oneOf:[<raw schema>]}`). This is the
   schema shape the Wave-1 IR emitter (`irNodeFromBranch`) densifies to a `SchemaNode`
   and emits a `validate_G0_branch_0` dispatch for. (A bare, non-composed JSTS schema
   materialises the typed-decoder model, not an IR validator — so it cannot be driven
   through the GENERATED dispatch; this matches the engine's current Wave-1 emit.)
2. **Generate:** run the **real** `cpp-boost-beast-client` generator
   (`openapi-generator-cli.jar`) on the wrapped doc.
3. **Compile:** `g++ -std=c++17` the emitted `schema_ir.generated.cpp` +
   `schema_validate.generated.cpp` + a per-group driver + `oas31_lexeme.hpp` with
   Boost (`-I/opt/homebrew/include`, `-lboost_json`).
4. **Run:** for every case, capture the **raw numeric lexeme** from the payload string
   (before Boost.JSON canonicalises it), build `RawInstance(value, lexeme)` so
   `asExactNumber()` stays exact, dispatch through the **GENERATED**
   `validate_G0_branch_0`, and compare the accept/reject verdict with the suite's
   `valid` flag.

**Exact command executed (real run, output captured in §3):**

```bash
python3 tools/jsts_genpath_slice.py \
    --suite oas31-jsts/vendor \
    --jar modules/openapi-generator-cli/target/openapi-generator-cli.jar \
    --work /tmp/jsub-full \
    --out /tmp/jsub-slice-final.json
```

Environment: `g++` (Apple clang 21 / ccache wrapper) C++17; Boost present at
`/opt/homebrew/include` (JSON + multiprecision header-only); JSTS corpus vendored from
the pinned `suiteCommit`.

---

## 3. Real measured results (verbatim run, one slice pass)

The runner's own summary line (tail of the captured run):

```
== boolean_schema.json ==
  file>boolean_schema.json: PASS=0 FAIL=0 BLOCKED=18 gen=PARTIAL
== not.json ==
  file>not.json: PASS=0 FAIL=0 BLOCKED=40 gen=PARTIAL
== const.json ==
  file>const.json: PASS=45 FAIL=9 BLOCKED=0 gen=OK
== enum.json ==
  file>enum.json: PASS=31 FAIL=12 BLOCKED=8 gen=OK
== minimum.json ==
  file>minimum.json: PASS=11 FAIL=0 BLOCKED=0 gen=OK
== maximum.json ==
  file>maximum.json: PASS=8 FAIL=0 BLOCKED=0 gen=OK
== exclusiveMinimum.json ==
  file>exclusiveMinimum.json: PASS=4 FAIL=0 BLOCKED=0 gen=OK
== exclusiveMaximum.json ==
  file>exclusiveMaximum.json: PASS=4 FAIL=0 BLOCKED=0 gen=OK
== multipleOf.json ==
  file>multipleOf.json: PASS=11 FAIL=0 BLOCKED=0 gen=OK
== type.json ==
  file>type.json: PASS=79 FAIL=1 BLOCKED=0 gen=OK

=== TOTALS ===
  files=10 cases=281 PASS=193 FAIL=22 BLOCKED=66
```

**Per-file PASS / FAIL / BLOCKED (from the machine-readable `--out`
`/tmp/jsub-slice-final.json`, same run):**

| File | PASS | FAIL | BLOCKED | Primary disposition |
| --- | --- | --- | --- | --- |
| `boolean_schema.json` | 0 | 0 | **18** | generation fail-closed: `Unsupported schema assertion 'boolean-schema'` |
| `not.json` | 0 | 0 | **40** | generation fail-closed: `Unsupported schema assertion 'not'` (35) + G3 no `validate_` emitted (2) |
| `const.json` | **45** | **9** | 0 | scalar-const green; object/array/null/nul-string const = FAIL (see §4) |
| `enum.json` | **31** | **12** | **8** | scalar-enum largely green; G1 compile shortfall (5) + G2 run-BLOCKED (3); mixed/object-enum = FAIL |
| `minimum.json` | **11** | 0 | 0 | minimum green (incl. "ignores non-numbers") |
| `maximum.json` | **8** | 0 | 0 | maximum green |
| `exclusiveMinimum.json` | **4** | 0 | 0 | exclusiveMinimum green |
| `exclusiveMaximum.json` | **4** | 0 | 0 | exclusiveMaximum green |
| `multipleOf.json` | **11** | 0 | 0 | exact `multipleOf` green (0.1/0.3 decimal divmod) |
| `type.json` | **79** | **1** | 0 | `type: number/integer` green; `type:[array,object,null]` null-case FAIL |
| **Slice total** | **193** | **22** | **66** | **281** |

**Metrics:**

| Metric | Value |
| --- | --- |
| Files run / slice total | 10 / 10 |
| Cases evaluated end-to-end (generate→compile→run) | **281 / 281** |
| PASS | **193** |
| FAIL | **22** |
| BLOCKED | **66** |
| **Zero-BLOCKED target** | **NOT met — 66 BLOCKED** (`targetZeroBlocked = false`) |
| **Zero-FAIL target** | **NOT met — 22 FAIL** |
| Required-vocab exclusions | zero (`jsts-exclusions.yaml` ledger remains empty) |

**Fully-green implemented keywords (generated-path runtime evidence, zero FAIL/BLOCKED
in those specific files):** `minimum`, `maximum`, `exclusiveMinimum`,
`exclusiveMaximum`, and `multipleOf` (exact decimal `divmod`, incl. `0.3 % 0.1 == 0`
true and `1.0 % 0.3 != 0` false), plus `type: number/integer` for the non-null type-array
cases. **These are the only rows this report marks as supported** — every row above is
backed by the GENERATED path, never a custom driver.

---

## 4. FAIL ledger (22 cases — genuine residual shortfalls, zero-FAIL NOT met)

A **FAIL** is a real disagreement between the generated-path verdict and the suite's
`valid` flag on a schema that *did* generate, compile and run. These are honest Wave-1
semantic gaps; they are **not** runner defects and **not** excluded. Per the slice
contract, every one is listed here so no zero-FAIL claim is made.

| File | Group / case | Test | Root cause |
| --- | --- | --- | --- |
| `const.json` | G1 c0,c1 | `const` with object `{"foo":"bar","baz":...}` — same object (any key order) valid | non-scalar `const` value not matched (IR only encodes number lexeme) |
| `const.json` | G2 c0 | `const` with array `[{"foo":"bar"}]` — same array valid | non-scalar `const` not matched |
| `const.json` | G3 c0 | `const: null` — null valid | null `const` not matched |
| `const.json` | G6 c0 | `const:[false]` — `[false]` valid | array `const` type-typed (`false`≠`0`) not matched |
| `const.json` | G7 c0 | `const:[true]` — `[true]` valid | array `const` type-typed not matched |
| `const.json` | G8 c0 | `const:{"a":false}` — object valid | object `const` not matched |
| `const.json` | G9 c0 | `const:{"a":true}` — object valid | object `const` not matched |
| `const.json` | G14 c0 | `const:"hello\u0000there"` — matching nul-string valid | nul char in string `const` not matched |
| `enum.json` | G3 c1,c2,c4,c5 | `enums in properties` — object schema, wrong/missing property values | object-with-properties schema outside scalar enum path / required-handling gap |
| `enum.json` | G6 c0 | `enum:[[false]]` — `[false]` valid | array-enum typed equality gap |
| `enum.json` | G8 c0 | `enum:[[true]]` — `[true]` valid | array-enum typed equality gap |
| `enum.json` | G10 c1,c2 | `enum:[[0]]` — `[0]`/`[0.0]` valid | array-enum typed equality gap |
| `enum.json` | G12 c1,c2 | `enum:[[1]]` — `[1]`/`[1.0]` valid | array-enum typed equality gap |
| `enum.json` | G13 c0 | `enum:["hello\u0000there"]` — matching nul-string valid | nul char in string-enum not matched |
| `enum.json` | G14 c0 | `enum: []` — string invalid | empty-enum not handled as reject-all |
| `type.json` | G10 c2 | `type:["array","object","null"]` — `null` valid | `null` in `type` array not recognised |

> **Quantitative.** 9 of the 22 FAIL are `const` non-scalar-value cases; 12 are `enum`
> mixed/typed/object/empty cases; 1 is `type` [array,object,null]-null. All 22 are
> outside the pure-scalar numeric/boolean cases that the generated path already answers
> green. This is the **explicit, bounded** zero-FAIL gap for the slice.

---

## 5. BLOCKED ledger (66 cases — zero-BLOCKED NOT met)

A case is **BLOCKED** when the production path produced no evaluable generated model or
did not compile/run, so no verdict exists. It is never a pass.

| File | Cases | Cause | Signature |
| --- | --- | --- | --- |
| `boolean_schema.json` | **18** (G0,G1: 9+9) | whole-group generation fail-closed | `UnsupportedSchemaAssertionException: Unsupported schema assertion 'boolean-schema' at #/components/schemas/G0` |
| `not.json` | **40** (G0–G8) | whole-group generation fail-closed (G0–G2,G4–G8; 35 cases) + G3 emits no `validate_G0_branch_0` (2 cases) | `UnsupportedSchemaAssertionException: Unsupported schema assertion 'not'`; G3 = `properties.foo: {not:...}` emits no validator |
| `enum.json` | **5** (G1) | compile shortfall in emitted IR | `schema_ir.generated.cpp:30:61: error: invalid suffix on literal … ExactNumber::parseLexeme("{"foo":12}")` — an object-typed enum value is written into `enumNumbers` as an **unescaped** C++ string literal (generator emit defect) |
| `enum.json` | **3** (G2) | run-stage BLOCKED | `enum:[6,null]` heterogeneous enum-with-null produced no evaluable verdict |
| **Total** | **66** | — | — |

> **Note on granularity.** The runner isolates each group (one OAS doc per group), so
> these BLOCKED counts are *minimum* honest counts after isolating the object-enum
> emit defect away from the rest of `enum.json`. A whole-file-per-doc run (not used
> here) would additionally report `enum.json` G1's compile defect as a full-file
> blocker — the per-group run is the more faithful measurement and is what this report
> cites.

---

## 6. Conclusion (honest bottom line)

- **Supported (GENERATED-path runtime evidence, zero FAIL/BLOCKED in those files):**
  `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `multipleOf` (exact),
  and `type` number/integer (non-null type-array cases). 193/281 cases PASS.
- **Zero-BLOCKED for the implemented keywords: NO.** 66 cases are BLOCKED — the two
  whole-file fail-closed keywords (`boolean_schema` 18, `not` 40) plus `enum.json`
  G1 (5, object-enum emit compile defect) and G2 (3, hetero-enum-with-null run gap).
  **We explicitly do NOT claim zero-BLOCKED.**
- **Zero-FAIL: NO.** 22 cases FAIL (non-scalar `const`/`enum` values, `null`-in-`type`-array).
  **We explicitly do NOT claim zero-FAIL.**
- **Full GS2 is NOT claimed** by this slice, and `targetZeroBlocked = false`.
  The whole required-vocabulary corpus (44 files / 1292 cases) remains out of scope.
- The `gate-generated-path.sh` regression (39/39 on
  `oas31-generated-path-regression.yaml`) remains the separate, fully-green generated-path
  baseline for the *hand-authored* numeric/boolean regression schemas; it does **not**
  imply the official JSTS corpus is green (per the FAIL/BLOCKED ledgers, it is not).

### Comparison to the Wave-0 decode-based baseline (same slice)

The Wave-0 `jsts_runner.py` (typed-decode `fromJsonValue*` path) measured this exact
slice at **PASS 32 / FAIL 20 / BLOCKED 229**. The Wave-1 GENERATED path measured here
improves that to **PASS 193 / FAIL 22 / BLOCKED 66**. Both are honest baselines; the
two runners measure different production paths and are **not interchangeable**. The
GENERATED-path result is the current authoritative number for this slice and supersedes
the Wave-0 figure **only for the keywords/rows this report marks supported above**; every
other row remains FAIL/BLOCKED exactly as listed.

---

## 7. Artifacts

| Item | Location / role |
| --- | --- |
| This report | `oas31-jsts/wave1-numeric-subset-report.md` (committed) |
| Slice runner (GENERATED path) | `oas31-jsts/tools/jsts_genpath_slice.py` (committed) |
| Wave-0 decode runner | `oas31-jsts/tools/jsts_runner.py` (unchanged, committed) |
| Generated-path baseline gate | `oas-compliance/gate-generated-path.sh` + `phase2_generated_path_driver.cpp` (39/39) |
| Exclusions ledger | `oas31-jsts/jsts-exclusions.yaml` (empty — zero required exclusions) |
| Run artifacts (not committed) | `/tmp/jsub-slice-final.json` (real run report), `/tmp/jsub-full/` (work) |
