#!/usr/bin/env python3
"""Wave-5.1 GC1 gate: OAS 3.1 parameter-style matrix golden wire-byte tests.

Generates the client from wave5/param-matrix.yaml, compiles a driver whose
RecordingClient captures the exact wire bytes (request target incl. query
string, request body, headers incl. Cookie) for every matrix cell, and
asserts each cell against the OAS 3.1 §Parameter Serialization goldens.

Exit 0 = all cells match; non-zero = list the mismatches.

The probe driver prints `CELL|<name>|PASS|...` or `CELL|<name>|FAIL|<expected>|<actual>`.
"""
import importlib.util
import json
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SUITE_DIR = os.path.join(HERE, "..")
JAR = os.path.normpath(os.path.join(
    SUITE_DIR, "..", "..", "..", "..", "..", "..", "..", "..",
    "modules", "openapi-generator-cli", "target", "openapi-generator-cli.jar"))
assert os.path.exists(JAR), "jar not built: " + JAR

spec = importlib.util.spec_from_file_location(
    "jsts", os.path.join(HERE, "jsts_genpath_slice.py"))
sl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sl)

MATRIX = os.path.join(SUITE_DIR, "wave5", "param-matrix.yaml")
SERVER_MATRIX = os.path.join(SUITE_DIR, "wave5", "server-matrix.yaml")
SECURITY_MATRIX = os.path.join(SUITE_DIR, "wave5", "security-matrix.yaml")
LEXEME_SRC = getattr(sl, "LEXEME_SRC", None)

DRIVER = r'''
// Wave-5.1 golden driver: assert the exact wire bytes per matrix cell.
#include <cstdio>
#include <map>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include <boost/optional.hpp>

#include "api/DefaultApi.h"
#include "model/QueryDeep_color_parameter.h"
#include "model/QueryFormObjectExpl_role_parameter.h"
#include "model/PathSimpleExpl_attrs_parameter.h"

using namespace model;
using namespace org::openapitools::client::api;

class RecordingClient : public HttpClient {
public:
    std::string lastVerb;
    std::string lastTarget;
    std::string lastBody;
    std::map<std::string, std::string> lastHeaders;

    std::pair<boost::beast::http::status, std::string>
    execute(const std::string& verb, const std::string& target,
            const std::string& body,
            const std::map<std::string, std::string>& headers) override {
        lastVerb = verb; lastTarget = target; lastBody = body;
        lastHeaders = headers;
        return {boost::beast::http::status::ok, "{}"};
    }
};

static int g_failures = 0;

static void check(const char* name, const std::string& actual,
                  const std::string& expected) {
    if (actual == expected) {
        printf("CELL|%s|PASS|%s\n", name, actual.c_str());
    } else {
        ++g_failures;
        printf("CELL|%s|FAIL|expected=%s|actual=%s\n",
               name, expected.c_str(), actual.c_str());
    }
}

int main() {
    auto client = std::make_shared<RecordingClient>();
    DefaultApi api(client);

    std::vector<std::string> colors{"blue", "black", "brown"};

    api.queryFormExploded(colors);
    check("queryFormExploded", client->lastTarget,
          "/queryFormExploded?color=blue&color=black&color=brown");

    api.queryFormUnexploded(colors);
    check("queryFormUnexploded", client->lastTarget,
          "/queryFormUnexploded?color=blue,black,brown");

    {
        auto role = std::make_shared<QueryFormObjectExpl_role_parameter>();
        role->setRole("admin");
        role->setFirstName("Alex");
        api.queryFormObjectExpl(role);
        check("queryFormObjectExpl", client->lastTarget,
              "/queryFormObjectExpl?role=admin&firstName=Alex");
    }

    api.querySpace(colors);
    check("querySpace", client->lastTarget,
          "/querySpace?color=blue%20black%20brown");

    api.queryPipe(colors);
    check("queryPipe", client->lastTarget,
          "/queryPipe?color=blue%7Cblack%7Cbrown");

    {
        auto c = std::make_shared<QueryDeep_color_parameter>();
        c->setR(100); c->setG(200); c->setB(150);
        api.queryDeep(c);
        check("queryDeep", client->lastTarget,
              "/queryDeep?color[R]=100&color[G]=200&color[B]=150");
    }

    api.queryReserved("a/b:c?d", "a/b:c?d");
    check("queryReserved", client->lastTarget,
          "/queryReserved?qres=a/b:c?d&qenc=a%2Fb%3Ac%3Fd");

    // allowEmptyValue=true keeps the empty string (qempty=); the non-
    // allowEmptyValue param with an empty value is omitted entirely.
    api.queryEmpty(boost::optional<std::string>(""), boost::optional<std::string>(""));
    check("queryEmpty", client->lastTarget, "/queryEmpty?qempty=");

    api.queryEsc("a b&c");
    check("queryEsc", client->lastTarget, "/queryEsc?qstr=a%20b%26c");

    api.pathSimple(colors);
    check("pathSimple", client->lastTarget, "/users/blue,black,brown");

    {
        auto a = std::make_shared<PathSimpleExpl_attrs_parameter>();
        a->setBlue("primary");
        a->setBlack("secondary");
        api.pathSimpleExpl(a);
        check("pathSimpleExpl", client->lastTarget,
              "/attrs/blue=primary,black=secondary");
    }

    api.pathLabel(colors);
    check("pathLabel", client->lastTarget, "/lab/.blue.black.brown");

    api.pathMatrix(colors);
    check("pathMatrix", client->lastTarget,
          "/mat/;color=blue,black,brown");

    api.pathMatrixExpl(colors);
    check("pathMatrixExpl", client->lastTarget,
          "/matx/;color=blue;color=black;color=brown");

    api.pathEscaped("a/b c");
    check("pathEscaped", client->lastTarget, "/pathEsc/a%2Fb%20c");

    api.headerSimple(colors);
    check("headerSimple", client->lastHeaders["X-Color"], "blue,black,brown");

    {
        auto a = std::make_shared<QueryDeep_color_parameter>();
        a->setR(100); a->setG(200); a->setB(150);
        api.headerExpl(a);
        check("headerExpl", client->lastHeaders["X-Attrs"], "R=100,G=200,B=150");
    }

    api.cookieForm(5, colors);
    check("cookieForm", client->lastHeaders["Cookie"],
          "id=5; color=blue,black,brown");

    api.cookieFormExpl(colors);
    check("cookieFormExpl", client->lastHeaders["Cookie"],
          "color=blue; color=black; color=brown");

    printf(g_failures == 0 ? "GOLDEN MATRIX PASS\n"
                           : "GOLDEN MATRIX FAIL (%d cells)\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
'''

