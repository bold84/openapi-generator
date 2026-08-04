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

---

## 9. WAVE-1-COMPLETION FROZEN CONTRACT (engine owner, committed a8b7728..HEAD)

Frozen by the **integration/engine owner** driving Wave-1 completion. This section
FREEZES the exact additional IR fields and evaluator entry points shipped in this
pass. It is additive to §3–§7 above; nothing in §0–§8 is changed. The Option-B
GEnerated path (real generator -> `schema_ir.generated.*` -> `validate_<id>` ->
`SchemaEvaluator`) remains the ONLY sanctioned Wave-1 semantic path.

### 9.1 Freeze rule (file ownership for THIS pass)
| Entity | Owner (THIS pass) | Deliverable |
| --- | --- | --- |
| `oas31_deep_equal.hpp` | **engine (new, FROZEN)** | exact deep JSON equality (ExactNumber + structural recursion) |
| `oas31_ir.hpp` (additive fields) | **engine (FROZEN)** | K-30/K-34 const/enum JSON store, `hasUniqueItems` (K-22) |
| `oas31_validator.hpp` (evaluator) | **engine (FROZEN)** | K-03 boolean, K-01 `not`, deep const/enum/uniqueItems, `$ref` walk |
| `CppBoostBeastClientCodegen.java` (IR emission) | **engine (extended for THIS pass)** | emit the new fields; keep hand-template `validateParams` untouched; main node indices 0..M-1 stable |
| `oas-compliance/gate-wave1-complete.sh` + driver + yaml | **engine (committed)** | regression proof of K-03/K-01/K-30/K-34/K-22/K-29 raw-instance verdicts |

### 9.2 Additional FROZEN IR fields (`oas31_ir.hpp`, additive)
Deep const/enum are stored as real JSON VALUES (booleans true/false are `BooleanValue`,
not stored here). Numbers inside stored const/enum are canonicalized via
`ExactNumber::from*` at compare time; the INSTANCE side keeps its raw lexeme, so
`1 == 1.0 == 1e0` holds exactly (ADR D1).
```cpp
struct SchemaNode {
    // ...existing fields (unchanged)...
    bool hasUniqueItems = false;                 // K-22 (array uniquess, deep-equal)
    bool constIsJson = false;  boost::json::value constJson;   // K-30 non-scalar const
    bool hasEnumJson = false;  std::vector<boost::json::value> enumJson; // K-34 non-scalar enum
};
```
`oas31_deep_equal.hpp` (NEW, FROZEN) exposes:
```cpp
namespace oas31 {
ExactNumber exactValueOf(boost::json::value const&);            // number KIND -> ExactNumber
bool deepJsonValueEqual(boost::json::value const&, boost::json::value const&); // stored-vs-stored
}
```
The evaluator adds `bool deepInstanceEqual(RawInstance const&, boost::json::value const&)`
(defined in `oas31_validator.hpp`, lexeme-first on the instance side; numbers only ever
compare through `ExactNumber`). Object keys are compared unordered; arrays positionally;
`null` == `null`.

### 9.3 Additional FROZEN evaluator behaviour (`oas31_validator.hpp`)
- **K-03 boolean value schemas:** node.booleanValue `true_` always-valid, `false_` never
  valid (unchanged, now REACHABLE from generated IR for literal boolean components).
- **K-01 `not`:** `node.notSchema != kNoSchema` -> invert subschema verdict; no subschema
  annotations are retained (annotations remain stubbed). FROZEN unconditional inversion.
- **Deep equality K-30/K-34:** when `hasEnumJson`/`constIsJson` is set the evaluator uses
  `deepInstanceEqual` over ALL JSON kinds (scalar + array + object); the legacy scalar
  buckets (`enumNumbers/Strings/Booleans`, `constNumber/String/Bool`) remain for backward
  compat and are used ONLY when the JSON store is absent.
- **K-22 uniqueItems:** `hasUniqueItems && instance.isArray()` rejects when ANY pair of
  point-wise items is deep-equal (e.g. `[1,2,1.0]` rejected because `1 == 1.0`).
- **K-29 `$ref`:** `applicator == ApplicationKind::ref && !children.empty()` validates
  `children[0]` (the resolved target node) transparently; the target is resolved at
  generation time within the SAME registry for local refs. Resource identity/baseUri/
  dialect/anchor carried on `SchemaResource`. External-file refs that cannot be resolved
  generation-time fall back to the inline keyword copy (honest: local $ref lands, external
  is partial).

### 9.4 Honest verification claims for this pass
- `[executed]` All engine headers + new `oas31_deep_equal.hpp` pass
  `g++ -std=c++17 -Wall -Wextra -Werror -fsyntax-only -I/opt/homebrew/include` (rc=0).
