# ADR: Wave-1 Validator Architecture — cpp-boost-beast-client OAS 3.1

| Field | Value |
| --- | --- |
| Status | **ADOPTED** (v1) — recorded before any Wave 1 implementation work |
| Plan hooks | §1.5 constraints, §1.6 strategic architecture, §4 target architecture (4.0/4.3/4.5), §12 Option-B escape hatch, §15 ADR in Wave 1 |
| Scope | Decision for the **exact-number layer, annotation/evaluation-context system, dynamic scope, and `unevaluated*` semantics**. Sole deliverable of task A-4; no code in this wave. |

## 1. Context and decision needed

Plan §1.6 offers two strategic options and mandates a concrete ADR before Wave 1:

- **Option A — Generated validators.** Expand `validate_<id>()` emission per
  `SchemaNode` (the current Wave-0 baseline already emits such functions).
- **Option B — Embeddable schema interpreter.** One `SchemaEvaluator` that
  walks densified IR tables over a mutable `ValidationContext`.

The decision is forced by the four hardest requirements this program cannot
ship without: (a) the exact arbitrary-precision base-10 JSON Number domain
(§4.0 / K-30, K-33, K-34, K-22); (b) annotation collection plus branch
transaction/rollback (§4.3 / S-A, GA2); (c) evaluation-path `unevaluated*`
semantics (§4.5 / K-12, K-35); and (d) `$dynamicRef` dynamic-scope resolution
(§4.3 / K-16). It must respect §1.5 (**correctness > dependency-free**,
**fail closed > partial fake**) and the §12 size thresholds.

## 2. Decision (recommendation)

> **ADOPT Option B — a single IR-table `SchemaEvaluator` interpreter — as the
> primary semantic validator from Wave 1 onward.** Wave 0 keeps its existing
> generated-validator foundation only as the already-landed baseline; no Wave-1
> feature is implemented in Option-A form. A thin `validate_<id>` symbol is
> retained per schema strictly as a stable entry point that dispatches into the
> shared `SchemaEvaluator` — not as a per-schema semantic implementation.

**REJECTED:** Option A as the engine for the exact-number, annotation,
dynamic-scope, and `unevaluated*` layers.

### Rationale

The four hardest requirements are **traversal-order-sensitive, cross-schema,
stateful** behaviours that Option A would have to re-derive at every composition
boundary inside a forest of generated functions:

1. **`unevaluated*` (§4.5):** "what was evaluated" is one accumulation across
   whatever subschemas actually ran (earlier in-place applicators, `$ref`/
   `$dynamicRef` targets, all successful branches). An interpreter holds it in
   one place; generated code threads the set through every call site — the
   program's highest-risk correctness surface (§9).
2. **Branch transaction/rollback (§4.3):** `beginBranch`/`commitBranch`/
   `rollbackBranch` must atomically cover annotations + `evaluatedProperties` +
   `evaluatedItems` at every boundary. In an interpreter this is one code path;
   in generated code it is boilerplate re-emitted at every `oneOf`/`anyOf`/
   `allOf`/`if` branch, compounding size and nesting-omission risk.
3. **`$dynamicRef` (§4.3):** resolution happens **at evaluation time**; the
   dynamic target is unknowable at generation time, so generated code cannot
   statically encode it.
4. **Size (§12):** full annotation + rollback + dynamic-scope scaffolding per
   function compounds on kitchen-sink specs; densified IR tables stay small.

Performance is real but secondary: raw-instance validity (S-V) is a correctness
oracle, not a hot user path, and one hot `SchemaEvaluator` loop is easier to
benchmark than hundreds of heterogeneous functions. **Correctness outranks raw
speed** (§1.5).

## 3. Decision areas

### D1. Exact JSON Number domain (§4.0) — ADOPT decimal-rational, separate from Boost.JSON

- **Options:** (a) rely on Boost.JSON `double` for numeric validity; (b) a
  bespoke exact base-10 type.
- **Trade-offs:** (a) is free but silently wrong for `enum`/`const`/
  `uniqueItems`/`multipleOf` outside `double`'s exact set (§4.0). (b) costs a
  small support library but is the only correct path.
- **Decision:** **ADOPT (b)**. Support header `oas31_exact_number.hpp`
  (`namespace oas31`):

  ```cpp
  class ExactNumber {
    boost::multiprecision::cpp_int mantissa; // arbitrary-precision integer
    std::int32_t              exponent10;    // value = mantissa * 10^exponent10
    // normalize(scales); compare(); add(); mul(); divmod() for multipleOf
  };
  ```

  `mantissa × 10^exponent10` is a finite base-10 arbitrary-precision decimal,
  exact by construction and independent of Boost.JSON `double`. Comparison
  normalizes exponents; multiplication multiplies mantissas, adds exponents;
  `multipleOf` is an exact `divmod` with zero remainder — never floating division.
