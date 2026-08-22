# Wave-5.4 slice: RequestBody / responses / media types (GC4)

Slice commit: `4149a8e029d`.

## Scope (plan §5.4/5.5, gate GC4)

requestBody handling (required/optional, exact/subtype-wildcard/`*/*`
matching with parameters ignored, the `+json` decoder convention, unexpected
content fallbacks), Encoding Object applicability (multipart/urlencoded),
response exact/range/default precedence, response headers surfaced to the
caller, optional/no-body responses, and the unexpected-status policy —
verified end-to-end with a scripted recording client.

## Goldens (committed `wave5/content-matrix.yaml`, gate CELLs: 21 cells)

### Request side

| cell | spec | asserted wire |
|---|---|---|
| postJson | `application/json` body, optional | `Content-Type: application/json` + JSON body |
| postJsonSuffix | `application/vnd.acme+json` body, required | `Content-Type: application/vnd.acme+json` + JSON body (the `+json` decoder convention) |
| postJsonCharset | `application/json; charset=utf-8` | the FULL string with parameters in the Content-Type (matching ignores them) |
| postText | `text/plain` raw | `Content-Type: text/plain` + raw body |
| postMultipart | multipart + Encoding Object | `multipart/form-data; boundary=…`; the `note` part carries `application/x-www-form-urlencoded` and the `doc` part `application/octet-stream` + `filename=` (Encoding Object applicability) |
| postUrlEncoded | urlencoded | `Content-Type: application/x-www-form-urlencoded` + `a=a1&b=5` |

### Response side

| cell | spec | asserted handling |
|---|---|---|
| response200 | exact 200 + declared header | union status/body + `X-Rate-Limit: 42` surfaced in the union's `headers` |
| responseRange2xx | `2XX` range + default | 201 dispatched to the range branch |
| responseJsonSuffix | `application/vnd.acme+json` 200 | `+json` response deserialized as the JSON model |
| responseDefault | exact 200 + default | 500 + `text/plain` lands in the default branch (string body) |
| responseUnexpected | single 200 only | 404 throws `DefaultApiException` (unexpected-status policy) |

## Changes

- `api-header.mustache` — the response-union struct gains a
  `std::map<std::string,std::string> headers;` member (response headers were
  previously dropped at the union boundary).
- `api-operation-source.mustache` — the union path captures the response
  headers from `executeWithMetadata` into `responseHeaders` and fills the
  union member (copy — the content-type detection loop still reads them).
- `wave5/content-matrix.yaml` — the committed 10-operation content spec.
- `tools/jsts_param_wire.py` — fourth golden matrix (21 cells) with a
  scripted client (per-call status/body/headers queue; records request
  target/body/headers on BOTH execute paths).

Existing machinery re-verified (no codegen changes needed): content-map
matching with parameters ignored (`normalizeMediaType` strips `;…`), exact >
subtype-wildcard > `*/*` dispatch on the response side, `+json` detection,
multipart/urlencoded serialization, unexpected-status exceptions.

## Evidence

```
GOLDEN MATRIX PASS — 19 cells / SERVER MATRIX PASS — 6 cells /
SECURITY MATRIX PASS — 11 cells / CONTENT MATRIX PASS — 21 cells
                   (python3 tools/jsts_param_wire.py)
Tests run: 114, Failures: 0               (generator JVM suite)
files=46 cases=1299 PASS=1299 FAIL=0 BLOCKED=0   (full JSTS corpus, batch)
```

## Gate status

- GC4 request/response surfaces: PASS per the matrix (incl. the response
  header surfacing gap fixed). XML wire binding remains excluded per 5.9
  (structured-XML emits the documented unsupported-codec diagnostic).
- GC3 (callbacks/webhooks), 5.6 Reference Objects, 5.8 runtime mock HTTP,
  GC5/FeatureSet (5.10) remain.