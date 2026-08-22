# Gap-1 slice report — multi-document OAD references (GC4 tail)

Commit: `8781a1a8cb1` (fixtures + wire gate + driver fix + conformance doc
wording) · re-verified at the gap-closure battery HEAD (`1ebf60d141d`).

## Finding (plan §3, Gap 1)

The C-profile claim covered intra-document non-schema `$ref`; external
`$ref` **across files** for parameters, requestBodies, responses and response
headers was explicitly deferred ("internal cross-component refs are the
golden surface"). The C-profile wording was scoped to single-document specs.

## Deliverables

1. **Fixture pair** `oas-compliance/multidoc/`:
   - `shared.yaml` — `components.parameters.ExternalQueryParam`
     (query, string), `components.requestBodies.ExternalRequestBody`
     (application/json), `components.responses.ExternalOkResponse`
     (JSON body + `X-External-Rate-Limit` header).
   - `main.yaml` — operations referencing each across files:
     `$ref: './shared.yaml#/components/parameters/ExternalQueryParam'`,
     `$ref: './shared.yaml#/components/requestBodies/ExternalRequestBody'`,
     `$ref: './shared.yaml#/components/responses/ExternalOkResponse'`
     (with a second `default` response branch so the response union vehicle
     carries the external header).
2. **Wire-gate matrix** `multidoc` (5 cells) in
   `tools/jsts_param_wire.py`, driven through the same
   wrap→generate→compile(-Werror)→run pipeline as the golden matrices:
   - `multidocQueryParam` — external query parameter serialized on the wire.
   - `multidocRequestBody` / `multidocRequestBodyJson` — external
     requestBody emission (form + JSON, `{"note":"external-note"}`).
   - `multidocResponse` — external response decoded via the runtime union
     dispatch (`isOk()` branch selection).
   - `multidocResponseHeader` — `X-External-Rate-Limit == "42"` surfaced
     from the decoded external response.
3. **Driver root-cause fix** (found while driving the cells): the plain
   `execute(verb, target, body, headers)` overload of `RecordingClient` did
   not record `lastVerb/lastTarget/lastBody/lastHeaders` (only the
   `executeWithMetadata` overload did), so the multidoc param/body cells
   failed with empty wire details. Both overloads now record.
4. **Conformance doc wording** (committed in `8781a1a8cb1`): §1 C-profile
   row now reads "seven golden matrices … gap-1 multi-document OAD matrix
   5/5"; §7 lists the multidoc evidence; §8 (closeout) summarizes the slice.

## Evidence (wire gate, HEAD-verified)

Battery re-run of `tools/jsts_param_wire.py` at current HEAD:

```
REF SOURCE PASS (webhook/callback/link metadata markers)
ref: 5 cells, 5 PASS, 0 FAIL
multidoc: 5 cells, 5 PASS, 0 FAIL
MOCK HTTP MATRIX PASS
mock: 7 cells, 7 PASS, 0 FAIL
```

Full wire gate at HEAD: **74 cells, all matrices PASS** — param 19/19,
server 6/6, security 11/11, content 21/21, ref 5/5, multidoc **5/5**,
mock HTTP 7/7 (wall ~60 s).

## Acceptance (plan §4 Gap 1)

- [x] All multidoc cells PASS (5/5 at the slice commit and re-verified at
      the closeout battery HEAD).
- [x] C-profile claim wording updated from "internal cross-component refs
      are the golden surface" to include file-level external non-schema
      refs (commit `8781a1a8cb1`, conformance doc §1 row C / §7 / §8).
- [x] Fixtures + gate + report committed; scratch kept untracked.