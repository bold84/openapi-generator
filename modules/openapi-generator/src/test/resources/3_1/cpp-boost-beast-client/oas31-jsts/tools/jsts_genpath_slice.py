#!/usr/bin/env python3
"""
jsts_genpath_slice.py — Wave-2 object/array-structural JSTS slice through the
GENERATED path (successor to the Wave-1 numeric/boolean slice).

Wave-2 (frozen contract §10) adds, over the Wave-1 driver:
  * container-depth EXACT numeric lexemes: write_driver attaches an
    `oas31::InstanceLexemeTable` (oas31_object_array.hpp) captured from the
    payload STRING, so numbers nested inside objects/arrays keep their raw
    lexeme (1 vs 1.0 vs 1e0) instead of degrading to the Boost.JSON value kind;
  * `$defs`/`$id`/local-pointer ref surfacing: wrap_spec hoists every ref that
    resolves within the group document (fragments against the nearest `$id`
    resource, `$id`-matched qualified/URN bases) into synthetic composed
    components.schemas and rewrites it to `#/components/schemas/<name>` — the
    exact shape the Wave-2 engine's refTargetIdOf/refSimpleName resolve. The
    upstream reader's OAS-3.1 `$id` NPE quirk is dodged by stripping `$id`
    AFTER hoisting (the engine never reads source `$id`); unresolvable
    remote/URN/metaschema refs are left as inert nodes (honest FAIL/PASS,
    never BLOCKED) and `--skip-validate-spec` prevents the upstream validator
    from fail-closing on them.

This is the Wave-1 (generated-path) slice driver for `oas31-jsts/`. It is the
honest successor report driver to the Wave-0 decode-based `jsts_runner.py`
(which measured the fromJsonValue* typed-decode path and reported the 229-BLOCKED
baseline). This driver proves the ADR Option-B **GENERATED** path — the artifacts
the REAL generator emits (`schema_ir.generated.{hpp,cpp}` +
`schema_validate.generated.cpp` with a thin `validate_<id>_branch_n` dispatch)
— by:

  1. OAS-WRAP:  each JSTS group's schema is lowered to a single-branch oneOf so
                the Wave-1 IR emitter (`irNodeFromBranch`) densifies it to a
                SchemaNode and emits a `validate_G<i>_branch_0` dispatch (this is
                the only shape the emitter currently lowers to a validate_<id>
                entry; a bare, non-composed schema materialises the typed-decoder
                model, not an IR validator).
  2. GENERATE:  run the real cpp-boost-beast-client generator on the wrapped doc.
  3. COMPILE:   g++ the emitted schema_ir.generated.* + schema_validate.generated.cpp
                + a per-run driver + oas31_lexeme.hpp under C++17 with Boost.
  4. RUN:       for every case, capture the raw numeric lexeme from the payload
                string (before Boost.JSON canonicalises it), build a
                RawInstance(value, lexeme) so asExactNumber() is exact, dispatch
                through the GENERATED validate_G<i>_branch_0, and compare the
                accept/reject verdict with the suite's `valid` flag.

Anti-greenwash (unchanged from the Wave-1 slice contract):
  * A case is PASS only when the GENERATED-path runtime verdict equals `valid`.
  * A case whose file fails generation (e.g. `not`, `boolean-schema` — the
    engine still fail-closes on these in Generator Phase 2) is BLOCKED (counted,
    never silently dropped, never a fake pass).
  * A whole file that does not generate is entirely BLOCKED.
  * Anything not run is never reported pass.
  * Full GS2 (the whole required-vocabulary corpus, 44 files / 1292 cases) is
    explicitly NOT claimed here; this slice is the strict 10-file numeric/boolean
    subset shared with `jsts_runner.py` (NUMERIC_BOOLEAN_SLICE_FILES).

Outcome codes (per case): PASS / FAIL / BLOCKED.
Report: per-file {generation, groups{gi:{PASS,FAIL,BLOCKED}}, totals} plus a run
log readable by the Wave-1 numeric subset report.

This tool is jsts-owned (oas31-jsts/). It reads engines/resources it does not own
(read-only) and drives the real generator + g++ — it never hand-rolls a validator.
"""
import argparse
import copy
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse as _urlparse

# Mirror the canonical slice set in jsts_runner.py.
NUMERIC_BOOLEAN_SLICE_FILES = [
    "boolean_schema.json", "not.json", "const.json", "enum.json",
    "minimum.json", "maximum.json", "exclusiveMinimum.json",
    "exclusiveMaximum.json", "multipleOf.json", "type.json",
]

RES_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "..", "..", "..", "..", "..", "..",
                       "src", "main", "resources", "cpp-boost-beast-client")
LEXEME_SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                          "..", "..", "..", "..", "..", "..", "..",
                          "src", "test", "resources", "3_1",
                          "cpp-boost-beast-client",
                          "oas-compliance", "oas31_lexeme.hpp")

# ---- remote vault (refRemote): map http://localhost:1234/<path> onto the
# vendored `vendor/remotes/<path>` files (the official JSTS remote set served
# at localhost:1234). swagger-parser FETCHES unresolved remote refs over HTTP
# at parse time (Connection refused => generation BLOCKED), so every remote
# ref must be hoisted into an in-doc component BEFORE generation.
_VAULT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                          "..", "vendor", "remotes")
_vault_cache = {}          # full URL -> parsed JSON document
_vault_processing = set()  # in-flight URLs (cycle guard)


def _vault_load(full):
    """Load a vault resource from the vendored remotes: localhost:1234 URIs
    (the suite's remotes) and https://json-schema.org/draft/2020-12/... URIs
    (the official metaschema + meta/* — vendored under vendor/remotes with
    the same path shape)."""
    if full in _vault_cache:
        return _vault_cache[full]
    if full in _vault_processing:
        return None
    _vault_processing.add(full)
    try:
        u = _urlparse.urlsplit(full)
        if u.scheme == "http" and u.hostname == "localhost" \
                and u.port in (None, 1234):
            rel = u.path.lstrip("/")
        elif u.scheme == "https" and u.hostname == "json-schema.org" \
                and (u.path.startswith("/draft2020-12/")
                     or u.path.startswith("/draft/2020-12/")):
            # Official URI form is /draft/2020-12/... ; the vendored vault
            # keeps the suite's localhost-style path shape draft2020-12/...
            rel = u.path.lstrip("/")
            rel = rel.replace("draft/2020-12/", "draft2020-12/", 1)
        else:
            return None
        fp = os.path.join(_VAULT_DIR, rel)
        if not os.path.isfile(fp):
            return None
        with open(fp, encoding="utf-8") as f:
            doc = json.load(f)
    except Exception:
        return None
    finally:
        _vault_processing.discard(full)
    _vault_cache[full] = doc
    return doc


