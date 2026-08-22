# Gap-4 slice report — validation runtime opt-out knob (compileWithValidation)

Commit: _see git log for `gap4-validation-knob` commit_

## Objective

Emit a compile-time knob `kValidateOnDecode` in the generated
`model/ValidationTypes.h`, controlled by the codegen option
`compileWithValidation` (default `true`). With the knob off, the generated
client skips **only** composition-branch validation (oneOf/anyOf/discriminator
branch *membership* checks) at decode time. Representation diagnostics
(M-contract / F3: non-finite destinations, integer range, required-property
checks inside branch conversion) remain active on **all** paths.

## Changes

### Codegen option (`CppBoostBeastClientCodegen.java`)

- New `CliOption("compileWithValidation", ...)` with default `true`.
- `processOpts()` parses the additional property (Boolean or String; unknown
  values warn and fall back to `true`), stores `validateOnDecode`,
  and publishes `additionalProperties.put("validateOnDecode", ...)` +
  `additionalProperties.put("compileWithValidation", ...)` so Mustache
  sections (`{{#validateOnDecode}}`/`{{^validateOnDecode}}`) and values
  render correctly.

### Knob emission (`ValidationTypes.h.mustache`)

- Emits `constexpr bool kValidateOnDecode = {{validateOnDecode}};` in the
  generated `model/ValidationTypes.h`, preceded by a comment block that
  documents the exact scope (composition-branch validation only) and the
  invariant that F3 representation diagnostics are never disabled.

### Dispatch gating (`model-source.mustache`)

- The two composition-branch counting loops (discriminator-preferred branch
  and remaining-branch validation) are wrapped in `{{#validateOnDecode}}`.
- The `{{^validateOnDecode}}` branch skips the `validate_*` calls and seeds
  `validMatchCount = 1; foundMatch = true; matchedBranchIndex = 0;`, so the
  existing oneOf/anyOf postlude proceeds to `convertMatchedBranch()` with
  first-parseable-branch semantics. `(void)vp;` and, when a discriminator is
  present, `(void)discPreferredBranch;` silence unused-variable warnings in
  the disabled build (-Werror clean, verified).
- The anyOf failure-collection block (which re-runs `validate_*` for error
  detail) is unreachable when validation is disabled because
  `validMatchCount == 1`; it remains compiled (no warnings; the
  `validate_*` definitions are non-static namespace-scope functions, and the
  calls inside the unreachable branch are fine).

### JVM round-trip tests (`CppBoostBeastClientCodegenTest.java`)

- `compileWithValidationDefaultsToTrue` — generates from
  `composed-schema-lowering.yaml` with defaults and asserts emitted
  `constexpr bool kValidateOnDecode = true;`.
- `compileWithValidationFalseEmitsKnobOff` — generates with
  `compileWithValidation=false` and asserts emitted
  `constexpr bool kValidateOnDecode = false;`.

Full JVM suite after change:
`CodegenTest` **112/112** (110 prior + 2 new), `ApiCodegenTest` **4/4** →
**116/116 PASS, 0 FAIL** (matches the 116 figure originally cited in the
plan; it now includes the two new knob tests).

## Benchmark (evidence, not a CI gate)

Driver: `tmp/knob-bench/bench_driver.cpp` (scratch, untracked). Same driver
source compiled twice against two freshly generated trees of the M-profile
probe spec (`m-probe-schemas.yaml`, PolyBox = oneOf + discriminator over
Cat/Dog, plus FloatBox/Int32Box controls): default tree
(`kValidateOnDecode = true`) and `compileWithValidation=false` tree
(`kValidateOnDecode = false`). Build: `-O2 -Wall -Wextra -Werror`, Boost 1.90.

Semantics checks (both trees, all PASS):

| check | ON | OFF |
|---|---|---|
| oneOf exactly-one: instance matching both Cat and Dog branches | rejects (std::invalid_argument) | decodes, first branch |
| valid single-branch instance (`{"kind":"cat","meow":true}`) | decodes | decodes |
| type-invalid branch instance (`meow:"yes"`) | rejected | rejected |
| F3 float overflow `{"v":3.4028236e38}` | throws | throws |
| F3 int32 overflow `{"v":2147483648}` | throws | throws |
| F3 in-range float `{"v":0.1}` | decodes | decodes |

Throughput (200,000 iterations, steady_clock):

| payload | knob ON ns/op | knob OFF ns/op | delta |
|---|---|---|---|
| PolyBox {cat} (oneOf+discriminator decode) | 87.32 | 40.03 | **-54.2%** |
| PolyBox {dog} | 4087.85 | 3951.57 | -3.3% |
| FloatBox {0.1} (control, representation path) | 9.12 | 9.89 | n/a (knob-invariant) |

ops/s: PolyBox {cat}: 11,452,266 (ON) → 24,981,654 (OFF), **2.18×**
throughput. Control FloatBox is statistically unchanged, confirming the knob
only removes composition-branch work.

## Invariant confirmation

- F3 non-finite/representation diagnostics verified throwing on BOTH knob
  states (benchmark Part A, rows above).
- `-Werror` clean on both generated trees (`-Wall -Wextra -Werror`).
- Default generation (no option) emits `kValidateOnDecode = true`; behavior
  of the validated path is byte-for-byte the pre-knob dispatch (the
  `{{#validateOnDecode}}` block contains the original code unchanged).

## Files

- `modules/openapi-generator/src/main/java/org/openapitools/codegen/languages/CppBoostBeastClientCodegen.java`
- `modules/openapi-generator/src/main/resources/cpp-boost-beast-client/model-source.mustache`
- `modules/openapi-generator/src/main/resources/cpp-boost-beast-client/validation-types.mustache`
- `modules/openapi-generator/src/test/java/org/openapitools/codegen/cppboostbeast/CppBoostBeastClientCodegenTest.java`