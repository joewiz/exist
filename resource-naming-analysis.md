# Resource Naming in eXist-db: Analysis and Plan

## 1. The Problem

[Issue #3795](https://github.com/eXist-db/exist/issues/3795) identifies a fundamental
conceptual gap in eXist-db: the system conflates **resource names** (human-readable
identifiers like filenames) with **URIs** (percent-encoded address strings). This confusion
permeates the APIs, documentation, and internal implementation, producing a family of bugs:

| Issue | Summary |
|-------|---------|
| [#3795](https://github.com/eXist-db/exist/issues/3795) | `xmldb:create-collection("/db", "hello world")` rejects a space in what is documented as a *name*, not a URI. Return values are URI-encoded without documentation saying so. |
| [#3943](https://github.com/eXist-db/exist/issues/3943) | Cannot import `[Content_Types].xml` because `[` and `]` are not URI-safe, yet eXist tries to parse the filename as a URI. |
| [#1824](https://github.com/eXist-db/exist/issues/1824) | `xmldb:decode()` wrongly converts `+` to space. This is correct for `application/x-www-form-urlencoded` query strings but incorrect for URI path segments (RFC 3986). Root cause: using Java's `URLDecoder` on path components. |
| [#4469](https://github.com/eXist-db/exist/issues/4469) | `xmldb:store()` cannot locate collections with non-ASCII names (e.g., `AéB`) unless the caller manually percent-encodes them, even though the parameters are documented as names/paths, not URIs. |

The common thread: **eXist-db lacks a clear, enforced distinction between a resource's
_name_ and its _URI representation_.**

---

## 2. Key Concepts That Need Definition

Before any fix can be applied, eXist-db needs to formally define:

| Concept | Proposed Definition |
|---------|-------------------|
| **Collection Name** | A literal string identifying a collection within its parent -- analogous to a directory name on a filesystem. May contain spaces, Unicode, brackets, `+`, etc. May NOT contain `/` (the path separator). |
| **Document Name** | A literal string identifying a document within its collection -- analogous to a filename. Same character rules as Collection Name. |
| **Database Path** | A `/`-separated sequence of collection names, optionally ending with a document name. Example: `/db/my collection/résumé.xml`. NOT percent-encoded. |
| **Database URI** | A percent-encoded (RFC 3986) representation of a database path, suitable for use in URIs. Example: `/db/my%20collection/r%C3%A9sum%C3%A9.xml`. |
| **xmldb URI** | A full URI with scheme, instance, optional host/port/context, and a database URI as its path component. Example: `xmldb:exist:///db/my%20collection/r%C3%A9sum%C3%A9.xml`. |

With these definitions, `"hello world"` and `"hello%20world"` would be two *different*
collection names -- the former contains a space, the latter contains a literal `%`, `2`, `0`.

---

## 3. How eXist-db's APIs Currently Handle Resource Names

### 3.1 The Core: `XmldbURI`

`XmldbURI` (`exist-core/.../xmldb/XmldbURI.java`) is the central abstraction. It stores
paths **percent-encoded** internally in `encodedCollectionPath` and decodes on demand:

```java
// Line 381-392
public String getRawCollectionPath() {
    return encodedCollectionPath;  // percent-encoded
}

public String getCollectionPath() {
    return URLDecoder.decode(encodedCollectionPath, UTF_8);  // decoded
}
```

**Problem:** `URLDecoder` is designed for `application/x-www-form-urlencoded` data (HTML
form submissions), not RFC 3986 URI path segments. It decodes `+` as space, which is
wrong for paths. This is the root cause of issue #1824.

**Problem:** The `XmldbURI.create()` factory method calls `java.net.URI`, which rejects
characters like `[` and `]` in path segments. This is why `[Content_Types].xml` (#3943)
cannot be stored.

### 3.2 XQuery API (`xmldb:*` functions)

The `xmldb` module functions (`exist-core/.../xquery/functions/xmldb/`) accept `xs:string`
parameters documented with names like `$target-collection-uri` and `$new-collection`.

| Function | Parameters | Behavior |
|----------|-----------|----------|
| `xmldb:create-collection($target, $name)` | `$target`: "target collection URI"; `$name`: "name of the new collection" | Feeds `$name` into XmldbURI, which requires it to be URI-safe. Returns a URI-encoded path. |
| `xmldb:store($collection, $name, $content)` | `$collection`: "collection URI"; `$name`: "document name" | `$name` is passed through XmldbURI. If the collection path contains non-ASCII characters, lookup fails unless the caller pre-encodes. |
| `xmldb:rename($collection, $old, $new)` | String parameters | Same URI-encoding assumptions apply. |
| `xmldb:encode($str)` / `xmldb:decode($str)` | Strings | Convenience wrappers around `URLEncoder`/`URLDecoder`, inheriting the `+`-as-space bug. |

**The fundamental issue:** Parameters labeled "name" are processed as URI components.
Users naturally pass literal names (with spaces, accents, brackets), which then fail.

### 3.3 REST API

`RESTServer.java` (`exist-core/.../http/RESTServer.java`) extracts resource paths from
HTTP request URIs. Since HTTP URLs are inherently percent-encoded, the REST API actually
handles this more naturally -- the web server decodes the URL, and `RESTServer` re-encodes
via `XmldbURI.create()`.

However, round-trip fidelity is still broken: if a resource is named `hello+world` (literal
plus sign), the REST API cannot distinguish it from `hello world` after `URLDecoder`
processing.

### 3.4 WebDAV

The WebDAV implementation (`extensions/webdav/.../ExistResourceFactory.java`) uses the
Milton framework and converts paths to `XmldbURI`. WebDAV clients typically handle
percent-encoding transparently, but the same `XmldbURI`/`URLDecoder` issues apply to the
underlying storage operations.

### 3.5 XML-RPC API

The XML-RPC API (`exist-core/.../xmlrpc/RpcAPI.java`) passes resource names as plain string
parameters in XML-RPC method calls. These strings are then converted to `XmldbURI`
internally, subject to the same encoding assumptions.

### 3.6 Java XML:DB API

The Java `XML:DB` API implementation (`LocalCollection.java`, `RemoteCollection.java`)
uses `XmldbURI` throughout. Methods like `createResource(String id, ...)` and
`storeResource(Resource)` pass resource IDs through `XmldbURI.create()`, which will reject
names containing URI-unsafe characters.

---

## 4. How BaseX Handles Resource Naming

BaseX takes a fundamentally different and simpler approach:

### 4.1 Database Names vs. Resource Paths

BaseX cleanly separates two concepts:

- **Database names**: Restricted to ASCII with an explicit allowlist of special characters:
  `!#$%&'()+-=@[]^_{}~` and backticks. No Unicode, no spaces. This avoids URI-encoding
  issues entirely at the database level.

- **Resource paths**: Hierarchical paths within a database using `/` as separator. These
  are treated as **plain strings**, not URIs. A resource path like `docs/résumé.xml` is
  stored as-is.

### 4.2 API-Specific Handling

- **Database Module functions** (`db:get`, `db:put`, `db:put-binary`): Accept database
  name and resource path as separate string arguments. Paths are plain strings, not URIs.

- **REST API**: Database name and resource path appear in the URL path. Standard HTTP
  percent-encoding applies at the transport layer, but BaseX decodes and stores the
  original string.

- **WebDAV**: Similar transparent decoding.

### 4.3 Known Tradeoffs

BaseX explicitly acknowledges that its "extensions for handling databases don't go 100%
hand in hand with standard XQuery functions" (see [BaseX issue #1172](https://github.com/BaseXdb/basex/issues/1172)). When database
names contain characters like `^` that are invalid in URIs, `fn:base-uri()` fails.
BaseX chose backward compatibility over full XQuery compliance, recommending
vendor-specific functions (`db:name`, `db:path`) for path manipulation.

### 4.4 Key Lesson for eXist-db

BaseX's design validates the core insight of issue #3795: **resource names and URIs are
fundamentally different things and should be treated separately.** BaseX sidesteps many
problems by restricting database names to ASCII and treating resource paths as opaque
strings. eXist-db could adopt a similar strategy while being more permissive with Unicode
in names.

---

## 5. What the W3C XQuery 3.1 Spec Says

The [XQuery 3.1](https://www.w3.org/TR/xquery-31/) and [XPath and XQuery Functions and
Operators 3.1](https://www.w3.org/TR/xpath-functions-31/) specifications are deliberately
implementation-agnostic about resource naming:

### 5.1 `fn:doc($uri as xs:string?) as document-node()?`

- Accepts a URI as `xs:string`. Relative URIs are resolved against the static base URI.
- The mapping from URI to document node is **implementation-defined**.
- Two calls with the same URI must return the same document node.

### 5.2 `fn:collection($arg as xs:string?) as item()*`

- Accepts a collection URI as `xs:string`.
- The mapping from URI to a sequence of items is **implementation-defined**.
- The spec does not prescribe the URI scheme or format.

### 5.3 `fn:uri-collection($arg as xs:string?) as xs:anyURI*`

- Returns URIs of resources in a collection.
- The spec notes that an implementation *might* ensure that
  `fn:uri-collection($c) ! fn:doc(.)` equals `fn:collection($c)`, but this is not
  required.

### 5.4 `fn:document-uri($node) as xs:anyURI?`

- Returns the URI associated with a document node.
- The spec requires that if `document-uri(D) = U`, then `doc(U) is D` -- i.e.,
  `fn:doc` and `fn:document-uri` must be consistent inverses.

### 5.5 URI Encoding Functions

The spec provides three functions with distinct purposes:

| Function | Purpose | Preserves |
|----------|---------|-----------|
| `fn:encode-for-uri()` | Encode a single URI path segment | Only unreserved chars: `A-Za-z0-9-._~` |
| `fn:iri-to-uri()` | Convert an IRI to a URI | ASCII chars valid in URIs |
| `fn:escape-html-uri()` | Escape for HTML href attributes | All printable ASCII |

### 5.6 Implications for eXist-db

The W3C spec gives implementations freedom to define how URIs map to resources. However,
it clearly expects:

1. **`fn:doc` accepts URIs**, not raw names. Implementations must define their URI scheme.
2. **`fn:document-uri` returns URIs**, and round-tripping through `fn:doc` must work.
3. **`fn:encode-for-uri` exists precisely for encoding name components** into URI segments.

This means eXist-db's XQuery API should:
- Accept **URIs** where parameters are documented as URIs (e.g., `$collection-uri`).
- Accept **names** where parameters are documented as names (e.g., `$new-collection`).
- Internally convert names to URI segments using `fn:encode-for-uri` semantics (not
  `URLEncoder`).
- Return URIs from functions documented as returning URIs/paths.

---

## 6. Proposed Plan

### Phase 1: Define and Document the Conceptual Model

1. **Write a specification** defining Collection Name, Document Name, Database Path,
   Database URI, and xmldb URI (as outlined in Section 2).
2. **Audit every `xmldb:*` function signature** and classify each parameter as either a
   "name," "path," or "URI." Update documentation strings accordingly.
3. **Publish the specification** as part of eXist-db's developer documentation so that
   all future API work follows consistent terminology.

### Phase 2: Fix the Encoding Infrastructure

4. **Replace `URLDecoder`/`URLEncoder` with RFC 3986-compliant encoding** in `XmldbURI`.
   `URLDecoder` decodes `+` as space (correct for form data, wrong for URI paths).
   Replace with a decoder that only handles `%XX` sequences, preserving `+` literally.
   The existing `URIUtils.encodeForURI()` is closer to correct but should be audited
   against RFC 3986 Section 2.1.

5. **Separate name storage from URI representation in `XmldbURI`.**  Currently,
   `XmldbURI` stores the percent-encoded form and decodes on demand. Consider instead
   storing the **decoded name** as the canonical form and encoding on demand for URI
   contexts. This makes the name the source of truth and avoids double-encoding bugs.

6. **Relax `XmldbURI.create()` to accept names containing `[`, `]`, and other
   characters** that are valid in resource names but not in URI path segments. This
   requires constructing the internal URI from individually-encoded path segments rather
   than parsing the entire string as a URI.

### Phase 3: Fix the XQuery API

7. **Make `xmldb:create-collection($target, $name)` accept a literal name** for `$name`.
   Internally, encode the name before constructing the `XmldbURI`. The function should
   accept `"hello world"` and create a collection named `hello world`.

8. **Make `xmldb:store($collection, $name, $content)` accept both encoded URIs and
   literal names** -- or, preferably, define separate parameters clearly. If `$collection`
   is a "collection URI," it should be percent-encoded. If `$name` is a "document name,"
   it should be literal.

9. **Fix `xmldb:encode()` and `xmldb:decode()`** to use RFC 3986 percent-encoding instead
   of `URLEncoder`/`URLDecoder`. Deprecate these in favor of the standard
   `fn:encode-for-uri()` where possible.

10. **Ensure `fn:doc()` and `fn:collection()` work with eXist-db's URI scheme.** Document
    the expected URI format (e.g., `xmldb:exist:///db/my%20collection/doc.xml` or
    `/db/my%20collection/doc.xml`). Ensure round-tripping:
    `fn:doc(fn:document-uri($node))` must return the same node.

### Phase 4: Fix the REST, WebDAV, and XML-RPC APIs

11. **REST API**: Verify that the HTTP server's percent-decoding produces the correct
    resource name (not double-decoded). Ensure `+` in URL paths is preserved as `+`, not
    converted to space.

12. **WebDAV**: Audit the Milton integration to ensure resource names are correctly
    round-tripped. WebDAV clients typically handle encoding transparently, but verify
    with names containing spaces, Unicode, brackets, and `+`.

13. **XML-RPC**: Since XML-RPC passes names as plain strings in XML, ensure these are
    treated as literal names, not URIs, when constructing `XmldbURI` internally.

### Phase 5: Storage Layer Considerations

14. **Audit the storage layer** (`NativeBroker`, `BTree`, etc.) to determine whether
    resource names are stored in encoded or decoded form. If encoded, a migration may be
    needed.

15. **Consider whether `hello%20world` and `hello world` should be distinct names.** Per
    the proposed conceptual model, they should be (one contains `%20`, the other a space).
    This requires the storage layer to store decoded names and encode only at the URI
    boundary.

16. **If storage format changes are required**, plan for a data migration tool and target
    a major version release (e.g., eXist-db 7.0 or 8.0).

### Phase 6: Testing and Compatibility

17. **Write comprehensive tests** for each API with resource names containing:
    - Spaces (`hello world`)
    - Unicode (`résumé.xml`, `\u00E0`, CJK characters)
    - Brackets (`[Content_Types].xml`)
    - Plus signs (`A+B.xml`)
    - Percent signs (`100%done.xml`)
    - Already-encoded strings (`hello%20world.xml` as a literal name)
    - Mixed cases (encoded parent path + literal child name)

18. **Document migration/compatibility impact** for users upgrading from versions where
    names were silently encoded.

---

## 7. Risk Assessment

| Risk | Mitigation |
|------|-----------|
| Breaking existing databases that store names in encoded form | Provide migration tooling; target a major version release |
| Breaking existing XQuery applications that pre-encode names | Provide a compatibility mode or deprecation period; document clearly |
| Inconsistency with BaseX or other implementations | Document eXist-db's URI scheme explicitly; follow W3C spec's implementation-defined latitude |
| Performance impact of encoding/decoding changes | Profile `XmldbURI` operations; the current `URLDecoder.decode()` call is already noted as a caching candidate (line 390: `//TODO: we might want to cache this value`) |

---

## 8. Summary

The root cause of issues #3795, #3943, #1824, and #4469 is that eXist-db treats resource
**names** as **URI components** throughout its stack. The fix requires:

1. **Conceptual clarity**: Define name vs. URI and enforce the distinction.
2. **Correct encoding**: Replace `URLEncoder`/`URLDecoder` with RFC 3986 percent-encoding.
3. **API consistency**: Parameters labeled "name" accept literal names; parameters labeled
   "URI" accept percent-encoded URIs.
4. **Storage truth**: Store decoded names; encode only at API boundaries.

BaseX's approach -- treating resource paths as plain strings and restricting database
names to a safe ASCII subset -- validates this direction. The W3C XQuery spec gives
implementations the freedom to define their URI-to-resource mapping, but requires
internal consistency (especially the `fn:doc`/`fn:document-uri` round-trip invariant).

This is a significant undertaking that touches every layer of eXist-db, but the current
state produces real user-facing bugs and will continue to generate edge cases until
addressed systematically.