def resolve_draft_dir(suite):
    for cand in (os.path.join(suite, "tests", "draft2020-12"),
                 os.path.join(suite, "draft2020-12"),
                 os.path.join(suite, "tests")):
        if os.path.isdir(cand):
            return cand
    return os.path.join(suite, "tests", "draft2020-12")


def load_groups(path):
    with open(path) as f:
        return json.load(f)


def _ptr_unescape(seg):
    """RFC 6901 JSON-pointer segment decode: percent-decode first, then
    unescape ~1 then ~0 (order matters)."""
    try:
        seg = __import__("urllib.parse", fromlist=["unquote"]).unquote(seg)
    except Exception:
        pass
    return seg.replace("~1", "/").replace("~0", "~")


def _hop_refs(branch, group_index=0):
    """Wave-2 `$ref`/`$defs`/`$id` surfacing (FROZEN contract §10.3). Rewrites
    every ref that RESOLVES WITHIN the current group doc into
    `#/components/schemas/<name>` and returns {name: {"oneOf": [target]}} extra
    components (synthetic composed components the engine densifies into
    `<name>_branch_0` rows). Local JSON pointers (`#`, `#/$defs/...`,
    `#/properties/...`, `#/prefixItems/...`, escaped pointers, bare-anchor
    fragments) are resolved against the nearest enclosing `$id` resource;
    relative/qualified bases are merged (urllib.parse.urljoin) against the
    containing resource id and matched against in-doc `$id` values.

    Wave-3: synthetic resource identity ((group_index<<20)|local, globally
    unique) is stamped as x-oas31-res / x-oas31-res-root boundary markers on
    resource ROOT objects; $dynamicRef is rewritten to $ref + x-oas31-dynref
    anchor marker; every $dynamicAnchor-bearing subschema is hoisted as a
    __da_* component tagged with x-oas31-dyanchor (except the group root and
    embedded-$id resource roots, whose anchors self-register in the engine).

    Refs that do NOT resolve inside the doc (remote `http(s)`, `urn:`, the
    metaschema) are left untouched — the engine emits them as inert nodes and
    the verdict is measured honestly (never BLOCKED, never fake-pass).

    In-place on `branch`; returns the extra components dict.
    """
    import copy as _copy
    import re as _re
    from urllib.parse import urljoin as _urljoin

    DOM = object()          # ctx marker for the document root
    hoisted = {}            # name -> {oneOf: [deepcopy(target)]}
    pending = {}            # name -> LIVE target (captured after pass 1 so
                            # inner refs already rewritten in the tree)
    id_index = {}           # resolved-resource-id -> target dict
    id_name = {}            # resolved-resource-id -> preferred hoist name
    used = set()
    obj_hoisted = {}        # id(target object) -> hoisted component name (dedupe)
    res_base = group_index << 20
    # Wave-3: synthetic resource identity (init BEFORE resolve_and_rewrite so
    # the VAULT branch can allocate vault-document resource ids and stamp
    # vault-document resource roots). Individual $id'd subtrees + the group
    # root are (re)numbered by assign_resources (call site is after walk, but
    # the DEFINITION must precede walk so the vault branch can invoke it).
    res_counter = [res_base + 1]
    res_of_obj = {}     # id(obj) -> containing synthetic resource id
    root_of_obj = {}    # id(obj) -> True for resource ROOT objects
    anchor_meta = {}    # hoisted name -> $dynamicAnchor name

    def assign_resources(node, outer_res):
        """Assign each object to its innermost enclosing resource. An $id'd
        object starts a NEW resource (stamped with boundary markers); its
        siblings stay in the outer resource (correct restore semantics — the
        walk() ctx rebasing above intentionally does NOT restore, but resource
        scoping must)."""
        if isinstance(node, dict):
            eff = outer_res
            rid2 = node.get("$id")
            if isinstance(rid2, str) and rid2:
                eff = res_counter[0]; res_counter[0] += 1
                node["x-oas31-res"] = eff
                node["x-oas31-res-root"] = True
                root_of_obj[id(node)] = True
            res_of_obj[id(node)] = eff
            for v in node.values():
                if isinstance(v, dict):
                    assign_resources(v, eff)
                elif isinstance(v, list):
                    for vv in v:
                        assign_resources(vv, eff)
        elif isinstance(node, list):
            for vv in node:
                assign_resources(vv, outer_res)

    def capture(name, target, force_name=False):
        """Register a live target for later capture (first-referenced wins).
        Object-identity dedupe: the same live object hoisted under ANY name
        returns its first hoisted name (anchor hoists and $dynamicRef static
        fallbacks frequently point at the SAME subschema). force_name bypasses
        the dedupe: the $dynamicRef static-fallback wrapper (__dynref_*) MUST
        exist under its encoded name even when the same object was already
        hoisted under the anchor-derived name."""
        oid = id(target)
        if not force_name and oid in obj_hoisted:
            return obj_hoisted[oid]
        if name not in pending:
            pending[name] = target
            if not force_name:
                obj_hoisted[oid] = name
            return name
        if pending[name] is target:
            if not force_name:
                obj_hoisted[oid] = name
            return name
        # same hoist name resolved to a DIFFERENT live object — dedupe
        k = 2
        while "%s__%d" % (name, k) in used:
            k += 1
        nm = "%s__%d" % (name, k)
        used.add(nm)
        pending[nm] = target
        if not force_name:
            obj_hoisted[oid] = nm
        return nm

    # ---- pass 0: index resources by RESOLVED $id (deep walk) ----------------
    def idx_walk(node, ctx_id, hint):
        if isinstance(node, dict):
            # $id must be a string to be a resource declaration (see the
            # walk() comment: the metaschema's properties-container has an
            # "$id"-NAMED property binding).
            rid = node.get("$id")
            eff = ctx_id
            if isinstance(rid, str) and rid:
                eff = _urljoin(str(ctx_id or ""), str(rid))
                id_index.setdefault(eff, node)
                id_name.setdefault(eff, hint)
            for k, v in node.items():
                if isinstance(v, str) and (k == "$id" or k == "$schema"):
                    continue
                if k == "$defs" and isinstance(v, dict):
                    for dk, dv in v.items():
                        idx_walk(dv, eff, str(dk))
                elif isinstance(v, dict):
                    idx_walk(v, eff, hint)
                elif isinstance(v, list):
                    for i2, vv in enumerate(v):
                        idx_walk(vv, eff, None)
        elif isinstance(node, list):
            for i2, vv in enumerate(node):
                idx_walk(vv, ctx_id, None)

    idx_walk(branch, branch.get("$id") if isinstance(branch, dict) else None, None)

    def navigate(base_obj, frag):
        """Resolve a fragment ('' | '/a/b' | 'bare-anchor') against base_obj.
        Returns a dict subschema or a bool, or None when unresolvable."""
        if not frag:
            return base_obj
        if frag.startswith("/"):
            cur = base_obj
            for seg in frag[1:].split("/"):
                dec = _ptr_unescape(seg)
                if isinstance(cur, dict):
                    if dec in cur:
                        cur = cur[dec]
                    else:
                        return None
                elif isinstance(cur, list):
                    try:
                        cur = cur[int(dec)]
                    except (ValueError, IndexError):
                        return None
                else:
                    return None
            return cur
        # bare anchor fragment (e.g. '#bigint') — scan subtree for $anchor in
        # DOCUMENT ORDER (the first $anchor with that name wins in 2020-12;
        # a LIFO stack would reverse sibling precedence and can pick the wrong
        # $anchor when several schemas share a name). Wave-3: a plain-name
        # fragment also identifies a $dynamicAnchor declaration (2020-12
        # §8.2.3: $dynamicRef = anchor-name resolution with dynamic scoping;
        # the static target is located exactly like a $ref fragment).
        # Wave-4 anchor g3: anchor lookup is scoped to the base RESOURCE —
        # subtrees declaring their own $id are separate resources and must
        # NOT contribute anchors (child1#my_anchor must find the anchor in
        # child1, not inside an embedded child2 resource).
        if isinstance(base_obj, list):
            base_obj = {"items": base_obj}
        from collections import deque as _deque
        dq = _deque([base_obj]) if isinstance(base_obj, dict) else _deque()
        while dq:
            n = dq.popleft()
            if isinstance(n, dict):
                if n.get("$anchor") == frag or n.get("$dynamicAnchor") == frag:
                    return n
                for v in n.values():
                    if isinstance(v, (dict,)):
                        if "$id" not in v:
                            dq.append(v)
                    elif isinstance(v, list):
                        dq.extend(x for x in v
                                 if isinstance(x, dict) and "$id" not in x)
        return None

    def resolve_and_rewrite(node, ref, ctx_obj, ctx_id):
        """Try to rewrite node['$ref'] (a local-or-$id-resolvable ref) to a
        components ref. Returns the hoisted component name when rewritten
        (None when the ref stays unresolvable/inert)."""
        if os.getenv("JSTS_TRACE") and ref.startswith("https://"):
            print("RESOLVE ref=%s ctx_id=%r" % (ref[:70], ctx_id),
                  file=sys.stderr)
        if ref.startswith("#"):
            base, frag = "", ref[1:]
            base_obj = ctx_obj if ctx_obj is not None else branch
        else:
            base, frag = ref.split("#", 1) if "#" in ref else (ref, "")
            full = _urljoin(str(ctx_id or ""), base) if ctx_id else base
            if full in id_index:
                base_obj = id_index[full]
            elif base in id_index:
                base_obj = id_index[base]
            elif ctx_id and full == ctx_id:
                # self-resource full ref -> the enclosing resource itself
                base_obj = ctx_obj if ctx_obj is not None else branch
            elif full.startswith("http://localhost") \
                    or full.startswith("https://json-schema.org/draft2020-12/") \
                    or full.startswith("https://json-schema.org/draft/2020-12/"):
                # refRemote: resolve against the vendored remotes vault. The
                # vault subdocument is registered as an in-doc resource and its
                # OWN inner refs are recursively rewritten in a nested walk
                # (same vault, so nested remote files hoist too). A FRESH copy
                # per group: the nested walk rewrites the document in place,
                # and the cached pristine copy must never leak rewritten refs
                # into later groups' specs.
                vdoc = copy.deepcopy(_vault_load(full))
                import sys as _sys
                if _sys.stderr and os.getenv("JSTS_TRACE"):
                    print("VAULT full=%s loaded=%s" % (full, vdoc is not None),
                          file=_sys.stderr)
                if vdoc is None or not isinstance(vdoc, dict):
                    return False
                # Wave-3: the vault document is a distinct RESOURCE — its own
                # synthetic resource id + scope-frame root markers (the
                # doc-root object), and every $id'd subtree inside receives its
                # own nested resource via assign_resources.
                vr = res_counter[0]; res_counter[0] += 1
                vdoc["x-oas31-res"] = vr
                vdoc["x-oas31-res-root"] = True
                root_of_obj[id(vdoc)] = True
                assign_resources(vdoc, vr)
                id_index.setdefault(full, vdoc)
                tail = full.split("?")[0].lstrip("/").split("/")[-1] or "res"
                id_name.setdefault(full, "__vault_"
                        + _re.sub(r"[^0-9A-Za-z_]+", "_", tail).rstrip("_"))
                base_obj = vdoc
                # recursively rewrite refs inside the vault document (its ctx
                # object/identity are the vault document itself, so local
                # pointers and bare anchors resolve against the VAULT root,
                # never against the enclosing group branch).
                walk(vdoc, vdoc, full)
            else:
                return False
            # 2020-12 root-self: a full URI identical to the ROOT resource id
            root_eff = None
            if isinstance(branch, dict) and branch.get("$id"):
                root_eff = _urljoin("", str(branch["$id"]))
            if base_obj is None and root_eff and full == root_eff:
                base_obj = branch
        if base_obj is None:
            return False
        target = navigate(base_obj, frag)
        if target is None or not isinstance(target, (dict, bool)):
            return False
        nm = _hoist_name_of(ref, base_obj, frag, ctx_obj, ctx_id, target)
        if nm is None:
            return False
        # component keys are dereferenced verbatim by the OAS-3.1 reader; keep
        # only fragment-safe characters ('' '" ' break ModelUtils pointer walk)
        nm = _re.sub(r"[^0-9A-Za-z_.~-]+", "_", nm) or "__empty"
        nm = capture(nm, target)
        node["$ref"] = "#/components/schemas/" + nm
        return nm

    def _hoist_name_of(ref, base_obj, frag, ctx_obj, ctx_id, target):
        # local pointer refs derive their name from the pointer leaf
        if ref.startswith("#"):
            if frag == "":
                # self/root ref — only the root resource may alias __root
                root_eff = None
                if isinstance(branch, dict) and branch.get("$id"):
                    root_eff = _urljoin("", str(branch["$id"]))
                if ctx_obj is None or ctx_id in (None, DOM):
                    return "__root"
                if root_eff is not None and ctx_id == root_eff:
                    return "__root"
                tail = str(ctx_id or "res").split("/")[-1] or "res"
                return "__res_" + _re.sub(r"[^0-9A-Za-z_]+", "_", tail)
            if frag.startswith("/"):
                return _ptr_unescape(frag.split("/")[-1]) or "__empty"
            return "__anchor_" + frag
        # qualified/base refs: name from the matched resource's $defs key
        base_, frag_ = ref.split("#", 1) if "#" in ref else (ref, "")
        full_ = _urljoin(str(ctx_id or ""), base_) if ctx_id else base_
        hit_id = full_ if full_ in id_index else (base_ if base_ in id_index else None)
        if hit_id is not None:
            nm = id_name.get(hit_id)
            if frag_.startswith("/"):
                nm = _ptr_unescape(frag_.split("/")[-1]) or nm
            if nm is None:
                tail = str(hit_id or "res").split("/")[-1] or "res"
                nm = "__res_" + _re.sub(r"[^0-9A-Za-z_]+", "_", tail)
            return nm
        return "__root"  # self/root alias (base resolves to the root resource)

    # ---- Wave-3 pass 1b: synthetic resource identity (PRE-WALK) ----------
    # Every group lives in a globally unique resource-id space
    # ((group_index<<20) | local). The GROUP ROOT is resource `res_base`;
    # embedded-$id subtrees and vault documents receive fresh ids. Only the
    # resource ROOT OBJECTS carry x-oas31-res / x-oas31-res-root markers (the
    # emitter needs them exactly there). Stamp BEFORE the ref walk so
    # $dynamicRef rewrites can encode the CONTAINING resource id into the
    # __dynref_<res>_<anchor> component name (the parser drops sibling
    # extensions on $ref-carrying schemas; the name channel is the only
    # reliable carrier) and so $anchor/$dynamicAnchor hoists capture the
    # correct owner resource at finalize time. The branch root itself must be
    # a scope-frame resource: if it declares no $id, stamp resource res_base
    # here (assign_resources would skip it); with an $id, assign_resources
    # rebases it to a fresh id + stamps the boundary.
    if isinstance(branch, dict):
        # The group root is the outermost dynamic-scope frame (2020-12: the
        # scope of the initial resource). If the root itself declares an $id,
        # assign_resources rebases it to a fresh id + stamps the boundary;
        # otherwise stamp resource res_base here (never re-stamped below).
        if not branch.get("$id"):
            branch["x-oas31-res"] = res_base
            branch["x-oas31-res-root"] = True
        res_of_obj[id(branch)] = res_base
        root_of_obj[id(branch)] = True
    assign_resources(branch if isinstance(branch, dict) else None, res_base)

    # ---- pass 1: rewrite refs with context-aware resource tracking ----------
    def walk(node, ctx_obj, ctx_id):
        if isinstance(node, dict):
            # $id declares a resource ONLY when its value is a URI string. A
            # dict like {"properties": {"$id": {...}}} is a properties
            # CONTAINER whose "$id" KEY names a property — never a resource
            # boundary (the official 2020-12 metaschema's meta/core has this
            # exact shape and a bindings-dict must not rebase the walk
            # context — the inner $dynamicRefs would then resolve against the
            # wrong base and stay raw).
            rid = node.get("$id")
            if isinstance(rid, str) and rid:
                eff = _urljoin(str(ctx_id or ""), str(rid)) if ctx_id else str(rid)
                ctx_obj, ctx_id = node, eff
            ref = node.get("$ref")
            if isinstance(ref, str):
                resolve_and_rewrite(node, ref, ctx_obj, ctx_id)
            # Wave-3/4 $dynamicRef: resolve the STATIC fallback exactly like a
            # $ref (pointer/empty fragments are plain $ref semantics). A bare
            # anchor fragment is rewritten to a dedicated
            # __dynref_<resid>_<anchor> component whose oneOf child is the
            # static fallback, with the anchor name encoded into the component
            # NAME: swagger-parser drops sibling extensions on $ref-carrying
            # schemas, so x-oas31-dynref never survives generation — the name
            # channel is the only reliable carrier for the engine's
            # dynamic-scope walk. An unresolvable static target leaves the raw
            # keyword in place (inert node, measured honestly).
            dref = node.get("$dynamicRef")
            if isinstance(dref, str):
                frag = dref.split("#", 1)[1] if "#" in dref else dref
                if frag and not frag.startswith("/"):
                    if os.getenv("JSTS_TRACE"):
                        print("DREFSEEN frag=%s ctx_id=%s" % (frag, ctx_id), file=sys.stderr)
                    nm = resolve_and_rewrite(node, dref, ctx_obj, ctx_id)
                    if os.getenv("JSTS_TRACE"):
                        print("DREFRES frag=%s nm=%r" % (frag, nm), file=sys.stderr)
                    if nm:
                        res_id = res_of_obj.get(id(node), res_base)
                        safe = _re.sub(r"[^0-9A-Za-z_.~-]+", "_", frag) or "anchor"
                        tgt = pending.get(nm)
                        if tgt is not None:
                            dynnm = capture(
                                "__dynref_%d_%s" % (res_id, safe),
                                tgt, force_name=True)
                            node["$ref"] = "#/components/schemas/" + dynnm
                        node.pop("$dynamicRef", None)
                elif resolve_and_rewrite(node, dref, ctx_obj, ctx_id):
                    node.pop("$dynamicRef", None)
            for k, v in node.items():
                if isinstance(v, str) and (k == "$ref" or k == "$schema"
                                           or k == "$dynamicRef"):
                    continue
                # enum/const members are literal JSON DATA, not schemas — a
                # `$ref` inside them must NOT be dereferenced (2020-12: an enum
                # member "{\$ref:...}" stays a literal value; e.g. ref.json
                # "naive replacement of $ref with its destination" depends on
                # the member being deep-equal to the verbatim object).
                if k == "enum" or k == "const":
                    continue
                if isinstance(v, dict):
                    walk(v, ctx_obj, ctx_id)
                elif isinstance(v, list):
                    for i2, vv in enumerate(v):
                        walk(vv, ctx_obj, ctx_id)
        elif isinstance(node, list):
            for i2, vv in enumerate(node):
                walk(vv, ctx_obj, ctx_id)

    walk(branch, branch if isinstance(branch, dict) else None,
         branch.get("$id") if isinstance(branch, dict) else None)

    # ---- Wave-3 pass 3: hoist every $dynamicAnchor-bearing subschema ------
    # The anchor CONTAINING-subschema must exist as a registry row for the
    # dynamic-scope walk to apply it. EVERY such subschema is hoisted to a
    # __da_* component (except the GROUP ROOT, whose own row self-registers
    # via the native getter); capture()'s object-identity dedupe guarantees a
    # single component even when $dynamicRef fallbacks hoist the same object.
    # The hoist name is recorded in anchor_meta so the finalize loop stamps
    # x-oas31-dyanchor on the component wrapper.
    anchor_seq = [0]
    branch_oid = id(branch) if isinstance(branch, dict) else None
    def discover_anchors(node):
        if isinstance(node, dict):
            anch = node.get("$dynamicAnchor")
            if isinstance(anch, str) and anch and id(node) != branch_oid:
                # Stamp the owner resource on the CONTAINING-SUBSchema object:
                # the hoisted __da_* content is densified as a component branch
                # and its branch-scan self-registration must key the WRAPPER's
                # owner resource (never the default 0) so (resource, anchor)
                # keys can never collide across groups or with the group root.
                node["x-oas31-res"] = res_of_obj.get(id(node), res_base)
                safe = _re.sub(r"[^0-9A-Za-z_.~-]+", "_", anch) or "anchor"
                anchor_seq[0] += 1
                nm = capture("__da_%s_%d" % (safe, anchor_seq[0]), node)
                anchor_meta[nm] = anch
            for v in node.values():
                if isinstance(v, dict):
                    discover_anchors(v)
                elif isinstance(v, list):
                    for vv in v:
                        discover_anchors(vv)
        elif isinstance(node, list):
            for vv in node:
                discover_anchors(vv)
    discover_anchors(branch if isinstance(branch, dict) else None)

    # ---- Wave-3 pass 2: hoist INLINE allOf members ----------------
    # openapi-generator's InlineModelResolver FOLDS pure-constraint inline
    # allOf members ({allOf:[{maximum:30},{minimum:20}]} -> member emptied,
    # inner constraint LOST in the model). Hoisting every inline member to a
    # component turns the member into a $ref the resolver will not fold,
    # preserving constraints verbatim through generation. Pure-$ref members
    # are left alone (never folded; avoids double-wrapping). Inner refs inside
    # a member were already rewritten by pass 1 in the live tree.
    def hoist_allof(node, counter):
        """Lift composed members (allOf/anyOf/oneOf) to components ($ref
        replacement) so the model layer cannot fold inline members into the
        composition's branch models. Folding is destructive in TWO ways:
        (a) inline oneOf/anyOf branch extraction re-typecasts boolean
        property schemas (`foo: true` becomes `foo: {"type":"string"}`),
        silently changing VALIDITY (not.json g8 "annotations inside a not":
        anyOf branch on `{"properties":{"foo":true}}` rejects numeric foo);
        (b) the content-dedupe across groups collapses identical members
        into one synthesized <name>_oneOf component. Pure-$ref members are
        left alone (never folded). `counter[0]` names the hoists;
        `counter[1]` is the group index used for the x-oas31-gid uniqueness
        marker (batch generation shares one OAS doc across groups)."""
        if isinstance(node, dict):
            for app in ("allOf", "anyOf", "oneOf"):
                al = node.get(app)
                if not isinstance(al, list):
                    continue
                for i, mem in enumerate(al):
                    if (isinstance(mem, dict)
                            and not (len(mem) == 1 and "$ref" in mem)):
                        counter[0] += 1
                        # Group-distinct marker (batch mode): the codegen's
                        # inline-model resolver dedupes IDENTICAL member
                        # content across groups into ONE synthesized
                        # <name>_oneOf component, collapsing the second
                        # group's member into a ref to the first (the dedupe
                        # path drops the member's unevaluated* boolean). This
                        # inert extension makes every hoisted member's
                        # content unique per group.
                        mem.setdefault("x-oas31-gid", counter[1])
                        nm = capture("__comp_%d" % counter[0], mem)
                        al[i] = {"$ref": "#/components/schemas/" + nm}
            for v in node.values():
                if isinstance(v, dict):
                    hoist_allof(v, counter)
                elif isinstance(v, list):
                    for vv in v:
                        hoist_allof(vv, counter)
        elif isinstance(node, list):
            for vv in node:
                hoist_allof(vv, counter)

    hoist_allof(branch, [0, group_index])

    # Wave-4.1: the codegen's inline-model resolver EXTRACTS inline object
    # subschemas into components — and both the extraction and the
    # content-dedupe across groups DROP `unevaluatedProperties/Items: false`
    # (and vendor extensions) from the copy, silently tolerating unevaluated
    # keys. Hoist every sub that carries an unevaluated* boolean into a
    # component (same wrapper pattern as allOf members, with the group-unique
    # x-oas31-gid marker) so the assertions survive generation verbatim.
    # Pure-$ref subs and the group's own root are left alone ($ref siblings
    # still ride the native path; the root is the composed content itself).
    def hoist_uneval(node, counter, is_root):
        if isinstance(node, dict):
            if not is_root and "$ref" not in node and (
                    node.get("unevaluatedProperties") is False
                    or node.get("unevaluatedItems") is False):
                counter[0] += 1
                node.setdefault("x-oas31-gid", counter[1])
                nm = capture("__upr_%d" % counter[0], node)
                return {"$ref": "#/components/schemas/" + nm}
            out = dict(node)
            for k, v in node.items():
                if isinstance(v, dict):
                    out[k] = hoist_uneval(v, counter, False)
                elif isinstance(v, list):
                    out[k] = [hoist_uneval(vv, counter, False)
                              if isinstance(vv, dict) else vv for vv in v]
            return out
        return node

    branch = hoist_uneval(branch, [0, group_index], True)

    # Capture hoisted targets AFTER pass 1 so any inner refs inside a target
    # were already rewritten in the live tree (never a stale copy). Wave-3:
    # every hoisted wrapper carries x-oas31-res = the CONTAINING synthetic
    # resource id of its target (rooted at res_base when unmapped); resource
    # ROOT targets additionally get x-oas31-res-root (scope-frame boundary);
    # $dynamicAnchor hoists get x-oas31-dyanchor (anchor name).
    for name in sorted(pending):
        if name not in hoisted:
            tgt = pending[name]
            wr = {"oneOf": [_copy.deepcopy(tgt)]}
            oid = id(tgt)
            wr["x-oas31-res"] = res_of_obj.get(oid, res_base)
            if name.startswith("__dynref_"):
                # Static-fallback container: it must NOT push a scope frame
                # (its resource may repeat a frame already on the path) and
                # it registers no anchor of its own.
                pass
            elif root_of_obj.get(oid, False):
                wr["x-oas31-res-root"] = True
            anch = anchor_meta.get(name)
            if anch is not None:
                wr["x-oas31-dyanchor"] = anch
            hoisted[name] = wr
    # HARD REQUIREMENT for the upstream reader: openapi-generator's OAS-3.1
    # parsing NPEs when a composed oneOf branch carries a non-"file:" `$id`.
    # The ENGINE never reads source `$id` (its resourceIdentity/baseUri comes
    # from the emitted SchemaResource, and all refs were already rewritten to
    # #/components/schemas/<name>), so stripping `$id` after hoisting is a
    # safe, honest workaround that keeps the corpus GENERATED-path green.
    def strip_ids(obj):
        if isinstance(obj, dict):
            obj.pop("$id", None)
            for v in obj.values():
                strip_ids(v)
        elif isinstance(obj, list):
            for v in obj:
                strip_ids(v)

    strip_ids(branch)
    for c in hoisted.values():
        strip_ids(c)
    return hoisted


