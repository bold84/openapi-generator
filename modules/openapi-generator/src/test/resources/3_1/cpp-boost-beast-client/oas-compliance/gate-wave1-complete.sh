#!/usr/bin/env bash
# =============================================================================
# gate-wave1-complete.sh — Wave-1 COMPLETE engine gate (boolean / not /
# deep-equality / uniqueItems / $ref) through the GENERATED validate_<id>
# dispatch under -Werror.
# =============================================================================
# PROVES the Wave-1 completion engine work end-to-end through the REAL
# generator (ADR Option-B GENERATED path):
#   K-03  boolean value-schemas (true/false),
#   K-01  `not` subschema inversion,
#   K-30  exact deep JSON CONST equality (all kinds, ExactNumber),
#   K-34  exact deep JSON ENUM equality (array + mixed members),
#   K-22  array uniqueItems (1 == 1.0 is a duplicate),
#   K-29  local $ref resolution to a real schema node.
#
# Pipeline (mirrors gate-generated-path.sh):
#   1. Build/refresh the generator CLI jar.
#   2. Run the REAL generator on the committed OAS 3.1 doc
#        src/test/resources/3_1/cpp-boost-beast-client/oas31-wave1-complete-regression.yaml
#      emitting model/{schema_ir.generated.hpp,cpp, schema_validate.generated.cpp}
#      + the oas31 engine headers.
#   3. Compile the generated artifacts + the committed driver
#      (phase2_wave1_complete_driver.cpp) + oas31_lexeme.hpp under -Werror.
#   4. RUN the generated validate_<Id>_branch_0 dispatch over raw instances and
#      verify every case (boolean/not/deep-equal/uniqueItems/$ref).
#
# All build artifacts live in phase2-wave1build/ (gitignored); only the OAS doc,
# the driver and this gate are committed.
#
# Usage:  ./gate-wave1-complete.sh
# =============================================================================
set -u
IFS=$'\n\t'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CANDIDATE="${SCRIPT_DIR}"
PROJECT_ROOT=""
while [[ "${CANDIDATE}" != "/" ]]; do
    if [[ -f "${CANDIDATE}/mvnw" ]]; then PROJECT_ROOT="${CANDIDATE}"; break; fi
    CANDIDATE="$(dirname "${CANDIDATE}")"
done
if [[ -z "${PROJECT_ROOT}" ]]; then
    echo "ERROR: project root (mvnw) not found" >&2; exit 2
fi

MVNW="${PROJECT_ROOT}/mvnw"
JAR="${PROJECT_ROOT}/modules/openapi-generator-cli/target/openapi-generator-cli.jar"
SPEC="${SCRIPT_DIR}/../oas31-wave1-complete-regression.yaml"
RES_DIR="${PROJECT_ROOT}/modules/openapi-generator/src/main/resources/cpp-boost-beast-client"
BUILD_DIR="${SCRIPT_DIR}/phase2-wave1build"
GEN_OUT="${BUILD_DIR}/generated"
DRIVER="${SCRIPT_DIR}/phase2_wave1_complete_driver.cpp"
LEXEME_HDR="${SCRIPT_DIR}/oas31_lexeme.hpp"
COMPILE_LOG="${BUILD_DIR}/wave1-compile.log"
RUN_LOG="${BUILD_DIR}/wave1-run.log"
TSV="${BUILD_DIR}/wave1-tsv.log"
BOOST_INCLUDE="${BOOST_INCLUDE_DIR:-/opt/homebrew/include}"

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; BOLD='\033[1m'; NC='\033[0m'
pass_msg() { echo -e "  ${GREEN}PASS${NC}  $1"; }
fail_msg() { echo -e "  ${RED}FAIL${NC}  $1"; }
info_msg() { echo -e "  ${BLUE}INFO${NC}  $1"; }
header()    { echo -e "\n${BOLD}═══ $1 ═══${NC}\n"; }

