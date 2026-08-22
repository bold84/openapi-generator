# Gap-2 slice: matrix truthfulness — re-measurement + re-adjudication

Slice of `CPP_BOOST_BEAST_OAS31_GAP_CLOSURE_PLAN.md` (Gap 2). Executed from
committed HEAD `4d0932098fd` (branch `plan/cpp-boost-beast-oas31-full`).

## What was done

1. **Re-measured the full pinned JSTS corpus at HEAD** with the distributed
   runner (`tools/jsts_genpath_slice.py`, batch mode, `--workers 6`, vendored
   suite at `vendor/`, pinned SHA `fb7372e8763a1417bddc65fa4c911b3e79b57b65`,
   CLI jar built from HEAD). No runner or generator changes were made in this
   slice — the measurement is the pre-existing product at HEAD.

2. **Re-adjudicated every formerly-`deferred` matrix row** against the
   committed slice evidence (wave-3/4/5/6 + M slice reports cited per row) and
   the HEAD re-measurement. Statuses now match committed runtime evidence.

3. **Updated the parser-capability appendix**
   (`docs/cpp-boost-beast-client-parser-blockers.md`) to match the matrix:
   the eight promoted rows were still marked `Workaround`/`**Blocker**` from
   the Wave-0 snapshot; `patternProperties` and `contentSchema` still carried
   stale `**Blocker**` statuses; the roll-up still claimed "only 7 supported"
   / "GS2 not met".

## Re-measurement at HEAD (authoritative numbers)

```
files=46 cases=1299 PASS=1299 FAIL=0 BLOCKED=0   (batch; gen=OK everywhere)
report: tmp/jsts-gap2-report.json (scratch, untracked, per evidence discipline)
```

Per-file verdicts for the adjudicated rows:

| file | PASS | FAIL | BLOCKED |
|---|---|---|---|
| dynamicRef.json | 44 | 0 | 0 |
| anchor.json | 8 | 0 | 0 |
| defs.json | 2 | 0 | 0 |
| vocabulary.json | 5 | 0 | 0 |
| unevaluatedProperties.json | 129 | 0 | 0 |
| unevaluatedItems.json | 71 | 0 | 0 |
| ref.json | 79 | 0 | 0 |
| refRemote.json | 31 | 0 | 0 |
| patternProperties.json | 25 | 0 | 0 |
| (all other 37 files) | 905 | 0 | 0 |

These confirm the wave-4 slice verdicts (dynamicRef 44/0/0, anchor 8/0/0,
unevaluated* 129/71 zero-FAIL, vocabulary 5/0/0, defs 2/0/0) still hold at
HEAD — the wave-3 corpus numbers cited by the old matrix rows ("19 FAIL",
"128/129", "58/71") were superseded by wave-4 fixes and are not current.

## Matrix changes (compliance-matrix.yaml)

| Row | Before | After | Evidence basis |
|---|---|---|---|
| `$schema` | deferred | **supported** | Wave-4 dialect slice (vocabulary.json 5/0/0, defs.json 2/0/0, metaschema-optional-vocabulary) + HEAD re-measure |
| `$id` | deferred | **supported** | refRemote.json 31/0/0 + defs.json 2/0/0; string-only resource declaration finding |
| `$defs` | deferred | **supported** | defs.json 2/0/0 via vendored official 2020-12 metaschema |
| `$anchor` | deferred | **supported** | anchor.json 8/0/0 |
| `$dynamicAnchor` | deferred | **supported** | anchor.json 8/0/0 + dynamicRef.json 44/0/0 |
| `$dynamicRef` | deferred | **supported** | dynamicRef.json 44/0/0 (initial-target rule, dynamic scope) |
| `$vocabulary` | deferred | **supported** | vocabulary.json 5/0/0 (per-resource gating, vocab-inert) |
| `$comment` | deferred | **annotation** | classification: no validity/annotation action (2020-12 §8.1.2) |
| `unevaluatedProperties` | deferred | **supported** | unevaluatedProperties.json 129/0/0 |
| `unevaluatedItems` | deferred | **supported** | unevaluatedItems.json 71/0/0 |
| `contentSchema` | annotation | annotation (unchanged) | already reclassified in the session edit (GA1 annotation gate) |
| `patternProperties` | supported | supported (irEvidence filled) | 25/0/0; the row was supported with empty irEvidence — invariant now passes |

Final matrix state: **68 rows, 40 supported / 16 annotation / 12 fail-closed /
0 deferred**.

## Invariant step

Ran the exact checker scripted in
`.github/workflows/cpp-boost-beast-oas31-conformance.yaml`
("Validate compliance matrix + JSTS ledger invariants") against the edited
files:

```
OK: 68 matrix rows + JSTS ledger pass all invariants
(40 supported rows all-with-evidence).
```

No appendix `**Blocker**` row is attached to any supported matrix keyword;
ledger empty with real 40-hex suiteCommit; all required-vocabulary keywords
present; duplicate-keyword and contract-field checks pass.

## Honesty notes

- No row claims `supported` without non-empty source/parser/IR/runtime
  evidence (enforced by the invariant step, not asserted by hand).
- The wave-3 numbers quoted by the old rows were truthful *at wave-3 HEAD*;
  they are superseded by the committed wave-4 slice reports and re-verified
  here at the current HEAD. Nothing was "promoted by belief".
- `patternProperties` was already `supported` at HEAD with runtimeEvidence but
  an empty irEvidence; the invariant would have failed on it at HEAD — fixed
  in this slice (evidence was in the wave-2.5 slice report; cited now).
- Scratch (w4*/w5* dirs, tmp/jsts-gap2-report.json) remains untracked per
  evidence discipline; stash@{0} untouched.