- **Rationale:** keeps `1`, `1.0`, `1e0` mathematically equal while preserving
  every representable JSON number exactly. The generator keeps the raw numeric
  lexeme (acceptable per §4.0) in a parallel raw-instance store, not parsed
  through Boost.JSON for validity; Boost.JSON remains the transport/typed model
  (M profile / representability gate) — a separate layer.
- **Risks:** bignum cost on pathological input (mitigated by §12 for perf only;
  correctness unaffected); lexeme edge cases (`-0`, leading zeros, extreme
  exponents) need dedicated JSTS numeric rows. `boost::multiprecision` is
  permitted: it does not replace Boost.JSON/Beast (§10).

### D2. Annotation + branch transaction/rollback (§4.3) — ADOPT in `ValidationContext`

- **Options:** (a) per-schema static annotation returns; (b) a mutable collector
  owned by the evaluation context with explicit branch snapshots.
- **Trade-offs:** (a) makes rollback across composition boundaries awkward and
  ties annotation to return plumbing; (b) centralizes the S-A contract (GA1) and
  the §4.3 transactional rule.
- **Decision:** **ADOPT (b)**. `ValidationContext` owns `AnnotationStore&
  annotations` plus `beginBranch()` / `commitBranch()` / `rollbackBranch()`,
  snapshotting annotations, `evaluatedProperties`, and `evaluatedItems` together.
  `SchemaEvaluator` calls them at every schema-object and applicator boundary.
- **Rationale:** makes "commit only when the producing schema object succeeds;
  discard local + descendant output on failure" one implementation reachable by
  every `allOf`/`anyOf`/`oneOf`/`not`/`if`/`then`/`else` path.
- **Risks:** snapshot cost under deep nesting — keep snapshots copy-on-write and
  only where a composing applicator exists; validate annotation identity fields
  (keyword, instance path, schema path, absolute schema URI, values) against GA1.

### D3. Evaluation-path `evaluatedProperties` / `evaluatedItems` (§4.5)

- **Options:** (a) lexical-sibling-only tracking; (b) evaluation-path tracking
  seeded by a `ValidationPath` and `$ref`/`$dynamicRef` targets.
- **Trade-offs:** (a) is simpler but is the "sibling-only mistake" §9 warns
  against and fails JSTS `unevaluated*`; (b) is required.
- **Decision:** **ADOPT (b)**. `ValidationContext` carries
  `std::set<std::string> evaluatedProperties` and `std::set<size_t> evaluatedItems`.
  `SchemaEvaluator` records successfully-evaluated locations from `properties`,
  `patternProperties`, `additionalProperties`, `prefixItems`, `items`,
  `contains`, and successful in-place applicators and `$ref`/`$dynamicRef`
  evaluations; failed branches and enclosing objects roll back their coverage via
  D2's snapshot; all successful `anyOf` branches contribute.
- **Rationale:** direct fulfilment of §4.5.
- **Risks:** the `contains` + `unevaluatedItems` interaction (full item
  consideration) needs dedicated fixture coverage (JSTS `unevaluated*.json` +
  §7.3).

### D4. `$dynamicRef` / dynamic scope (§4.3) — ADOPT runtime dynamic-scope resolution

- **Options:** (a) static/generation-time resolution; (b) ordered `DynamicScope`
  on the validation path with evaluation-time resolution.
- **Trade-offs:** (a) cannot represent "outermost matching resource in dynamic
  scope" and is wrong for recursive dynamic anchors; (b) matches the spec.
- **Decision:** **ADOPT (b)**. Add `DynamicScope dynamicScope` (ordered
  `SchemaResource*` stack, outermost-first) and a `RecursionSet recursionStates`
  whose stop-condition test includes dynamic scope (§4.3). IR carries
  `dynamicRefUri` plus the statically resolved initial target (§4.1). At
  evaluation time `SchemaEvaluator` resolves the initial fragment against the
  current base URI; if it was defined by `$dynamicAnchor`, it substitutes the
  identically named anchor in the outermost matching resource in `dynamicScope`;
  otherwise it behaves exactly as `$ref`.
- **Rationale:** `$dynamicRef` is inherently evaluation-time behaviour; only the
  interpreter implements it once, shared by all callers.
- **Risks:** the outermost-matching rule across embedded resources/dialect
  boundaries needs the official dynamicRef corpus (§9); guard against truncating
  resolution via `RecursionSet` containing dynamic scope.

### D5. C++ shape / where code lives