- `[executed]` `gate-wave1-complete.sh` compiles+runs the REAL-generator-emitted
  `schema_ir.generated.*` + `schema_validate.generated.cpp` + the committed driver under
  `-Werror` and verifies boolean/`not`/deep-equal(uniqueItems)/`$ref` raw-instance verdicts,
  with per-case PASS recorded (evidence `oas-compliance/phase2-wave1build/…`).
- `[open/partial]` External-file `$ref`, `$anchor` URI resolution across resources,
  dialect switches: MAY be partial — reported honestly in the engine return JSON.
- `[out-of-slice]` Still later waves: allOf/anyOf/oneOf full applicator walk,
  unevaluated*, `$dynamicRef`, string length+pattern, annotations, contains,
  dependent/if-then-else, C-profile, Waves 5–6. NOT claimed.

---

## 10. OBJECT/ARRAY STRUCTURAL FROZEN CONTRACT (engine integration owner, THIS pass)

Frozen by the **integration/engine owner** driving the Wave-2 object/array structural
pass. This section FREEZES the additional IR fields, evaluator semantics and ownership
map for object/array traversal, container-depth exact deep-equality, `$defs`/`$ref`
surfacing, and the Wave-1 residual closes. It is additive to §3–§9; nothing is changed
backwards. The GENERATED-path (real generator → `schema_ir.generated.*` →
`validate_<id>` → `SchemaEvaluator`) remains the ONLY promotion authority.

### 10.1 Freeze rule (file ownership for THIS pass)
| Entity | Owner (THIS pass) | Deliverable |
| --- | --- | --- |
| `oas31_ir.hpp` (additive fields) | **engine (FROZEN)** | object/array structural IR (§10.2) |
| `oas31_validator.hpp` (evaluator) | **engine (FROZEN)** | object/array traversal, applicator walk, unevaluatedProperties, ref-sibling semantics, container-depth lexeme propagation (§10.3) |
| `oas31_object_array.hpp` (NEW) | **engine (FROZEN)** | instance lexeme table capture at container depth (exactness) |
| `CppBoostBeastClientCodegen.java` (IR emission) | **engine (extended for THIS pass)** | emit §10.2 fields from the branch assertion scan; main-node indices 0..M-1 stay stable; extra child rows appended after |
| `oas-compliance/gate-oastructural.sh` + `phase2_oastructural_driver.cpp` + `oas31-object-array-regression.yaml` | **engine (committed, SMOKE)** | -Werror regression proof of object/array/not/enum/uniqueItems/$defs through GENERATED `validate_<id>` |
| `oas31-jsts/tools/jsts_genpath_slice.py` (`wrap_spec` + `write_driver`) | **engine (extended for THIS pass)** | `$defs`/local-pointer ref surfacing into OAS-wrap scope; container-depth lexeme capture in the generated driver |
| `docs/cpp-boost-beast-oas31-wave1-slice-contract.md` §10 | **engine (FROZEN)** | this contract |

### 10.2 Additional FROZEN IR fields (`oas31_ir.hpp`, additive; all default-inert)
```cpp
enum class AdditionalPropertiesKind : std::uint8_t { absent, allowed, reject, schema };
struct PropertyBinding { std::string name; SchemaIndex node = kNoSchema; };

struct SchemaNode {
    // ...existing fields (unchanged)...
    // object structural
    bool hasObjectSchema = false;
    std::vector<PropertyBinding> properties;      // declared property subschemas (order-preserving)
    std::vector<std::string> required;
    AdditionalPropertiesKind additionalProperties = AdditionalPropertiesKind::absent;
    SchemaIndex           additionalSchema = kNoSchema;   // only when additionalProperties == schema
    ExactNumber           minProperties;  bool hasMinProperties = false;
    ExactNumber           maxProperties;  bool hasMaxProperties = false;
    // array structural
    std::vector<SchemaIndex> prefixItems;             // prefixItems[i] applies to index i (2020-12)
    SchemaIndex              items = kNoSchema;       // items applies to indices >= prefixItems.size()
    ExactNumber              minItems;  bool hasMinItems = false;
    ExactNumber              maxItems;  bool hasMaxItems = false;
    // applicator + unevaluated (best-effort, bool/absent forms)
    bool hasUnevaluatedProperties = false;            // keyword present
    bool unevaluatedPropertiesRejects = false;        // false => reject unevaluated; true => allow
    SchemaIndex unevaluatedSchema = kNoSchema;        // schema form (validates unevaluated values)
    // NOTE: uniqueItems has NO "false" IR field — `uniqueItems:false` is a no-op that still
    // materialises the node (so the JSTS runner never reports "no validate_ emitted").
};
```
Numbers helpers: bounds (`minProperties`, `maxProperties`, `minItems`, `maxItems`) are
`ExactNumber` constructed from `ExactNumber::fromUint(instance size)` at compare time —
never an `int`/`double` shortcut (a decimal `1.0` bound compares equal to `1`).

