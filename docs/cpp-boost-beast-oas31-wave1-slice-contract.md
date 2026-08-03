# Wave-1 Slice Contract — cpp-boost-beast-client OAS 3.1 Validator IR + ExactNumber

| Field | Value |
| --- | --- |
| Status | **FROZEN** (architecture owner) — later agents implement against this. |
| ADR | `docs/cpp-boost-beast-oas31-wave1-adr.md` (Option B — shared `SchemaEvaluator` over densified IR tables) |
| Branch | `plan/cpp-boost-beast-oas31-full` (current checkout; never reset) |
| Build | `./mvnw` (NOT `mvn`). Java 26. Boost header-only at `/opt/homebrew/include` (Boost.JSON + Beast + multiprecision). Skeletal headers must pass `g++ -std=c++17 -fsyntax-only -Werror -I/opt/homebrew/include` |
| Anti-greenwash | Every claim below labelled `[executed]` was actually run and recorded. Anything not runnable in this slice is labelled `[unverified]` / `[out-of-slice]`. GS2/GS4/Wave-1-full-fidelity are **OUT of scope** — never claim them. |

---

## 0. Purpose and boundaries

This slice pins the frozen C++ interface contracts for the Wave-1 shared
`SchemaEvaluator` interpreter. It delivers:

1. The exact C++ IR data structures (`SchemaNode`, `SchemaResource`,
   `SchemaResourceRegistry`) for the Wave-1 keyword subset.
2. The `oas31::SchemaEvaluator` public API.
3. The `oas31::ExactNumber` public API (D1).
4. The `validate_<id>` thin-dispatch signature (D5).
5. The exact file-ownership map (who implements what).
6. Three **skeletal, `-Werror`-clean** support headers.

This slice does **not** implement the evaluator's full semantics, annotation
transaction code, `unevaluated*`, or `$dynamicRef`. Those are later agents'
scope; the *signatures* here are frozen so they can be filled without rework.

## 1. Where the generated files live (investigation `[executed]`)

Mustache resource dir: `modules/openapi-generator/src/main/resources/cpp-boost-beast-client/`
Current templates:

- Model emission: `model-header.mustache` (→ `.h`), `model-source.mustache` (→ `.cpp`)
  via `modelTemplateFiles.put("model-header.mustache", ".h")` etc. (`CppBoostBeastClientCodegen.java` ~1436).
- Shared validation support header: `validation-types.mustache` → `model/ValidationTypes.h`,
  added as a `SupportingFile` (`supportingFiles.add(new SupportingFile("validation-types.mustache","model","ValidationTypes.h"))` ~1478).
  It defines (global namespace, non-oas31): `ValidationPath`, `ValidationResult`,
  `isJsonNumber`, `isJsonInteger`, `checkJsonType`, `validatePattern`, etc.
- Other support templates wired the same way: `NullableField.h.mustache`, `AnyType.h`, `README`, `CMakeLists`, `HttpClient{Impl}.{h,cpp}`, `MultipartWireTest.cpp`.

Per the ADR D5 the three support headers are **header-only static** files. They are
placed in the same resource dir and are copied to the generated `model/` output dir.
The Java wiring (owned by the `ir-gen` agent, **not** by this slice) is:

```java
supportingFiles.add(new SupportingFile("oas31_exact_number.hpp", "model", "oas31_exact_number.hpp"));
supportingFiles.add(new SupportingFile("oas31_ir.hpp",        "model", "oas31_ir.hpp"));
supportingFiles.add(new SupportingFile("oas31_validator.hpp", "model", "oas31_validator.hpp"));
```

Emitted (generation-time) IR artifacts, owned by the `ir-gen` agent on the Java side:

- `model/schema_ir.generated.hpp` — `constexpr`/`const` densified `SchemaNode`/`SchemaResource`
  tables + `SchemaResourceRegistry schemaResourceRegistry`.
- `model/schema_ir.generated.cpp` — the table storage.
- `model/schema_validate.generated.cpp` — thin `validate_<id>` entry points.

