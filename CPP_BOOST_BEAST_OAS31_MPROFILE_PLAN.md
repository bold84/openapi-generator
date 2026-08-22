# cpp-boost-beast-client — M Profile (typed C++ mapping) + G-outbound-json-client Claim Plan

## Document status

Follow-on to `CPP_BOOST_BEAST_OPENAPI_31_FULL_COMPLIANCE_PLAN.md` (Waves 0–6
complete; G-full-schema claimed; C profile complete). This plan closes the
only unclaimed profile — **M (typed C++ mapping), gates GM1–GM3** — and
then claims **G-outbound-json-client (S-V + S-A + M + C)** with maintainer-
oriented packaging (standard-surface tests + docs, thin harness additions,
CI wiring). Slices are committed with evidence reports exactly like the
parent plan; no fake passes.

---

## 1. Objective

Deliver, with direct current-state evidence:

1. **GM1** — a mapping corpus that operationally distinguishes the five
   outcome classes for every declared destination domain.
2. **GM2** — decode→encode round trips compare exact JSON mathematical
   values; destination range/precision failures surface as
   **representation diagnostics, never schema-invalid results**.
3. **GM3** — a public typed-mapping contract document covering every C++
   destination domain, optional/null/presence, enum/open-value, default,
   and raw fallback.
4. **FeatureSet accuracy** — `DataTypeFeature` reflects the delivered
   typed mapping (currently only `AnyType`/`Null` are included; the enum
   offers Int32/Int64/Float/Double/Decimal/String/Boolean/Enum/Map/Array/
   Binary/Date/DateTime/Uuid/Object/Null/AnyType/Maps/…).
5. **G-outbound-json-client claimed** in the conformance report + CI, with
   the claim re-verifiable on the agreed CI surface.
6. **Maintainer packaging** — the M content lands as JVM contract tests +
   docs + one thin compiled gate (the established wire-gate pattern), not
   as report-only ceremony.

### Success gates

| Gate | Requirement |
| --- | --- |
| **GM1** | Corpus rows for ALL five classes across every destination domain; every row's classification is operationally defined and executable by the M driver; no class left empty; corpus self-checks (schema refs resolve against in-repo fixtures; instance text parses). |
| **GM2** | M driver run: 100% rows classified exactly as the corpus expects (PASS == total, FAIL == 0). Unrepresentable rows emit representation diagnostics; schema-invalid rows emit validation failures; the two are distinguishable in the driver's output taxonomy. Round-trip rows: re-encoded JSON equals input exactly (mathematical value, not text). |
| **GM3** | `docs/cpp-boost-beast-client-typed-mapping.md` documents every destination domain + the five-class error taxonomy + how each class surfaces in C++ (exception type / status), with every documented example executable in the M gate. |
| **M-audit** | FeatureSet `DataTypeFeature` includes exactly the domains with corpus + driver evidence; JVM test asserts the set. |
| **M-CI** | Conformance workflow gains the M gate step; full battery (JSTS 1299, JVM, wire 69, Gate A 191/0/0, annotation) re-runs green at each slice HEAD. |
| **M-claim** | Conformance doc: M row COMPLETE, G-outbound-json-client CLAIMED, final status refreshed; migration guide links the typed-mapping contract. |

---

## 2. Current state inventory (grounded)

### Already real (do not redo)

- S-V validator (1299/1299 JSTS, GS4 191/0/0), S-A annotations (GA1–GA3),
  C profile (69 wire cells incl. real-loopback mock HTTP).
- **Round-trip mode in the Phase-2 runner** (`phase2_runner.cpp`
  `round_trip`: decode via generated `fromJsonValue_X` → `toJsonValue()`
  → exact `boost::json::value ==`) — currently proven for exactly
  TriStateContainer + NullableObjectRoot (7 rows).
- Emitted model axis: `fromJsonValue_X` (by value, throws on validation
  failure), `toJsonValue() const`, `NullableField<T>` tri-state,
  `boost::optional`, open-value enums, `AnyType` raw model, variant
  models for oneOf/anyOf, `JsonValueConverter` (generic + `nullptr_t` +
  `optional` + `shared_ptr<Model>` specializations; primitive decode =
  `boost::json::value_to<T>` — checked for integral targets per Boost.JSON,
  narrowing for floating targets — **to be proven empirically**).
