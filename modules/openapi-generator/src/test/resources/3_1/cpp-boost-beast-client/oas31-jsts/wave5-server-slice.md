# Wave-5.2 slice: operation server precedence + variables (GC-5.2)

Slice commit: `c5ec12343e6`.

## Scope (plan §5.2)

Operation/Path-Item/root server precedence, `{variable}` substitution with
declared defaults, trailing-slash and origin-only URL normalization, and
document-relative (relative) server URLs — verified through the same
recording-client runtime harness as Wave-5.1 (exact target bytes).

## Behavior

- **Precedence**: operation `servers` > path-item `servers` > root `servers`.
  The generator resolves the effective server URL per operation and stamps it
  `x-codegen-op-server`; the C++ `operationServerPrefix(context, resolved)`
  helper joins it with the request path. No servers anywhere → the caller's
  context (constructor default) applies unchanged.
- **Parser-default filter**: swagger-parser injects an implicit
  `Server("/")` at operation, path-item AND document level when the spec
  declares none; `isParserDefaultServerList` treats that exact shape as
  "no override" so precedence falls through (mirrors the upstream
  basePath handling that yields `context = ""`).
- **Default selection**: the FIRST entry of the effective list is the
  default; user selection among multiple entries = the API constructor's
  context override (documented seam).
- **Variables**: `{name}` substituted with each variable's declared default
  at generation time. Enum members constrain the default per spec.
- **Normalization**: absolute URLs contribute only their path prefix (the
  HttpClientImpl owns scheme/host/port; a server host different from the
  caller's HttpClient configuration must be pointed at by the caller —
  documented seam); trailing slashes — including the lone root slash — are
  stripped; origin-only URLs (no path) yield the empty prefix; relative
  server URLs pass through unchanged (`/internal` + `/pets`).

## Goldens (committed `wave5/server-matrix.yaml`, asserted by
`tools/jsts_param_wire.py` → SERVER MATRIX PASS, 6 cells)

| cell | spec | generated target |
|---|---|---|
| rootOnly | root `/v1` (first root server; no op servers) | `/v1/rootOnly` |
| varSelect | op server `https://{region}.api.example.com/{ver}` (region default `ap`, ver default `v3`) | `/v3/varSel` |
| piServ | path-item server `https://pi.example.com/v9/` (trailing slash) | `/v9/items` |
| piOverride | op server `https://op.example.com/` (origin-only) overriding the PI server | `/items` |
| opRel | relative op server `/internal` | `/internal/pets` |
| originOnly | op server `https://origin.example.com` (no path) | `/origin` |

## Changes

- `CppBoostBeastClientCodegen.java`
  - `preprocessOpenAPI` captures `phaseOpenAPI` for the later phases.
  - `postProcessOperationsWithModels` stamps every operation with
    `x-codegen-op-server` (resolved effective URL) and exposes
    `x-codegen-has-models` for the API templates (the upstream `hasModels`
    flag is not populated for this generator's API context).
  - `resolveEffectiveServerUrl` (precedence + variable-default substitution),
    `operationFor` (PathItem-method lookup), `isParserDefaultServerList`.
- `api-source.mustache`
  - `serverPathPrefix` (scheme/authority strip, trailing-slash normalize)
    + `operationServerPrefix(context, resolved)`.
  - `using namespace <modelNamespace>;` now guarded by
    `x-codegen-has-models` — specs WITHOUT component schemas previously
    emitted an undeclared-namespace compile error (legal-OAS wart fixed).
- `api-operation-source.mustache` — both operation variants emit
  `operationServerPrefix(m_context, "<op-server>") + "<path>"`.
- `wave5/server-matrix.yaml` — the committed server matrix spec.
- `tools/jsts_param_wire.py` — extended into the single Wave-5 wire gate:
  param matrix (19 cells) + server matrix (6 cells).
- `CppBoostBeastClientApiCodegenTest.java` — path-line assertion updated to
  the `operationServerPrefix(...)` emission.

## Evidence

```
GOLDEN MATRIX PASS — 19 cells, 19 PASS      (param matrix, jsts_param_wire.py)
SERVER MATRIX PASS — 6 cells, 6 PASS       (server matrix)
Tests run: 114, Failures: 0                (generator JVM suite)
files=46 cases=1299 PASS=1299 FAIL=0 BLOCKED=0   (full JSTS corpus)
```

## Gate status

- GC1/GC-5.2: the parameter matrix + server precedence goldens PASS.
- Runtime mock HTTP endpoint tests (5.8), GC2 (security), GC3 (callbacks/
  webhooks), GC4 (content negotiation), GC5/FeatureSet (5.10) remain.<br>
  Documented seams (caller-owned): server host switching (HttpClientImpl
  config), user selection among multiple server entries (context override),
  non-leading-slash relative server URLs.