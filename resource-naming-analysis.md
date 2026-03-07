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

### 4.3 Explicit Character Rules

BaseX defines precise character rules for database names:

- **Allowed**: Letters, numbers, and `` !#$%&'()+-=@[]^_{}~ `` and backticks
- **Disallowed**: `,` `?` `*` (reserved for glob syntax); `;` (command separator);
  `\` `/` (path separators); `:` `"` `<` `>` `|` (invalid on Windows)
- Names must **not** start or end with `.` (hidden folder convention)
- As of BaseX 8.5, dots are allowed within names but not at boundaries

This explicit allowlist/denylist approach avoids ambiguity -- users know exactly what is
permitted.

### 4.4 Known Tradeoffs and Bugs

BaseX explicitly acknowledges that its "extensions for handling databases don't go 100%
hand in hand with standard XQuery functions" (see [BaseX issue #1172](https://github.com/BaseXdb/basex/issues/1172)). When database
names contain characters like `^` that are invalid in URIs, `fn:base-uri()` fails.
BaseX chose backward compatibility over full XQuery compliance, recommending
vendor-specific functions (`db:name`, `db:path`) for path manipulation.

Additional known issues illustrate the broader difficulty of this problem space:

- [BaseX #1473](https://github.com/BaseXdb/basex/issues/1473): WebDAV `PROPFIND` fails
  on resources with **spaces** in their names, due to a bug in the Milton WebDAV library's
  percent-encoding handling -- not in BaseX itself.
- [BaseX #1706](https://github.com/BaseXdb/basex/issues/1706): A migration failed because
  a **semicolon** in a resource path was interpreted as a command separator.
- [BaseX #1344](https://github.com/BaseXdb/basex/issues/1344): Forward slashes in JSON
  string values were incorrectly escaped during serialization.

These demonstrate that even BaseX's more conservative approach does not eliminate
encoding-related edge cases entirely.

### 4.5 Key Lesson for eXist-db

BaseX's design validates the core insight of issue #3795: **resource names and URIs are
fundamentally different things and should be treated separately.** BaseX sidesteps many
problems by restricting database names to ASCII and treating resource paths as opaque
strings. eXist-db could adopt a similar strategy while being more permissive with Unicode
in names. However, BaseX's experience also shows that any approach involving multiple
transport layers (REST, WebDAV) will encounter encoding edge cases, so explicit
documentation and well-defined character rules are essential.

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

---

## 9. Lowest Common Denominator: Resource Naming Restrictions

If eXist-db were to enforce resource naming restrictions that guarantee all interfaces
work correctly **without any fixes to the encoding infrastructure**, the "safe" character
set would be quite limited. This section analyzes what each interface can handle today and
identifies the intersection.

### 9.1 Per-Interface Constraints

| Character / Category | REST API | WebDAV (Milton) | XML-RPC | XQuery (`xmldb:*`) | Java XML:DB | `XmldbURI` / `java.net.URI` |
|---------------------|----------|-----------------|---------|-------------------|-------------|---------------------------|
| ASCII alphanumeric `A-Za-z0-9` | OK | OK | OK | OK | OK | OK |
| Hyphen `-` | OK | OK | OK | OK | OK | OK (unreserved) |
| Underscore `_` | OK | OK | OK | OK | OK | OK (unreserved) |
| Period `.` | OK | OK | OK | OK | OK | OK (unreserved) |
| Tilde `~` | OK | OK | OK | OK | OK | OK (unreserved) |
| Space ` ` | Needs `%20` in URL | Milton bugs ([BaseX #1473]) | OK (plain string) | **Fails** in `XmldbURI.create()` | **Fails** in `XmldbURI.create()` | **Rejected** by `java.net.URI` |
| Unicode (é, ä, CJK) | Needs `%`-encoding in URL | Encoding-dependent | OK (XML string) | **Fails** unless pre-encoded | **Fails** unless pre-encoded | **Rejected** by `java.net.URI` |
| Square brackets `[]` | Needs `%5B/%5D` | OK | OK | **Fails** in `java.net.URI` | **Fails** in `java.net.URI` | **Rejected** (IPv6 syntax) |
| Hash `#` | **Interpreted as fragment** | OK | OK | **Fails** (URI fragment) | **Fails** (URI fragment) | **Rejected** (fragment delimiter) |
| Question mark `?` | **Interpreted as query** | OK | OK | **Fails** (query delimiter) | **Fails** (query delimiter) | **Rejected** (query delimiter) |
| Plus `+` | **Decoded as space** by `URLDecoder` | OK | OK | OK (not a URI special) | OK (sub-delim, allowed in path) | OK in `java.net.URI`, but **mangled** by `getCollectionPath()` |
| Colon `:` | OK (pchar) | OK | OK | OK (pchar) | OK (pchar) | OK (pchar) |
| At `@` | OK (pchar) | OK | OK | OK (pchar) | OK (pchar) | OK (pchar) |
| Sub-delims `!$&'()*+,;=` | OK (pchar) | Varies | OK | Most OK in `java.net.URI` | Most OK | Allowed in URI path |
| Curly braces `{}` | Needs encoding | OK | OK | **Fails** in `java.net.URI` | **Fails** | **Rejected** ("not legal URI characters") |
| Pipe `\|` | Needs encoding | OK | OK | **Fails** in `java.net.URI` | **Fails** | **Rejected** |
| Backslash `\` | Needs `%5C` | OK | OK | **Fails** in `java.net.URI` | **Fails** | **Rejected** |
| Caret `^` | Needs encoding | OK | OK | **Fails** in `java.net.URI` | **Fails** | **Rejected** |
| Backtick `` ` `` | Needs encoding | OK | OK | **Fails** in `java.net.URI` | **Fails** | **Rejected** |
| Percent `%` | Must be `%25` | Must be `%25` | OK (literal) | Ambiguous | Ambiguous | OK only as `%XX` escape |
| Forward slash `/` | **Path separator** | **Path separator** | **Path separator** | **Path separator** | **Path separator** | **Path separator** |
| Null `\0` | **Invalid** | **Invalid** | **Invalid** (XML) | **Invalid** (XML) | **Invalid** | **Invalid** |
| Control chars (`\x01-\x1F`) | **Invalid** | **Invalid** | **Invalid** (XML) | **Invalid** (XML) | **Invalid** | **Invalid** |

### 9.2 The Current "Safe" Set (No Changes Required)

Characters that work across **all** eXist-db interfaces today without any encoding:

```
A-Z  a-z  0-9  -  .  _  ~
```

Plus the "pchar" characters that `java.net.URI` allows unescaped in path segments:

```
!  $  &  '  (  )  *  ,  ;  =  :  @
```

**But** `+` must be excluded due to the `URLDecoder` bug (decoded as space in
`XmldbURI.getCollectionPath()`), and `:` may cause issues on Windows filesystems
if data is exported.

So the practical safe set is:

```
Safe:     A-Z a-z 0-9 - . _ ~ ! $ & ' ( ) * , ; = @
Avoid:    + (URLDecoder bug), : (Windows), % (ambiguity)
Reject:   / (path separator), # ? (URI delimiters), [ ] { } | \ ^ ` (java.net.URI),
          space, Unicode, null, control chars
```

### 9.3 What Could Be Made Safe With Targeted Fixes

If eXist-db applies the Phase 2 fixes from Section 6 (fixing the encoding infrastructure),
the safe set expands dramatically:

| Fix | Characters Unlocked |
|-----|-------------------|
| Build `XmldbURI` from individually-encoded segments instead of parsing whole string as URI | Space, Unicode, `[]`, `{}`, `\|`, `\`, `^`, `` ` `` |
| Replace `URLDecoder` with RFC 3986 decoder | `+` (no longer mangled to space) |
| Store decoded names, encode at boundaries | `%` (literal percent in names) |

After these fixes, the only characters that should remain **permanently forbidden** are:

```
Permanently forbidden:
  /     Path separator — fundamental to the collection hierarchy
  \0    Null byte — invalid in XML, Java strings, and most transports
  \x01-\x1F  Control characters — invalid in XML

Permanently problematic (recommend avoiding):
  #     Fragment delimiter in URIs — causes issues in REST API URLs
  ?     Query delimiter in URIs — causes issues in REST API URLs
```

### 9.4 Recommended Naming Policy

Taking all interfaces into account, here is a practical naming policy that eXist-db could
adopt and enforce:

#### Tier 1: Always Safe (recommended)
```
A-Z  a-z  0-9  -  _  .  ~
```
These characters require no encoding in any context.

#### Tier 2: Safe with Proper Encoding (supported)
```
Space, Unicode (accented Latin, CJK, Arabic, etc.),
! $ & ' ( ) * + , ; = : @
[ ] { } | \ ^ `
%  (as a literal character in the name, distinct from percent-encoding)
```
These work when the encoding infrastructure correctly encodes/decodes at API boundaries.
They require the Phase 2 fixes.

#### Tier 3: Forbidden (never allowed in resource names)
```
/     Forward slash (path separator)
\0    Null byte
\x01-\x1F  Control characters (except \x09 tab, \x0A newline, \x0D carriage return,
            which could theoretically appear but are strongly discouraged)
```

#### Tier 4: Discouraged (technically possible but problematic)
```
#     Fragment delimiter — breaks REST API URL construction
?     Query delimiter — breaks REST API URL construction
```
If these are allowed, the REST API must always percent-encode them in URLs, and users
must be aware they cannot type these literally in browser address bars.

### 9.5 Comparison with BaseX

| Aspect | BaseX | Proposed eXist-db |
|--------|-------|-------------------|
| Unicode in names | **Not allowed** in database names | **Allowed** (with encoding at boundaries) |
| Spaces in names | **Not allowed** in database names | **Allowed** (with encoding at boundaries) |
| Forbidden chars | `, ? * ; \ / : " < > \|` | `/ \0 \x01-\x1F` (and discourage `# ?`) |
| Approach | Restrict names to avoid encoding | Accept any name, encode at API boundaries |

eXist-db's approach is more permissive, which is appropriate for an XML database that
stores documents with arbitrary filenames (e.g., `[Content_Types].xml` from OOXML). But
it requires the encoding infrastructure to be correct — which is exactly what issues
#3795, #3943, #1824, and #4469 demonstrate is not yet the case.

---

## 10. Test Coverage Map

The following test classes provide systematic coverage of resource naming across
eXist-db's interfaces:

| Test Class | Location | What It Tests |
|-----------|----------|---------------|
| `ResourceNamingTest` | `exist-core/.../xmldb/ResourceNamingTest.java` | Unit tests for URI encoding/decoding of special characters via `URIUtils` and `XmldbURI` |
| `URIUtilsEncodingTest` | `exist-core/.../xquery/util/URIUtilsEncodingTest.java` | Unit tests for `URIUtils.encodeForURI()`, round-trip fidelity, idempotency |
| `ResourceNamingIntegrationTest` | `exist-core/.../xmldb/ResourceNamingIntegrationTest.java` | Integration tests: Java XML:DB API store/retrieve/copy/move/rename/remove with special names; XQuery `fn:doc()`/`fn:collection()` access |
| `RESTResourceNamingTest` | `exist-core/.../http/RESTResourceNamingTest.java` | REST API PUT/GET/DELETE with special character names via percent-encoded URLs |
| `CrossApiResourceNamingTest` | `exist-core/.../http/CrossApiResourceNamingTest.java` | Cross-API consistency: store via REST → retrieve via XML-RPC, and vice versa |
| `XQueryResourceNamingTest` | `exist-core/.../xquery/functions/xmldb/XQueryResourceNamingTest.java` | XQuery `xmldb:store/rename/copy/move/remove`, `fn:doc-available`, `fn:collection`, `xmldb:create-collection` with special names |

### Coverage Gaps

| Gap | Priority | Notes |
|-----|----------|-------|
| WebDAV with special character names | Medium | Requires Milton client library in test classpath; existing tests use only ASCII names |
| XML-RPC standalone (without REST cross-reference) | Low | Partially covered by `CrossApiResourceNamingTest` |
| `+` sign round-trip via `XmldbURI.getCollectionPath()` | High | Known bug (#1824); needs explicit regression test |
| `hello%20world` as a **literal name** (not encoding of space) | High | Tests the conceptual model: `%20` in a name ≠ space |
| Concurrent access to special-named resources | Low | Edge case; existing concurrency tests use simple names |
