# Wave-5.6/5.7 slice: non-schema Reference Objects + callbacks/webhooks/links (GC3)

Slice commit: `ff80a04b4a8`.

## Scope (plan §5.6/5.7, gate GC3)

Non-schema Reference Objects (parameters, requestBodies, responses, headers)
for the outbound client; callbacks/webhooks/links preserved as metadata with
visible diagnostics and NO inbound listener / automatic Link traversal.

## Behavior

- **Non-schema refs** (5.6): `$ref`-ed parameters (header/query), requestBody,
  responses and response headers resolve into the generated operation —
  identical wire behavior to inline definitions (verified at runtime).
- **Webhooks** (5.7): webhooks are inbound-only metadata for a client
  generator. The upstream pipeline folds them into the api map under the same
  fallback classname as the paths operations — SILENTLY REPLACING the paths
  api (a spec with webhooks lost ALL its path operations, a GH violation).
  The codegen now: snapshots the webhook metadata
  (`name[ METHOD operationId ]` list), strips them from generation, and
  emits the preserved list into the generated api source as a visible
  diagnostic. No inbound listener is generated.
- **Callbacks/links** (5.7): per-operation callback/link names are captured
  from the RAW spec (the swagger-models Operation exposes callbacks but NOT
  links) and emitted as per-operation comments in the generated source.
- **Multi-document OAD resolution**: external $ref fetching is a runner/
  CLI-level concern (the JSTS remotes vault); internal cross-component refs
  are the golden surface here.

## Goldens (`wave5/ref-callback-matrix.yaml` + gate: REF SOURCE PASS + 5 cells)

| cell | asserted |
|---|---|
| refHeaderParam | the `$ref`-ed header parameter emits `X-Trace: trace-1` |
| refQueryParam | the `$ref`-ed query parameter emits `page=3` |
| refBody | the `$ref`-ed requestBody serializes `{"payload":"p1"}` |
| refResponse | the `$ref`-ed 200 response dispatches into the union body (Inline_object.nextPage == 9) |
| refResponseHeader | the `$ref`-ed `X-Total` response header surfaced in the union `headers` |
| REF SOURCE (python side) | generated source contains: the webhook preservation marker (`newEvent[POST newEventPost]`), the callback marker (`onEvent`), and the link marker (`next`) |

## Changes

- `CppBoostBeastClientCodegen.java`
  - `preprocessOpenAPI`: webhook snapshot (`webhookPreservation`) + strip
    (fixes the paths-api overwrite); `captureRawOperationMetadata` reads the
    raw input spec (SnakeYAML) for per-op callbacks/links names (the model
    lacks a links accessor).
  - `postProcessOperationsWithModels`: per-op `x-codegen-op-callbacks` /
    `x-codegen-op-links` stamps + `x-codegen-webhook-metadata` on the
    operations map.
  - `getWebhookPreservation()` public accessor (test face).
- `api-source.mustache` — the Webhook-5.7 preservation diagnostic comment.
- `api-operation-source.mustache` — per-operation callback/link preservation
  comments (both operation variants).
- `wave5/ref-callback-matrix.yaml` — the committed ref/callback/webhook spec.
- `tools/jsts_param_wire.py` — fifth golden matrix + the python-side source
  marker assertions.
- `CppBoostBeastClientCodegenTest.java` —
  `webhooksArePreservedAndDoNotSuppressPathOperations` (metadata captured,
  webhooks stripped, registered against the model).

## Evidence

```
GOLDEN MATRIX PASS — 19 / SERVER — 6 / SECURITY — 11 / CONTENT — 21 /
REF — 5 + REF SOURCE PASS      (python3 tools/jsts_param_wire.py)
Tests run: 115, Failures: 0  (JVM suite, incl. the new webhook test)
files=46 cases=1299 PASS=1299 FAIL=0 BLOCKED=0   (full JSTS corpus, batch)
```

## Gate status

- GC3 (callbacks/webhooks preserved with visible diagnostics; no inbound
  listener; links preserved without automatic traversal): PASS.
- 5.8 runtime mock HTTP endpoints + FeatureSet (5.10) remain; Wave 6.