# JSON Schema Test Suite (JSTS) Pin — cpp-boost-beast-client

This directory pins the **official JSON Schema Test Suite** for the
`cpp-boost-beast-client` 3.1 conformance program, driving **GS2** of
`CPP_BOOST_BEAST_OPENAPI_31_FULL_COMPLIANCE_PLAN.md`:

> JSON Schema Test Suite at a pinned commit SHA: **100%** of applicable
> required-vocabulary tests execute and pass, with zero exclusions, skips, or
> unresolved harness defects. `jsts-exclusions.yaml` may identify only
> demonstrably inapplicable optional profiles. Harness defects are tracked
> separately and block GS2. Register remotes; record discovered
> files/cases/tests/skips/failures/crashes; self-test discovery.

The suite is the canonical shared fixture set used to validate JSON Schema
draft dialect behaviour. We pin it **by commit SHA** (never by branch/tag) so
every run is exactly reproducible, and we run it against the **2020-12** branch,
which is the dialect the pinned OAS 3.1 schema dialect (`/base`, alias of
`https://spec.openapis.org/oas/3.1/dialect/2024-11-10`) resolves to
(JSON Schema Draft 2020-12).

## Provenance & pinned revision

- Source repository: `https://github.com/json-schema-org/JSON-Schema-Test-Suite`
- Dialect corpus: **Draft 2020-12** under `tests/draft2020-12/` (the dialect the
  pinned OAS 3.1 schema dialect `/base`, alias of
  `https://spec.openapis.org/oas/3.1/dialect/2024-11-10`, resolves to).
- Recorded commit SHA: see `suiteCommit` in `jsts-exclusions.yaml`
  (`fb7372e8763a1417bddc65fa4c911b3e79b57b65`, recorded 2026-08-03 via
  `git ls-remote`). It is a real 40-hex SHA and is the byte-for-byte source of
  truth for vendoring.

> **Branch-name note (Wave-0 finding, 2026-08-03).** The upstream repo no
> longer has a branch literally named `2020-12`; the suite keeps every dialect
> under `tests/<dialect>/` on `main`. The 2020-12 corpus is
> `tests/draft2020-12/`. The recorded SHA is `main` HEAD whose tree carries that
> corpus. `tools/vendor.sh` clones `main`, checks out the pinned SHA, and copies
> `tests/draft2020-12/` + `remotes/`.

## How to vendor the suite

Reproduce the exact pinned tree from the recorded SHA:

```bash
# Reproducible vendor (reads suiteCommit from jsts-exclusions.yaml):
./tools/vendor.sh ../vendor

# Manual reproduction:
git clone https://github.com/json-schema-org/JSON-Schema-Test-Suite.git /tmp/jsts
git -C /tmp/jsts checkout fb7372e8763a1417bddc65fa4c911b3e79b57b65
git -C /tmp/jsts rev-parse HEAD      # must equal the SHA above
cp -r /tmp/jsts/tests/draft2020-12 /tmp/jsts/remotes ./vendor
```

For repeatable CI, `tools/vendor.sh` (a) clones `main`, (b) checks out the
`suiteCommit` from `jsts-exclusions.yaml`, (c) copies `tests/draft2020-12/` and
`remotes/` into the vendored location, and (d) verifies the checkout matches the
recorded SHA before proceeding. The vendored tree is a build artifact and is
regenerated from the SHA; never edit vendored files in place — treat them as
read-only fixtures.

A `vendor/` subdirectory holds the actual checked-out test files once vendored;
until then the SHA placeholder keeps the directory intentionally empty except
for this scaffold.

## Runner requirement (L2 / L5 direct + L6 OAS-wrapped)

Executing JSTS is **not** a generator-codegen test and **not** a pure-text
step — it requires the **compiled C++ raw-instance validator** so every test
case is evaluated through the real production schema-evaluation pipeline:

| Layer | Where | How JSTS is consumed |
| --- | --- | --- |
| L5 | `oas31-jsts/` (this dir) | **Direct** JSON Schema runner: for each JSTS case, schema → parser → IR → compiled validator corpus, compare accept/reject verdicts. |
| L6 | OAS-wrapped | **Adapter**: each JSTS schema is wrapped in an OAS document (Schema Object context) and pushed through the OAS entry point the generator accepts, so dialect detection and Schema-Object evaluation are honoured end to end. |

