# cpp-boost-beast-client — OpenAPI 3.1 Conformance Status (Wave 0)

> Status: **Wave-0 foundation / honest-partial (G-honest) only.**
> This repository does **not** currently claim `G-full-schema` (S-V + S-A complete)
> nor `G-outbound-json-client` (S-V + S-A + M + C). This document records the
> Wave-0 conformance status honestly, including what is currently proven vs still
> unproven for the compiled Phase-2 C++ runner.

This document is the Wave-0 conformance record for the
`CPP_BOOST_BEAST_OPENAPI_31_FULL_COMPLIANCE_PLAN.md` program (target generator
`cpp-boost-beast-client`). It is inventory-oriented and honest by construction:
it states exactly what exists, what is programmed but not yet wired, what is
scaffolded, and what is currently proven vs not yet proven. Boost/Beast is
installed on this host, so the compiled raw-instance runner runs locally; what
remains unproven is explicitly the GS4 remainder and the JSTS (GS2) full run.

- Pinned OAS schema dialect: `https://spec.openapis.org/oas/3.1/dialect/2024-11-10`
  (alias `/base`)
- Underlying schema foundation: JSON Schema Draft **2020-12**
- Test resources root:
  `modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/`
- Existing harness: `modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/oas-compliance/`
  (`gate-a.sh`, `fixtures.yaml`, `fixtures-negative.yaml`, `semantic-cases.yaml`, `expected-types.yaml`)

---

## 1. Compliance profiles

The plan defines four normative profiles. A profile claim is made only when the
profile's complete definition (`Definition of Done`) is satisfied; no profile is
claimed complete at Wave 0.

| Profile | Name | Meaning | Wave-0 status |
| --- | --- | --- | --- |
| **S-V** | Schema validity | Every required Core, Applicator, Validation, and Unevaluated keyword implemented with correct JSON Schema 2020-12 validity semantics, including the exact JSON Number domain. | **Not complete.** The compiled Phase-2 raw-instance runner runs locally and has produced real accept/reject evidence for the 7 currently `supported` keywords (`allOf`, `anyOf`, `oneOf`, `type`, `enum`, `multipleOf`, `discriminator`; 19 of 38 semantic rows resolved), but GS4 'zero DEFERRED' is not met and the full validity surface is not implemented (see §5). Per GS1/GS2, the 15 keywords whose validators are emitted but lack runtime proof are `deferred`, not `supported`. Partially attacked via pure Java dialect/structure helpers, the fail-closed surface, and the Phase-2 runner. |
| **S-A** | Schema annotation | All OAS 3.1 Schema dialect annotation keywords collected, propagated, and exposed with keyword, instance location, schema-location path, absolute schema URI, and value(s). `$comment` is not an annotation. | **Not complete.** Annotation collector/evaluation context is a Wave 3 deliverable (plan §3.0); no annotation suites (GA1–GA3) exist yet. |
| **M** | Typed C++ mapping | Declared/representable JSON domain mapped to C++ via Boost.JSON DOM; separate representability gate; typed decode/encode is not a validity oracle. | **Not complete.** M corpus and GM1–GM3 are not established in this program wave. |
| **C** | Outbound HTTP client | Non-schema OAS 3.1 client behaviour (parameter serialization, security, request/response, servers, media negotiation). | **Not complete.** Plan §5 Wave 5; runtime mock HTTP tests absent. |

**"Full OAS 3.1 schema compliance"** = S-V + S-A complete. **"OAS 3.1 JSON
outbound-client profile"** = S-V + S-A + M + C complete. Neither is claimed here.

---

## 2. Goal levels

| Level | ID | Definition | This repository |
| --- | --- | --- | --- |
| Honest partial | **G-honest** | Zero silent ignore: every dialect keyword is **Supported** or **Fail-closed**, with tests and matrix rows. | **Claimed status target for Wave 0.** The current baseline is not yet G-honest (plan §1.4 lists known silent-skip gaps: allOf unsupported-assertion exemption, missing scanner keywords/schema locations, anchored `regex_match`, byte-length strings). Wave 0 must close these via the exhaustive scanner and fail-closed handling. |
| Full schema | **G-full-schema** | S-V + S-A complete; every required-vocabulary keyword **Supported**, no required-keyword product exclusion, full exact JSON Number domain. | **NOT claimed.** This is the program's ~9–16 month primary success criterion; requires Wave 1–4 plus the Boost-gated compiled raw-validator and JSTS. |
| JSON outbound client | **G-outbound-json-client** | G-full-schema + M + C DoD for the enumerated JSON-focused outbound profile. | **NOT claimed.** Follow-on goal; requires Wave 5 as well. |

