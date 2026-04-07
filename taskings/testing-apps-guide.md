# Testing Apps on eXist-db 7.0 (next-v2)

## Quick Start

Pull the pre-built Docker image:

```bash
docker pull joewiz/existdb:next-v2
docker run -d --name existdb-next -p 8080:8080 -p 8443:8443 joewiz/existdb:next-v2
```

Or build locally from the next-v2 branch (requires Jackrabbit Jakarta prerequisite):

```bash
# Build prerequisite: Jakarta EE 10 transform of Jackrabbit WebDAV
git clone https://github.com/joewiz/jackrabbit-webdav-jakarta.git
cd jackrabbit-webdav-jakarta
mvn install
cd ..
```

```bash
git clone https://github.com/joewiz/exist.git -b next-v2 exist-next-v2
cd exist-next-v2
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -T1.5C clean package -DskipTests \
  -Ddependency-check.skip=true -Ddocker=true \
  -P 'skip-build-dist-archives,!build-dist-archives,!mac-dmg-on-mac,!codesign-mac-dmg,!mac-dmg-on-unix,!installer,!concurrency-stress-tests,!micro-benchmarks,!appassembler-booter' \
  -pl exist-docker -am
cp exist-docker/target/classes/Dockerfile exist-docker/target/exist-docker-*-docker-dir/Dockerfile
docker build -t joewiz/existdb:next-v2 exist-docker/target/exist-docker-*-docker-dir/
docker run -d --name existdb-next -p 8080:8080 -p 8443:8443 joewiz/existdb:next-v2
```

The image comes with 19 pre-installed app packages via autodeploy. Access at: http://localhost:8080/exist/

**Default credentials**: admin / (empty password)

## eXist-db Source

