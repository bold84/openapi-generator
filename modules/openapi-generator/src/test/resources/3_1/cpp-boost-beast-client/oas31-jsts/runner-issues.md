# cpp-boost-beast-client — JSTS blocking runner issues (Wave 0, 2026-08-03)

**THIS REPORT IS ARM'S-LENGTH FROM `jsts-exclusions.yaml`.** By plan §7.5/§8.1/
§10, required-vocabulary semantic JSTS exclusions **must be zero** for
G-full-schema, and runner/pipeline defects are tracked **here**, never in the
exclusion ledger. Nothing below is a product exclusion; every item is a real
Wave-0 shortfall that **blocks GS2** until a fully-faithful raw-instance JSON
Schema 2020-12 validator lands in the production pipeline (Wave 1, "Raw-instance
validator" §5 and ADR §1.0 / §15).

This document is the **authoritative defect ledger** for the JSTS runner. Every
row has an `id`, `severity`, `status`, `owner`, and an `evidence` reference; no
row is a TODO placeholder. It is split into two arm's-length categories:

- **(A) Harness / pipeline defects** — defects in the runner, vendor, compile,
  or generation glue. These are *zeroable by fixing harness code* and are the
  minimum set that must be closed before the remaining required-vocabulary
  corpus can even be measured. Fix-ownership lives with the runner/conformance
  tooling.
- **(B) Semantic shortfalls** — genuine JSON Schema 2020-12 keyword-validity
  gaps in the *generator's* model (typed decode vs schema semantics; fail-closed
  keyword rejection; no production model for certain schema shapes). These are
  **Wave 1–4 plan work** (see §5 / K-18 / K-30), not runner bugs. They cannot be
  zeroed by harness edits; they require production-pipeline work.

Anything not executed is reported as **not run / BLOCKED**, never as pass
(anti-greenwash).

## Provenance
- Distributed runner: `tools/jsts_runner.py` (`discover` + `run`), pinned
  `suiteCommit` `fb7372e8763a1417bddc65fa4c911b3e79b57b65` (2020-12 corpus).
- Compiler: `/opt/homebrew/bin/g++` `-std=c++17`, headers `-I/opt/homebrew/include`,
  link `-lboost_json`. Boost **is** installed (the "no Boost" note in older docs
  is stale).

## Verified subset (this Wave-0 run — exact, not extrapolated)
Ran the production pipeline (generator → g++ → run) on **10** files / 388 cases:

| Metric | Count |
| --- | --- |
| Files run | 10 |
| Cases evaluated end-to-end | 388 |
| **PASS** (decode verdict == suite `valid`) | **40** |
| **FAIL** (decode verdict != suite `valid`) | **32** |
| **BLOCKED** (no production model / generation failure / compile failure / runner crash) | **316** |
| Files NOT run | 34 of 44 required-vocab (remainder not executed → reported as **not run**, not pass) |

`requiredVocabExclusionsZero = true` (exclusions ledger is empty). **GS2 is NOT
met**: 100% required-vocabulary execution+pass is blocked by the shortfalls
below. Nothing below is claimed as support; it is the honest Wave-0 baseline.

## Wave-1 numeric/boolean slice — EXPLICIT target set (2026-08-03, [executed])

This ledger's **Wave-1 slice** targets **only the numeric/boolean 2020-12
subset** — the 10 files whose keywords fall inside the Wave-1
shared-`SchemaEvaluator` keyword set (slice contract §6): `type`
(number/integer), `const`, `enum`, `minimum`, `maximum`, `exclusiveMinimum`,
`exclusiveMaximum`, `multipleOf`, plus `boolean_schema` (OAS 3.1 true/false
value-schema) and `not`. This is a **strict subset** of the required-vocabulary
GS2 corpus (`requiredVocabSubset = true`, asserted by the runner) and runs
through the **same OAS-wrapped + compiled path**. The exact set is
`NUMERIC_BOOLEAN_SLICE_FILES` in `tools/jsts_runner.py` and the `slice` section
of the discovery manifest; run it canonically with `--slice numeric-boolean`.

**Full GS2 is explicitly NOT claimed by this slice.** Neither this
numeric/boolean target nor anything below is a GS2 / G-full-schema claim (slice
contract §0: GS2/GS4/Wave-1-full-fidelity are out of scope — never claim them).

### [executed] Measured result (`--slice numeric-boolean`, real run)

Ran the production pipeline (generator jar → g++ with Boost → run) on all **10**
slice files / **281** cases:

| Metric | Value |
| --- | --- |
| Files run / slice total | 10 / 10 |
| Cases evaluated | 281 |
| **PASS** | **32** |
| **FAIL** | **20** |
| **BLOCKED** | **229** |
| **Zero-BLOCKED target (numeric/boolean)** | **NOT met — 229 BLOCKED** |

Per-file tallies (reproducible; `/tmp/jsts-slice-report.json`):

| File | PASS | FAIL | BLOCKED | Dominant cause (see ledger rows below) |
| --- | --- | --- | --- | --- |
| `boolean_schema.json` | 0 | 0 | 18 | generation rejects top-level boolean schema (H1 remaining wall — spec-validation, not runner crash) |
| `not.json` | 0 | 0 | 40 | whole-file generation rejection of `not` (S3) |
| `const.json` | 0 | 0 | 54 | no production model (S4) |
| `enum.json` | 25 | 20 | 6 | decode fidelity (S2); G14 no model (S4) |
| `minimum.json` | 0 | 0 | 11 | no production model (S4) |
| `maximum.json` | 0 | 0 | 8 | no production model (S4) |
| `exclusiveMinimum.json` | 0 | 0 | 4 | no production model (S4) |
| `exclusiveMaximum.json` | 0 | 0 | 4 | no production model (S4) |
| `multipleOf.json` | 0 | 0 | 11 | no production model (S4); exact-`divmod` not yet exercised (D1 `[unverified]` on this path) |
| `type.json` | 7 | 0 | 73 | mostly no production model (S4); G7 passes 7 |

Result: `targetZeroBlocked = false` for this slice. The dominant blocker is the
K-18 raw-instance-validator gap (S4) — the OAS-wrapped decode path only
materialises a model for object-like schemas, so the scalar/number/bare-object
schemas these files exercise have no evaluable production model; `boolean_schema`
and `not` additionally fail whole-file generation (H1-remaining / S3). The
exact-number layer (D1) is **not** claimed: numbers are parsed through
Boost.JSON, which destroys the numeric lexeme, so `ExactNumber`-based exact
`multipleOf`/`const`/`enum`-number validity is `[unverified]` on this path until
the `runner` agent lands the number-lexeme tokenizer (contract §2).

> **[note] Baselines are not interchangeable.** The earlier Wave-0 "10 files /
> 388 cases" numbers (40 PASS / 32 FAIL / 316 BLOCKED) were a **different
> 10-file selection** (incl. `required.json`/`properties.json`), not this
> slice. The authoritative current number for the numeric/boolean slice is the
> **281-case** run in the table above.

## (A) Harness / pipeline defects — zeroable by fixing runner tooling

These are runner/vendor/compile-glue defects. Closing them is prerequisite to
*measuring* the remaining corpus; they do **not** make the generator a validator.
Status is `open` until a fix is landed and the Wave-0 subset re-run shows the
affected cases move out of BLOCKED.

| id | Defect | Symptom / effect | Severity | Status | Owner | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| **H1** | `build_spec` crashes on boolean schemas | `tools/jsts_runner.py:88` `schema = dict(g.get("schema", {}))` assumes every group schema is a JSON object. `boolean_schema.json` groups have schema `true`/`false`, so `dict(True)` raises `TypeError: 'bool' object is not iterable` **before generation** → the whole file is never run (18 cases across G0=true / G1=false groups). | Blocker (whole file unmeasurable) | **crash-fixed (2026-08-03); open — file still BLOCKED** | Runner / Verify | Crash fixed & verified: `build_spec` now treats non-dict schemas (true/false) as-is (runner-issues H1 drill, 2026-08-03 verify). Real run: `boolean_schema.json` no longer raises `TypeError` and advances to generation, but the file remains whole-file BLOCKED (18) because the OpenAPI generator's spec validation rejects a top-level boolean schema ("attribute components.schemas.G0 is not of type `object`"; `--skip-validate-spec` yields only `AnyType`, no `fromJsonValue_G0`). Remaining wall is a generator/spec-validation shortfall, not the runner's `build_spec` crash. Root cause of the original crash was a harness assumption (schema ⊂ object); **not** a schema-semantic gap — a boolean schema is valid 2020-12. |
| **H2** | Compile shortfall from member-name mangling | Required/property keys with chars illegal in C++ identifiers (e.g. `"foo\nbar"`, `"toString"`, Unicode/colliding manglings) produce duplicate/invalid `m_*` members → g++ fails on a valid JSTS schema (required G3; properties G3). Runner only links the group's own `.cpp`, so a mangled member can block only its group. | Blocker (corpus not compilable) | **open** | Runner robustness (Wave 1 glue) + generator | Wave-0 run: required G3 / properties G3 groups compile-fail → BLOCKED. |
| **H3** | Cross-model link errors | Some generated model `.cpp` reference symbols not linked when compiled per-group (properties G5) → compile FAIL. Runner must resolve the generator's *full dependency set* for a group, not only the group's own `.cpp`. | Blocker | **open** | Runner dependency resolution (Wave 1) | Wave-0 run: properties G5 → BLOCKED with missing-link compile error. |
| **H4** | Remotes not registered | `$ref` / `$dynamicRef` / `$dynamicAnchor` / recursive / external-ref cases resolve against the suite's `remotes/` tree served at `http://localhost:1234/`; the runner does not bind/serve the vendored `remotes/` tree at launch, so these groups are not run. | Blocker (corpus subset unmeasurable) | **open** | Runner infra (register remotes at launch) | README "Registering remotes"; those groups currently report **not run**. |