### Explicit honest statement

> **This repository currently claims — at most — `G-honest` / Wave-0-foundation.**
> It does **not** claim `G-full-schema` (S-V + S-A complete) and does **not**
> claim `G-outbound-json-client`. Every keyword whose exact mathematical, Unicode,
> dynamic-scope, or annotation semantics are not proven stays `fail-closed`
> (not `supported`) in the Wave-0 compliance matrix. Any marketing that describes
> this generator as "fully OAS 3.1 compliant" is unsupported by evidence until
> GS1–GS8 and GA1–GA3 pass on an agreed CI surface.

---

## 3. `CPP_BOOST_BEAST_OPENAPI_31_FULL_COMPLIANCE_PLAN.md` — Wave-0 deliverables

| Wave-0 deliverable (plan §5, "Wave 0 — Foundation") | Status | Evidence / notes |
| --- | --- | --- |
| **Exhaustive schema-valued-position scanner + keyword occurrence ledger** | **Foundation** | The keyword occurrence ledger contract is defined in the Wave-0 compliance matrix header; the matrix records every manifest keyword with classification, profiles, status, and evidence columns. The known baseline scanner gaps (plan §1.4) are acknowledged and targeted by this wave. (See compliance-matrix deliverable below for the machine-readable ledger.) |
| **OAS 3.1 structural-normative + dialect-gate pure Java helpers** | **Programmed (helpers); enforcement wiring deferred to Wave 1** | `CppBoostBeastClientCodegen.java` (`modules/openapi-generator/src/main/java/org/openapitools/codegen/languages/CppBoostBeastClientCodegen.java`): pinned `OAS_31_DIALECT` / `OAS_31_DIALECT_BASE_ALIAS` / `DRAFT_2020_12` constants, `OasDialect` enum, `resolveEffectiveDialect(...)`, `resolveDocumentDialect(...)`, `validateNormativeOas3Structure(...)` (root `openapi` + `info`, `info.title`, `info.version`, at least one of `paths`/`components`/`webhooks`, empty maps permitted), and `validateDialectPolicy(...)` (unknown dialect → refuse). These are **not** auto-wired into `processOpenAPI`; unconditional fail-closed wiring would regress hundreds of existing test fixtures that omit `info`. Enforcement wiring is explicitly deferred to **Wave 1** for test-suite compatibility, as documented in the source comment. Full `$vocabulary`/metaschema inspection and `$schema`-in-subschema rejection remain Wave-1 `SchemaResourceRegistry` blockers. |
| **Compiled raw-instance Phase-2 runner (Gate A semantics)** | **Landed, run locally; GS4 not fully green** | Replaces `DEFERRED` semantic rows in `semantic-cases.yaml` with raw-validator accept/reject evidence (plan Wave 0 item 2, GS4, K-18). `oas-compliance/` ships a self-contained Phase-2 runner (`phase2_runner.cpp` + `phase2_gen_cases.py` + `phase2_classify.py`) that reuses the **generated** raw-instance validation path and compiles the generated model axis + Boost under `-Werror`. On this host (Boost at `/opt/homebrew/include`) a live `gate-a.sh` run resolved **19** DEFERRED rows to real accept/reject (17 via the generated validators, 2 via a single hand-written `type: number` check) — DEFERRED **38 → 19**. The records are regenerated deterministically by `gate-a.sh` into `oas-compliance/semantic-results.tsv` (19 PASS / 19 DEFERRED) and `oas-compliance/semantic-resolved.tsv` (19 PASS) — both are gitignored run outputs, not committed sources. **19 rows remain DEFERRED** (external spec files, typed round-trip M-corpus semantics, response-dispatch/outbound-client behaviour, wire-level multipart encoding C-08). This Phase-2 evidence is the `runtimeEvidence` cited by the `supported` composition/validation rows of the refreshed matrix. Full GS4 'zero DEFERRED' is **not** claimed. See §4/§5. |
| **JSTS pin** | **Pinned (real SHA); baseline recorded; GS2 NOT met** | `modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/oas31-jsts/` holds `README.md`, `jsts-exclusions.yaml`, `runner-issues.md`, and `tools/` (vendor.sh + jsts_runner.py). `suiteCommit` is a **real 40-hex SHA** (`fb7372e8763a1417bddc65fa4c911b3e79b57b65`, recorded via `git ls-remote`), not a placeholder; `runnerVersion: 0.1.0-wave0`; the `exclusions[]` ledger is empty (zero required-vocabulary exclusions). A Wave-0 baseline run over **10** files / **388** cases recorded PASS **40** / FAIL **32** / BLOCKED **316** (see `README.md` + `runner-issues.md`). **GS2 (100% required-vocabulary execution + pass) is NOT met** at Wave 0 and is uploaded as a blocking runner-issues report. I did not re-execute the full JSTS runner in this documentation pass; the recorded baseline is quoted from the committed artifacts. |
| **`compliance-matrix.yaml`** | **Refreshed (matches reality; GS1/GS2 reclassification applied)** | `modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/compliance-matrix.yaml` (67 rows) defines the full row contract (`keyword`, `vocabularyUri`, `classification`, `profiles`, `status`, `acceptedSchemaDomain`, `sourceEvidence`, `parserEvidence`, `irEvidence`, `runtimeEvidence`, `jstsGroups`, `notes`) and the Wave-0 status vocabulary. Applying the GS1/GS2 runtime-only rule (`supported` requires real runtime evidence — a JSTS required-vocabulary group passing or a compiled raw-instance accept/reject proof), the matrix now marks only 7 keywords `supported` — `allOf`/`anyOf`/`oneOf`/`type`/`enum`/`multipleOf`/`discriminator` — each citing real compiled Phase-2 `runtimeEvidence` from `semantic-results.tsv`. The 15 keywords whose validators are emitted (source+IR) but have no runtime proof — `$ref`, `properties`, `items`, `const`, `maximum`, `exclusiveMaximum`, `minimum`, `exclusiveMinimum`, `maxLength`, `minLength`, `pattern`, `maxItems`, `minItems`, `uniqueItems`, `required` — were downgraded from `supported` to `deferred` (runtime-unproven; they do not refuse generation, so not `fail-closed`). It is explicitly a Wave-0 matrix that does **not** claim G-full-schema; EXACT-MATH / UNICODE caveats are called out per row. README table generation from the matrix is not yet wired (plan §5 item 8). |
| **Parser-blockers appendix** | **Landed (exhaustive; GS8)** | Plan §11 (`docs/cpp-boost-beast-client-parser-blockers.md`) is a living, exhaustive table. **Wave-0 refresh lands it**: all **63** §3 dialect-manifest keywords have a row (Core 11, Applicator 15, Unevaluated 2, Validation 20, Meta-Data 7, Format-Annotation 1, Content 3, OAS base 4) with source/parser/IR/runtime evidence. No `**Blocker**` row is attached to any matrix-`supported` keyword (the 7 `Blocker` rows — `$id`, `$defs`, `$anchor`, `$dynamicAnchor`, `$dynamicRef`, `patternProperties`, `contentSchema` — are all Wave 1/3/4 keywords not claimed supported). Supported rows carry Phase-2 runtime evidence where it exists; remaining rows are honestly marked 'Wave-0 subset / not run'. No TBD/blank entries. |
| **CI job `cpp-boost-beast-oas31-conformance`** | **Landed (scaffold, inventory-only)** | `.github/workflows/cpp-boost-beast-oas31-conformance.yaml` now exists: nightly + `workflow_dispatch` + push on `plan/cpp-boost-beast-*`. It builds the codegen tests, syntax-checks `gate-a.sh`, runs Gate A **inventory-only**, YAML-parses `compliance-matrix.yaml` + `jsts-exclusions.yaml`, and installs the Boost toolchain. The compiled Phase-2 runner + JSTS (GS2) steps are **not yet wired** (an explicit TODO placement-marker step makes no conformance claim).

