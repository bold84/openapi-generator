# Wave-M2 slice: mapping corpus + M driver (GM1 run + GM2)

Slice commit: `9c7dc14a018`.

## What landed

- `oas31-jsts/mprofile/m-corpus.yaml` — 50 rows over the m-probe schemas,
  five classes: 25 representable, 10 unrepresentable, 10 schemaInvalid,
  3 transportParseError, 2 narrowed; every class ≥ 2 rows; every schema in
  the generated set; instances parse-checked (transport rows must fail,
  others must parse); no duplicate ids (self-checks in the generator,
  `oas-compliance/phase2_m_gen.py`).
- `oas-compliance/m_driver.cpp` — data-driven five-class classifier over
  the GENERATED model axis: parse → GENERATED `fromJsonValue_X` → failure
  classification (text-literal integrality: fractional literals =
  schemaInvalid, integral literals out of range = unrepresentable;
  "non-finite destination" = unrepresentable) → re-encode → exact
  `deepJsonValueEqual` (representable) else idempotence check
  (narrowed). Outputs `m-resolved.tsv`.
- **F3 converter fix** (`model-source.mustache`): `JsonValueConverter<float>`
  and `<double>` specializations — `std::isfinite` check after
  `value_to` → non-finite destinations throw a representation diagnostic
  ("Decode failed: value not representable as … (non-finite
  destination)") instead of silently emitting `±inf`. `<cmath>` added.
- Petstore sample regenerated with the new emission (276 lines added).

## GM2 findings confirmed

1. int32/int64 range failures THROW ("not exact") — never silent
   truncation; classified unrepresentable for integral literals.
2. Fractional literals into integer destinations (1.5) = schemaInvalid
   (type: integer violation) — validator-first.
3. float = documented narrowing domain (0.1 → 0.1000000014901161,
   idempotent); exact round trips for binary32-representable values.
4. Non-finite literals (1e400 → +inf at parse; serializes `1e99999`) are
   rejected at float/double destinations (representation) — never silent.
5. Exact comparands use `oas31::deepJsonValueEqual` (1 == 1.0 == 1.0e0).
6. 3.1 `[T,"null"]` → `NullableField` tri-state: null/missing/value all
   round-trip exactly.

## Evidence

```
__M_TOTAL__=50 __M_PASS__=50 __M_FAIL__=0   (M driver, -Werror)
JSTS 1299/1299 | Gate A 191/0/0 | JVM 115/115 | wire gate 6 matrices
(petstore sample regenerated + committed)
```

## Files

- `oas31-jsts/mprofile/m-corpus.yaml`, `oas-compliance/phase2_m_gen.py`,
  `oas-compliance/m_driver.cpp`, `model-source.mustache`,
  `samples/client/petstore/cpp-boost-beast`