# ---- 1. Build generator jar (freshness check) ------------------------------
header "Step 1: Build generator CLI jar"
if [[ -f "${JAR}" ]]; then
    need_rebuild=false
    for src_dir in "${PROJECT_ROOT}/modules/openapi-generator/src" \
                   "${PROJECT_ROOT}/modules/openapi-generator-cli/src" \
                   "${PROJECT_ROOT}/modules/openapi-generator-core/src"; do
        newest=$(find "${src_dir}" -name '*.java' -newer "${JAR}" -print -quit 2>/dev/null || true)
        if [[ -n "${newest}" ]]; then need_rebuild=true; break; fi
    done
    if [[ "${need_rebuild}" == "false" ]]; then
        info_msg "Jar up to date: ${JAR}"
    else
        info_msg "Rebuilding generator jar (CppBoostBeastClientCodegen changed)..."
        (cd "${PROJECT_ROOT}" && "${MVNW}" -pl modules/openapi-generator-cli -am compile package -DskipTests -Dmaven.test.skip=true -q)
    fi
fi
if [[ ! -f "${JAR}" ]]; then
    info_msg "Building generator jar (first run)..."
    (cd "${PROJECT_ROOT}" && "${MVNW}" -pl modules/openapi-generator-cli -am compile package -DskipTests -Dmaven.test.skip=true -q)
fi
if [[ ! -f "${JAR}" ]]; then
    echo -e "  ${RED}ERROR${NC} generator jar not found at ${JAR}"; exit 2
fi
pass_msg "generator jar ready"

# ---- 2. Generate the real artifact from the committed OAS 3.1 doc ----------
header "Step 2: Generate Wave-1-complete IR + dispatch (real generator)"
rm -rf "${GEN_OUT}"; mkdir -p "${BUILD_DIR}"
java -jar "${JAR}" generate \
    --generator-name cpp-boost-beast-client \
    --input-spec "${SPEC}" \
    --output "${GEN_OUT}" \
    --additional-properties packageName=CppBoostBeastOas31Wave1 \
    --additional-properties apiPackage=api \
    --additional-properties modelPackage=model >/dev/null 2>&1
gen_rc=$?
if [[ "${gen_rc}" -ne 0 ]] || \
   [[ ! -f "${GEN_OUT}/model/schema_ir.generated.hpp" ]] || \
   [[ ! -f "${GEN_OUT}/model/schema_ir.generated.cpp" ]] || \
   [[ ! -f "${GEN_OUT}/model/schema_validate.generated.cpp" ]]; then
    echo -e "  ${RED}ERROR${NC} generator did not emit schema_ir.generated.* + schema_validate.generated.cpp"
    exit 2
fi
pass_msg "emitted schema_ir.generated.{hpp,cpp} + schema_validate.generated.cpp"

# Sanity: the generated IR must carry the Wave-1 completion constructs.
IRCPP="${GEN_OUT}/model/schema_ir.generated.cpp"
if grep -q "ApplicatorKind::ref" "${IRCPP}"; then
    pass_msg "IR carries \$ref applicator (K-29)"
else
    echo -e "  ${RED}FAIL${NC} IR missing ApplicatorKind::ref (K-29)"; exit 2
fi
if grep -q "hasUniqueItems = true" "${IRCPP}" && grep -q "n.hasUniqueItems = true;" "${IRCPP}" \
   || grep -q "hasUniqueItems" "${IRCPP}"; then
    pass_msg "IR carries uniqueItems flag (K-22)"
else
    echo -e "  ${RED}FAIL${NC} IR missing uniqueItems (K-22)"; exit 2
fi
if grep -q "BooleanValue::false_" "${IRCPP}" && grep -q "BooleanValue::true_" "${IRCPP}"; then
    pass_msg "IR carries boolean value-schemas true/false (K-03)"
else
    echo -e "  ${RED}FAIL${NC} IR missing boolean value-schemas (K-03)"; exit 2
fi
if grep -q "n.notSchema =" "${IRCPP}"; then
    pass_msg "IR carries notSchema reference (K-01)"
