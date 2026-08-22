# Wave-5.8/5.10 slice: runtime mock HTTP tests + FeatureSet (GC1..GC3 close)

Slice commit: `10fa1d218ea`.

## Scope (plan §5.8/5.10, gate 5.8 + FeatureSet)

Close Wave 5: REAL loopback HTTP execution of the generated client (not
only source goldens), and the FeatureSet metadata reflecting everything the
C profile now delivers.

## Behavior

- **Runtime mock HTTP (5.8)**: a boost::beast HTTP/1.1 server bound to an
  ephemeral 127.0.0.1 port inside the golden driver; the REAL
  `HttpClientImpl` (HTTP transport; NOT the recording client) plus the
  generated `DefaultApi` talk over actual sockets. The server captures the
  raw request line/headers/body and scripts the response; assertions cover
  the exact wire bytes sent by the generated client.
  - The driver links the impl + OpenSSL (homebrew keg) + pthreads; the impl
    TU carries the boost::json definitions, so the separate
    `boost_json_src.cpp` TU is omitted for the mock build (duplicate
    symbols otherwise).
- **FeatureSet (5.10)**: exclusions removed as their gates passed —
  `ParameterStyling` (5.1), `MultiServer` (5.2), `Callbacks`/`LinkObjects`
  (5.7, preserved metadata), `ParameterFeature.Cookie` (5.1). Only
  `XMLStructureDefinitions` stays excluded (no XML content keywords for a
  JSON wire client). `securityFeatures` remain empty by design (hooks are
  pluggable, 5.3). JVM test asserts the delivered set.

## Goldens (`mock-http-matrix.yaml` + gate: MOCK HTTP MATRIX PASS, 7 cells)

| cell | asserted |
|---|---|
| mockTarget | real transport sent `GET /v1/rootOnly` (root server prefix applied) |
| mockHost | `Host` header carries the loopback address |
| mockUserAgent | `User-Agent` carries Boost.Beast |
| mockOpServer | operation-level `servers: /internal` wins on the real wire (`/internal/pets`) |
| mockResponseStatus | scripted 200 dispatched into the union body (`ok == true`) |
| mockResponseHeader | `X-Total: 42` response header surfaced through the real transport |
| mockContentType | `contentType == application/json` |

## Changes

- `CppBoostBeastClientCodegen.java` — FeatureSet builder: the four
  global-feature exclusions removed + included; `ParameterFeature.Cookie`
  included.
- `tools/jsts_param_wire.py` — sixth matrix: mock loopback server + real
  transport driver; keg OpenSSL link; per-matrix source handling.
- `wave5/mock-http-matrix.yaml` — the committed mock spec (root server
  `/v1`, op server `/internal`, union op with response header).
- `CppBoostBeastClientCodegenTest.java` —
  `featureSetReflectsWave5Deliverables` (parameter styling / multi-server /
  callbacks / links / cookie in the delivered set; XML stays excluded).

## Evidence

```
GOLDEN — 19/19 / SERVER — 6/6 / SECURITY — 11/11 / CONTENT — 21/21 /
REF — 5/5 + REF SOURCE PASS / MOCK HTTP MATRIX PASS — 7/7 (real sockets)
      (python3 tools/jsts_param_wire.py)
Tests run: 115, Failures: 0  (JVM suite incl. the two new tests; isolated reruns of both: 1/1 each)
files=46 cases=1299 PASS=1299 FAIL=0 BLOCKED=0   (full JSTS corpus, batch)
```

## Gate status

- Wave 5 complete: 5.1 parameter matrix (19/19), 5.2 servers (6/6), 5.3
  security hooks (11/11), 5.4 requestBody/media types (21/21), 5.6 refs +
  5.7 metadata (5/5 + source markers), 5.8 runtime mock (7/7 over real
  HTTP), 5.10 FeatureSet delivered.
- Remaining: Wave 6 (hardening/code-size audit, sample regen, migration
  guide + conformance report, CI promotion).