SERVER_DRIVER = r'''
// Wave-5.2 golden driver: operation server precedence + variable defaults.
#include <cstdio>
#include <map>
#include <memory>
#include <string>
#include <utility>

#include "api/DefaultApi.h"

using namespace org::openapitools::client::api;

class RecordingClient : public HttpClient {
public:
    std::string lastTarget;
    std::pair<boost::beast::http::status, std::string>
    execute(const std::string&, const std::string& target,
            const std::string&,
            const std::map<std::string, std::string>&) override {
        lastTarget = target;
        return {boost::beast::http::status::ok, "{}"};
    }
};

static int g_failures = 0;

static void check(const char* name, const std::string& actual,
                  const std::string& expected) {
    if (actual == expected) {
        printf("CELL|%s|PASS|%s\n", name, actual.c_str());
    } else {
        ++g_failures;
        printf("CELL|%s|FAIL|expected=%s|actual=%s\n",
               name, expected.c_str(), actual.c_str());
    }
}

int main() {
    auto client = std::make_shared<RecordingClient>();
    // Default context: the FIRST root server's path (upstream basePath).
    DefaultApi api(client);

    // No per-op servers: the root server /v1 applies (context default).
    api.rootOnly();
    check("rootOnly", client->lastTarget, "/v1/rootOnly");

    // Operation-level server with variables: defaults substituted (ap/v3).
    api.varSelect();
    check("varSelect", client->lastTarget, "/v3/varSel");

    // Path-item server (trailing slash normalized): /v9/items.
    api.piServ();
    check("piServ", client->lastTarget, "/v9/items");

    // Path-item server, overridden by the operation server (origin-only URL
    // → empty path prefix): /items.
    api.piOverride();
    check("piOverride", client->lastTarget, "/items");

    // Relative operation server: /internal + /pets.
    api.opRel();
    check("opRel", client->lastTarget, "/internal/pets");

    // Origin-only operation server: empty path prefix.
    api.originOnly();
    check("originOnly", client->lastTarget, "/origin");

    printf(g_failures == 0 ? "SERVER MATRIX PASS\n"
                           : "SERVER MATRIX FAIL (%d cells)\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
'''


