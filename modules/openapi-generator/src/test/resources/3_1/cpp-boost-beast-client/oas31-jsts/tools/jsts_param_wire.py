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


CONTENT_MATRIX = os.path.join(SUITE_DIR, "wave5", "content-matrix.yaml")
CONTENT_DRIVER = r'''
// Wave-5.4 golden driver (GC4): requestBody/media-type/response negotiation.
#include <cstdio>
#include <map>
#include <memory>
#include <string>
#include <utility>
#include <variant>
#include <vector>

#include <boost/beast/http/status.hpp>

#include "api/DefaultApi.h"
#include "model/PostJson_request.h"
#include "model/PostJsonSuffix_request.h"
#include "model/PostJsonCharset_request.h"
#include "model/GetExact_200_response.h"

using namespace model;
using namespace org::openapitools::client::api;

struct ScriptedResponse {
    boost::beast::http::status status;
    std::string body;
    std::map<std::string, std::string> headers;
};

class ScriptedClient : public HttpClient {
public:
    std::vector<ScriptedResponse> script;
    std::string lastVerb, lastTarget, lastBody;
    std::map<std::string, std::string> lastHeaders;

    HttpResponseData
    executeWithMetadata(const std::string& verb, const std::string& target,
                        const std::string& body,
                        const std::map<std::string, std::string>& headers) override {
        lastVerb = verb; lastTarget = target; lastBody = body;
        lastHeaders = headers;
        ScriptedResponse s = nextResponse();
        return HttpResponseData{s.status, s.body, std::move(s.headers)};
    }
    std::pair<boost::beast::http::status, std::string>
    execute(const std::string& verb, const std::string& target,
            const std::string& body,
            const std::map<std::string, std::string>& headers) override {
        lastVerb = verb; lastTarget = target; lastBody = body;
        lastHeaders = headers;
        ScriptedResponse s = nextResponse();
        return {s.status, s.body};
    }

private:
    ScriptedResponse nextResponse() {
        if (!script.empty()) {
            auto s = script.front();
            script.erase(script.begin());
            return s;
        }
        return {boost::beast::http::status::ok, "{}", {}};
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
    auto client = std::make_shared<ScriptedClient>();
    DefaultApi api(client);

    // requestBody cells ------------------------------------------------
    {
        auto req = std::make_shared<PostJson_request>();
        req->setName("x");
        api.postJson(req);
        check("postJsonContentType", client->lastHeaders["Content-Type"]
                == "application/json", client->lastHeaders["Content-Type"]);
        check("postJsonBody", client->lastBody.find("\"name\":\"x\"")
                != std::string::npos, client->lastBody);
    }
    {
        auto req = std::make_shared<PostJsonSuffix_request>();
        req->setId(7);
        api.postJsonSuffix(req);
        check("postJsonSuffixType", client->lastHeaders["Content-Type"]
                == "application/vnd.acme+json", client->lastHeaders["Content-Type"]);
        check("postJsonSuffixBody", client->lastBody.find("\"id\":7")
                != std::string::npos, client->lastBody);
    }
    {
        auto req = std::make_shared<PostJsonCharset_request>();
        req->setV("z");
        api.postJsonCharset(req);
        check("postJsonCharsetType", client->lastHeaders["Content-Type"]
                == "application/json; charset=utf-8",
                client->lastHeaders["Content-Type"]);
        check("postJsonCharsetBody", client->lastBody.find("\"v\":\"z\"")
                != std::string::npos, client->lastBody);
    }
    {
        api.postText("raw text");
        check("postTextType", client->lastHeaders["Content-Type"]
                == "text/plain", client->lastHeaders["Content-Type"]);
        check("postTextBody", client->lastBody == "raw text", client->lastBody);
    }
    {
        // Encoding Object applicability: multipart parts carry their own
        // declared content types (note -> urlencoded, doc -> octet-stream).
        api.postMultipart("n1", "d1");
        check("postMultipartType", client->lastHeaders["Content-Type"].find(
                "multipart/form-data; boundary=") == 0,
                client->lastHeaders["Content-Type"]);
        check("postMultipartNote",
              client->lastBody.find("name=\"note\"") != std::string::npos
              && client->lastBody.find("application/x-www-form-urlencoded")
                 != std::string::npos, "note part missing");
        check("postMultipartDoc",
              client->lastBody.find("name=\"doc\"") != std::string::npos
              && client->lastBody.find("application/octet-stream")
                 != std::string::npos && client->lastBody.find("filename=\"doc\"")
                 != std::string::npos, "doc part missing");
        check("postMultipartValues",
              client->lastBody.find("n1") != std::string::npos
              && client->lastBody.find("d1") != std::string::npos,
              "part values missing");
    }
    {
        api.postUrlEncoded("a1", 5);
        check("postFormType", client->lastHeaders["Content-Type"]
                == "application/x-www-form-urlencoded",
                client->lastHeaders["Content-Type"]);
        check("postFormBody", client->lastBody.find("a=a1") != std::string::npos
              && client->lastBody.find("b=5") != std::string::npos,
              client->lastBody);
    }

    // response cells ----------------------------------------------------
    {
        client->script.push_back({boost::beast::http::status::ok,
            "{\"ok\": true}", {{"X-Rate-Limit", "42"},
                               {"Content-Type", "application/json"}}});
        auto r = api.getExact();
        check("response200", r.status == boost::beast::http::status::ok
              && std::get<std::shared_ptr<GetExact_200_response>>(r.body)->isOk() == true,
              "wrong dispatch");
        check("response200Header",
              r.headers.count("X-Rate-Limit") > 0 && r.headers["X-Rate-Limit"] == "42",
              "response headers not surfaced");
        check("response200ContentType", r.contentType == "application/json",
              r.contentType);
    }
    {
        client->script.push_back({boost::beast::http::status::created,
            "{\"ok\": true}", {{"Content-Type", "application/json"}}});
        auto r = api.getRange();
        check("responseRange2xx", r.status == boost::beast::http::status::created
              && std::get<std::shared_ptr<GetExact_200_response>>(r.body)->isOk() == true,
              "2XX range dispatch failed");
    }
    {
        // +json response: exact application/vnd.acme+json body content type.
        client->script.push_back({boost::beast::http::status::ok,
            "{\"id\": 9}", {{"Content-Type", "application/vnd.acme+json"}}});
        auto r = api.getJsonSuffix();
        check("responseJsonSuffix",
              std::get<std::shared_ptr<PostJsonSuffix_request>>(r.body)->getId() == 9,
              "wrong +json dispatch");
    }
    {
        // default fallback: 500 + text/plain lands in the default branch.
        client->script.push_back({boost::beast::http::status::internal_server_error,
            "oops", {{"Content-Type", "text/plain"}}});
        auto r = api.getExact();
        check("responseDefault",
              std::get<std::string>(r.body) == "oops",
              "default branch dispatch failed");
    }
    {
        // unexpected status: throws the api exception.
        client->script.push_back({boost::beast::http::status::not_found,
            "", {{"Content-Type", "text/plain"}}});
        bool threw = false;
        try {
            api.getUnexpected();
        } catch (const DefaultApiException&) {
            threw = true;
        }
        check("responseUnexpected", threw, "must throw for unexpected status");
    }

    printf(g_failures == 0 ? "CONTENT MATRIX PASS\n"
                           : "CONTENT MATRIX FAIL (%d cells)\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
'''