### Status legend

| Status | Meaning |
| --- | --- |
| **Foundation** | Core mechanism/contract established and acknowledged as the base for this wave; may still carry acknowledged baseline gaps to close. |
| **Scaffold** | Directory/contract/skeleton exists with the shape in place, but no live run / no real evidence produced (e.g., placeholder SHA, no runner). |
| **Started** | Substantial in-progress artifact exists (e.g., populated matrix / appendix outline) but its generating/verification pipeline is not complete. |
| **Blocked** | Cannot progress in the current environment (missing Boost/Beast, no root); requires a Boost-equipped CI surface. *(This no longer applies to the raw-instance runner: Boost/Beast is installed on this host, so the Phase-2 runner compiles and runs locally. It may still describe items whose external fixtures / M corpus / CI are not present.)* |

---

## 4. Harness reality (Gate A)

- `modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/oas-compliance/gate-a.sh` is the Gate A harness. It builds the generator CLI jar, generates from the composition fixtures (`fixtures.yaml`, `fixtures-negative.yaml`), runs the **type inventory** against `expected-types.yaml`, runs the **negative-fixture** tests, and classifies the **semantic-cases.yaml** outcome buckets.
- The semantic step first records the honest **baseline** `DEFERRED` count (every runtime-decode row without compiled evidence). Then, when a Boost toolchain is detected, `gate-a.sh` builds and runs the **Phase-2 compiled raw-instance runner** (`phase2_runner.cpp` under `-Werror`, reusing the generated `validate_*_branch_N` / `fromJsonValue_*` raw-instance validators), and the semantic classifier consumes the resulting `semantic-resolved.tsv` to replace the `DEFERRED` classification with real accept/reject evidence. When Boost is absent the runner is skipped and rows stay honestly `DEFERRED`.
- On this host the runner resolved **19** DEFERRED rows to real accept/reject (DEFERRED **38 → 19**). A subset of rows remain `DEFERRED` by design (external spec files, typed `round_trip` M-corpus semantics, response-dispatch/outbound-client cases, wire-level multipart encoding C-08). These are counted and surfaced by `gate-a.sh`; they are never silently passed.
- **Count reconciliation (do not confuse the two "deferred" meanings).** `semantic-results.tsv` holds **all 38** semantic cases: its `expected` column records the intended outcome class of every row (20 `decode_accept` + 10 `decode_reject` + 1 `generation_failure` + 7 `round_trip` = 38), while its `result` column records what is actually proven so far — **19 `PASS` / 19 `DEFERRED`**. `semantic-resolved.tsv` is the smaller Phase-2 evidence file (19 rows, all `PASS`) that backs the 19 resolved rows. Separately, the **compliance matrix** has its own status `deferred` (24 rows): that counts *keywords* whose validator is emitted but lacks runtime proof (GS1/GS2), which is a different axis from the *semantic-case* count of 19. Neither meaning implies the other; neither is a pass.
- GS4's 'zero `DEFERRED` semantic rows' requirement is therefore **not yet fully satisfied**; the local run proves the runner machinery and the S-V accept/reject semantics for the rows it covers, but the remainder (+ the JSTS runtime of GS2) still require the spec fixtures / M corpus / Wave-1+ work or a Boost-equipped CI to close.