def _vocab_validation_enabled(branch):
    """2020-12 dialect: a schema resource's active vocabularies come from
    its metaschema ($schema URI -> metaschema document -> $vocabulary).
    No $schema / no $vocabulary / unresolvable metaschema -> the default
    dialect (full 2020-12, validation active). Only vault-resolvable
    metaschemas are inspected (best-effort, mirroring the remotes vault)."""
    if not isinstance(branch, dict):
        return True
    sch = branch.get("$schema")
    if not isinstance(sch, str) or not sch:
        return True
    try:
        meta = _vault_load(sch)
    except Exception:
        return True
    if not isinstance(meta, dict):
        return True
    vocab = meta.get("$vocabulary")
    if not isinstance(vocab, dict):
        return True
    return bool(vocab.get(
        "https://json-schema.org/draft/2020-12/vocab/validation", False))


def _stamp_dialect(branch, comp, group_index):
    """Stamp the resource root with the validation-vocabulary status before
    the $schema key is dropped (the IR carries dialect info per resource)."""
    if not isinstance(branch, dict):
        return
    if not _vocab_validation_enabled(branch):
        branch["x-oas31-vocab-inert"] = True


def wrap_spec(groups):
    """OAS-wrap each group's schema as a single-branch oneOf so the Wave-1 IR
    emitter lowers it to a validate_G<i>_branch_0 dispatch. Wave-2 (contract
    §10.3): local `$defs`/pointer/`$id`-resolvable refs inside a group are
    hoisted into synthetic composed `components.schemas` and the refs rewritten
    to `#/components/schemas/<name>` (the engine's `refTargetIdOf`/`refSimpleName`
    expect exactly this shape). Wave-3: the group root is stamped as the
    OUTERMOST dynamic-scope resource (x-oas31-res = (i<<20)|0 + root marker);
    Unresolvable remote/URN refs are left in place (inert nodes — measured
    honestly)."""
    comp = {}
    for i, g in enumerate(groups):
        s = g.get("schema", {})
        branch = dict(s) if isinstance(s, dict) else s
        if isinstance(branch, dict):
            _stamp_dialect(branch, comp, i)
            branch.pop("$schema", None)
        comp["G%d" % i] = {"oneOf": [branch]}
        if isinstance(branch, dict):
            comp.update(_hop_refs(branch, i))
    return {"openapi": "3.1.0", "info": {"title": "jsts-genpath-slice",
                                         "version": "1.0.0"},
            "paths": {}, "components": {"schemas": comp}}