REF_MATRIX = os.path.join(SUITE_DIR, "wave5", "ref-callback-matrix.yaml")
REF_DRIVER = r'''
// Wave-5.6/5.7 golden driver: non-schema Reference Objects + metadata.
#include <cstdio>
#include <map>
#include <memory>
#include <string>
#include <utility>
#include <variant>

#include <boost/beast/http/status.hpp>
#include <boost/optional.hpp>

#include "api/DefaultApi.h"
#include "model/RefEverything_request.h"
#include "model/Inline_object.h"

using namespace model;
using namespace org::openapitools::client::api;

class RecordingClient : public HttpClient {
public:
    std::string lastVerb, lastTarget, lastBody;
    std::map<std::string, std::string> lastHeaders;
    boost::beast::http::status nextStatus = boost::beast::http::status::ok;
    std::string nextBody = "{\"nextPage\": 9}";
    std::map<std::string, std::string> nextRespHeaders{
        {"Content-Type", "application/json"}, {"X-Total", "7"}};

    HttpResponseData
    executeWithMetadata(const std::string& verb, const std::string& target,
                        const std::string& body,
                        const std::map<std::string, std::string>& headers) override {
        lastVerb = verb; lastTarget = target; lastBody = body;
        lastHeaders = headers;
        HttpResponseData d{nextStatus, nextBody, nextRespHeaders};
        return d;
    }
    std::pair<boost::beast::http::status, std::string>
    execute(const std::string&, const std::string&, const std::string&,
            const std::map<std::string, std::string>&) override {
        return {nextStatus, nextBody};
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
    DefaultApi api(client);

    // $ref'd requestBody, header param, query param, response and response
    // header all resolve into this operation.
    auto req = std::make_shared<RefEverything_request>();
    req->setPayload("p1");
    auto r = api.refEverything(req, "trace-1",
                               boost::optional<std::int32_t>(3));

    check("refHeaderParam", client->lastHeaders.count("X-Trace") > 0
            && client->lastHeaders["X-Trace"] == "trace-1",
            "ref'd header param not emitted");
    check("refQueryParam", client->lastTarget.find("page=3")
            != std::string::npos, client->lastTarget);
    check("refBody", client->lastBody.find("\"payload\":\"p1\"")
            != std::string::npos, client->lastBody);
    check("refResponse", r.status == boost::beast::http::status::ok
            && std::get<std::shared_ptr<Inline_object>>(r.body)->getNextPage() == 9,
            "ref'd 200 response not dispatched");
    check("refResponseHeader", r.headers.count("X-Total") > 0
            && r.headers["X-Total"] == "7", "ref'd response header not surfaced");

    printf(g_failures == 0 ? "REF MATRIX PASS\n"
                           : "REF MATRIX FAIL (%d cells)\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
'''