---

## 5. Verification constraints

**The local environment can now verify a meaningful subset of the Wave-0 schema-validity claim, but not the full plan.**

- **Boost/Beast + Boost.JSON are installed** (Homebrew, `/opt/homebrew/include/boost`, Boost 1.90), so the **compiled raw-instance Phase-2 runner** compiles and runs **locally** under `-Werror`. `g++`, `clang++`, and `cmake` are present. The local 'no Boost' claim of earlier record is **stale** and is corrected here.
- On this host a live `gate-a.sh` run resolves **19** of 38 DEFERRED semantic rows to real accept/reject (17 via the generated raw-instance validators + 2 via a hand-written `type: number` check); **19 rows remain DEFERRED** and are honestly surfaced.
- What still cannot be proven locally: the **19 remaining DEFERRED rows** (external spec fixtures, typed `round_trip` M semantics, response-dispatch/outbound-client precedence, wire-level multipart encoding C-08) and the **JSTS runtime (GS2)**. These require those fixtures / the M corpus / Wave-1+ work, or a Boost-equipped CI to close GS4's 'zero DEFERRED' and GS2.
- The generated `-Werror` runner is the authoritative Boost-path evidence source; CI can call the same `gate-a.sh` Phase-2 step for a portable re-run.

### Exact verifying commands

Java-side / inventory steps that **can** be run locally:

```bash
# 1. Build the generator CLI jar (Gate A does this automatically when stale).
cd /workspace
./mvnw -pl modules/openapi-generator-cli -am compile package -DskipTests -Dmaven.test.skip=true

# 2. Full Gate A run (build + generate + inventory + negative fixtures + semantic classification).
modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/oas-compliance/gate-a.sh

# 2a. Re-run inventory only (requires existing generated output).
modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/oas-compliance/gate-a.sh inventory

# 2b. Skip a stale rebuild.
modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/oas-compliance/gate-a.sh --skip-build
```

Boost-gated steps that must run (local host Boost at `/opt/homebrew/include`; portable re-run on a Boost-equipped CI):