SECURITY_DRIVER = r'''
// Wave-5.3 golden driver (GC2): security metadata + pluggable credential hook.
#include <cstdio>
#include <map>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "api/DefaultApi.h"

using namespace org::openapitools::client::api;

class RecordingClient : public HttpClient {
public:
    std::map<std::string, std::string> lastHeaders;
    std::string lastTarget;
    std::pair<boost::beast::http::status, std::string>
    execute(const std::string&, const std::string& target,
            const std::string&,
            const std::map<std::string, std::string>& headers) override {
        lastTarget = target;
        lastHeaders = headers;
        return {boost::beast::http::status::ok, "{}"};
    }
};

static std::string groupText(const SecurityRequirementGroup& g) {
    std::string out = "(";
    for (const auto& u : g.ands) {
        out += u.type + ":" + u.schemeName + ":" + u.in + ":" + u.paramName
             + ":" + u.httpScheme + ":{";
        for (const auto& s : u.scopes) { out += s + ","; }
        out += "};";
    }
    out += ")";
    return out;
}

class HookedApi : public DefaultApi {
public:
    using DefaultApi::DefaultApi;
    std::vector<std::pair<std::string, std::string>> calls;
    bool injected = false;

    void applyOperationSecurity(
        const std::string& operationId,
        const std::vector<SecurityRequirementGroup>& requirements,
        std::string& target,
        std::map<std::string, std::string>& headers) override {
        std::string all;
        for (const auto& g : requirements) { all += groupText(g); }
        calls.emplace_back(operationId, all);
        // Demonstrate pluggability: attach the apiKey credentials.
        for (const auto& g : requirements) {
            for (const auto& u : g.ands) {
                if (u.type == "apiKey" && u.in == "header") {
                    headers.emplace(u.paramName, "k-" + u.schemeName);
                    injected = true;
                } else if (u.type == "apiKey" && u.in == "query") {
                    target += (target.find('?') == std::string::npos ? "?" : "&")
                            + u.paramName + "=k-" + u.schemeName;
                }
            }
        }
    }
};

static int g_failures = 0;

static void check(const char* name, bool ok, const std::string& detail) {
    if (ok) {
        printf("CELL|%s|PASS|%s\n", name, detail.c_str());
    } else {
        ++g_failures;
        printf("CELL|%s|FAIL|%s\n", name, detail.c_str());
    }
}

int main() {
    auto client = std::make_shared<RecordingClient>();
    HookedApi api(client);

    api.inheritedSecurity();
    check("inheritedSecurity", api.calls.size() == 1
            && api.calls[0].second == "(apiKey:apiKeyHeader:header:X-API-Key::{};)",
            api.calls.empty() ? "no hook call" : api.calls[0].second);
    check("inheritedCredential", client->lastHeaders.count("X-API-Key") > 0
            && client->lastHeaders["X-API-Key"] == "k-apiKeyHeader",
            "missing injected header");
    api.calls.clear();

    api.clearedSecurity();
    check("clearedSecurity", api.calls.empty(), "hook must not fire for security: []");
    api.calls.clear();

    api.anonymousAllowed();
    check("anonymousAllowed", api.calls.size() == 1
            && api.calls[0].second == "()(apiKey:apiKeyHeader:header:X-API-Key::{};)",
            api.calls.empty() ? "no hook call" : api.calls[0].second);
    api.calls.clear();

    api.andCombined();
    check("andCombined", api.calls.size() == 1
            && api.calls[0].second
               == "(http:basicAuth:::basic:{};apiKey:apiKeyHeader:header:X-API-Key::{};)",
            api.calls.empty() ? "no hook call" : api.calls[0].second);
    api.calls.clear();

    api.orAlternatives();
    check("orAlternatives", api.calls.size() == 1
            && api.calls[0].second
               == "(apiKey:apiKeyHeader:header:X-API-Key::{};)(apiKey:apiKeyQuery:query:api_key::{};)",
            api.calls.empty() ? "no hook call" : api.calls[0].second);
    check("orQueryCredential",
          client->lastTarget.find("api_key=k-apiKeyQuery") != std::string::npos,
          client->lastTarget);
    api.calls.clear();

    api.oauthScoped();
    check("oauthScoped", api.calls.size() == 1
            && api.calls[0].second == "(oauth2:oauth::::{read,write,};)",
            api.calls.empty() ? "no hook call" : api.calls[0].second);
    api.calls.clear();

    api.bearerOnly();
    check("bearerOnly", api.calls.size() == 1
            && api.calls[0].second == "(http:bearerAuth:::bearer:{};)",
            api.calls.empty() ? "no hook call" : api.calls[0].second);
    api.calls.clear();

    api.cookieKey();
    check("cookieKey", api.calls.size() == 1
            && api.calls[0].second == "(apiKey:apiKeyCookie:cookie:session::{};)",
            api.calls.empty() ? "no hook call" : api.calls[0].second);
    api.calls.clear();

    api.mutualTlsOnly();
    check("mutualTls", api.calls.size() == 1
            && api.calls[0].second
               == "(mutualTLS:mtls::::{};openIdConnect:oidc::::{};)",
            api.calls.empty() ? "no hook call" : api.calls[0].second);
    api.calls.clear();

    printf(g_failures == 0 ? "SECURITY MATRIX PASS\n"
                           : "SECURITY MATRIX FAIL (%d cells)\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
'''


