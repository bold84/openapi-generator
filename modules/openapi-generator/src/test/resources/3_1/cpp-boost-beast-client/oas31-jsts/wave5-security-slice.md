# Wave-5.3 slice: pluggable security hooks (GC2)

Slice commit: `1b13360ac03`.

## Scope (plan §5.3, gate GC2)

apiKey/http/oauth2/openIdConnect/mutualTLS metadata + pluggable credential
hooks. OR alternatives, AND groups, `{}` anonymous alternative,
operation `security: []` (removes inheritance), root inheritance, and
scope-array rules — verified through the recording-client runtime harness.

## Behavior

- **Effective requirements** per operation: operation-level `security`
  (including `[]`, which clears all inheritance) > root-level `security`;
  no security anywhere → the hook is not invoked.
- **OR / AND / anonymous**: each entry of the effective security list is an
  OR alternative rendered as one `SecurityRequirementGroup`; the schemes
  inside one entry are AND requirements; an entry that is an empty map
  (the `{}` alternative) renders as an empty group (anonymous allowed).
- **Scheme metadata** per use: `type` (apiKey/http/oauth2/openIdConnect/
  mutualTLS/unknown), apiKey `in` (header/query/cookie) + `paramName`,
  http `scheme` (basic/bearer/custom), and the requirement's scopes array.
- **Pluggable hook**: generated operations call the virtual
  `applyOperationSecurity(operationId, requirements, target, headers)`
  before executing; the default is a no-op; subclasses override it to attach
  credentials. `target` is mutable for apiKey-in-query placement. The hook
  fires for both the normal and streaming operation variants.

## Goldens (committed `wave5/security-matrix.yaml` + gate CELLs, 11 cells)

| cell | spec | asserted hook payload |
|---|---|---|
| inheritedSecurity | root `[{apiKeyHeader: []}]`, no op security | 1 group: `apiKey:apiKeyHeader:header:X-API-Key` |
| inheritedCredential | (same op) | hook-injected `X-API-Key: k-apiKeyHeader` reaches the recorded request headers |
| clearedSecurity | op `security: []` | hook NOT invoked (no requirements) |
| anonymousAllowed | `[{}, {apiKeyHeader: []}]` | 2 groups: `()` then `(apiKey:…)` |
| andCombined | `[{basicAuth: [], apiKeyHeader: []}]` | 1 group, 2 AND uses: http:basic + apiKey:header |
| orAlternatives | `[{apiKeyHeader: []}, {apiKeyQuery: []}]` | 2 groups; hook-injected query `api_key=k-apiKeyQuery` lands in the target |
| oauthScoped | `[{oauth: [read, write]}]` | group with scopes `{read,write,}` |
| bearerOnly | `[{bearerAuth: []}]` | http:bearer metadata |
| cookieKey | `[{apiKeyCookie: []}]` | apiKey in=cookie name=session |
| mutualTls | `[{mtls: [], oidc: []}]` | 1 group, 2 ANDs: mutualTLS + openIdConnect |

## Changes

- `CppBoostBeastClientCodegen.java`
  - `effectiveSecurityGroups(op)` — per-op effective requirements as
    template-ready `List<List<Map<String,Object>>>` (groups → AND lists →
    scheme maps with name/type/in/paramName/httpScheme/scopes).
  - `postProcessOperationsWithModels` stamps `x-codegen-op-security-groups`
    + `x-codegen-op-has-security` per operation.
- `api-header.mustache` — `SecuritySchemeUse` / `SecurityRequirementGroup`
  structs + the virtual `applyOperationSecurity` hook (default no-op).
- `api-operation-source.mustache` — both operation variants emit the static
  per-op requirements table + the hook call before executing.
- `wave5/security-matrix.yaml` — the committed 9-operation security spec.
- `tools/jsts_param_wire.py` — third golden matrix (11 cells incl. the
  end-to-end injected-header/query checks).

## Evidence

```
GOLDEN MATRIX PASS — 19 cells / SERVER MATRIX PASS — 6 cells /
SECURITY MATRIX PASS — 11 cells           (python3 tools/jsts_param_wire.py)
Tests run: 114, Failures: 0               (generator JVM suite)
files=46 cases=1299 PASS=1299 FAIL=0 BLOCKED=0   (full JSTS corpus, batch)
```

## Gate status

- GC2 (security metadata + pluggable credential hooks, AND/OR, `{}`,
  `security: []`, inheritance, scopes): PASS per the matrix above.
- GC3 (callbacks/webhooks), GC4 (content negotiation), 5.8 runtime mock HTTP,
  GC5/FeatureSet (5.10) remain.