def generate(jar, spec_path, out_dir):
    cmd = ["java", "-jar", jar, "generate", "--generator-name",
           "cpp-boost-beast-client", "--input-spec", spec_path,
           "--output", out_dir, "--skip-validate-spec",
           "--additional-properties", "packageName=Jsts",
           "--additional-properties", "modelPackage=model"]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=900)


def write_driver(groups, work_dir, tag):
    """Driver that dispatches every case through the GENERATED
    validate_G<i>_branch_0. Uses RawInstance(value, lexeme) with the raw number
    lexeme captured from the payload string so ExactNumber stays exact."""
    if not os.path.exists(os.path.join(work_dir, "oas31_lexeme.hpp")):
        # vendor it alongside from oas-compliance (read-only source)
        src = LEXEME_SRC
        if os.path.exists(src):
            import shutil
            shutil.copy(src, os.path.join(work_dir, "oas31_lexeme.hpp"))
    lines = []
    lines.append('#include <boost/json.hpp>')
    lines.append('#include <cstdio>')
    lines.append('#include <string>')
    lines.append('#include "oas31_lexeme.hpp"')
    lines.append('#include "oas31_object_array.hpp"')
    lines.append('#include "oas31_validator.hpp"')
    lines.append('#include "schema_ir.generated.hpp"')
    lines.append('using oas31::RawInstance; using oas31::ValidationContext;')
    lines.append('using oas31::ValidationPath; using oas31::ValidationResult;')
    for i in range(len(groups)):
        lines.append("oas31::ValidationResult validate_G%d_branch_0("
                     "oas31::RawInstance const&,oas31::ValidationPath&,"
                     "oas31::ValidationContext&);" % i)
    lines.append('int main(){')
    lines.append('  int pass=0,fail=0,blocked=0;')
    for i, g in enumerate(groups):
        for ci, t in enumerate(g["tests"]):
            data = t.get("data")
            payload = json.dumps(data, separators=(",", ":"))
            payload_c = payload.replace('\\', '\\\\').replace('"', '\\"')
            exp = "true" if bool(t["valid"]) else "false"
            lines.append('  { std::string pl="%s"; std::string lx;' % payload_c)
            lines.append('    oas31::captureLeadingNumberLexeme(pl,lx);')
            lines.append('    oas31::InstanceLexemeTable tbl;')
            lines.append('    oas31::captureInstanceLexemes(pl,tbl);')
            lines.append('    try {')
            lines.append('      boost::json::value v=boost::json::parse(pl);')
            lines.append('      RawInstance ri(&v,lx); ri.lexemes=&tbl; ValidationPath p; '
                         'ValidationContext c;')
            lines.append(f'      ValidationResult r=validate_G{i}_branch_0(ri,p,c);')
            lines.append('      bool ok=(r.success==%s);' % exp)
            lines.append(f'      printf("RESULT|{i}|{ci}|%s\\n", ok?"PASS":"FAIL");')
            lines.append('    } catch(...){')
            lines.append(f'      printf("RESULT|{i}|{ci}|BLOCKED\\n");')
            lines.append('    }')
            lines.append('  }')
    lines.append('  return 0;')
    lines.append('}')
    main_path = os.path.join(work_dir, "main_%s.cpp" % tag)
    with open(main_path, "w") as f:
        f.write("\n".join(lines) + "\n")
    return main_path