def main():
    import glob
    import subprocess

    work = tempfile.mkdtemp(prefix="jsts-param-wire-")
    ok = True
    for name, spec_path, driver in (
            ("param", MATRIX, DRIVER),
            ("server", SERVER_MATRIX, SERVER_DRIVER),
            ("security", SECURITY_MATRIX, SECURITY_DRIVER)):
        gen_dir = os.path.join(work, name)
        r = sl.generate(JAR, spec_path, gen_dir)
        if r.returncode != 0:
            print("GENERATION FAILED (%s)" % name, file=sys.stderr)
            print(r.stderr[-1200:], file=sys.stderr)
            return 2
        main_path = os.path.join(work, name + "_main.cpp")
        with open(main_path, "w") as f:
            f.write(driver)
        sources = [os.path.join(gen_dir, "api", "DefaultApi.cpp")]
        for cpp in glob.glob(os.path.join(gen_dir, "model", "*.cpp")):
            base = os.path.basename(cpp)
            if base.startswith("HttpClientImpl"):
                continue
            sources.append(cpp)
        boost_json_src = os.path.join(SUITE_DIR, "..", "oas-compliance",
                                      "phase2-wiregen-build", "boost_json_src.cpp")
        cmd = ["g++", "-std=c++17", "-O0",
               "-I" + gen_dir, "-I" + os.path.join(gen_dir, "api"),
               "-I" + os.path.join(gen_dir, "model"),
               "-I" + os.path.join(SUITE_DIR, "..", "oas-compliance"),
               "-I/opt/homebrew/include",
               main_path] + sources + [boost_json_src]
        compiled = os.path.join(work, "run_" + name)
        cc = subprocess.run(cmd + ["-o", compiled], capture_output=True,
                            text=True, timeout=600)
        if cc.returncode != 0:
            print("COMPILE FAILED (%s)" % name, file=sys.stderr)
            print(cc.stderr[-1500:], file=sys.stderr)
            return 2
        run = subprocess.run([compiled], capture_output=True, text=True,
                             timeout=300)
        failures = 0
        passed = 0
        for ln in run.stdout.splitlines():
            if ln.startswith("CELL|"):
                parts = ln.split("|")
                if parts[2] == "PASS":
                    passed += 1
                else:
                    failures += 1
                    print("  " + ln, file=sys.stderr)
            elif ln.startswith(("GOLDEN", "SERVER")):
                print(ln)
        print("%s: %d cells, %d PASS, %d FAIL" % (name, passed + failures,
                                                  passed, failures))
        if failures:
            ok = False
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())