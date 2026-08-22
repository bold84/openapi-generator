# Wave-5.1 slice: parameter serialization matrix (GC1)

Slice commit: `791dfe5d0b4`.

## Scope (plan §5.1, gate GC1)

OAS 3.1 parameter serialization for the outbound client: location-style
defaults, `explode`, `allowReserved`, `allowEmptyValue`, schema-vs-content
query values, path style/template matching, header restrictions, cookie
params (previously a FeatureSet exclusion), and EXACT wire bytes — verified
through a recording-client runtime harness (no HTTP; the generated API's
`HttpClient` seam captures the target/headers/body the API would send).

## Golden matrix (committed `wave5/param-matrix.yaml` + `jsts_param_wire.py`)

All wire bytes below are asserted at runtime by the generated client
(`tools/jsts_param_wire.py` → GOLDEN MATRIX PASS, 19 cells):

| cell | spec | generated wire bytes |
|---|---|---|
| query form explode=true array | default | `/q?color=blue&color=black&color=brown` |
| query form explode=false array | csv | `/q?color=blue,black,brown` |
| query form explode=true object | | `/q?role=admin&firstName=Alex` (no base name) |
| query spaceDelimited | explode=false | `/q?color=blue%20black%20brown` |
| query pipeDelimited | explode=false | `/q?color=blue%7Cblack%7Cbrown` (RFC-3986-strict — the spec table's raw `\|` is not a legal query char; documented stance) |
| query deepObject | object | `/q?color[R]=100&color[G]=200&color[B]=150` |
| query allowReserved=true | string `a/b:c?d` | `qres=a/b:c?d` (raw) |
| query allowReserved=false | same value | `qenc=a%2Fb%3Ac%3Fd` |
| query allowEmptyValue=true | `""` | `qempty=` (present) |
| query no allowEmptyValue | `""` | omitted entirely |
| query string escaping | `a b&c` | `qstr=a%20b%26c` |
| path simple array | | `/users/blue,black,brown` |
| path simple explode=true object | | `/attrs/blue=primary,black=secondary` |
| path label array | | `/lab/.blue.black.brown` |
| path matrix array | | `/mat/;color=blue,black,brown` |
| path matrix explode=true array | | `/matx/;color=blue;color=black;color=brown` |
| path value escaping | `a/b c` | `/pathEsc/a%2Fb%20c` |
| header simple array | | `X-Color: blue,black,brown` |
| header explode=true object | | `X-Attrs: R=100,G=200,B=150` (previously threw at runtime) |
| cookie form explode=false array | | `Cookie: id=5; color=blue,black,brown` (previously SILENTLY DROPPED) |
| cookie form explode=true array | | `Cookie: color=blue; color=black; color=brown` (previously SILENTLY DROPPED) |

## Gaps closed

1. **Cookie parameters were silently dropped** (GH violation; the 5.10 Cookie
   exclusion). Cookie params now emit into the `Cookie` header with form-style
   serialization (`name=value` pairs joined `; `); empty array/string values
   are treated as absent.
2. **Object-valued params serialized as pointer text** (`?role=0x…`): the
   wire layer is now JSON-driven — `toJsonValue()` overloads (incl. the
   generated parameter/model classes' `toJsonValue()` and `std::map`)
   feed style-aware renderers.
3. **Path styles beyond simple were style-blind** (`/lab/blue,black,brown`):
   label (`.`-prefix + dot-join) and matrix (`;name=` / exploded repeats) now
   emit per spec; simple-exploded objects emit `k=v,k=v`.
4. **deepObject was absent** for model-class params (`?color=…` pointer):
   emits `name[k]=v&name[k2]=v2`.
5. **allowReserved was ignored** (both cells encoded): new
   `percentEncodeQueryReservedValue` keeps the RFC 3986 reserved set raw for
   `allowReserved=true` only.
6. **allowEmptyValue semantics missing**: without it, an empty string query
   value is omitted entirely; with it, `name=` is emitted.
7. **Header object values threw at runtime** (old fail-closed
   `serializeHeaderParameterValue` enable_if guard): the guard is gone;
   objects serialize as `k=v,…`.
8. **space/pipe wire delimiters**: elements are individually RFC-3986-encoded
   and joined with the encoded delimiter (`%20` / `%7C`); the raw space/pipe
   never appears on the wire.

## Changes

- `CppBoostBeastClientCodegen.java`
  - `fromParameter` rewritten: stamps EVERY parameter (all locations) with
    `x-codegen-param-style` (location defaults: query/cookie → form,
    path/header → simple), `x-codegen-param-explode` (form → true,
    otherwise false), `x-codegen-param-allow-reserved`, and
    `x-codegen-param-allow-empty-value`. The old
    query-collection-delimiter/multi/map-exploded/map-deep-object extension
    set and its `queryCollectionDelimiter` helper are deleted.
- `api-source.mustache` — JSON-driven serialization layer:
  `toJsonValue` overload family (`std::string`, arithmetic, `std::vector`,
  `std::map`, `std::shared_ptr` model classes, legacy fallback),
  `percentEncodeQueryReservedValue`, `appendParamQueryParameter` (form /
  spaceDelimited / pipeDelimited / deepObject × explode × allowReserved ×
  allowEmptyValue), `pathStyleValue` (simple / label / matrix × explode),
  style-aware `replacePathParameter(path, name, value, style, explode)`,
  `serializeHeaderParameterValue(value, explode)` (objects supported),
  `appendCookieParameter`. The old style-blind path/header serializers and
  the header throw-guard are deleted.
- `api-operation-source.mustache` — unified emissions in both the normal and
  streaming operation variants: query block uses the style-stamped appender,
  path block passes style/explode, header block passes explode, NEW cookie
  block builds the `Cookie` header.
- `wave5/param-matrix.yaml` — the committed 21-operation matrix spec.
- `tools/jsts_param_wire.py` — the committed golden gate: wrap → generate →
  compile (generated api + models + driver) → run; 19 cells asserted.
- `CppBoostBeastClientApiCodegenTest.java` — the OAS2-multi and
  parameter-serialization golden-source tests updated to the new emission
  shapes (incl. the removed header fail-closed guard).

## Evidence

```
GOLDEN MATRIX PASS — 19 cells, 19 PASS, 0 FAIL   (python3 tools/jsts_param_wire.py)
Tests run: 114, Failures: 0, Errors: 0           (generator JVM suite)
files=46 cases=1299 PASS=1299 FAIL=0 BLOCKED=0   (full JSTS corpus, batch)
```

## Gate status

- **GC1 (parameter style/explode matrix golden tests)**: the matrix golden
  gate + runtime recording-client harness PASS (this slice); the runtime mock
  HTTP endpoint tests (5.8) remain in a later Wave-5 slice.
- GC2 (security), GC3 (callbacks/webhooks), GC4 (content negotiation),
  GC5 (FeatureSet): planned.
- GH: the cookie silently-dropped path is closed; the ledger rows for
  param-style keywords land in the FeatureSet update slice (5.10).