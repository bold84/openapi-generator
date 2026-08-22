# CPP Boost Beast OAS 3.1 — Gap-Closure Status & Plan

**Created:** 2026-08-22 · **Branch:** `plan/cpp-boost-beast-oas31-full` · **HEAD at audit:** `4d0932098fd`
**Predecessors:** `CPP_BOOST_BEAST_OPENAPI_31_FULL_COMPLIANCE_PLAN.md` (waves 0–6, gates GS1–GS8/GA1–GA3/GM1–GM3/GC1–GC5/GH) · `CPP_BOOST_BEAST_OAS31_MPROFILE_PLAN.md` (M profile, COMPLETE)

---

## 1. Provenance & Scope

The full-compliance plan and the M-profile follow-on are both **COMPLETE** and claimed
(`G-full-schema`, `G-outbound-json-client`). A strict post-claim audit ("what is missing for
full compliance?") surfaced five residual items that are **not** covered by the claims as
written. This document records (a) the verified current state, (b) each gap with its finding and
decision, and (c) the execution plan to close all five with the same evidence discipline as the
parent plans.

Scope: only these five items. No new generator features beyond the audit findings; no change to
previously claimed surfaces unless an audit re-measurement revises a claim (see Gap 2).

---

## 2. Current Status (verified at HEAD `4d0932098fd`)

| Claim | Surface | Evidence at HEAD |
|---|---|---|
| S-V validation, 2020-12 required vocab | G-full-schema | JSTS **46 files / 1299 cases = 1299 PASS / 0 FAIL / 0 BLOCKED** (batch ≡ serial) |
| S-A annotations (GA1–GA3) | G-full-schema | GA1 gate PASS 36 records |
| C profile (param styles, servers, security, content, refs, webhooks, mock HTTP) | G-outbound-json-client | wire gate **69 cells** (param 19 + server 6 + security 11 + content 21 + ref 5 + mock 7) |
| M profile (five-class typed mapping, F3 non-finite fix) | G-outbound-json-client | M gate **50/50 PASS**; JVM **116/116** |
| Hardening | — | 7/7 TUs `-Werror`; sample zero drift; 13-step CI workflow |

**Repo hygiene:** tracked tree clean (0 modified); untracked = documented scratch dirs only
(`w4*`, `w5*`, `w5probe`, `w5g1`, `m-gate/tmp`, …) — never committed by evidence discipline;
`stash@{0}` (wip-snapshot) preserved, never dropped. Two stray gate-output tsvs
(`m-resolved.tsv`, `negative-composed-results.tsv`) removed from the tree after the audit.

---

## 3. The Five Gaps (full-compliance audit)

| # | Gap | Finding | Claim impact |
|---|---|---|---|
| 1 | Multi-document OAD resolution (GC4 tail) | External `$ref` **across files** for non-schema objects (parameters/responses/headers/requestBodies) was explicitly deferred: "internal cross-component refs are the golden surface". | C-profile claim scoped to single-document specs; multi-doc non-schema refs unproven |
| 2 | Matrix truthfulness (`contentSchema` + deferred rows) | `compliance-matrix.yaml` has **10 rows still marked `deferred`** even though slice evidence for several (e.g. `unevaluatedProperties` 128/129 measured, `$id`/`$defs` Wave-1 implemented, `$dynamicRef` generation-blockers cleared) was committed in later waves. `contentSchema` (K-15) is a Content-vocabulary **annotation** (2020-12 §8.2.6) yet was marked deferred. | "Every required keyword claimed" overstated/unverifiable while statuses lie vs. evidence |
| 3 | Stale generated generator docs | `docs/generators/cpp-boost-beast-client.md` was last touched by the Wave-0/Phase-10 commit (`a4fd0d6e2df`); it predates Wave-5 (C profile) and the M profile. GS6 said "docs match matrix" — overstated. | Docs page doesn't reflect shipped options/behavior |
| 4 | Validator runtime overhead — no benchmark, no opt-out | The attached validator runs on every decode; there is no measured cost and no way to disable composition validation for high-throughput clients. | Operationally unproven; no escape hatch |
| 5 | DateTime/UUID/Byte/Binary as distinct destinations | No format-specific C++ destination: `date-time`/`date`/`uuid`/`byte`/`binary`/`password` → `std::string`, `decimal` → `double` (Format-Assertion intentionally NOT claimed — 2020-12 default = annotation). Correct, but FM features were never FeatureSet-declared nor asserted by a test; GM3 doc silent on them. | Documentation + test gap only |

Not gaps: `$recursiveRef`/`$recursiveAnchor` (draft-07 legacy → unknown-annotation policy, correct),
XML wire binding (documented product exclusion, GC4), Format-Assertion (correctly not claimed).

---

## 4. Gap Detail & Decisions

### Gap 2 — Matrix truthfulness (IN FLIGHT)

**Already done (uncommitted session edit):** `compliance-matrix.yaml` `contentSchema` row
reclassified `deferred` → `annotation` with GA1 evidence citation (annotation gate, 36 records;
2020-12 §8.2.6 content keywords are annotations and can never affect membership).

**Remaining (planned):** re-adjudicate all 10 deferred rows against committed slice evidence and
re-measure at HEAD where evidence is ambiguous:

| Row | Slice evidence on file | Planned action |
|---|---|---|
| `contentSchema` | GA1 annotation gate | ✅ reclassified → `annotation` |
| `$schema` | `resolveEffectiveDialect()`/`validateDialectPolicy()` (Wave 0), pure gate | Promote to `supported` with IR evidence, or `annotation` if policy is gate-only; re-check semantics |
| `$id` | SchemaResourceRegistry (Wave 1, K-29) | Verify registry re-walk evidence → promote |
| `$defs` | Raw parser re-walk (§4.4, Wave 1) | Verify → promote |
| `$anchor` | Wave 4 (K-16) | Re-measure at HEAD → promote or keep with honest note |
| `$dynamicAnchor` | Wave 4 (K-16) | Re-measure at HEAD |
| `$dynamicRef` | Wave 4: 11 BLOCKED → 0 cleared; **19 FAIL remains** in its own slice | **Re-run full JSTS corpus at HEAD** → `supported` only if 0 FAIL, else `fail-closed` with the residual slice cited |
| `$vocabulary` | Top-level refusal implemented; full metaschema inspection Wave 1 | Adjudicate: scope is refusal-only → `annotation` or `fail-closed` honestly |
| `$comment` | Wave 3 (K-32): shape check, never annotation output | Reclassify — `annotation` (no runtime effect) per its classification |
| `unevaluatedProperties` | "Emitted and measured… 128/129" | Identify the 1 residual FAIL → promote with cited remainder or `fail-closed` |
| `unevaluatedItems` | "EMITTED and measured (no longer fail-closed): 58/71" | 13 residual FAILs → `fail-closed` with blocklist or `deferred` with honest note until fixed |

**Acceptance:** every row's `status` matches committed runtime evidence; the workflow's matrix-
invariant step passes; zero rows claim `supported` without evidence.

### Gap 1 — Multi-document OAD references

**Finding:** single-spec fixture generation proves intra-document `$ref` for non-schema objects;
`$ref: './sibling.yaml#/components/…'` across files never exercised for parameters, responses,
response headers, or requestBodies.

**Plan:**
1. Add a two-file fixture pair under `oas-compliance/` (e.g. `multidoc/main.yaml` +
   `multidoc/shared.yaml`): main spec references an external parameter, response (with header),
   and requestBody from the sibling file.
2. Extend the wire gate (`tools/jsts_param_wire.py`) with a **multidoc matrix**: generate the
   pair, compile `-Werror`, and drive cells: external parameter serialization (query/header),
   external response header surface, external requestBody emission + runtime mock exchange.
3. Report per-cell PASS; commit fixtures + gate + report.

**Acceptance:** all multidoc cells PASS; the C-profile claim text updated from "internal
cross-component refs are the golden surface" to include file-level external non-schema refs.

### Gap 3 — Generated generator docs

**Finding:** the docs page is generated (it is not maintained by hand). Mechanism:
`bin/utils/export_generator.sh` runs

```
java -jar modules/openapi-generator-cli/target/openapi-generator-cli.jar \
  config-help -g cpp-boost-beast-client --full-details --named-header \
  --format markdown --markdown-header -o docs/generators/cpp-boost-beast-client.md
```

CLI jar exists (`target/`, built 2026-08-22 21:06) but must be rebuilt **from HEAD** so the page
reflects current codegen (option set, FeatureSet, wire/mock options).

**Plan:** incremental jar rebuild → run the export script → inspect diff → commit the regenerated
page. If regeneration exposes option-set drift vs. the codegen's actual options, fix the codegen
**before** regenerating (never hand-edit the page).

**Acceptance:** page regenerated from HEAD codegen; diff reviewed; any drift resolved.

### Gap 4 — Validation runtime opt-out + benchmark

**Finding:** validator dispatch sites in `model-source.mustache` (oneOf/anyOf/discriminator
branch validation: `validate_{{validator-id}}` calls at template positions ~726, ~749, ~817);
`ValidationTypes.h` is emitted with the model axis.

**Plan:**
1. Add an emitted compile-time knob (e.g. `constexpr bool kValidateOnDecode = true;` in the
   generated `ValidationTypes.h`, overridable via a codegen option such as
   `additionalProperties: compileWithValidation=false` or an emitted macro) that skips **only
   composition-branch validation** (oneOf/anyOf/discriminator).
2. **Invariant:** representation diagnostics must survive the knob — the M-contract "never
   schema-invalid for representation failures" (F3, non-finite destination) stays on ALL paths.
   Document precisely what the knob skips.
3. Add a small benchmark driver (representative composed payload, N iterations, decode with
   validation ON vs OFF): record ops/s + delta in the slice report. Benchmark is evidence, not a
   fragile CI timing gate.
4. Wire the knob through the codegen option surface (JVM test for the option round-trip).

**Acceptance:** knob test proves disabled path skips composition checks while F3 representation
diagnostics still throw; benchmark numbers committed in the slice report.

### Gap 5 — Format destinations documented + asserted

**Finding:** codegen maps `date-time`/`date`/`uuid`/`byte`/`binary`/`password` → `std::string`
(no distinct destination/FeatureSet); `decimal` → `double`; Format-Assertion deliberately not
claimed. No test or doc asserts this mapping.

**Plan:**
1. JVM test in the existing FeatureSet suite: assert `format: date-time|date|uuid|byte|binary|password`
   → `std::string` destination, `format: decimal` → `double`, and that NO format-specific
   FeatureSet entries (Uuid/Date/Byte/Binary features) are emitted.
2. GM3 doc (`docs/cpp-boost-beast-client-typed-mapping.md`): new "Format destinations" section
   with the mapping table + statement that formats are annotations (2020-12 default) and
   therefore need no runtime validation.
3. No corpus churn required — the `String` domain is already corpus-proven; cite it.

**Acceptance:** new JVM tests green; GM3 section committed; FeatureSet unchanged + asserted.

---

## 5. Execution Plan (slice order)

Each slice: executed from committed HEAD, committed with its own report, scratch kept untracked.

| Order | Slice | Deliverables | Commit expectation |
|---|---|---|---|
| 1 | Gap 2 | matrix reclassification + HEAD re-measurement (JSTS full corpus for `$dynamicRef`/`unevaluated*`) + invariant step green | matrix + invariant script runs + report |
| 2 | Gap 5 | JVM FeatureSet format test + GM3 doc section | test + doc |
| 3 | Gap 3 | rebuild CLI jar at HEAD → regenerate docs page → diff review | regenerated page |
| 4 | Gap 1 | multidoc fixture pair + wire-gate matrix + driver cells + runtime mock | fixtures + gate + report |
| 5 | Gap 4 | `kValidateOnDecode` knob + codegen option + knob test + benchmark driver + numbers | codegen + template + test + report |
| 6 | Closeout | conformance doc updates (Gap table, M row refresh, claim wording for multi-doc) + **full battery** (M 50/50 · JSTS · Gate A · wire · GA1 · JVM · sample drift · workflow) + gap-closure slice report | final commit(s) |

Rules carried from parent plans: evidence discipline (commit-then-report), no fake passes,
`-Werror` builds, batch ≡ serial where suites run, five-class M taxonomy untouched, scratch dirs
never committed, `stash@{0}` preserved.

---

## 6. Definition of Done (closeout)

- [ ] All 10 deferred matrix rows re-adjudicated; statuses match runtime evidence; matrix
      invariant step green.
- [ ] Multi-doc non-schema refs proven: wire-gate multidoc cells PASS; C-profile wording updated.
- [ ] `docs/generators/cpp-boost-beast-client.md` regenerated from HEAD; diff reviewed.
- [ ] Validation knob shipped with documented skip-scope; F3 representation diagnostics intact
      on all paths; benchmark numbers committed.
- [ ] Format destinations: JVM test + GM3 section committed; FeatureSet unchanged.
- [ ] Conformance doc updated; full final battery green at HEAD; every slice committed with
      report; tracked tree clean (scratch/stash per discipline).

---

## 7. Risks & Honest Constraints

- **`$dynamicRef` / `unevaluated*` residuals:** HEAD re-measurement may still show FAILs > 0 for
  the JSTS `dynamicRef` group and isolated unevaluated cases. If so, the matrix must say
  `fail-closed`/`deferred` with the residual slice cited — **no claim inflation**. These rows are
  the only place this closeout can legitimately end "not supported", and the plan does not force
  support; it forces truth.
- **Docs regen may surface option drift:** fix the codegen, then regenerate; do not hand-patch
  the generated page.
- **Knob scope creep:** the knob disables composition validation only; any temptation to widen it
  to representation diagnostics is rejected (M-contract).
- **No new feature work beyond the five gaps:** anything else discovered during execution is
  logged in the slice report and adjudicated, not silently absorbed.