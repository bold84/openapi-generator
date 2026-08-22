# Wave-6 slice: hardening + code-size audit + sample regen + CI promotion

Slice commit: `c44f3043698`.

## Scope (plan §6.1/6.3/6.6)

1. Validator dedupe + code-size audit
2. Sample regen (petstore) + docs
4. FeatureSet accuracy (asserted in the 5.8/5.10 slice, `10fa1d218ea`)
6. Conformance report + CI promotion

## Hardening (6.1)

- **Dedupe scan** across the five fixed validator TUs
  (`oas31_validator.hpp` 1328 lines, `oas31_ir.hpp` 289,
  `oas31_exact_number.hpp` 251, `oas31_object_array.hpp` 235,
  `oas31_deep_equal.hpp` 93): zero cross-TU duplicate functions, zero unused
  statics, zero TODO/FIXME/HACK markers. Overload families
  (ExactNumber/compare/exactValueOf) are distinct signatures within one
  self-contained header each — no reduction.
- **Code-size audit** (emitted tree, `wave5/content-matrix.yaml`):
  - fixed attached library ≈ 2196 lines + per-spec generated IR
    (schema_ir.generated ~179 lines for the content matrix);
  - per-operation emission linear: 3 ops → api.cpp 1417 lines, 10 ops →
    1782 lines (≈ 52 lines/op marginal; no quadratic growth);
  - golden binaries 1.26–1.93 MB (recording-client drivers); the mock
    driver 36.8 MB (static OpenSSL).
- **Warning-free gate**: every emitted TU compiles at
  `-Wall -Wextra -Werror -O1` (api + models + schema_ir + validate
  generated sources) — 7/7 TUs clean.

## Sample regen (6.3)

`bin/generate-samples.sh bin/configs/cpp-boost-beast-client-petstore.yaml`
regenerated `samples/client/petstore/cpp-boost-beast/generated` (3.0
petstore): the diff = the Wave-5 emission (JSON-driven parameter
serialization, security hooks, validator attachment) — 7 files changed +
the validator files added. The 3.0 path remains fully generated.

## CI promotion (6.6)

`.github/workflows/cpp-boost-beast-oas31-conformance.yaml`:
- toolchain: + `libssl-dev` (mock link) + `python3-yaml`;
- JSTS step promoted from the representative subset to the FULL pinned
  2020-12 corpus via `jsts_genpath_slice.py` (GS2/GS3 claimed only on
  full-corpus PASS==total with FAIL==0, BLOCKED==0);
- NEW: C-profile wire gate step — `tools/jsts_param_wire.py` (6 matrices
  incl. the real-loopback mock; OpenSSL paths env-overridable
  `OMP_JSTS_SSL_LIB`/`OMP_JSTS_CRYPTO_LIB` for the apt layout) — failures
  fail the job;
- NEW: sample regen + hardening compile step (`-Wall -Wextra -Werror`);
- artifacts: + wire-gate log + sample-diff stat.

## Evidence

```
full JSTS corpus: files=46 cases=1299 PASS=1299 FAIL=0 BLOCKED=0 (batch)
wire gate: 19/19 + 6/6 + 11/11 + 21/21 + 5/5 + REF SOURCE PASS + 7/7 mock
JVM: 115/115 (previous slice; unchanged surface)
hardening: 7/7 emitted TUs warning-free (-Wall -Wextra -Werror -O1)
workflow: 12 steps validated via YAML load + step inventory
```

## Remaining

- 6.2 optional fuzz: skipped by plan wording (optional); the JSTS corpus
  serves as the bounded adversarial surface.
- 6.5 migration guide + final conformance report: next slice.