`schema_validate.generated.cpp` may need to be a model-source-adjacent artifact. Because the
current pipeline only emits `model-{header,source}.mustache` per model, the `ir-gen` agent must
extend `CppBoostBeastClientCodegen` to emit `schema_ir.generated.*` **once** (not per model) —
e.g. via a `SupportingFile` fed by `postProcessSupportingFileData`, or a dedicated
`processOpenAPI`-driven emission — **without** touching the existing `model-*.mustache` emission so
the current generated-validator baseline and its tests stay green. The plan is:
- Reuse the same assertion-scan that `processComposedModel`/`fromModel` already performs
  (the `validateParams` block, lines ~472–690 of `CppBoostBeastClientCodegen.java`) as the input
  to IR emission, so the IR reflects exactly the same keyword subset the hand templates handle
  today. See §6 for the keyword subset mapping.

## 2. Raw-instance parse path & numeric lexeme capture (investigation `[executed]`)

Phase-2 runner: `modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/oas-compliance/phase2_runner.cpp`.
It does `boost::json::value v; v = boost::json::parse(c.payload);` (line ~91), then calls the
generated `fromJsonValue_<Schema>(v)` built on the Wave-0 `validate_*_branch_N` validators,
which read `instance.as_int64()/as_uint64()/as_double()`.

**Finding: `boost::json::parse` destroys the numeric lexeme.** Boost.JSON canonicalizes every
JSON number into one of three kinds — `int64`, `uint64`, `double_` — and discards the original
spelling. Consequences:
- `1.0000000000000000001`, `1e400`, `-0`, extreme-mantissa values, and any number outside
  `double`'s exact representable set are **lossy** in the current parse path.
- Therefore exact-number validity for `enum`/`const`/`multipleOf`/range **cannot be proven**
  through the current parse path.

**Lexeme-capture approach (designed; integration owned by `runner` agent, `[out-of-slice]`):**
a standalone raw **number-lexeme tokenizer** that scans the JSON payload and, per instance path,
captures the exact numeric spelling into a **parallel raw-instance store** keyed by JSON path
(`[](root)`, `.prop`, `[i]`, etc.). `oas31::RawInstance` carries both the `boost::json::value
const*` (typed/transport view, representability gate) and the captured `numericLexeme` string.
`RawInstance::asExactNumber()` prefers the captured lexeme when non-empty
(`ExactNumber::parseLexeme`), otherwise degrades to a conversion from the Boost.JSON value kind.

For THIS slice `[executed]`: the three skeletal headers compile and `ExactNumber::parseLexeme`,
`compare`, `add`, `mul`, `divmod` are implemented. But **no tokenizer is wired into the Phase-2
runner**, so from that parse path the lexeme is NOT preserved.
=> `parsePathLexemePreserved = false`; exact-number on the raw runner path is `[unverified]`
until the `runner` agent lands the tokenizer slice.

## 3. Frozen C++ IR data structures (`namespace oas31`) — `oas31_ir.hpp`

```cpp
enum class JsonType : std::uint8_t { null_, boolean, number, string, array, object };
enum class ApplicatorKind : std::uint8_t { none, allOf, anyOf, oneOf, not_, ref, dynamicRef };
using SchemaIndex = std::int32_t; inline constexpr SchemaIndex kNoSchema = -1;
enum class BooleanValue : std::uint8_t { notBoolean, true_, false_ };

struct SchemaNode {                       // densified node; one row per schema object
    std::uint32_t      resourceIdentity;  // index into SchemaResourceRegistry::resources
    SchemaIndex        parent = kNoSchema;
    BooleanValue       booleanValue = BooleanValue::notBoolean;  // OAS 3.1 true/false value schema
    std::uint8_t       typeFlags = 0;     // bitmask of JsonType (type or type-array)
    ExactNumber        minimum;            bool hasMinimum = false;
    ExactNumber        maximum;            bool hasMaximum = false;
    ExactNumber        exclusiveMinimum;   bool hasExclusiveMinimum = false;
    ExactNumber        exclusiveMaximum;   bool hasExclusiveMaximum = false;
    ExactNumber        multipleOf;         bool hasMultipleOf = false;
    std::vector<ExactNumber> enumNumbers;  // numeric enum values, exact
    std::vector<std::string> enumStrings;
    std::vector<bool>        enumBooleans;
    bool               hasConst = false;
    ExactNumber        constNumber;  bool constIsNumber = false;
    std::string        constString;  bool constIsString = false;
    bool               constBool = false; bool constIsBool = false;
    ApplicatorKind     applicator = ApplicatorKind::none;
    std::vector<SchemaIndex> children;     // allOf/anyOf/oneOf member indices
    SchemaIndex        notSchema = kNoSchema;   // `not` subschema reference
    std::string        dynamicRefUri;      // D4 / Wave-2+ — unused by this keyword set
};

struct SchemaResource { std::string baseUri; std::string dialect; std::string anchor;
                        std::vector<SchemaIndex> rootNodes; };

struct SchemaResourceRegistry {
    std::vector<SchemaResource> resources;
    std::vector<SchemaNode>     nodes;
    SchemaNode const& node(SchemaIndex i) const;
    SchemaResource const& resourceByIdentity(std::uint32_t id) const;
};
```