```bash
# 3. Phase-2 raw-validator project — generated by Gate A automatically, then
#    compiled under -Werror against the GENERATED model axis + Boost:
#    g++ -std=c++17 -Wall -Wextra -Werror -I/opt/homebrew/include
#        -I<generated> -I<generated>/model
#        <compliance>/phase2_runner.cpp <compliance>/boost_json_src.cpp
#        <generated>/model/<ComposedSchema>.cpp... -o phase2_runner
#    (boost_json_src.cpp = #include <boost/json/src.hpp> for portable header-only
#     definition compilation; no libboost_json linkage required)

# 4. Run the compiled runner over the semantic-cases.yaml raw-instance rows:
#      <compliance>/phase2-build/phase2_runner <compliance>/semantic-resolved.tsv
#    (gate-a.sh does steps 3+4 automatically when Boost is detected).
#    Expected/observed locally: 19 DEFERRED rows resolved to real accept/reject.
#    GS4 'zero DEFERRED' is NOT yet met: 19 rows remain DEFERRED (see §4).
#    oas31-corpus fixture verdicts (GS3 markers) are separate from these rows.

# 5. (On the same Boost host or CI) JSTS runtime (GS2):
#    suiteCommit in
#    modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/oas31-jsts/jsts-exclusions.yaml
#    is already a real 40-hex SHA (fb7372e8763a1417bddc65fa4c911b3e79b57b65).
#    The direct (L2/L5) and OAS-wrapped (L6) suite runners are in oas31-jsts/tools/.
#    Wave-0 baseline: 10 files / 388 cases -> PASS 40 / FAIL 32 / BLOCKED 316
#    (recorded in oas31-jsts/README.md + runner-issues.md). GS2 100% execution+
#    pass is NOT met. Required-vocabulary exclusions remain zero.
```

> A local run now **does** produce real compiled raw-instance accept/reject evidence.
> It still does **not** constitute a full **schema-validity** green light: 19 DEFERRED
> semantic rows and the JSTS suite (GS2) remain unproven, and `G-full-schema` is not
> claimed by this document.

---

## 6. Current bottom line

- **Claims made:** Wave-0 foundation work exists as documented in §3; the
  generator is heading toward **G-honest** (zero silent ignore) as its minimum
  Wave-0 bar.
- **Reclassification (GS1/GS2 runtime-only rule):** `supported` is granted
  **only** to keywords with real runtime evidence (a JSTS required-vocabulary
  group passing, or a compiled raw-instance accept/reject proof from
  `oas-compliance/semantic-results.tsv`). Source/parser/IR evidence alone is
  insufficient. The matrix therefore lists exactly **7** `supported` keywords
  (`allOf`, `anyOf`, `oneOf`, `type`, `enum`, `multipleOf`, `discriminator`),
  each runtime-proven. The remaining emitted-but-unproven keywords
  (`$ref`, `properties`, `items`, `const`, `maximum`, `exclusiveMaximum`,
  `minimum`, `exclusiveMinimum`, `maxLength`, `minLength`, `pattern`, `maxItems`,
  `minItems`, `uniqueItems`, `required`) are **deferred** — not claimed
  `supported` — until a JSTS group or raw-instance proof lands. They are not
  `fail-closed` because the generator does not refuse generation for them.
- **Claims NOT made:** `G-full-schema` (S-V + S-A complete) and
  `G-outbound-json-client` are **not** claimed. Profile-defining gates
  (GS1–GS8, GA1–GA3, GM1–GM3, GC1–GC5) are not satisfied.
- **Status here:** the compiled raw-instance Phase-2 runner (K-18 / GS4) is **wired and verified locally** with Boost (`/opt/homebrew/include`), resolving 19 DEFERRED semantic rows to real accept/reject under `-Werror`; GS4 'zero DEFERRED' is **not yet fully met** (19 rows remain DEFERRED: external specs / typed round-trip M / response-dispatch / wire C-08). The JSTS suite (GS2) is **pinned at a real SHA** (`fb7372e8763a1417bddc65fa4c911b3e79b57b65`) with a recorded Wave-0 baseline (PASS 40 / FAIL 32 / BLOCKED 316 of 388 run) — **GS2 is not met** and must still be proven on a Boost-equipped CI. Boost is present on this host, and the parser-blockers appendix + CI job have landed (GS8/§7 items are in place); what remains unproven is GS4's remainder and GS2.
- **Deferred to Wave 1:** wiring of the structural-normative + dialect-gate
  helpers into the generation pipeline (for test-suite compatibility), the
  Wave-1 architecture ADR, `SchemaResourceRegistry`, exact-number layer, and full
  raw-instance validator.

*Cross-reference:* this document is the Wave-0 conformance companion to
`CPP_BOOST_BEAST_OPENAPI_31_FULL_COMPLIANCE_PLAN.md`. Any claim stronger than
those stated in §2 must be backed by the plan's gate evidence or this record is
stale.
