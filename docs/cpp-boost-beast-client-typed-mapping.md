# cpp-boost-beast-client — Typed C++ Mapping Contract (M profile, GM3)

Software contract for the generated typed layer. Every claim here is backed
by a row in the M corpus (`oas31-jsts/mprofile/m-corpus.yaml`) executed by
the M driver (`oas-compliance/m_driver.cpp`) against the generated model
axis — documentation cannot drift from behavior.

## 1. Destination domains

| OAS schema domain | C++ destination | Exact round trip | Notes |
| --- | --- | --- | --- |
| `integer` no format / `format: int32` | `std::int32_t` | yes | out-of-range → representation error (never silent) |
| `format: int64` | `std::int64_t` | yes | out-of-range (≥ 2^63 or > uint64) → representation error |
| `number` no format / `format: double` | `double` | yes (finite) | non-finite input (1e309, 1e400) → representation error; see §4 |
| `format: float` | `float` | no — **narrowed** (§3) | idempotent; > FLT_MAX → representation error |
| `string` | `std::string` | yes | |
| `string` + format (`date-time`, `date`, `uuid`, `byte`, `binary`, `password`) | `std::string` | yes | formats are **annotations** (2020-12 default); see §1a |
| `decimal` | `double` | yes (finite) | exact decimals are not a declared C++ type; see §1a |
| `boolean` | `bool` | yes | |
| `string` + `enum` | `std::string` | yes (closed set) | unknown value → schema-invalid at decode |
| `string`/`integer` via type-array `[T, "null"]` (3.1) | `NullableField<T>` | yes (null/missing/value tri-state) | 3.1 `nullable: true` is NOT a keyword — plain type |
| any schema (bare object / untyped) | `AnyType` = `boost::json::value` | yes (stored value) | raw fallback; see §5 |
| `oneOf`/`anyOf` + discriminator | `std::variant<A, B>` | yes (matched branch) | unknown/missing discriminator → schema-invalid |
| `array` | `std::vector<T>` / `std::vector<std::shared_ptr<Model>>` | yes | |
| `object` / `additionalProperties` | `std::map<…>` / model class | yes | |

## 1a. Format destinations (annotations, no distinct types)

`format` is part of the **Format-Annotation** vocabulary in the pinned OAS 3.1
dialect (2020-12 default): formats carry **no validation semantics** and
therefore require **no runtime validation**. The generator maps every
string-domain format to the plain `std::string` destination, and `decimal` to
`double` — it declares **no format-specific destinations** and **no
format-specific `DataTypeFeature` entries** (Uuid, Date, DateTime, Byte,
Binary, Password are all absent from the FeatureSet). Asserted by the JVM test
`formatDestinationsMapToStringOrDoubleAndFeatureSetStaysClean`
(`CppBoostBeastClientApiCodegenTest`) over `format-destinations.yaml`.

| OAS `format` | Destination |
| --- | --- |
| `date-time` | `std::string` |
| `date` | `std::string` |
| `uuid` | `std::string` |
| `byte` | `std::string` |
| `binary` | `std::string` |
| `password` | `std::string` |
| `decimal` | `double` |

Format-Assertion (validating formats) is intentionally **not** claimed: the
2020-12 dialect default treats formats as annotations, so there is no validity
surface to implement.

## 2. Error taxonomy (five classes, distinguishable)

| Class | Observation |
| --- | --- |
| `transportParseError` | `boost::json::parse` throws (malformed JSON) |
| `schemaInvalid` | decode failure with validator language: "Value not allowed" (enum), "value is not a string/boolean", "Required field … not found", "No matching branch"/"More than one matching branch", fractional literal into an integer destination ("not exact" + fractional text) |
| `unrepresentable` | decode failure "Decode failed … **not exact**" for integral out-of-range values; "**non-finite destination**" for float/double overflow — representation diagnostics, NEVER reported as schema validity |
| `representable` | decode ok + exact deep round trip (`oas31::deepJsonValueEqual`; 1 == 1.0) |
| `narrowed` | float destination: decode ok, re-encoded value = nearest float, IDEMPOTENT (stable) |

Callers can distinguish representation from validation by exception
message marker (`not exact` / `non-finite destination` = representation).

## 3. Float precision policy

`format: float` destinations hold IEEE-754 binary32. Decimals with more
than 7 significant digits decode to the nearest float and re-encode as
that float's exact decimal (`0.1` → `1.0000000149011612E-1`). The
round trip is **idempotent** (re-decode of the re-encoded value is
stable). Exact round trips are guaranteed only for values exactly
representable in binary32 (`0.5`, `FLT_MAX`, …).

## 4. Non-finite policy (F3 fix)

Values outside the destination's finite range are representation
diagnostics: `value_to<float>/<double>` overflow now throws
"non-finite destination" instead of silently producing `±inf`.
JSON literals like `1e400` parse (Boost.JSON maps them to `inf` and
serializes `1e99999`); the M2 finite check rejects them at the
destination. The exact mathematical value of such literals is preserved
in the oas31 lexeme layer for validator-side reasoning, but the float/
double destinations do not claim them.

## 5. Big-number boundary (F5)

Integers beyond `uint64` (e.g. `2^64`, `2^70`) parse as lossy `double`
(16 significant digits). `int64` destinations reject them ("not exact").
`AnyType` holds the stored (lossy) value; the corpus documents this
boundary — the exact-number domain exceeds the stored-value domain.

## 6. Optional/null/presence semantics

- `nullable` (OAS 3.0) / `[T, "null"]` (OAS 3.1) → `NullableField<T>`:
  missing ≠ null ≠ value; all three round-trip exactly.
- optional properties → `boost::optional<T>` where emitted; absent keys
  are dropped on re-encode; unknown keys are dropped (schema-declared
  members only).
- Raw fallback: untyped/bare-object schemas → `AnyType` (identity).

## 7. Enum/open-value policy

Destinations are plain `std::string` (open member type). The closed
value set is enforced at decode by the validator ("Value not allowed").
Pre-declared unknown values fail decode (schema-invalid); open-value
usage requires relaxing the schema (e.g. `anyOf` with the open string).

## 8. Default policy

Schema `default` values are annotations (S-A): they are collected and
exposed but NOT injected into the typed value at decode (presence vs
default is the caller's choice). The wire layer never fabricates missing
properties from defaults.