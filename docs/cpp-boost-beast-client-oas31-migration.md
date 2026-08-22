# Migrating to the OAS 3.1 cpp-boost-beast client

This guide covers what changed for users of the `cpp-boost-beast-client`
generator when a spec moves from OAS 2.0 / 3.0 to OAS 3.1, plus the
behavioral upgrades that land for 3.0 specs too.

## 1. Dialect: the validator is attached

Generated clients now ship a JSON Schema 2020-12 validator
(`oas31_validator.hpp` + supporting headers + the per-spec generated IR)
that implements the full required vocabulary. Implications:

- **Specs without schemas**: the validator adds a fixed ~2.2k lines to the
  generated tree. The binary cost is bounded — see the code-size audit in
  the Wave-6 slice — and it only grows per spec with the number of
  schemas.
- **3.0 semantics are interpreted through the 2020-12 lens** where OAS 3.1
  redefines them:
  - `exclusiveMinimum`/`exclusiveMaximum` are booleans in 3.0 and numbers
    in 3.1; the 3.0 boolean form is normalized, the 3.1 numeric form is
    validated exactly.
  - `type: "number"` accepts integers (and vice versa) under 2020-12's
    numeric equality; integer-valued floats remain valid `"integer"`.
  - `nullable: true` on a 3.0 spec keeps working (normalized to
    `anyOf [schema, null]`).
- **Numbers are exact**: no IEEE round-trip shortcuts (`exact_number`,
  decimal-fraction-based compare). `multipleOf` is exact, not
  floating-point-epsilon.
- **Unicode**: `minLength`/`maxLength`/`pattern` count Unicode code
  points; the pattern engine is unanchored per 2020-12 (with `^`/`$`
  assertions honored).

## 2. Composition and dynamic schemas

- `allOf`/`anyOf`/`oneOf`/`not` compose with full annotation
  propagation; `if`/`then`/`else`, `dependentSchemas`,
  `patternProperties`, `propertyNames` are supported.
- `unevaluatedProperties`/`unevaluatedItems` are evaluated per 2020-12
  (including through `$dynamicRef` recursion).
- `$ref` siblings are allowed (3.1) and evaluated: `$ref` no longer
  discards adjacent keywords.
- **`$dynamicRef`/`$dynamicAnchor`** resolve through the full dynamic
  scope (recursive schemas that `$ref` themselves behave correctly).
- Unknown/meta keywords fail closed at generation time with a ledgered
  diagnostic — never silently at runtime.

## 3. Parameter serialization (the visible wire change)

The old `collectionFormat`/implicit conventions are replaced by the OAS
3.1 style/explode matrix, fully JSON-driven:

| style | explode | example |
| --- | --- | --- |
| form (query/cookie) | true | `color=blue&color=black&color=brown` |
| form | false | `color=blue,black,brown` |
| spaceDelimited | false | `color=blue%20black%20brown` |
| pipeDelimited | false | `color=blue%7Cblack%7Cbrown` |
| deepObject | true | `color[R]=100&color[G]=200&color[B]=150` |
| simple (path/header) | false | `/path/blue,black,brown` |
| label | false | `/.blue.black.brown` |
| matrix | false | `/;color=blue,black,brown` |
| matrix | true | `/;color=blue;color=black;color=brown` |

- **Object query params** now serialize per style instead of printing the
  pointer.
- **`allowReserved`** is honored (raw reserved characters) vs
  percent-encoding of `:/?#[]@!$&'()*+,;=`.
- **Cookie parameters** are a single `Cookie` header joined with `; `
  (empty arrays are omitted).
- **`allowEmptyValue`**: with it, an empty value is sent; without it, an
  empty value omits the parameter.
- Query delimiters are RFC-3986-strict on the wire (`%20`, `%7C`).
- **Breaking**: if you relied on collection-format custom extensions
  (`x-codegen-query-collection-*`), migrate to the standard
  style/explode keywords. The legacy extension families were removed.

## 4. Servers and security

- **Servers + variables**: multi-server specs resolve the first root
  server, operation-level servers take precedence, relative server URLs
  are resolved against the root. Variables do not need to appear in every
  URL (absence is tolerated); defaults are substituted when present.
- **Security**: generated operations call a pluggable
  `applyOperationSecurity(operationId, requirements, target, headers)`
  hook before every request. The default is a no-op; subclass the API to
  attach credentials. `OR` alternatives are separate groups; `AND` within
  a group; `security: []` clears the root requirement for an operation.
  oauth2/openIdConnect are surfaced as scheme metadata (the flow itself
  stays on the caller).

## 5. Request bodies and responses

- **Media types**: JSON (`application/json` and `+json` suffixes, with or
  without `charset`), text, urlencoded, and multipart forms are emitted
  with the right content type and serialization. Response `Content-Type`
  is honored; suffix media types parse as JSON.
- **Response unions**: operations with multiple success shapes return a
  `status` + `headers` + `contentType` + `std::variant` body.
- **Response headers** are surfaced in the union `headers` map.

## 6. Reference Objects, callbacks, webhooks, links

- `$ref` works for parameters, requestBodies, responses, headers (not
  just schemas).
- **Webhooks are inbound-only metadata**: a client generator does not
  listen; the webhook names/operations are preserved as a visible
  diagnostic in the generated source. (This also fixes a silent
  generation bug where webhooks replaced the paths API.)
- Callbacks/links are preserved per-operation as comments with no
  automatic traversal or listener.

## 7. What did NOT change

- The `HttpClient`/`HttpClientImpl` transport contract, `execute` /
  `executeWithMetadata`, the response-body-limit and operation-timeout
  knobs.
- The model JSON round-trip API (`toJsonValue`/`fromJson`), `std::shared_ptr`
  model ownership, `boost::optional` for optional parameters.
- The base64/credential helpers and the `-Wall -Wextra -Werror`-clean
  emitted surface.

## 8. Regeneration checklist

1. Regenerate (the generator emits the validator + JSON serialization
   layer for 3.0 and 3.1 specs alike).
2. Re-run your integration tests against the C-profile wire goldens
   (`oas31-jsts/tools/jsts_param_wire.py`) if you customize serialization.
3. If you used `collectionFormat: multi` on query params: now form/explode
   (same wire bytes) — no change needed.
4. If you subclass `DefaultApi` for security: the new
   `applyOperationSecurity` hook replaces manual per-op header injection.
5. For 3.1 specs with dynamic recursive schemas: confirm the validator
   IR generated for your schemas carries the expected `$dynamicRef`
   anchors (diagnostics are emitted on failure).