## (B) Semantic shortfalls — genuine generator/validator gaps, Wave 1–4 plan work

These are not runner bugs; they are production-pipeline capability gaps. Fixing
them is plan work (Wave 1 raw-instance validator, K-18/K-30; Wave 4 for
`unevaluated*` / dynamics). They remain open until the corresponding Wave
delivers and the JSTS subset re-passes.

| id | Shortfall | Groups / scale | Severity | Status | Owner | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| **S1** | Vacuous-truth for non-object instances | `required`, `properties`, `additionalProperties`. Schema: these keywords ignore non-object data (`[]`, `""`, `12`, `null`, `true` → `valid:true`). Generated `fromJsonValue*` throws (missing required / not-object) → decode rejects where suite says valid → **FAIL**. Root cause: model is a typed decode, not a schema validator. (required G0 case2-6; properties G0 case4-5.) | Blocker | **open** | S-V (Wave 1, K-18) | Wave-0 run → FAIL (counted in 32). |
| **S2** | Property type/constraint fidelity | `properties`. Some instances suite marks invalid (e.g. `{"foo":[1,2,3,4]}` under a `type`-constrained `foo`) are **accepted** by decode → **FAIL**. Generator property typing doesn't enforce 2020-12 type/items constraints. | Blocker | **open** | S-V (Wave 1, K-18) | Wave-0 run → FAIL (counted in 32). |
| **S3** | Whole-file generation rejection of valid keywords | `unevaluatedProperties` → 129 blocked; `oneOf` → 27 blocked. Generator fails closed on these required-vocabulary keywords; corpus cannot even compile. Fail-closed (good for GH) but not support. | Blocker | **open** | S-V/S-A (Wave 4 for `unevaluated*`; Wave 1 for `oneOf`) | Wave-0 run → BLOCKED (counted in 316). |
| **S4** | No production model for scalar / bare-object schemas | `type` 73 blocked; `minProperties`/`maxProperties`/`dependentRequired`/`patternProperties` fully blocked. Generator only emits models for object-like schemas with members; scalar-only / member-less schemas materialise `AnyType` or no evaluable model → no verdict possible (K-18 gap: no raw-instance validator layer). | Blocker | **open** | S-V (Wave 1, K-18) | Wave-0 run → BLOCKED (counted in 316). |

## Defect classification note

- **(A) H1–H4** are **harness defects**: fixing the runner/tooling zeroes the
  unusable corpus and lets the remaining cases be *measured*. They are tracked
  here, never in `jsts-exclusions.yaml` (arm's-length rule).
- **(B) S1–S4** are **semantic shortfalls**: only Wave 1–4 production work can
  close them. They are the reason `runner-issues.md` cannot be empty even after
  H1–H4 are fixed.

## What would unblock GS2
- **H1–H4** zeroed by runner fixes (boolean-schema handling in `build_spec`;
  special-character/mangling robustness; full dependency-set linking; `remotes/`
  registration at `http://localhost:1234`).
- A faithful raw-instance validator (exact-number enabled) that evaluates
  instances per JSON Schema 2020-12 semantics instead of typed decode (§5
  Wave 1, K-30) — closes S1/S2/S4 and re-frames S3/S4 as supported.
- Runner generation output that is robust to special-character member names and
  keyword-rejection (fail-closed diagnostics surfaced per-keyword, but the
  corpus must be *runnable* to count).
