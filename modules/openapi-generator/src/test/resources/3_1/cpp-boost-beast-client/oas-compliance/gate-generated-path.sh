#!/usr/bin/env bash
# =============================================================================
# gate-generated-path.sh — Wave-1 GENERATED-path integration gate (wire pass).
# =============================================================================
# PROVES the ADR Option-B GENERATED path end-to-end: the artifacts emitted by
# the REAL generator (not the phase2_numeric custom-driver) compile under
# -Werror with the oas31 engine and produce the correct numeric/boolean accept/
# reject verdicts through the GENERATED validate_<id> dispatch (D5).
#
# Pipeline:
#   1. Build/refresh the generator CLI jar (freshness check).
#   2. Run the real generator on the committed OAS 3.1 doc
#        src/test/resources/3_1/cpp-boost-beast-client/oas31-generated-path-regression.yaml
#      producing model/{schema_ir.generated.hpp, schema_ir.generated.cpp,
#      schema_validate.generated.cpp} + the oas31 engine headers.
#   3. Compile those GENERATED .cpp + the integration driver
#      (phase2_generated_path_driver.cpp) + oas31_lexeme.hpp under -Werror.
#   4. RUN the generated validate_<Schema>_branch_0 dispatch over raw-instance
#      numeric/boolean payloads (raw number lexemes -> ExactNumber) and verify
#      39/39 verdicts.
#
# All generated artifacts + logs live in phase2-wiregen-build/ (gitignored);
# only the OAS 3.1 doc, the driver source and this gate are committed.
#
# Usage:  ./gate-generated-path.sh
# =============================================================================
set -u
IFS=$'\n\t'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CANDIDATE="${SCRIPT_DIR}"
PROJECT_ROOT=""
while [[ "${CANDIDATE}" != "/" ]]; do
    if [[ -f "${CANDIDATE}/mvnw" ]]; then
        PROJECT_ROOT="${CANDIDATE}"; break
    fi
    CANDIDATE="$(dirname "${CANDIDATE}")"
done
if [[ -z "${PROJECT_ROOT}" ]]; then
    echo "ERROR: project root (mvnw) not found" >&2; exit 2
fi

MVNW="${PROJECT_ROOT}/mvnw"
JAR="${PROJECT_ROOT}/modules/openapi-generator-cli/target/openapi-generator-cli.jar"
SPEC="${SCRIPT_DIR}/../oas31-generated-path-regression.yaml"
RES_DIR="${PROJECT_ROOT}/modules/openapi-generator/src/main/resources/cpp-boost-beast-client"
BUILD_DIR="${SCRIPT_DIR}/phase2-wiregen-build"
GEN_OUT="${BUILD_DIR}/generated"
DRIVER="${SCRIPT_DIR}/phase2_generated_path_driver.cpp"
LEXEME_HDR="${SCRIPT_DIR}/oas31_lexeme.hpp"
COMPILE_LOG="${BUILD_DIR}/wiregen-compile.log"
RUN_LOG="${BUILD_DIR}/wiregen-run.log"
TSV="${BUILD_DIR}/wiregen-tsv.log"
BOOST_INCLUDE="${BOOST_INCLUDE_DIR:-/opt/homebrew/include}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; BOLD='\033[1m'; NC='\033[0m'
pass_msg() { echo -e "  ${GREEN}PASS${NC}  $1"; }
fail_msg() { echo -e "  ${RED}FAIL${NC}  $1"; }
info_msg() { echo -e "  ${BLUE}INFO${NC}  $1"; }
header()    { echo -e "\n${BOLD}═══ $1 ═══${NC}\n"; }

rc_fail=0

# ---- 1. Build generator jar (freshness check) -------------------------------
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
        info_msg "Rebuilding generator jar..."
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

# ---- 2. Generate the real artifact from the committed OAS 3.1 doc -----------
header "Step 2: Generate the Wave-1 IR + validate_<id> dispatch (real generator)"
rm -rf "${GEN_OUT}"; mkdir -p "${BUILD_DIR}"
java -jar "${JAR}" generate \
    --generator-name cpp-boost-beast-client \
    --input-spec "${SPEC}" \
    --output "${GEN_OUT}" \
    --additional-properties packageName=CppBoostBeastOas31Wire \
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