Both pathways require the **Boost C++ toolchain** (Boost with Boost.JSON and
Beast) to compile the generated C++ and run the raw-instance binaries. Boost IS
installed in this environment at `/opt/homebrew/include/boost` (Beast + JSON
present, header-only works); generated C++ compiles with
`g++ -std=c++17 -I/opt/homebrew/include` and links `-lboost_json` when needed.
(An earlier revision of this README claimed Boost was absent — that note is
stale as of 2026-08-03.)

## Runner invocation

The distributed runner `tools/jsts_runner.py` drives discovery and the L2/L5
(direct) + L6 (OAS-wrapped) pipeline. It is a **VS-verifying harness**: it wraps
JSTS schemas as Schema Objects in an OpenAPI 3.1 document, generates with the
cpp-boost-beast generator, compiles the C++ with Boost, runs a raw-instance
binary per schema group, and compares each verdict against the suite's `valid`
flag.

```bash
# 1. Vendor the pinned corpus (or point --suite at a JSTS clone):
./tools/vendor.sh ../vendor

# 2. Discovery (count files / groups / cases):
python3 tools/jsts_runner.py discover --suite ../vendor --out manifest.json

# 3. Run the production pipeline on a subset (needs the generator jar):
python3 tools/jsts_runner.py run \
    --suite <jsts-clone> \
    --jar ../modules/openapi-generator-cli/target/openapi-generator-cli.jar \
    --work ./run-work \
    --files required.json,properties.json \
    --out report.json
# omit --files to run all required-vocabulary files
```

Outcome codes: **PASS** = decode verdict equals suite `valid`; **FAIL** = decode
verdict disagrees with `valid` (a genuine required-vocabulary shortfall);
**BLOCKED** = the production path produced no evaluable model, or generation
rejected the schema, or compile failed. Anti-greenwash: anything not run is
recorded as **not run**, never as pass. Per-group reports live in the JSON
`report` and in `runner-issues.md`.

### Wave-0 baseline (2026-08-03, exact measured numbers)
| Metric | Value |
| --- | --- |
| Files run / total required-vocab | 10 / 44 |
| Cases run / total required-vocab | 388 / 1292 |
| PASS | 40 |
| FAIL | 32 |
| BLOCKED | 316 |

> **Authoritative defect ledger.** Every runner/harness defect and semantic
> shortfall that blocks GS2 is tracked in **`runner-issues.md`** (this
> directory). It is the single authoritative ledger for these items;
> `runner-issues.md` separates (A) harness/pipeline defects (zeroable by runner
> fixes) from (B) semantic shortfalls (Wave 1–4 plan work), and every row
> carries an `id` / `severity` / `status` / `owner` / `evidence`. Nothing is a
> silent or TODO entry. If a defect, FAIL, or BLOCKED case is found and not yet
> catalogued there, it must be added to `runner-issues.md` — never converted
> into an exclusion.

**GS2 is NOT met in Wave 0.** Required-vocabulary exclusions remain **zero**
(`jsts-exclusions.yaml` ledger is empty), but 100% execution+pass is blocked by
the genuine shortfalls catalogued in `runner-issues.md`. This is the honest
baseline; it is not claimed as support.

Dialect-scoped `optional/` tests, which frequently rely on format validation or
the optional annotation vocabularies, are run through their own optional
profile and use the same compiled validator.

## Registering remotes

Many JSTS cases (all `$ref`, `$dynamicRef`, `$dynamicAnchor`, recursive, and
external-reference cases) resolve against the suite's **`remotes/`** tree
(`http://localhost:1234/<path>`). The runner must register these remotes before
the corpus runs:

- The vendored `remotes/` directory is served at `http://localhost:1234/` so
  that `http://localhost:1234/<relative-path>` resolves exactly as the suite's
  official runners expect (the JSON Schema Test Suite harness and its GitHub
  Actions use the same `localhost:1234` convention).
- Registration is a **launch-time side effect** of the runner, not part of the
  fixture files: the runner starts/binds the mock endpoint on
  `http://localhost:1234`, maps the vendored `remotes/` tree to it, and tears it
  down when the run completes.
