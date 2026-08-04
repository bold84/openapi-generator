# Wave-1 Verification Evidence — cpp-boost-beast-client (VERIFY phase, label v1)

| Field | Value |
| --- | --- |
| Branch | `plan/cpp-boost-beast-oas31-full` (never reset; clean tree at a8b7728) |
| Task | VERIFY 1 — Full regression of the Wave-1 Option-B engine |
| Date (env) | 2026-08-04 |
| Build | `./mvnw` · Java 26 · Boost at `/opt/homebrew/include` · `g++ -std=c++17 -Wall -Wextra -Werror` |

Every claim below was **run** and the output recorded. This file is the committed,
reproducible evidence bundle for label `v1`. Anti-greenwash: nothing reported PASS
without a real command + captured output.

---

## 1. Engine headers — `-Werror` syntax-only (rc must be 0)

Command:
```
for h in oas31_exact_number.hpp oas31_ir.hpp oas31_deep_equal.hpp oas31_validator.hpp
  g++ -std=c++17 -Wall -Wextra -Werror -fsyntax-only -I/opt/homebrew/include -I<resources> $h
```
Headers: `modules/openapi-generator/src/main/resources/cpp-boost-beast-client/{oas31_exact_number,oas31_ir,oas31_deep_equal,oas31_validator}.hpp`

Result: **WERROR_SYNTAX_RC=0** — all four headers compile clean under `-Werror`.

Note: `oas31_deep_equal.hpp` (NEW, FROZEN K-30/K-34) and `oas31_validator.hpp`
(evaluator K-03/K-01/K-22/K-29) are included in the checked set. There is no
separate `registry` header in this pass — `SchemaResource`/`SchemaResourceRegistry`
live in `oas31_ir.hpp`, which also passed rc=0.

## 2. Java generator tests — CppBoostBeastClient* (exact count)

Command:
```
./mvnw -pl modules/openapi-generator -Dtest=CppBoostBeastClientCodegenTest,CppBoostBeastClientApiCodegenTest test
```
Result (recorded tail):
```
Tests run: 105, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
(105 = 103 codegen + 2 api tests. No failures/errors/skips.)

## 3. Committed Wave-1 regression gates (real generator -> IR -> dispatcher -> run)

### 3.1 `gate-wave1-complete.sh` (the committed Wave-1 completion gate)
```
emitted schema_ir.generated.{hpp,cpp} + schema_validate.generated.cpp   PASS
IR carries $ref applicator (K-29)                                       PASS
IR carries uniqueItems flag (K-22)                                      PASS
IR carries boolean value-schemas true/false (K-03)                      PASS
IR carries notSchema reference (K-01)                                   PASS
IR carries deep JSON value store (K-30/K-34)                            PASS
IR carries deep enum JSON store (K-34)                                  PASS
-Werror full-artifact compile rc=0                                      PASS
35 Wave-1-complete cases via GENERATED dispatch: 35 PASS, 0 FAIL        PASS
WAVE-1-COMPLETE GATE RESULT: GREEN
```

### 3.2 `gate-generated-path.sh` (prior committed numeric/boolean gate)
```
emitted schema_ir.generated.{hpp,cpp} + schema_validate.generated.cpp   PASS
IR carries exact lexemes (2^70 const, 0.1/0.3 multipleOf)               PASS
-Werror full-artifact compile rc=0                                      PASS
39 raw-instance cases via GENERATED dispatch: 39 PASS, 0 FAIL           PASS
GENERATED-PATH GATE RESULT: GREEN
```
(Consistent with the committed 39/39 in the env context.)

## 4. Compliance matrix — YAML parse (`python3 yaml`)

Command: `python3` + `yaml.safe_load` on
`modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/compliance-matrix.yaml`
```
YAML_PARSE_OK=True
ROWS: 67
ROWS with required keys ok: True
STATUS_COUNTS: {'deferred': 22, 'annotation': 14, 'supported': 9, 'fail-closed': 22}
```
The matrix is well-formed (list of 67 keyword records, all carrying `keyword` + `status`).

---

## Verdict summary (label v1)

| Check | Result |
| --- | --- |
| werrorOk (headers `-Werror -fsyntax-only`) | true (rc=0, 4/4) |
| javaCount (`CppBoostBeastClient*Test`) | 105 run, 0 fail, 0 error, 0 skip |
| wave1GateGreen (`gate-wave1-complete.sh`) | true — 35/35, compile rc=0 |
| matrixYamlOk (`python3 yaml` compliance-matrix.yaml) | true — 67 rows parse clean |
| Extra: `gate-generated-path.sh` | true — 39/39 |

No failures. No fixes made (verify-only task).

Scope honesty: this verification covers the committed Wave-1 completion engine
(K-03 boolean, K-01 not, K-30/K-34 exact deep const/enum equality, K-22 uniqueItems,
K-29 local `$ref` to a real schema node) through the real-generator GENERATED path.
Out-of-slice (allOf/anyOf/oneOf walk, unevaluated*, `$dynamicRef`, string length+pattern,
annotations, contains, dependent/if-then-else, external-file `$ref`/`$anchor` cross-resource
dialect) is NOT claimed here.