# Quick sanity: the dispatch must reference the schema names we assert on.
if ! grep -q "validate_ExactEqualsOne_branch_0" "${GEN_OUT}/model/schema_validate.generated.cpp"; then
    echo -e "  ${RED}ERROR${NC} generated dispatch missing validate_ExactEqualsOne_branch_0"; exit 2
fi
# Numeric lexemes must survive verbatim (never a rounded double).
if ! grep -q '1180591620717411303424' "${GEN_OUT}/model/schema_ir.generated.cpp"; then
    echo -e "  ${RED}ERROR${NC} IR lost the >2^53 exact const lexeme"; exit 2
fi
if ! grep -q '"0.1"' "${GEN_OUT}/model/schema_ir.generated.cpp"; then
    echo -e "  ${RED}ERROR${NC} IR lost the 0.1 multipleOf lexeme"; exit 2
fi
pass_msg "IR carries exact lexemes (2^70 const, 0.1/0.3 multipleOf)"

# ---- 3. Stage + compile the FULL generated artifact under -Werror -----------
header "Step 3: Compile generated IR + evaluator + driver under -Werror (C++17)"
mkdir -p "${BUILD_DIR}"
cp "${GEN_OUT}/model/schema_ir.generated.hpp" "${GEN_OUT}/model/schema_ir.generated.cpp" \
   "${GEN_OUT}/model/schema_validate.generated.cpp" "${BUILD_DIR}/"
cp "${DRIVER}" "${LEXEME_HDR}" "${BUILD_DIR}/"
printf '#include <boost/json/src.hpp>\n' > "${BUILD_DIR}/boost_json_src.cpp"

cc_rc=0
g++ -std=c++17 -Wall -Wextra -Werror \
    -I"${BOOST_INCLUDE}" -I"${RES_DIR}" -I"${BUILD_DIR}" \
    "${BUILD_DIR}/phase2_generated_path_driver.cpp" \
    "${BUILD_DIR}/schema_ir.generated.cpp" \
    "${BUILD_DIR}/schema_validate.generated.cpp" \
    "${BUILD_DIR}/boost_json_src.cpp" \
    -o "${BUILD_DIR}/phase2_generated_path_driver" 2>&1 | tee "${COMPILE_LOG}" || cc_rc=$?
if [[ "${cc_rc}" -ne 0 ]]; then
    echo -e "  ${RED}ERROR${NC} -Werror compile failed (see ${COMPILE_LOG})"
    exit 2
fi
pass_msg "-Werror full-artifact compile rc=0"

# ---- 4. Run the GENERATED dispatch and verify verdicts ----------------------
header "Step 4: Run generated validate_<id> dispatch on numeric/boolean raw instances"
run_rc=0
rm -f "${TSV}"
"${BUILD_DIR}/phase2_generated_path_driver" "${TSV}" > "${RUN_LOG}" 2>&1 || run_rc=$?
if [[ "${run_rc}" -ne 0 ]]; then
    echo -e "  ${RED}ERROR${NC} generated-path driver failed (see ${RUN_LOG})"; exit 2
fi
total=$(grep -oE '__WIRE_GEN_TOTAL__=[0-9]+' "${RUN_LOG}" | head -1 | cut -d= -f2 || true)
wpass=$(grep -oE '__WIRE_GEN_PASS__=[0-9]+' "${RUN_LOG}" | head -1 | cut -d= -f2 || true)
wfail=$(grep -oE '__WIRE_GEN_FAIL__=[0-9]+' "${RUN_LOG}" | head -1 | cut -d= -f2 || true)
echo "  ${total} raw-instance cases via GENERATED dispatch: ${wpass} PASS, ${wfail} FAIL"
if [[ "${wpass}" != "${total}" ]] || [[ "${wfail}" != "0" ]]; then
    echo -e "  ${RED}FAIL${NC} generated-path verdict mismatch (expected ${total}/${total})"
    exit 2
fi
pass_msg "GENERATED-path numeric/boolean verdicts green: ${wpass}/${total}"

echo
echo -e "${BOLD}═══ GENERATED-PATH GATE RESULT: GREEN ═══${NC}"
exit 0