- Harness patterns: `phase2_*` compiled drivers under `-Werror`; wire-gate
  python generators (`jsts_param_wire.py`); gate-evidence index mechanism;
  JVM suite (115 tests); conformance CI workflow (12 steps).
- Docs: conformance report (final §7), migration guide, parser-blockers.

### Missing (this plan)

1. Operational five-class taxonomy pinned to this generator's behavior
   (esp. Boost.JSON number boundaries: >uint64 integers, 1e400-style
   decimals, double narrowing, `value_to<int32_t>` overflow behavior).
2. M corpus (`oas31-jsts/m-corpus.yaml`) + M driver + M gate
   (`tools/jsts_m_gate.py` pattern).
3. Representability diagnostics where the emitted converter today would
   silently narrow or overflow (or proof that Boost.JSON already throws —
   then the corpus documents it).
4. `DataTypeFeature` accuracy + FeatureSet JVM assertion.
5. GM3 contract doc + migration-guide linkage.
6. CI M step + conformance claim flip + final evidence.

---

## 3. Wave M1 — taxonomy probe + corpus design (GM1 foundation)

### 3.1 Operational taxonomy (probe first)

Probe spec + tiny compiled driver pinning, for every destination domain:
- `int32`/`int64` decode of values 2^31-1/2^31/2^63-1/2^63/2^64/2^70
  (− and the negative edges): does `value_to<int32_t>` throw
  (representable-diagnostic) or truncate (silent bug → M2 fix)?
- `float`/`double` of 1e400, 1e308±eps, 20-digit decimals: inf/narrowing —
  silent or diagnostic?
- `uint64` (if emitted) edges; `string`/`bool`/`null` trivial domains.
- `enum` open-value policy: unknown string → raw fallback or throw.
- `AnyType` raw: any JSON document round-trips via `boost::json::value`.
- Objects/arrays/maps/variants: deep round-trip + deeply-nested bounds.
- `NullableField`: null vs missing (already proven); `optional` inside
  arrays/maps.
- boost::json parser boundaries: `2^70` integer literal (transport parse
  vs value kinds), `1e400`, negative zero, `-0`, duplicate keys.

Output: `m1-taxonomy-probe` workdir + report rows (`PROBE|<domain>|<edge>
|<observed>`); the observed behaviors become the corpus's operational
definitions. **Fail-closed rule:** any silent truncation/narrowing observed
becomes a Wave-M2 converter fix, not a corpus "expected" row.

### 3.2 Corpus design

`oas31-jsts/m-corpus.yaml`, rows `(id, schema-ref, instance, expectedClass)`:

- Classes: `schemaInvalid` | `representable` | `unrepresentable` |
  `transportParseError` | `typedDecodeError`.