- `validate_<id>` remains the public per-schema symbol (plan §4.3 signature) but
  is a thin dispatch:

  ```cpp
  ValidationResult validate_<id>(RawInstance const& instance,
                                 ValidationPath& path, ValidationContext& ctx) {
    return evaluator.validate(schemaNodeFor(id), instance, path, ctx);
  }
  ```

  One shared `SchemaEvaluator`; `ValidationContext` is per-validation-call and
  carries all mutable state (D1–D4). Generated output:
  - `schema_ir.generated.hpp/.cpp` — densified `SchemaNode`/`SchemaResource` IR
    tables (`SchemaResourceRegistry`, §4.2) as `constexpr`/`const` data;
  - `schema_validate.generated.cpp` — thin `validate_<id>` entry points (debugger
    ergonomics, stable ABI);
  - generated support headers (`oas31_exact_number.hpp`, `oas31_validator.hpp`,
    `oas31_ir.hpp`), **header-only**, built under `-Werror` and
    `g++ -std=c++17 -fsyntax-only -I/opt/homebrew/include`.
- **Rationale:** concentrates semantics in the engine, keeps emitted LOC small,
  preserves the `validate_<id>` call contract locked by
  `CppBoostBeastClientCodegenTest`.

### D6. Runtime dependencies (§1.5) — ADOPT a small, frozen support library

- **Decision:** **ADOPT** a header-only support library for exactly three
  concerns: exact numbers (`boost::multiprecision` as bigint backbone), the
  ECMA-262-compatible Unicode pattern engine (§4.6/§3.6, G-full-schema path),
  and the `SchemaEvaluator` core. Explicitly permitted by §1.5
  **correctness > dependency-free**; does not replace Boost.JSON/Beast (§10).
  Library is frozen and pinned for reproducible output; dependency list locked in
  the conformance README; pattern engine flagged per §4.6 (subset = G-honest only).

### D7. Generated-code size control (§12)

- **Decision:** **ADOPT** densified IR tables as the size-control mechanism, with
  structural-hash deduplication of `SchemaNode` only when the semantic
  environment (resource identity, base URI, dialect, anchors) is identical
  (§4.1 rule 5). Reopen triggers: median generated model/TU **> 50k LOC** or
  **> 5 MB** on the 3.1 kitchen-sink corpus, or validator dedupe unable to hold
  size after Wave 2. A CI size-audit (Wave 6 + §12) runs these.
- **Rationale:** the interpreter makes output proportional to IR density rather
  than per-keyword × per-node combinatorial expansion, answering §12 and
  §1.7 GS6/§8.1 determinism.
- **Risks:** IR density itself must be measured; the audit canonicalizes it.

## 4. Consolidated option register

| Area | Option | Verdict |
| --- | --- | --- |
| Primary engine | A — generated validators | **REJECTED** (as semantic engine) |
| Primary engine | B — `SchemaEvaluator` interpreter | **ADOPTED** |
| Exact numbers | (a) Boost.JSON `double` | **REJECTED** |
| Exact numbers | (b) `oas31::ExactNumber` decimal-rational | **ADOPTED** |
| Annotation/rollback | (a) static returns | **REJECTED** |
| Annotation/rollback | (b) `ValidationContext` transactions | **ADOPTED** |
| Evaluated coverage | (a) lexical-only | **REJECTED** |
| Evaluated coverage | (b) evaluation-path sets | **ADOPTED** |
| `$dynamicRef` | (a) static resolution | **REJECTED** |
| `$dynamicRef` | (b) runtime `DynamicScope` resolution | **ADOPTED** |
| Support library | dependency-free | **REJECTED** |
| Support library | frozen header-only exact/regex/evaluator lib | **ADOPTED** |

## 5. Open questions

1. **`validate_<id>` retention:** keep the thin per-schema symbol in all builds,
   or gate it behind a debug flag once IR is stable and size thresholds tighten?
   (Revisit at D7 audit.)
2. **Pattern engine backend:** which specific ECMA-262-compliant Unicode engine
   is adopted (§4.6); confirm full Unicode/code-point + non-BMP coverage before
   it counts toward G-full-schema (Wave 3.6).
3. **`unevaluatedItems` + `contains`:** exact interaction and whether full item
   consideration changes rollback semantics (D3) — needs a JSTS/fixture spike in
   Wave 4.
4. **Lexeme normalization:** canonicalize `-0`, leading zeros, extreme exponents
   across JSTS numeric groups; confirm `uniqueItems` ordering under `ExactNumber`
   equality.
5. **OneOf "sole successful branch retained"** rule (§4.3) needs one
   source-of-truth table in `SchemaEvaluator` so every composing applicator is
   provably identical; confirm no stray per-applicator override.

## 6. Escalation path

If Wave 1–2 shows the interpreter insufficiently fast for a gate (not a size or
semantics issue), escalate via §12; the fallback is **not** wholesale Option A
but a *validity-presentation fast path* layered on top of the interpreter —
never an alternate semantic oracle — and it must keep GS/GA fixtures green
before it is enabled.