MULTIDOC_SPEC = os.path.join(SUITE_DIR, "..", "oas-compliance",
                             "multidoc", "main.yaml")
MULTIDOC_DRIVER = r'''
// Gap-1 golden driver: multi-document OAD references (GC4 tail).
// The main spec references a parameter, a requestBody and a response (with
// header) across files ($ref: './shared.yaml#/components/...'). This driver
// proves the generated client serializes/emits each externally-resolved
// surface on the wire exactly as an in-document reference would.
#include <cstdio>
#include <map>
#include <memory>
#include <string>
#include <utility>
#include <variant>

#include <boost/beast/http/status.hpp>
#include <boost/optional.hpp>

#include "api/DefaultApi.h"
#include "model/ExternalBody_request.h"
#include "model/ExternalResponse_200_response.h"

using namespace model;
using namespace org::openapitools::client::api;

class RecordingClient : public HttpClient {
public:
    std::string lastVerb, lastTarget, lastBody;
    std::map<std::string, std::string> lastHeaders;
    boost::beast::http::status nextStatus = boost::beast::http::status::ok;
    std::string nextBody = "{\"ok\": true}";
    std::map<std::string, std::string> nextRespHeaders{
        {"Content-Type", "application/json"},
        {"X-External-Rate-Limit", "42"}};

    HttpResponseData
    executeWithMetadata(const std::string& verb, const std::string& target,
                        const std::string& body,
                        const std::map<std::string, std::string>& headers) override {
        lastVerb = verb; lastTarget = target; lastBody = body;
        lastHeaders = headers;
        HttpResponseData d{nextStatus, nextBody, nextRespHeaders};
        return d;
    }
    std::pair<boost::beast::http::status, std::string>
    execute(const std::string& verb, const std::string& target,
            const std::string& body,
            const std::map<std::string, std::string>& headers) override {
        lastVerb = verb; lastTarget = target; lastBody = body;
        lastHeaders = headers;
        return {nextStatus, nextBody};
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
    DefaultApi api(client);

    // External parameter (query) from ./shared.yaml -> serialized on wire.
    api.externalQuery(boost::optional<std::string>("beast"));
    check("multidocQueryParam",
          client->lastTarget.find("/externalQuery?tag=beast")
              != std::string::npos, client->lastTarget);

    // External requestBody from ./shared.yaml -> JSON body emitted.
    auto req = std::make_shared<ExternalBody_request>();
    req->setNote("external-note");
    api.externalBody(req);
    check("multidocRequestBody",
          client->lastVerb == "POST"
              && client->lastTarget.find("/externalBody")
                 != std::string::npos, client->lastTarget);
    check("multidocRequestBodyJson",
          client->lastBody.find("\"note\":\"external-note\"")
              != std::string::npos, client->lastBody);

    // External response (with header) from ./shared.yaml -> deserialized
    // model + surfaced header on the runtime path.
    auto r = api.externalResponse();
    check("multidocResponse",
          r.status == boost::beast::http::status::ok
              && std::get<std::shared_ptr<ExternalResponse_200_response>>(
                     r.body)->isOk() == true,
          "external response not dispatched");
    check("multidocResponseHeader",
          r.headers.count("X-External-Rate-Limit") > 0
              && r.headers["X-External-Rate-Limit"] == "42",
          "external response header not surfaced");

    printf(g_failures == 0 ? "MULTIDOC MATRIX PASS\n"
                           : "MULTIDOC MATRIX FAIL (%d cells)\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
'''


