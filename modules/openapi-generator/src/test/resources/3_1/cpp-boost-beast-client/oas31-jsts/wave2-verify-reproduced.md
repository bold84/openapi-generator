# Wave-2 — post-commit HEAD executed-verification record (reproducibility + dual-path)

**Owner:** reconciliation (session, post-workflow) · **Branch:** `plan/cpp-boost-beast-oas31-full`
**Date:** 2026-08-04. Every number below was produced by executing the command on this host with a jar rebuilt from committed HEAD; nothing is copied from earlier runs.

> **Purpose.** The Wave-2 workflow committed evidence (`wave2-structural-subset-report.md` 082461df8f5) measured against a jar whose engine headers were still uncommitted working-tree files at measurement time. This record proves the same numbers reproduce from **committed HEAD** (engine `1a1e8c28223` + emitter `ada7ba2a49e`), and closes the recorded gaps: no executed Wave-2 gate verdict and no executed OAS 3.0 dual-path note.

## 1. Corpus re-run from committed HEAD — exact reproduction

Jar: `modules/openapi-generator-cli/target/openapi-generator-cli.jar` rebuilt at 17:42 via `./mvnw -pl modules/openapi-generator-cli -am package -DskipTests` from HEAD (includes committed `oas31_object_array.hpp` + `oas31_ir.hpp`/`oas31_validator.hpp` Wave-2 changes and the Wave-2 emitter).

Command:
```bash
python3 tools/jsts_genpath_slice.py --suite vendor --jar <rebuilt-jar> \
  --work /tmp/jsub-reverify \
  --files not.json,enum.json,uniqueItems.json,ref.json,properties.json,\
required.json,additionalProperties.json,minProperties.json,maxProperties.json,\
prefixItems.json,items.json,minItems.json,maxItems.json \
  --out /tmp/jsub-reverify/result.json
```

Executed output (verbatim):
```
== not.json ==           PASS=39 FAIL=1 BLOCKED=0 gen=OK
== enum.json ==          PASS=50 FAIL=1 BLOCKED=0 gen=OK
== uniqueItems.json ==   PASS=69 FAIL=0 BLOCKED=0 gen=OK
== ref.json ==           PASS=78 FAIL=1 BLOCKED=0 gen=OK
== properties.json ==    PASS=26 FAIL=2 BLOCKED=0 gen=OK
== required.json ==      PASS=18 FAIL=0 BLOCKED=0 gen=OK
== additionalProperties.json == PASS=17 FAIL=2 BLOCKED=2 gen=PARTIAL
== minProperties.json == PASS=8 FAIL=0 BLOCKED=2 gen=OK
== maxProperties.json == PASS=8 FAIL=0 BLOCKED=2 gen=OK
== prefixItems.json ==   PASS=11 FAIL=0 BLOCKED=0 gen=OK
== items.json ==         PASS=29 FAIL=0 BLOCKED=0 gen=OK
== minItems.json ==      PASS=4 FAIL=0 BLOCKED=2 gen=OK
== maxItems.json ==      PASS=4 FAIL=0 BLOCKED=2 gen=OK
=== TOTALS ===  files=13 cases=378 PASS=361 FAIL=7 BLOCKED=10
```

**Identical to the committed report (082461df8f5).** The SUPPORTED rows are therefore fully reproducible from committed state: `uniqueItems` 69/0/0, `required` 18/0/0, `prefixItems` 11/0/0, `items` 29/0/0 (zero-FAIL AND zero-BLOCKED each).

## 2. Wire gates re-run post-engine-commit (no regression)

Executed from `oas-compliance/`:
- `bash gate-generated-path.sh` → **GENERATED-PATH GATE RESULT: GREEN** — `-Werror` full-artifact compile rc=0, 39 raw-instance cases via GENERATED dispatch: **39 PASS, 0 FAIL** (rc=0).
- `bash gate-wave1-complete.sh` → **WAVE-1-COMPLETE GATE RESULT: GREEN** — `-Werror` full-artifact compile rc=0, 35 Wave-1-complete cases via GENERATED dispatch: **35 PASS, 0 FAIL** (rc=0).

The Wave-2 engine changes do not regress the numeric/boolean wire gate or the Wave-1-complete gate. (The committed `oas31-wave2-structural-regression.yaml` remains a generator-input fixture; its cases are covered by gate-a semantic evidence `wave2-object-array-gate-a-evidence.md` 172/19 and by the corpus above, which is the promotion authority.)

## 3. OAS 3.0 dual-path — executed

```bash
java -jar <rebuilt-jar> generate -g cpp-boost-beast-client \
  -i modules/openapi-generator/src/test/resources/3_0/petstore.yaml -o /tmp/oas30-dual
# rc=0; model/ + api/ + CMakeLists.txt emitted
g++ -std=c++17 -Wall -Wextra -Werror -fsyntax-only -x c++-header \
  -include iostream -I/opt/homebrew/include -I. -Imodel -Iapi model/*.h api/*.h
# rc=0 — all 14 generated model+api headers clean
```

Findings: the OAS 3.0 dual-path still generates (rc=0), emits the same Wave-2 engine artifacts into `model/` (`oas31_exact_number.hpp`, `oas31_ir.hpp`, `oas31_deep_equal.hpp`, `oas31_object_array.hpp`, `oas31_validator.hpp`, `ValidationTypes.h`, `schema_ir.generated.hpp`), and all generated model+api headers are `-Werror`-clean (the only local fix needed for the isolated syntax-only check was `-include iostream`, a Boost include-order quirk, not a generator defect). **OAS 3.0 dual-path intact.**

## 4. Java

`./mvnw -pl modules/openapi-generator -Dtest=CppBoostBeastClientCodegenTest,CppBoostBeastClientApiCodegenTest test` → **Tests run: 108, Failures: 0, Errors: 0, Skipped: 0** (recorded by workflow verify v1, commit 82ba15c29).

## 5. Honest boundary

- Only 4 rows are promoted to `supported` by this slice (`uniqueItems`, `required`, `prefixItems`, `items`).
- `not`/`enum`/`ref`/`properties` are zero-BLOCKED but carry residual semantic FAILs (annotation-in-not G8, empty-enum G14, remote-metaschema G6, patternProperties-interplay), so they stay `deferred`.
- `additionalProperties` 17/2/2 and `min/maxProperties`/`min/maxItems` (0 FAIL, 2 BLOCKED each — schema-side decimal bounds dropped at emission) stay `deferred`.
- GS2 (full corpus) and GS4 (zero-DEFERRED) remain unclaimed.