| | Repo | Branch |
|-|------|--------|
| **Integration branch** | [joewiz/exist](https://github.com/joewiz/exist) | `next-v2` |
| **Individual PRs** | [joewiz/exist](https://github.com/joewiz/exist) | `v2/*` branches (see [consolidation report](v2-consolidation-report.md)) |

## App Packages

### A. Website Apps

These are the apps that power exist-db.org and the new developer experience, built with the [Jinks](https://github.com/eeditiones/jinks) template engine and [exist-site-shell](https://github.com/joewiz/website-next).

| App | Repo | Branch | Build |
|-----|------|--------|-------|
| **Website Shell** | [joewiz/website-next](https://github.com/joewiz/website-next) | `main` | `npm install && npm run build` |
| **Dashboard** | [joewiz/dashboard-next](https://github.com/joewiz/dashboard-next) | `master` | `npm install && npm run build` |
| **Documentation** | [joewiz/documentation-next](https://github.com/joewiz/documentation-next) | `main` | `npm install && npm run build` |
| **Wiki** | [joewiz/wiki-next](https://github.com/joewiz/wiki-next) | `master` | `npm install && npm run build` |
| **Notebook** | [joewiz/notebook](https://github.com/joewiz/notebook) | `main` | `npm install && npm run build` |

**Website Shell** — Shared navigation, header, footer, and search across all eXist-db apps. Built as a Jinks profile that each app extends, giving all apps a consistent look and feel. Includes site-wide search powered by Lucene.

**Dashboard** (replaces old dashboard + monex) —
- Package manager: install, remove, and update XAR packages
- User/group management with permission editor
- System info: memory, running queries, cache statistics
- Query profiling: real-time timing, memory, and index usage via `util:profile()`
- Index browser: inspect Lucene, range, and structural indexes
- Replaces monex's monitoring features in a unified interface

**Documentation** (replaces old doc app + function docs) —
- All eXist-db documentation articles in one place
- Integrated function reference browser (absorbs function-documentation app)
- Full-text search across articles and function signatures
- DocBook source rendered via TEI Publisher's processing model
- Inline code editor (via JinnTap) for trying examples

**Wiki** (replaces AtomicWiki) —
- News, blog posts, and release announcements for the eXist-db project
- Full archive of posts from 2007–present migrated from the old AtomicWiki
- Markdown authoring with live preview
- Tag-based navigation and archive browsing
- Admin interface for creating and editing posts

**Notebook** (replaces sandbox) —
- Interactive XQuery sandbox with multi-cell evaluation
- Tutorial content covering new eXist 7.0 features (CSV, query profiling, etc.)
- Results displayed inline with syntax highlighting
- Share notebooks as URLs

### B. Platform Packages

Core infrastructure that ships with eXist-db.

| Package | Repo | Branch | Build |
|---------|------|--------|-------|
| **exist-api** | [joewiz/exist-api](https://github.com/joewiz/exist-api) | `develop` | `mvn package` |
| **eXide** | [joewiz/eXide](https://github.com/joewiz/eXide) | `pr/modernize-eXide` | `ant` |
| **exist-markdown** | [joewiz/exist-markdown](https://github.com/joewiz/exist-markdown) | `feature/commonmark-gfm` | `mvn package` |
| **Jinks** | [joewiz/jinks](https://github.com/joewiz/jinks) | `exist-site-profile` | `npm install && npm run build` |
| **Jinks Templates** | [joewiz/jinks-templates](https://github.com/joewiz/jinks-templates) | `master` | `npm install && npm run build` |

**exist-api** — Unified REST API for eXist-db, replacing the scattered endpoints across packageservice, dbutils, and exist-lsp. Provides:
- Database management (collections, resources, permissions)
- Query execution with streaming results
- Language services (diagnostics, completions, hover, go-to-definition) for IDE integration
- User and group management
- Package management API
- Cross-app linking and search

**eXide** (modernized) —
- CodeMirror 6 editor (replaces CodeMirror 5) with improved performance and accessibility
- REx-based XQuery parser for syntax highlighting and error detection
- LSP integration via exist-api for diagnostics, completions, hover, and go-to-definition
- WebSocket support for streaming query evaluation
- New favicon and UI refinements
- Compile/evaluate timing breakdown in results bar

**exist-markdown** — CommonMark + GFM (GitHub Flavored Markdown) rendering, replacing the old custom Markdown parser. Used by the wiki and notebook apps.

**Jinks** / **Jinks Templates** — Template engine and app generator from [e-editiones](https://e-editiones.org). Provides the `extends` / `block` template inheritance that all website apps use for consistent layout.

> **Jinks Templates note**: The `joewiz/jinks-templates` fork includes a workaround for a `map:merge` key enumeration issue discovered during eXist 7.0 testing. The upstream [eeditiones/jinks-templates](https://github.com/eeditiones/jinks-templates) will work once the workaround is upstreamed or the underlying issue is confirmed fixed.

### C. EXPath Module XARs

Java-based XAR packages providing EXPath standard module implementations.

| Package | Repo | Branch | Build |
|---------|------|--------|-------|
| **EXPath File** | [joewiz/exist-file](https://github.com/joewiz/exist-file) | `main` | `mvn package` |
| **EXPath Binary** | [joewiz/exist-binary](https://github.com/joewiz/exist-binary) | `main` | `mvn package` |
| **EXPath Crypto** | [joewiz/exist-crypto](https://github.com/joewiz/exist-crypto) | `master` | `mvn package` |
| **EXPath HTTP Client** | [joewiz/exist-http-client](https://github.com/joewiz/exist-http-client) | `master` | `mvn package` |

### D. Stock Packages (unchanged)

These ship with the standard eXist-db distribution and are included as-is:

- **functx** — FunctX XQuery function library
- **packageservice** — Package repository service
- **semver-xq** — Semantic versioning library
- **templating** — HTML templating library

## Pre-installed in Docker Image

The Docker image includes all packages above, installed via autodeploy in dependency order:

```
001-exist-api.xar          # Platform API (base dependency)
002-jinks-templates.xar    # Template engine library
003-jinks.xar              # App generator
004-exist-markdown.xar     # Markdown rendering
005-exist-site-shell.xar   # Website shell (nav, chrome)
010-dashboard.xar          # Dashboard app
010-docs.xar               # Documentation app
010-wiki.xar               # Wiki/blog app
010-notebook.xar           # XQuery sandbox
eXide.xar                  # XQuery IDE
exist-binary.xar           # EXPath Binary module
exist-crypto.xar           # EXPath Crypto module
exist-file.xar             # EXPath File module
exist-http-client.xar      # EXPath HTTP Client module
functx.xar                 # FunctX library
packageservice.xar         # Package repository
semver-xq.xar              # Semver library
templating.xar             # HTML templating
```

## Installing Packages Manually

Use `xst` (the standard eXist-db CLI tool) for package management:

```bash
# Install from local XAR
xst package install path/to/package.xar --force

# Requires .env in working directory:
#   EXISTDB_SERVER=http://localhost:8080/exist
#   EXISTDB_USER=admin
#   EXISTDB_PASS=
```

## What's New in next-v2

The next-v2 branch integrates 15 feature branches for eXist-db 7.0:

- **XQuery 4.0 Parser** — pipeline, focus functions, keyword args, string templates, ternary conditional, braced if, otherwise, while clause, and more. Version-gated: requires `xquery version "4.0"` declaration. Feature flag: `exist.xquery4.enabled`.
- **XQuery 4.0 Functions** — 82 new/updated functions across fn:, map:, array:, math: namespaces.
- **W3C XQuery Update Facility 3.0** — copy-modify-return, insert/delete/replace/rename expressions.
- **XQuery Full Text 3.0** — `contains text` with stemming, thesaurus, wildcards, proximity.
- **Lucene 10 Upgrade** — Lucene 4.10.4 → 10.3.0, ICU4J 59.1 → 76.1 (Unicode 16). PR #6146 by @duncdrum.
- **Saxon 12 Upgrade** — Saxon-HE 9.9 → 12.5, eliminates the exist-saxon-regex fork.
- **Jetty 12 Upgrade** — Jetty 11 → 12, Jakarta Servlet 6.0.
- **Jackrabbit WebDAV** — Replaces Milton with Apache Jackrabbit. Shared locks (Level 2), dead properties, lock persistence. Litmus compliance CI.
- **Serialization Compliance** — XML, HTML, XHTML, JSON, text, adaptive, CSV output methods per W3C spec.
- **Hand-Written Parser** — Optional rd parser (`-Dexist.parser=rd`), 15-82x faster than ANTLR 2.
- **Query Profiling** — `util:time()`, `util:memory()`, `util:track()`, `util:explain()`, `util:profile()`.
- **declare decimal-format** — XQuery 3.1 decimal format declarations.
- **XQ 3.1 Compliance Fixes** — casting, error codes, fn:not, path dedup, format-date, and more.
- **New Dashboard** — Ground-up rewrite with Jinks templates, absorbs monitoring features from monex.
- **New Documentation** — Rebuilt on Jinks/site-shell with search, function browser, and article editor.
- **New Wiki** — Blog/wiki platform with Markdown support, migrated archive from the old AtomicWiki.
- **New Notebook** — XQuery sandbox with interactive evaluation and tutorial content.
- **Modernized eXide** — CodeMirror 6 editor, REx-based parser, LSP integration via exist-api.

## XQTS Compliance Scores

| Suite | next-v2 | develop | Improvement |
|-------|---------|---------|-------------|
| **QT4** | 71,412 / 83,228 (85.8%) | 31,674 / 36,965 (85.7%) | +39,738 pass, +0.1% rate |
| **XQ 3.1** | 48,598 / 52,374 (92.8%) | 24,025 / 26,773 (89.7%) | +24,573 pass, +3.1% rate |
| **FTTS** | 1,320 / 1,334 (99.0%) | 661 / 667 (99.1%) | +659 pass |

exist-core unit tests: 6,240 / 6,357 (98.2%) — 15 failures, all pre-existing on develop.

## Feedback

Please report issues to https://github.com/eXist-db/exist/issues or discuss on the [eXist-db Slack](https://exist-db.slack.com/).