def compile_run(main_path, gen_dir, work_dir, binary, timeout, tag):
    srcs = [os.path.join(gen_dir, "model", f)
            for f in ("schema_ir.generated.cpp",
                      "schema_validate.generated.cpp")]
    boost_src = os.path.join(work_dir, "boost_json_src.cpp")
    if not os.path.exists(boost_src):
        with open(boost_src, "w") as f:
            f.write('#include <boost/json/src.hpp>\n')
    cmd = (["g++", "-std=c++17", "-I/opt/homebrew/include", "-I", RES_DIR,
            "-I", work_dir, "-I", os.path.join(gen_dir, "model"),
            main_path] + srcs + [boost_src, "-o", binary])
    c = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if c.returncode != 0:
        return None, c.stderr[-1800:]
    r = subprocess.run([binary], capture_output=True, text=True, timeout=timeout)
    return r, None


def parse_results(out):
    res = {}
    for ln in out.splitlines():
        m = re.match(r"RESULT\|(\d+)\|(\d+)\|(PASS|FAIL|BLOCKED)", ln.strip())
        if m:
            res[(int(m.group(1)), int(m.group(2)))] = m.group(3)
    return res


def evaluate_file(suite, jar, work_dir, filename, timeout):
    """Evaluate one JSTS file by generating/running EACH group independently.

    Per-group isolation mirrors the Wave-0 decoder runner so a single broken
    generated artifact (e.g. an object-typed enum value that the emitter writes
    into enumNumbers as a malformed string) cannot mask the rest of the file:
    each group becomes schema G0 in its own OAS doc and is driven through the
    GENERATED validate_G0_branch_0 dispatch.
    """
    path = os.path.join(resolve_draft_dir(suite), filename)
    groups = load_groups(path)
    tag = filename.replace(".json", "")
    per_group = {}
    verdicts = {}
    pass_n = fail_n = blocked_n = 0
    file_gen_ok = True
    t0 = time.time()

    for gi, g in enumerate(groups):
        gen_dir = os.path.join(work_dir, "gen", tag, str(gi))
        os.makedirs(gen_dir, exist_ok=True)
        spec_path = os.path.join(work_dir, "spec_%s_%d.json" % (tag, gi))
        with open(spec_path, "w") as f:
            json.dump(wrap_spec([g]), f)

        r = generate(jar, spec_path, gen_dir)
        vpath = os.path.join(gen_dir, "model", "schema_validate.generated.cpp")
        gen_ok = (r.returncode == 0) and os.path.exists(vpath)
        if not gen_ok:
            file_gen_ok = False
            reason = "generation rejected (fail-closed): " + next(
                (l.strip() for l in r.stderr.splitlines()
                 if "UnsupportedSchemaAssertionException" in l
                 or "Exception" in l), "see generator stderr")[:200]
            bc = len(g["tests"])
            cd = {"PASS": 0, "FAIL": 0, "BLOCKED": bc, "note": reason,
                  "stage": "generation"}
            per_group[gi] = cd
            blocked_n += bc
            for ci in range(bc):
                verdicts["%d:%d" % (gi, ci)] = "BLOCKED"
            continue

        if "validate_G0_branch_0(" not in open(vpath).read():
            bc = len(g["tests"])
            cd = {"PASS": 0, "FAIL": 0, "BLOCKED": bc,
                  "note": "no validate_G0_branch_0 emitted",
                  "stage": "emission"}
            per_group[gi] = cd
            blocked_n += bc
            for ci in range(bc):
                verdicts["%d:%d" % (gi, ci)] = "BLOCKED"
            continue

        main_path = write_driver([g], work_dir, "%s_%d" % (tag, gi))
        binary = os.path.join(work_dir, "run_%s_%d" % (tag, gi))
        rr, err = compile_run(main_path, gen_dir, work_dir, binary, timeout,
                              "%s_%d" % (tag, gi))
        if rr is None:
            bc = len(g["tests"])
            cd = {"PASS": 0, "FAIL": 0, "BLOCKED": bc,
                  "note": "compile shortfall: " + (err or "")[:160],
                  "stage": "compile"}
            per_group[gi] = cd
            blocked_n += bc
            for ci in range(bc):
                verdicts["%d:%d" % (gi, ci)] = "BLOCKED"
            continue

        observed = parse_results(rr.stdout)
        # write_driver compiled a single-group doc, so its emitted indices are
        # (0, ci); remap to the real group index (gi, ci).
        observed = {(gi, ci): v for (_, ci), v in observed.items()}
        cd = {"PASS": 0, "FAIL": 0, "BLOCKED": 0, "stage": "run"}
        for ci, t in enumerate(g["tests"]):
            key = (gi, ci)
            if key not in observed:
                cd["BLOCKED"] += 1
                verdicts["%d:%d" % (gi, ci)] = "BLOCKED"
            elif observed[key] == "PASS":
                cd["PASS"] += 1
                verdicts["%d:%d" % (gi, ci)] = "PASS"
            elif observed[key] == "FAIL":
                cd["FAIL"] += 1
                verdicts["%d:%d" % (gi, ci)] = "FAIL"
            else:
                cd["BLOCKED"] += 1
                verdicts["%d:%d" % (gi, ci)] = "BLOCKED"
        per_group[gi] = cd
        pass_n += cd["PASS"]; fail_n += cd["FAIL"]; blocked_n += cd["BLOCKED"]

    return filename, {"generation": "OK" if file_gen_ok else "PARTIAL",
                      "groups": per_group, "verdicts": verdicts,
                      "pass": pass_n, "fail": fail_n, "blocked": blocked_n,
                      "seconds": round(time.time() - t0, 2)}