### 10.3 Additional FROZEN evaluator semantics (`oas31_validator.hpp`)
- **Object traversal** (only when `instance.isObject()`): (1) `minProperties`/`maxProperties`
  via `ExactNumber::fromUint(size())`; (2) `required` member presence at the current level;
  (3) each declared `properties[name]` subschema validated with `path.enter(name)`;
  (4) `additionalProperties` tri-state — `allowed`/`absent` impose nothing, `reject`
  rejects any UNLISTED key, `schema` validates unlisted values via `additionalSchema`;
  **listed properties are NEVER additionally evaluated** (excluding them from the
  additionalProperties pass). Evaluated property names are recorded in
  `ValidationContext::evaluatedProperties` for applicator/unevaluated tracking.
- **Array traversal** (only when `instance.isArray()`): (1) `minItems`/`maxItems` via
  `ExactNumber::fromUint(size())`; (2) `prefixItems[i]` validated by index for
  `i < min(prefixItems.size, size)`; (3) `items` applies to indices
  `i >= prefixItems.size()` (2020-12 remainder semantics); recorded into
  `evaluatedItems`.
- **Container-depth EXACT lexemes:** `RawInstance` gains an optional instance-lexeme
  table (`oas31_object_array.hpp`, `InstanceLexemeTable`). `atMember`/`atIndex` propagate
  the canonical instance path + table to children; `asExactNumber()` consults the table
  (path-keyed) BEFORE degrading to the Boost.JSON value kind. All container-depth
  deep-equality (const/enum/uniqueItems/dynamic `not` children) therefore NEVER degrades
  a nested number to `double`: `1 == 1.0 == 1e0` holds with the raw lexeme, even several
  container levels down. When no table is attached (legacy drivers) behaviour is the
  documented value-kind fallback.
- **`uniqueItems:false` is a NO-OP** (blithely accepted; the node is still emitted so the
  corpus never counts the case as BLOCKED-at-emission). `uniqueItems:true` keeps the
  existing exact deep-uniqueness rejection.
- **`$ref` + siblings (2020-12):** a node with `applicator == ref` FIRST validates the
  resolved target node, then FALLS THROUGH to its own sibling keywords (type/enum/
  uniqueItems/object/array/min-maxItems/…). Purely-ref nodes carry no siblings so their
  behaviour is unchanged (transparent).
- **`$defs`/local-pointer surfacing:** the JSTS OAS-wrap hoists `$defs` and local JSON
  pointers (`#`, `#/properties/...`, `#/prefixItems/...`, escaped pointers) into
  `components.schemas` as synthetic oneOf components and rewrites the refs to
  `#/components/schemas/<hoisted>`. The Java emitter resolves local refs to the hoisted
  branch rows; unresolvable external refs (remote `http(s)`, unresolvable `urn:`) are
  emitted as inert nodes (honest FAIL/PASS measured, never BLOCKED).
- **Applicator walk (best-effort, needed by deep `not` + unevaluated):** `allOf`/
  `anyOf`/`oneOf` children are evaluated transactionally; annotations (evaluated
  property/item sets) from SUCCESSFUL branches only are retained (anyOf/oneOf). A
  node with `hasUnevaluatedProperties` snapshots the evaluated-property set at entry,
  and, at exit, `unevaluatedPropertiesRejects` rejects object keys not evaluated within
  that node's subtree. This is a pragmatic subset of annotation semantics; full
  `unevaluatedItems`/`$dynamicRef` remain out of slice.

### 10.4 Honest verification claims for this pass (measured, not estimated)
- `[executed]` `g++ -std=c++17 -Wall -Wextra -Werror -fsyntax-only -I/opt/homebrew/include`
  on ALL engine headers including new `oas31_object_array.hpp` (rc=0).
- `[executed]` SMOKE ONLY (NOT promotion authority): `oas-compliance/gate-oastructural.sh`
  proves object/array/not/enum/uniqueItems/$defs verdicts through the GENERATED
  `validate_<id>` dispatch under `-Werror`.
- `[executed]` THE EXECUTED GENERATED-PATH JSTS CORPUS (the only promotion authority):
  `oas31-jsts/tools/jsts_genpath_slice.py` re-run on the Wave-1 six files plus the
  Wave-2 structural files; a keyword is supported only on zero-FAIL AND zero-BLOCKED.
- `[open/partial, honestly reported]` `patternProperties`, `propertyNames`,
  `dependentSchemas`, `contains`, if/then/else, string length+pattern, `unevaluatedItems`,
  `$dynamicRef`/anchors, remote/URN `$ref` resolution partially; NOT claimed.