else
    echo -e "  ${RED}FAIL${NC} IR missing notSchema (K-01)"; exit 2
fi
if grep -q 'boost::json::parse(R"W1J(' "${IRCPP}"; then
    pass_msg "IR carries deep JSON value store (K-30/K-34)"
else
    echo -e "  ${RED}FAIL${NC} IR missing deep JSON value store (K-30/K-34)"; exit 2
fi
if grep -q "n.hasEnumJson = true;" "${IRCPP}"; then
    pass_msg "IR carries deep enum JSON store (K-34)"
else
    echo -e "  ${RED}FAIL${NC} IR missing deep enum JSON store (K-34)"; exit 2
fi

# ---- 3. Compile generated IR + evaluator + driver under -Werror ------------
header "Step 3: Compile generated IR + evaluator + driver under -Werror (C++17)"
mkdir -p "${BUILD_DIR}"
cp "${GEN_OUT}/model/schema_ir.generated.hpp" "${GEN_OUT}/model/schema_ir.generated.cpp" \
   "${GEN_OUT}/model/schema_validate.generated.cpp" "${BUILD_DIR}/"
cp "${DRIVER}" "${LEXEME_HDR}" "${BUILD_DIR}/"
printf '#include <boost/json/src.hpp>\n' > "${BUILD_DIR}/boost_json_src.cpp"

cc_rc=0
g++ -std=c++17 -Wall -Wextra -Werror \
    -I"${BOOST_INCLUDE}" -I"${RES_DIR}" -I"${BUILD_DIR}" \
    "${BUILD_DIR}/phase2_wave1_complete_driver.cpp" \
    "${BUILD_DIR}/schema_ir.generated.cpp" \
    "${BUILD_DIR}/schema_validate.generated.cpp" \
    "${BUILD_DIR}/boost_json_src.cpp" \
    -o "${BUILD_DIR}/phase2_wave1_complete_driver" 2>&1 | tee "${COMPILE_LOG}" || cc_rc=$?
if [[ "${cc_rc}" -ne 0 ]]; then
    echo -e "  ${RED}ERROR${NC} -Werror compile failed (see ${COMPILE_LOG})"
    exit 2
fi
pass_msg "-Werror full-artifact compile rc=0"

# ---- 4. Run and verify ALL Wave-1-complete verdicts -------------------------
header "Step 4: Run generated validate_<Id>_branch_0 on Wave-1 cases"
run_rc=0
rm -f "${TSV}"
"${BUILD_DIR}/phase2_wave1_complete_driver" "${TSV}" > "${RUN_LOG}" 2>&1 || run_rc=$?
if [[ "${run_rc}" -ne 0 ]]; then
    echo -e "  ${RED}ERROR${NC} wave1-complete driver failed (see ${RUN_LOG})"; exit 2
fi
total=$(grep -oE '__WAVE1_COMPLETE_TOTAL__=[0-9]+' "${RUN_LOG}" | head -1 | cut -d= -f2 || true)
pass=$(grep -oE '__WAVE1_COMPLETE_PASS__=[0-9]+' "${RUN_LOG}" | head -1 | cut -d= -f2 || true)
fail=$(grep -oE '__WAVE1_COMPLETE_FAIL__=[0-9]+' "${RUN_LOG}" | head -1 | cut -d= -f2 || true)
echo "  ${total} Wave-1-complete cases via GENERATED dispatch: ${pass} PASS, ${fail} FAIL"
if [[ "${pass}" != "${total}" ]] || [[ "${fail}" != "0" ]]; then
    echo -e "  ${RED}FAIL${NC} Wave-1-complete verdict mismatch (expected ${total}/${total})"
    exit 2
fi
pass_msg "Wave-1-complete verdicts green: ${pass}/${total}"

echo
echo -e "${BOLD}═══ WAVE-1-COMPLETE GATE RESULT: GREEN ═══${NC}"
exit 0