def wrap_specs_batch(groups):
    """wrap_spec for ALL groups in ONE OAS document (batch mode): one
    generation + one compile per FILE instead of per group.

    Hoisted component keys are per-group closure state and would collide in
    the shared components map (two groups hoisting distinct schemas under the
    same generated name), so every non-__dynref_ hoist is suffixed __g<i> and
    the group's refs (inside the branch AND inside hoisted content) are
    rewritten to the suffixed key. __dynref_ names keep their raw form: their
    resource ids are already globally unique and the emitter's anchor decode
    depends on the exact __dynref_<res>_<anchor> shape."""
    comp = {}
    for i, g in enumerate(groups):
        s = g.get("schema", {})
        branch = dict(s) if isinstance(s, dict) else s
        if isinstance(branch, dict):
            _stamp_dialect(branch, comp, i)
            branch.pop("$schema", None)
        comp["G%d" % i] = {"oneOf": [branch]}
        if isinstance(branch, dict):
            grp = _hop_refs(branch, i)

            def _rewrite(obj):
                if isinstance(obj, dict):
                    for k, v in list(obj.items()):
                        if k == "$ref" and isinstance(v, str):
                            base = "#/components/schemas/"
                            if v.startswith(base):
                                nm = v[len(base):]
                                if nm in grp and not nm.startswith("__dynref_"):
                                    obj[k] = base + nm + "__g%d" % i
                        elif isinstance(v, (dict, list)):
                            _rewrite(v)
                elif isinstance(obj, list):
                    for v in obj:
                        _rewrite(v)

            _rewrite(comp["G%d" % i])
            renamed = {}
            for k, v in grp.items():
                if k.startswith("__dynref_"):
                    renamed[k] = v
                else:
                    renamed[k + "__g%d" % i] = v
            for _v in renamed.values():
                _rewrite(_v)
            comp.update(renamed)
    return {"openapi": "3.1.0", "info": {"title": "jsts-genpath-slice",
                                         "version": "1.0.0"},
            "paths": {}, "components": {"schemas": comp}}