- Domains: int32, int64, uint64 (if emitted), float, double, decimal
  (string-typed if that's the policy), string, boolean, null, enum
  (string+int32), optional, nullable tri-state, object, array, map,
  anyOf/oneOf variant, AnyType raw, date-time/binary (as emitted).
- Target ≈ 120–160 rows: every domain ≥ 1 representable + ≥ 1
  schemaInvalid + the class-relevant edges (e.g. int32 overflow rows
  where expected = `unrepresentable` iff the probe showed a
  representation diagnostic; otherwise the row is a **M2 fix marker**).
- Schemas: reuse an `m-corpus.yaml` embedded `components.schemas` +
  $refs from the in-repo fixture specs where names collide — schema
  references must resolve during M2 generation (self-check: python walks
  `$ref` chains at corpus-load time).

### 3.3 Acceptance (M1)

- Probe report committed with observed rows for every edge above.
- Corpus parses; refs resolve; every class ≥ 5 rows; no domain left
  without representable + schemaInvalid coverage; expected classes
  consistent with probe observations (or carry accepted-fix markers).

---

## 4. Wave M2 — M driver + diagnostics + round-trips (GM1 run + GM2)

### 4.1 Driver

`oas-compliance/m_driver.cpp` + `phase2_m_gen.py` (reuse the
`phase2_gen_cases.py` emission pattern):

1. parse instance text → `transportParseError` on failure;
2. raw schema validity via the GENERATED validators →
   `schemaInvalid` on failure;
3. **representability gate**: destination-range/precision check (see
   4.2) → `unrepresentable` (representation diagnostic recorded);
4. typed decode via `fromJsonValue_X` → `typedDecodeError` on throw that
   is NOT a validation failure (mechanism-level);
5. `representable` → re-encode via `toJsonValue` → exact compare
   (`boost::json::value ==`); mismatch = FAIL (GM2 violation).

Rows keyed by corpus id; output `m-resolved.tsv`
(`id\tCLASS\texpected\tPASS|FAIL`) — same contract shape as
`semantic-resolved.tsv`.

### 4.2 Representability gate design

- **int32/int64/uint64 (if emitted)**: bounded check BEFORE decode
  (value fits the destination exactly: integer kind + range), or rely on
  a throwing `number_cast` — pinned by the M1 probe; silent-narrowing
  paths get a converter fix in this wave (emitted `JsonValueConverter`
  specializations: checked arithmetic conversion that throws a
  representation error distinct from validation).
- **float/double**: precision-loss policy — define operationally
  (e.g., finite target: `std::isfinite` after conversion + documented
  rounding for representable decimals; non-finite or >15–17 significant
  digits → representation diagnostic per the policy chosen in M1; the
  corpus encodes the chosen policy).
- **enum open-value**: unknown value → raw fallback (documented) vs
  diagnostic — pinned in M1; row classes match.
- Error taxonomy in the driver surface:
  `ValidationError` / `RepresentationError` / `TypedDecodeError`
  distinguishable by type + message prefix (`[schema]` / `[representation]`
  / `[decode]`).

### 4.3 Round-trip extension

- Extend the GM2 evidence from 2 schemas to every corpus
  representable row (no schema-name hardcoding: the generator emits a
  per-schema dispatch table include like `phase2_cases.inc`).
- Exactness = mathematical value equality (`boost::json::value ==`
  already proven; corpus adds object/array/map/variant/AnyType deep rows).

### 4.4 Acceptance (M2)

- M driver compiles `-Wall -Wextra -Werror`, links the generated model
  axis; run: **PASS == total rows, FAIL == 0**; classification outputs
  match the corpus exactly; every `unrepresentable` row carries the
  `[representation]` diagnostic (never `[schema]`); every round-trip row
  is exact.
- Converter fixes (if any) are covered by the corpus rows that exposed
  them + JVM shape assertions (Wave M3).

---

## 5. Wave M3 — JVM contract tests + GM3 documentation (maintainer packaging)

### 5.1 JVM tests (generation-level contract, standard suite)

`CppBoostBeastClientCodegenTest` additions (fixture specs + assertions):

- **Destination domains**: `format: int32` → 32-bit C++ type, int64 →
  64-bit, float/double, string, boolean, binary, date-time (as emitted);
  assert the emitted model member types for each format.
- **Enumerable**: string/int32 enums → open-value policy visible in
  emission (raw fallback member / converter policy).
- **Tri-state**: nullable property emission (`NullableField`), optional
  (`boost::optional`), required, AnyType raw.
- **Representability surface**: after any M2 converter fix, assert the
  emitted converter specializations (checked int conversion, finite
  float policy) exist for int32/int64/float/double.
- **FeatureSet**: `getDataTypeFeatures()` contains exactly the corpus-
  proven domains (Int32/Int64/Float/Double/String/Boolean/Enum/Map/Array/
  Object/Null/AnyType/… as evidenced) — the M-audit gate.

Each test defends an observable emission contract that a template
regression would break.

### 5.2 GM3 contract doc

`docs/cpp-boost-beast-client-typed-mapping.md`:

- Table of destination domains → C++ type → exact JSON-domain mapping;
- five-class error taxonomy + how each surfaces in the public API
  (validation throw vs representation throw vs value fallback), with
  code examples (each example also present as M-corpus rows — doc and
  gate cross-check);
- optional/null/presence semantics (tri-state, missing-vs-null),
  enum/open-value, default-value policy, raw fallback (`AnyType`),
  variant/union semantics, map/array key rules;
- known limits (e.g., decimal policy, float significant-digit policy)
  stated explicitly — no silent assumptions.
- Migration guide: add a "typed mapping contract" section linking the
  doc.

### 5.3 Acceptance (M3)

- JVM suite green (115 + new tests); FeatureSet assertion passes.
- Doc cross-checked: every documented example string appears in the M
  corpus (script assert); doc compiles' worth of claims = corpus rows.

---

## 6. Wave M4 — CI wiring + FeatureSet + claim (M-CI gate)

1. **Workflow**: conformance workflow gains the M gate step
   (python `tools/jsts_m_gate.py` — generate m-corpus spec → compile M
   driver `-Werror` → run → assert `PASS==total`; env knob for the
   OpenSSL/Boost paths like the wire gate); artifacts += `m-report.txt`.
2. **Sample/sample-path**: the hardening compile step gains one M-fixture
   compile.
3. **FeatureSet**: codegen `modifyFeatureSet` `includeDataTypeFeatures`
   per the M-audit evidence (only corpus-proven domains).
4. **Conformance doc**: M row COMPLETE (GM1 corpus rows / GM2 PASS==total
   / GM3 doc), G-outbound-json-client **CLAIMED**; final status refresh;
   parent plan "Document status" note.
5. **Full battery at HEAD**: JSTS 1299/1299, JVM (115+n), wire 69 cells,
   Gate A 191/0/0, GA1 36, M gate PASS==total; workflow YAML validated.

---

## 7. Wave M5 — evidence + PR packaging

- Slice reports: `oas31-jsts/m1-taxonomy-slice.md`, `m2-driver-slice.md`,
  `m3-contract-slice.md`, `m4-claim-slice.md` (same conventions as the
  parent: commit + report + reproduce at HEAD).
- Final numbers table; scratch dirs (`m1*`, `m2*`) untracked; stash@{0}
  untouched.
- PR-facing summary: what a maintainer runs (JVM suite, M gate command,
  conformance workflow) + where the representability contract is
  documented.

---

## 8. Risk register

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Boost.JSON `value_to<int32_t>` overflow behavior differs from assumption (throws vs truncates) | Corpus taxonomy wrong / M2 scope grows | M1 probe FIRST; behavior becomes the corpus definition; silent-truncation = converter fix |
| `double` narrowing (1e400 → inf) is silent | GM2 unrepresentable rows impossible without a fix | M2 gate adds pre-decode `isfinite`/precision policy + converter specialization; corpus encodes policy |
| `float` significant-digit policy is subjective | Corpus/documentation drift | Policy pinned in M1 report + GM3 doc; corpus rows follow it exactly |
| "typedDecodeError" and "schemaInvalid" blur (numeric equality 1.0 vs int32) | Classification noise | Corpus uses unambiguous instances; driver taxonomy asserts message prefixes |
| FeatureSet enum cannot express some mappings (e.g., decimal-as-string policy) | M-audit partial | Document the mapping as far as the model allows; assert only what is expressible |
| Custom CI workflow doesn't run on upstream main after merge | Evidence stops re-verifying | Wire the M gate into the branch workflow (runs nightly + on plan branches); note upstream wiring as out-of-repo decision |
| Corpus/driver growth slows full-battery runs | CI time | Keep corpus ≈120–160 rows; driver O(rows) compile once per run |
| Converter change regresses 3.0 petstore sample | Merge risk | Sample regen + hardening compile in CI step; full battery before each commit |

---

## 9. Slice/commit conventions (inherit from the parent plan)

- Evidence discipline: every slice executed from committed HEAD + committed
  with its report; scratch dirs never committed; `stash@{0}` preserved.
- Runner idiom: compiled drivers via `g++ -std=c++17 -Wall -Wextra -Werror`
  with generated model axis + `boost_json_src.cpp`; python gates via
  `python3 tools/jsts_*.py`.
- No fake passes: a class/variant only counts when the driver executed
  real generated code against it; the gate-evidence index mechanism
  (already in the repo) verifies file+marker before any reference
  classification.
- Commit messages: `Wave-M<n> slice: <summary> — <evidence numbers>`.

---

## 10. DoD checklist (before the claim lands)

- [ ] M1 taxonomy probe report committed; corpus (≈120–160 rows) with all
      five classes + all domains; refs self-check.
- [ ] M2 driver + gate: PASS == total, FAIL == 0; `[representation]` vs
      `[schema]` vs `[decode]` distinguishable; round-trips exact;
      silent-narrowing converter paths fixed (if probe found any).
- [ ] M3 JVM contract tests green (incl. FeatureSet `DataTypeFeature`
      assertion); GM3 doc committed with corpus cross-check.
- [ ] M4 CI M step + full battery green at HEAD; workflow validated.
- [ ] M5 reports committed; conformance: M COMPLETE, G-outbound-json-client
      CLAIMED; parent plan status note updated.
- [ ] Final verify run of the complete claim stack at the last slice HEAD.