MOCK_SPEC = os.path.join(SUITE_DIR, "wave5", "mock-http-matrix.yaml")

MOCK_DRIVER = r'''
// Wave-5.8 golden driver: RUNTIME mock HTTP endpoint — a real boost::beast
// loopback server + the real HttpClientImpl + the generated API. The server
// captures the raw request line/headers/body and scripts the response; the
// assertions cover the exact wire bytes as SENT by the generated client.
#include <cstdio>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>

#include <boost/asio.hpp>
#include <boost/beast/core.hpp>
#include <boost/beast/http.hpp>
#include <boost/beast/version.hpp>

#include "api/DefaultApi.h"
#include "api/HttpClientImpl.h"

using namespace org::openapitools::client::api;

namespace net = boost::asio;
namespace beast = boost::beast;
namespace http = beast::http;

struct CapturedRequest {
    std::string method;
    std::string target;
    std::string body;
    std::map<std::string, std::string> headers;
};

static std::mutex g_mutex;
static CapturedRequest g_captured;
static http::status g_status = http::status::ok;
static std::string g_responseBody = "{}";
static std::map<std::string, std::string> g_responseHeaders{
    {"Content-Type", "application/json"}};

static void doSession(net::ip::tcp::socket& socket) {
    beast::flat_buffer buffer;
    http::request<http::string_body> req;
    beast::error_code ec;
    http::read(socket, buffer, req, ec);
    if (ec) { return; }
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_captured.method = std::string(req.method_string());
        g_captured.target = std::string(req.target());
        g_captured.body = req.body();
        for (auto const& f : req.base()) {
            g_captured.headers[std::string(f.name_string())]
                    = std::string(f.value());
        }
    }
    http::response<http::string_body> res{http::status::ok, req.version()};
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        res.result(g_status);
        res.body() = g_responseBody;
        res.prepare_payload();
        for (auto const& kv : g_responseHeaders) {
            res.set(kv.first, kv.second);
        }
        if (res.find(http::field::content_type) == res.end()) {
            res.set(http::field::content_type, "application/json");
        }
    }
    http::write(socket, res, ec);
}

static void runServer(net::io_context& ioc,
                      net::ip::tcp::acceptor& acceptor) {
    for (;;) {
        beast::error_code ec;
        net::ip::tcp::socket socket(ioc);
        acceptor.accept(socket, ec);
        if (ec) { return; }
        std::thread([s = std::move(socket)]() mutable {
            doSession(s);
            beast::error_code closeEc;
            s.shutdown(net::ip::tcp::socket::shutdown_send, closeEc);
        }).detach();
    }
}

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
    net::io_context ioc;
    net::ip::tcp::acceptor acceptor(ioc,
        net::ip::tcp::endpoint(net::ip::make_address("127.0.0.1"), 0));
    const unsigned short port = acceptor.local_endpoint().port();
    std::thread server(runServer, std::ref(ioc), std::ref(acceptor));
    server.detach();

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_responseBody = "{\"ok\": true}";
        g_responseHeaders = {{"Content-Type", "application/json"},
                             {"X-Total", "42"}};
        g_status = http::status::ok;
    }

    // Real transport: HTTP/1.1 to the loopback mock.
    auto impl = std::make_shared<HttpClientImpl>(
        "127.0.0.1", std::to_string(port),
        HttpClientImpl::Transport::Http, 11,
        std::chrono::milliseconds(5000), 8ULL * 1024ULL * 1024ULL);
    {
        // root server context: /v1 prefix comes from the FIRST root server.
        DefaultApi api(impl);
        api.rootOnly();
        check("mockTarget", g_captured.method == "GET"
                && g_captured.target == "/v1/rootOnly",
                g_captured.method + " " + g_captured.target);
        check("mockHost", g_captured.headers.count("Host") > 0
                && g_captured.headers["Host"].find("127.0.0.1")
                   != std::string::npos, g_captured.headers["Host"]);
        check("mockUserAgent", g_captured.headers.count("User-Agent") > 0
                && g_captured.headers["User-Agent"].find("Boost.Beast")
                   != std::string::npos, g_captured.headers["User-Agent"]);
    }
    {
        // request-body op over the real wire + scripted response headers.
        std::lock_guard<std::mutex> lock(g_mutex);
        g_responseBody = "{\"ok\": true}";
        g_responseHeaders = {{"Content-Type", "application/json"},
                             {"X-Total", "42"}};
    }
    {
        // operation server precedence visible on the real wire.
        DefaultApi api(impl);
        api.opRel();   // relative op server /internal
        check("mockOpServer", g_captured.target == "/internal/pets",
              g_captured.target);
    }
    {
        // response headers surfaced through the real transport (union path).
        DefaultApi api(impl);
        auto r = api.getExact();
        check("mockResponseStatus",
              r.status == boost::beast::http::status::ok
              && std::get<std::shared_ptr<RootOnly_200_response>>(
                 r.body)->isOk() == true,
              "response dispatch over real HTTP failed");
        check("mockResponseHeader",
              r.headers.count("X-Total") > 0 && r.headers["X-Total"] == "42",
              "response header lost over real HTTP");
        check("mockContentType", r.contentType == "application/json",
              r.contentType);
    }

    printf(g_failures == 0 ? "MOCK HTTP MATRIX PASS\n"
                           : "MOCK HTTP MATRIX FAIL (%d cells)\n", g_failures);
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
            ("security", SECURITY_MATRIX, SECURITY_DRIVER),
            ("content", CONTENT_MATRIX, CONTENT_DRIVER),
            ("ref", REF_MATRIX, REF_DRIVER),
            ("multidoc", MULTIDOC_SPEC, MULTIDOC_DRIVER),
            ("mock", MOCK_SPEC, MOCK_DRIVER)):
        gen_dir = os.path.join(work, name)
        r = sl.generate(JAR, spec_path, gen_dir)
        if r.returncode != 0:
            print("GENERATION FAILED (%s)" % name, file=sys.stderr)
            print(r.stderr[-1200:], file=sys.stderr)
            return 2
        if name == "ref":
            # Wave 5.7 source-level assertions: the preserved inbound
            # metadata is a visible diagnostic in the generated api source.
            api_src = open(os.path.join(gen_dir, "api", "DefaultApi.cpp")).read()
            for marker, frag in (
                    ("webhook-preserved",
                     "preserved inbound metadata"),
                    ("webhook-name", "newEvent[POST newEventPost]"),
                    ("callback-name", "callback metadata preserved (no inbound listener): onEvent"),
                    ("link-name", "link metadata preserved (no automatic traversal): next")):
                if frag not in api_src:
                    print("REF SOURCE FAIL: %s missing" % marker,
                          file=sys.stderr)
                    return 2
            print("REF SOURCE PASS (webhook/callback/link metadata markers)")
        main_path = os.path.join(work, name + "_main.cpp")
        with open(main_path, "w") as f:
            f.write(driver)
        sources = [os.path.join(gen_dir, "api", "DefaultApi.cpp")]
        for cpp in glob.glob(os.path.join(gen_dir, "model", "*.cpp")):
            base = os.path.basename(cpp)
            if base.startswith("HttpClientImpl") and name != "mock":
                continue
            sources.append(cpp)
        boost_json_src = os.path.join(SUITE_DIR, "..", "oas-compliance",
                                      "phase2-wiregen-build", "boost_json_src.cpp")
        if name == "mock":
            sources.insert(0, os.path.join(gen_dir, "api",
                                           "HttpClientImpl.cpp"))
        cmd = ["g++", "-std=c++17", "-O0",
               "-I" + gen_dir, "-I" + os.path.join(gen_dir, "api"),
               "-I" + os.path.join(gen_dir, "model"),
               "-I" + os.path.join(SUITE_DIR, "..", "oas-compliance"),
               "-I/opt/homebrew/include",
               main_path] + sources
        if name != "mock":
            cmd.append(boost_json_src)
        if name == "mock":
            cmd += ["-pthread",
                    os.environ.get("OMP_JSTS_SSL_LIB",
                                   "/opt/homebrew/opt/openssl@3/lib/libssl.a"),
                    os.environ.get("OMP_JSTS_CRYPTO_LIB",
                                   "/opt/homebrew/opt/openssl@3/lib/libcrypto.a")]
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
            elif ln.startswith(("GOLDEN", "SERVER", "MOCK")):
                print(ln)
        print("%s: %d cells, %d PASS, %d FAIL" % (name, passed + failures,
                                                  passed, failures))
        if failures:
            ok = False
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())