def evaluate_file_batch(suite, jar, work_dir, filename, timeout):
    """Evaluate one JSTS file with a SINGLE generation + compile (batch
    mode): all groups share one OAS document (validate_G<i>_branch_0 per
    group) and one driver binary exercises every case. Verdict keys are the
    real (gi, ci) pairs directly."""
    path = os.path.join(resolve_draft_dir(suite), filename)
    groups = load_groups(path)
    tag = filename.replace(".json", "")
    per_group = {}
    verdicts = {}
    pass_n = fail_n = blocked_n = 0
    t0 = time.time()

    gen_dir = os.path.join(work_dir, "gen", tag)
    os.makedirs(gen_dir, exist_ok=True)
    spec_path = os.path.join(work_dir, "spec_%s.json" % tag)
    with open(spec_path, "w") as f:
        json.dump(wrap_specs_batch(groups), f)

    r = generate(jar, spec_path, gen_dir)
    vpath = os.path.join(gen_dir, "model", "schema_validate.generated.cpp")
    gen_ok = (r.returncode == 0) and os.path.exists(vpath)
    if not gen_ok:
        reason = "generation rejected (fail-closed): " + next(
            (l.strip() for l in r.stderr.splitlines()
             if "UnsupportedSchemaAssertionException" in l
             or "Exception" in l), "see generator stderr")[:200]
        for gi, g in enumerate(groups):
            bc = len(g["tests"])
            cd = {"PASS": 0, "FAIL": 0, "BLOCKED": bc,
                  "note": reason, "stage": "generation"}
            per_group[gi] = cd
            blocked_n += bc
            for ci in range(bc):
                verdicts["%d:%d" % (gi, ci)] = "BLOCKED"
        return filename, {"generation": "PARTIAL", "groups": per_group,
                          "verdicts": verdicts, "pass": 0, "fail": 0,
                          "blocked": blocked_n,
                          "seconds": round(time.time() - t0, 2)}

    main_path = write_driver(groups, work_dir, tag)
    binary = os.path.join(work_dir, "run_%s" % tag)
    rr, err = compile_run(main_path, gen_dir, work_dir, binary, timeout, tag)
    if rr is None:
        for gi, g in enumerate(groups):
            bc = len(g["tests"])
            cd = {"PASS": 0, "FAIL": 0, "BLOCKED": bc,
                  "note": "compile shortfall: " + (err or "")[:160],
                  "stage": "compile"}
            per_group[gi] = cd
            blocked_n += bc
            for ci in range(bc):
                verdicts["%d:%d" % (gi, ci)] = "BLOCKED"
        return filename, {"generation": "OK", "groups": per_group,
                          "verdicts": verdicts, "pass": 0, "fail": 0,
                          "blocked": blocked_n,
                          "seconds": round(time.time() - t0, 2)}

    observed = parse_results(rr.stdout)
    for gi, g in enumerate(groups):
        cd = {"PASS": 0, "FAIL": 0, "BLOCKED": 0, "stage": "run"}
        for ci, t in enumerate(g["tests"]):
            key = (gi, ci)
            if key not in observed:
                cd["BLOCKED"] += 1
                verdicts["%d:%d" % (gi, ci)] = "BLOCKED"
            elif observed[key] == "PASS":
                cd["PASS"] += 1
                verdicts["%d:%d" % (gi, ci)] = "PASS"
            elif observed[key] == "FAIL":
                cd["FAIL"] += 1
                verdicts["%d:%d" % (gi, ci)] = "FAIL"
            else:
                cd["BLOCKED"] += 1
                verdicts["%d:%d" % (gi, ci)] = "BLOCKED"
        per_group[gi] = cd
        pass_n += cd["PASS"]; fail_n += cd["FAIL"]; blocked_n += cd["BLOCKED"]

    return filename, {"generation": "OK", "groups": per_group,
                      "verdicts": verdicts, "pass": pass_n, "fail": fail_n,
                      "blocked": blocked_n,
                      "seconds": round(time.time() - t0, 2)}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--suite", required=True)
    ap.add_argument("--jar", required=True)
    ap.add_argument("--work", default=None)
    ap.add_argument("--files", default=None)
    ap.add_argument("--out", default=None)
    ap.add_argument("--timeout", type=int, default=300)
    ap.add_argument("--workers", type=int, default=6,
                    help="parallel evaluation of independent files")
    ap.add_argument("--gen-mode", default="batch",
                    choices=["batch", "serial"],
                    help="batch: one generate+compile per FILE (default); "
                         "serial: one per GROUP (isolation for debugging)")
    args = ap.parse_args()

    if not os.path.exists(args.jar):
        print("ERROR: --jar required and must exist", file=sys.stderr)
        sys.exit(2)
    if args.files:
        files = [s.strip() for s in args.files.split(",") if s.strip()]
    else:
        files = list(NUMERIC_BOOLEAN_SLICE_FILES)
    work = args.work or "/tmp/jsts-genpath-run"
    os.makedirs(work, exist_ok=True)

    eval_fn = evaluate_file if args.gen_mode == "serial" else evaluate_file_batch
    report = {"runner": "jsts_genpath_slice.py (Wave-1 GENERATED path)",
              "mode": args.gen_mode, "workers": args.workers,
              "suite": args.suite, "files": {},
              "totals": {"files": 0, "cases": 0, "PASS": 0, "FAIL": 0,
                         "BLOCKED": 0}}
    results = {}
    if args.workers > 1 and len(files) > 1:
        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(max_workers=args.workers) as ex:
            futs = {ex.submit(eval_fn, args.suite, args.jar, work, fn,
                              args.timeout): fn for fn in files}
            for fut in futs:
                fn, res = fut.result()
                results[fn] = res
    else:
        for fn in files:
            _, res = eval_fn(args.suite, args.jar, work, fn, args.timeout)
            results[fn] = res
    for fn in files:
        res = results[fn]
        print("== %s ==" % fn, flush=True)
        report["files"][fn] = res
        report["totals"]["files"] += 1
        fp = ff = fb = 0
        for cd in res["groups"].values():
            fp += cd["PASS"]; ff += cd["FAIL"]; fb += cd["BLOCKED"]
        report["totals"]["cases"] += fp + ff + fb
        report["totals"]["PASS"] += fp
        report["totals"]["FAIL"] += ff
        report["totals"]["BLOCKED"] += fb
        print("  file>%s: PASS=%d FAIL=%d BLOCKED=%d gen=%s" %
              (fn, fp, ff, fb, res["generation"]), flush=True)

    t = report["totals"]
    print("\n=== TOTALS ===")
    print("  files=%d cases=%d PASS=%d FAIL=%d BLOCKED=%d" %
          (t["files"], t["cases"], t["PASS"], t["FAIL"], t["BLOCKED"]))
    if args.out:
        with open(args.out, "w") as f:
            json.dump(report, f, indent=2)
        print("  report -> %s" % args.out)


if __name__ == "__main__":
    main()