- Because the remotes map straight to the vendored tree, they are fully
  covered by the same commit-SHA pin — no mutable network state is involved.

## Offline reproducibility

- **Single source of truth is the SHA** in `jsts-exclusions.yaml`. Given `git
  clone --branch 2020-12 --single-branch` + `git checkout <suiteCommit>`, the
  vendored tree is byte-for-byte reproducible with no network access after the
  clone (the remotes are served locally at `localhost:1234`).
- All remotes are local; there is **no external network dependency** at run
  time. The only network operation during a run is the re-resolution of the
  pinned OAS dialect and 2020-12 metaschema URIs for dialect/metaschema gates,
  which are separately cached/allow-listed.
- Every run against a given SHA and runner version must yield identical
  discovered-file/case/test/skip/failure/crash tallies, which are recorded
  per-run for the conformance report and compared against the GS2 baseline.

## Acceptance rule (G-full-schema)

For the suite to count toward **GS2 / G-full-schema**, the required-vocabulary
portion of the pinned suite must run **100%** with **zero required exclusions**:

- **100%** of applicable required-vocabulary JSTS tests execute and pass.
- **Zero** entries in `jsts-exclusions.yaml` may classify a required-vocabulary
  test as excluded. Per plan §8.1/§10, `classification` for any exclusion must
  be `optional-profile` only, and "Required-vocabulary semantic JSTS exclusions
  must be zero for G-full-schema."
- Any discovered test that is skipped, fails, or crashes must be resolved as a
  harness/runner defect (tracked in **`runner-issues.md`**, a separate blocking
  runner-issues report arm's-length from the exclusion ledger), **not** silently
  converted into an exclusion. Silent ignore is strictly forbidden (G-honest:
  zero silent ignore).
- Optional-profile tests are only eligible for exclusion when they are
  demonstrably inapplicable (e.g. require a non-required annotation/format
  vocabulary that the C profile does not claim); such exclusions never count as
  support toward G-full-schema.

## Promotion criteria (plan §5 item 7)

A repository CI job named `cpp-boost-beast-oas31-conformance` (started
nightly/scheduled) can promote GS2 to green **only when all of** the following
hold against the pinned `suiteCommit` + `runnerVersion`:
1. Discovery tallies match `manifest.json` (files/groups/cases) exactly.
2. Every required-vocabulary test file executes end-to-end (generator + compile
   + run) with **zero** BLOCKED cases and **zero** FAIL cases — i.e.
   PASS == total required-vocab cases.
3. `jsts-exclusions.yaml` contains **zero** required-vocabulary exclusions
   (every entry, if any, must be `optional-profile` and still match a
   discovered test).
4. `runner-issues.md` has **no** open required-vocabulary blocker.
5. The conformance README records the exact GitHub Actions workflow compiled
   from `compliance-matrix.yaml` rows.
Until these hold, the run is reported honestly as FAIL/BLOCKED/not-run, never as
pass.

## File inventory

| File | Purpose |
| --- | --- |
| `README.md` (this file) | Pinning, vendoring, runner, remotes, acceptance, promotion. |
| `jsts-exclusions.yaml` | Pinned `suiteCommit`/`dialect`/`runnerVersion` and the bounded optional-only exclusion ledger (see §8.1). |
| `runner-issues.md` | **Blocking runner-issues report** (arm's-length from the ledger) — the **authoritative defect ledger** for Wave-0 required-vocabulary shortfalls that block GS2, split into (A) harness/pipeline defects and (B) semantic shortfalls. |
| `tools/vendor.sh` | Reproducible vendor script bound to `suiteCommit`. |
| `tools/jsts_runner.py` | L2/L5 direct + L6 OAS-wrapped runner: discover + generate/compile/run. |
| `vendor/` (created by `tools/vendor.sh`, not committed) | The checked-out 2020-12 tests + `remotes` tree. |

## Relationship to other suites

| Suite | Role |
| --- | --- |
| `../oas-compliance/` | Gate A composition inventory + semantic cases (raw-instance runner is the same binary consumed at L5). |
| `../oas31-corpus/` | OAS-wrapped 3.1 fixtures for GS3. |
| `oas31-jsts/` (this dir) | Pinned official JSON Schema Test Suite for GS2 (L2/L5 direct + L6 OAS-wrapped). |