## 4. Frozen `oas31::ExactNumber` public API (D1) — `oas31_exact_number.hpp`

```cpp
class ExactNumber {                       // value = mantissa * 10^exponent10 (exact base-10)
  using Integer = boost::multiprecision::cpp_int;
  ExactNumber();  ExactNumber(Integer mantissa, std::int32_t exponent10);
  static ExactNumber parseLexeme(std::string const& lexeme); // exact, full JSON number grammar
  static ExactNumber fromInt(std::int64_t v);
  static ExactNumber fromUint(std::uint64_t v);
  static ExactNumber fromDouble(double v); // exact ONLY for double's exact set
  Integer const& mantissa() const;  std::int32_t exponent10() const;
  int compare(ExactNumber const&) const;   // normalizes exponents
  bool isZero() const;
  ExactNumber add(ExactNumber const&) const;
  ExactNumber mul(ExactNumber const&) const;
  void divmod(ExactNumber const& divisor, ExactNumber& quotient, ExactNumber& remainder) const; // multipleOf
  bool operator==(ExactNumber const&) const;  bool operator!=(ExactNumber const&) const;
  bool operator<(ExactNumber const&) const;   std::string toString() const;
};
```

Invariants: `1 == 1.0 == 1e0` under `==`/`compare`; independent of Boost.JSON double;
`multipleOf` computed by exact `divmod` (zero remainder), never floating-point modulo.

## 5. Frozen validator contract (`namespace oas31`) — `oas31_validator.hpp`

```cpp
struct RawInstance {                     // the ADR's raw-instance view
    boost::json::value const* value = nullptr;  // typed/transport view (representability gate)
    std::string numericLexeme;                  // exact lexeme when number (may be empty, slice)
    JsonType   kind() const;   bool isNumber() const;  bool asBool() const;
    std::string asString() const;   // by value (avoids dangling ref to sub-values)
    ExactNumber asExactNumber() const;     // lexeme-first, else from value kind
    RawInstance atMember(char const* key) const;  RawInstance atIndex(std::size_t i) const;
    std::size_t size() const; bool isObject() const; bool isArray() const;
};

struct ValidationPath { /* JSON-instance-path accumulator: enter/enterIndex/exit/str */ };
struct ValidationResult { bool success; std::string failurePath; std::string failureMessage;
    static ValidationResult valid(); static ValidationResult invalid(std::string,std::string); };

struct Annotation { std::string keyword, instancePath, schemaPath, absSchemaUri, value; };
struct AnnotationStore { void add(Annotation); std::size_t snapshot() const;
                         void rollbackTo(std::size_t); std::vector<Annotation> const& all() const; };

struct ValidationContext {               // D2/D3 mutable state, per validation call
    AnnotationStore annotations;
    std::set<std::string> evaluatedProperties;
    std::set<std::size_t> evaluatedItems;
    struct Branch { /* beginBranch in task doc: snapshot all three */ };
    Branch beginBranch();  bool commitBranch(Branch);  void rollbackBranch(Branch);
};

class SchemaEvaluator {
  explicit SchemaEvaluator(SchemaResourceRegistry const& registry);
  SchemaResourceRegistry const& registry() const;
  ValidationResult validate(SchemaIndex node, RawInstance const&, ValidationPath&, ValidationContext&) const;
  ValidationResult validateSchemaNode(SchemaNode const&, RawInstance const&, ValidationPath&, ValidationContext&) const;
};
```

