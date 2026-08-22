# Wave-M4/M5 slice: CI M gate + typed-mapping claim (G-outbound-json-client)

Slice commit: `ef19431a4f0`.

## What landed

1. **CI M gate step** (`.github/workflows/cpp-boost-beast-oas31-conformance.yaml`,
   now 13 steps): generates the m-profile spec → compiles the M driver
   `-Werror` (Boost + the 12 model TUs) → runs the 50-row five-class corpus
   → asserts `__M_PASS__==50` via the tail line; log uploaded as an
   artifact. Failures fail the job.
2. **FeatureSet / M-audit**: `DataTypeFeature` now declares exactly the
   corpus-proven domains (Int32/Int64/Float/Double/String/Boolean/Enum/
   Array/Maps/Object/Null/AnyType) with `Decimal` excluded (no decimal
   destination); locked by the JVM test
   (`mProfileDestinationDomainsAndFeatureSet`, 116/116 suite).
3. **Conformance claim flip**: the §7 final status records
   **M COMPLETE (GM1–GM3)** and **G-outbound-json-client CLAIMED**
   (S-V + S-A + M + C); the §1 M row updated; the plugin migration guide
   links the typed-mapping contract.
4. **GM3 contract doc**: `docs/cpp-boost-beast-client-typed-mapping.md`
   (destination-domain table, five-class taxonomy, float precision policy,
   non-finite diagnostics, big-number boundary, tri-state, enum/open-value,
   default policy) — every claim = a corpus row.

## Evidence (full battery at HEAD)

```
M gate:         50 rows, 5 classes (25 representable / 10 unrepresentable /
                10 schemaInvalid / 3 transport / 2 narrowed) — 50 PASS, 0 FAIL
JSTS corpus:    46 files / 1299 PASS / 0 FAIL / 0 BLOCKED
Gate A:         191 PASS / 0 FAIL / 0 DEFERRED (GS4 zero-deferred)
Wire gate:      param 19 + server 6 + security 11 + content 21 + ref 5 +
                mock 7 (69 cells PASS) + annotation GA1 PASS (36)
JVM suite:      116/116 (incl. the M destination-domain + FeatureSet tests)
Sample:         petstore regen committed with the new converter emission;
                rebuild produces zero drift
```

## Gate status

- GM1: corpus with all five classes, self-checked (schema-validity refs,
  parse-expectations, duplicate ids) — PASS.
- GM2: 50/50; representation failures classified `unrepresentable` (never
  `schemaInvalid`): int range "not exact" (integral literals), float/double
  "non-finite destination" — the F3 silent-inf gap fixed in the emitted
  converter; float narrowing idempotent; exact round trips via
  `deepJsonValueEqual` (1 == 1.0).
- GM3: typed-mapping contract doc with doc↔corpus cross-check.
- M-audit: FeatureSet DataTypeFeature = corpus-proven domains;
  Decimal excluded; JVM-locked.
- M-CI: workflow M gate step + artifact; nightly + branch-push re-verify.
- M-claim: G-outbound-json-client CLAIMED in the conformance report.

## Files

- `.github/workflows/cpp-boost-beast-oas31-conformance.yaml`
- `docs/cpp-boost-beast-client-oas31-conformance.md`,
  `docs/cpp-boost-beast-client-oas31-migration.md` (+ typed-mapping doc)
- Wave-M1..M3 commits: `380d2a0ea9f` (taxonomy probe), `9c7dc14a018`
  (corpus + driver + F3 fix), `f792dfac5ff` (GM3 + JVM tests)