**`validate_<id>` thin dispatch (D5) — signature (globally visible, in `schema_validate.generated.cpp`):**

```cpp
ValidationResult validate_<id>(oas31::RawInstance const& instance,
                               oas31::ValidationPath& path, oas31::ValidationContext& ctx) {
    return evaluator.validate(schemaNodeFor(<id>), instance, path, ctx); // thin dispatch
}
```

`schemaNodeFor(<id>)` resolves the `SchemaIndex` for the named schema (a helper in
`schema_ir.generated.hpp` or the registry lookup), implemented by the `ir-gen` agent.

## 6. Wave-1 keyword subset ↔ IR field mapping (from `CppBoostBeastClientCodegen` assertion scan)

The IR must be populated by the same logic that today fills `validateParams`
(lines ~472–690): `type`/`type-array` → `typeFlags`; `enum` → enum* arrays; `const` → const* fields;
`minimum`/`maximum`/`exclusiveMinimum`/`exclusiveMaximum` → the 4 bound fields; `multipleOf` →
`multipleOf`; OAS 3.1 boolean value-schema → `booleanValue`; `not` → `notSchema` reference.
Numbers must be emitted as **exact lexemes** (`ExactNumber::parseLexeme`) using the raw spelling
(`BigDecimal.toString()` / `constVal.toString()`), NOT as rounded doubles.

## 7. File ownership map (FROZEN)

| Entity | Owner | Deliverable |
| --- | --- | --- |
| `docs/cpp-boost-beast-oas31-wave1-slice-contract.md` | **arch (this slice)** | this contract |
| `.../resources/cpp-boost-beast-client/oas31_exact_number.hpp` | **arch (skeletal) → `exact-lib` agent (bodies)** | `ExactNumber` (D1) |
| `.../resources/cpp-boost-beast-client/oas31_ir.hpp` | **arch (skeletal)** | IR structs (§3) |
| `.../resources/cpp-boost-beast-client/oas31_validator.hpp` | **arch (skeletal) → `eval` agent (bodies)** | `RawInstance`, `ValidationContext`, `SchemaEvaluator`, `validate_<id>` API |
| `CppBoostBeastClientCodegen.java` (IR emission) | **`ir-gen` agent** | emitting `schema_ir.generated.{hpp,cpp}`, `schema_validate.generated.cpp`, the 3 `SupportingFile` wiring; keep 100+ tests green |
| `phase2_runner.cpp` + number-lexeme tokenizer | **`runner` agent** | raw-instance lexeme capture + driving `SchemaEvaluator` |
| Exact-number semantic corpus / JSTS numeric rows | **`semantics` agent** | prove D1 (later slice) |
| `validation-types.mustache` (Wave-0 baseline) | unchanged | do not modify (contract drift guard) |

Rules: no agent edits another agent's owned file. The Java generator must not be modified by the
`exact-lib`/`eval` agents, and vice-versa. `-Werror` applies to all headers.

## 8. Verification status of this slice (honest)

- `[executed]` Three skeletal headers created and compile with
  `g++ -std=c++17 -fsyntax-only -Werror -I/opt/homebrew/include` (see arch output JSON).
- `[executed]` `ExactNumber` core (`parseLexeme`, `compare`, `add`, `mul`, `divmod`) implemented
  in the skeletal header and self-checked.
- `[unverified]` Full `SchemaEvaluator` semantics (allOf/anyOf/oneOf walk, annotation
  transaction, `unevaluated*`, `$dynamicRef`) — later slices.
- `[unverified]` Exact-number validity on the Phase-2 raw path — blocked until the `runner`
  agent inserts the lexeme tokenizer; the current `boost::json::parse` path **destroys lexemes**.
- **Out of scope (do not claim):** GS2, GS4, Wave-1-full-fidelity.
