# Lucene 10 Highlighting Tutorial

This tutorial shows how to use eXist-db's Lucene full-text search highlighting,
from basic queries through google-style search result snippets and full-document
highlighting. All examples assume Lucene 10 with the Matches API highlighting
engine.

## Setup: Sample Data and Index Configuration

First, store some sample documents and configure a Lucene index.

### Sample data

```xquery
xquery version "3.1";

(: Store a collection of articles :)
let $articles :=
    <articles>
        <article id="1">
            <title>The Quick Brown Fox</title>
            <body>
                <p>The quick brown fox jumps over the lazy dog in the meadow.
                Birds sing their morning songs while the sun rises above the
                distant mountains.</p>
                <p>In the forest near the river bank, tall trees with green
                leaves provide shade. Wild flowers grow along the ancient path
                through the woods.</p>
                <p>The fox returned to its den as evening fell. Stars appeared
                one by one in the darkening sky above the quiet meadow.</p>
            </body>
        </article>
        <article id="2">
            <title>Forest Wildlife</title>
            <body>
                <p>Deep in the ancient forest, wildlife thrives undisturbed.
                The quick movements of deer through the underbrush startle
                nesting birds from their morning rest.</p>
                <p>A brown bear ambles along the river, searching for salmon.
                The forest floor is carpeted with fallen leaves and moss.</p>
            </body>
        </article>
        <article id="3">
            <title>Morning in the Mountains</title>
            <body>
                <p>Dawn breaks over the mountain ridge, painting the sky in
                shades of orange and gold. The morning air is crisp and clear,
                carrying the scent of pine.</p>
                <p>A fox picks its way carefully along the rocky trail. Far
                below, the river winds through the valley like a silver ribbon,
                its quick waters catching the first light of day.</p>
            </body>
        </article>
    </articles>

return (
    xmldb:create-collection("/db", "tutorial"),
    xmldb:store("/db/tutorial", "articles.xml", $articles)
)
```

### Index configuration

Store this as `/db/system/config/db/tutorial/collection.xconf`:

```xquery
xquery version "3.1";

let $config :=
    <collection xmlns="http://exist-db.org/collection-config/1.0">
        <index>
            <lucene>
                <text qname="article"/>
                <text qname="p"/>
                <text qname="title"/>
            </lucene>
        </index>
    </collection>

return (
    xmldb:create-collection("/db/system/config/db", "tutorial"),
    xmldb:store("/db/system/config/db/tutorial", "collection.xconf", $config),
    xmldb:reindex("/db/tutorial")
)
```

---

## 1. Basic Queries with ft:query

The `ft:query()` function searches for text in indexed elements. It returns
matching nodes with match metadata attached.

### Simple term search

```xquery
xquery version "3.1";

(: Find paragraphs containing "fox" :)
for $hit in collection("/db/tutorial")//p[ft:query(., "fox")]
return $hit
```

This returns the raw `<p>` elements — no highlighting yet. The match metadata
is attached to the nodes but not visible until you expand them.

### Phrase search

```xquery
(: Find the exact phrase "quick brown fox" :)
for $hit in collection("/db/tutorial")//p[ft:query(., '"quick brown fox"')]
return $hit
```

### Boolean search

```xquery
(: Find paragraphs with both "fox" AND "river" :)
for $hit in collection("/db/tutorial")//p[ft:query(., "+fox +river")]
return $hit
```

### Wildcard search

```xquery
(: Find words starting with "morn" :)
for $hit in collection("/db/tutorial")//p[ft:query(., "morn*")]
return $hit
```

### Proximity search

Use XML query syntax for proximity (near) queries:

```xquery
(: Find "fox" near "river" within 10 words :)
for $hit in collection("/db/tutorial")//p[ft:query(.,
    <query><near slop="10">fox river</near></query>)]
return $hit
```

### Fuzzy search

```xquery
(: Find approximate matches for "quik" (matches "quick") :)
for $hit in collection("/db/tutorial")//p[ft:query(., "quik~")]
return $hit
```

---

## 2. Full-Element Highlighting with ft:highlight

`ft:highlight()` returns a copy of the matched element with all matching
terms wrapped in `<exist:match>` elements. The full structure of the
original element is preserved.

### Highlighting a single hit

```xquery
xquery version "3.1";

declare namespace exist = "http://exist.sourceforge.net/NS/exist";

for $hit in collection("/db/tutorial")//p[ft:query(., "fox")]
return ft:highlight($hit)
```

**Output:**
```xml
<p>The quick brown <exist:match>fox</exist:match> jumps over the lazy dog
in the meadow. Birds sing their morning songs while the sun rises above
the distant mountains.</p>
```

### Highlighting phrase matches

With the Matches API, phrase matches are highlighted as a single span:

```xquery
for $hit in collection("/db/tutorial")//p[ft:query(., '"quick brown fox"')]
return ft:highlight($hit)
```

**Output:**
```xml
<p>The <exist:match>quick brown fox</exist:match> jumps over the lazy dog
in the meadow. ...</p>
```

### Highlighting proximity matches

Proximity queries highlight the entire matching span — this was broken
in older versions (issue #833) and is now fixed:

```xquery
for $hit in collection("/db/tutorial")//p[ft:query(.,
    <query><near slop="3">quick fox</near></query>)]
return ft:highlight($hit)
```

**Output:**
```xml
<p>The <exist:match>quick brown fox</exist:match> jumps over the lazy dog
in the meadow. ...</p>
```

### Highlighting at the article level

You can highlight an entire article by querying child elements but
highlighting the parent:

```xquery
for $article in collection("/db/tutorial")//article[ft:query(p, "fox")]
return ft:highlight($article)
```

This returns the full `<article>` with all `<p>` elements that contain
"fox" having `<exist:match>` wrappers around the matched terms.

---

## 3. Google-Style Search Result Snippets with ft:get-passages

`ft:get-passages()` extracts the **best-scoring** text passages from a hit,
ranked by match density. This is what you want for search result pages —
concise, relevant snippets showing why each result matched.

### Basic passage extraction

```xquery
xquery version "3.1";

declare namespace exist = "http://exist.sourceforge.net/NS/exist";

for $hit in collection("/db/tutorial")//article[ft:query(., "fox")]
let $title := $hit/title/string()
let $passages := ft:get-passages($hit, 2)  (: top 2 passages :)
return
    <result>
        <title>{$title}</title>
        {$passages}
    </result>
```

**Output:**
```xml
<result>
    <title>The Quick Brown Fox</title>
    <exist:passage score="3.00">The quick brown <exist:match>fox</exist:match>
    jumps over the lazy dog in the meadow. ...</exist:passage>
    <exist:passage score="2.00">The <exist:match>fox</exist:match> returned to
    its den as evening fell. ...</exist:passage>
</result>
```

Passages are ranked by score — the passage with the most (and densest)
matches comes first.

### Configuring passage width

Control how much text each passage contains:

```xquery
(: Shorter passages — 80 characters :)
ft:get-passages($hit, 3, <options width="80"/>)

(: Longer passages — 300 characters :)
ft:get-passages($hit, 3, <options width="300"/>)
```

### Character-based breaking

By default, passages break at sentence boundaries. Use `break="character"`
for fixed-width passages:

```xquery
ft:get-passages($hit, 3, <options width="100" break="character"/>)
```

### Setting defaults in collection.xconf

You can set default passage width and break type in the index configuration
so that `ft:get-passages()` uses them automatically without inline options:

```xml
<collection xmlns="http://exist-db.org/collection-config/1.0">
    <index>
        <lucene>
            <text qname="p" passage-width="200" passage-break="sentence"/>
        </lucene>
    </index>
</collection>
```

With this config, `ft:get-passages($hit, 3)` uses width 200 and sentence
breaking by default. Inline `<options>` still override these defaults.

The precedence order is: **inline options > collection.xconf > defaults**
(width=150, break=sentence).

---

## 4. Building a Search Results Page

Here is a complete example combining scoring, passages, and highlighting
into a search results page:

```xquery
xquery version "3.1";

declare namespace exist = "http://exist.sourceforge.net/NS/exist";

declare function local:search($query as xs:string) {
    let $hits := collection("/db/tutorial")//article[ft:query(., $query)]
    for $hit in $hits
    let $score := ft:score($hit)
    order by $score descending
    return
        <div class="search-result">
            <h3 class="result-title">
                {$hit/title/string()}
                <span class="score">({round($score * 100) div 100})</span>
            </h3>
            <div class="result-snippets">{
                for $passage in ft:get-passages($hit, 2, <options width="200"/>)
                return
                    <p class="snippet">{
                        local:render-passage($passage)
                    }</p>
            }</div>
        </div>
};

(:~
 : Render a passage, converting exist:match to HTML <mark> elements.
 :)
declare function local:render-passage($passage as element()) {
    for $node in $passage/node()
    return
        typeswitch ($node)
            case element(exist:match) return
                <mark>{$node/string()}</mark>
            case text() return
                $node
            default return
                $node
};

local:search("fox river")
```

**Output:**
```xml
<div class="search-result">
    <h3 class="result-title">
        The Quick Brown Fox
        <span class="score">(0.72)</span>
    </h3>
    <div class="result-snippets">
        <p class="snippet">In the forest near the <mark>river</mark> bank,
        tall trees with green leaves provide shade. Wild flowers grow along
        the ancient path through the woods. The <mark>fox</mark> returned to
        its den as evening fell.</p>
    </div>
</div>
```

---

## 5. Full-Document Highlighting for Detail Views

When a user clicks through to view the full document, use `ft:highlight()`
to show the complete content with all matches marked:

```xquery
xquery version "3.1";

declare namespace exist = "http://exist.sourceforge.net/NS/exist";

declare function local:highlight-document($article-id as xs:string,
                                          $query as xs:string) {
    let $article := collection("/db/tutorial")//article[@id = $article-id]
    (: Re-query to attach match metadata :)
    let $hit := $article[ft:query(., $query)]
    return
        if ($hit) then
            let $highlighted := ft:highlight($hit)
            return
                <div class="document">{
                    local:render-highlighted($highlighted)
                }</div>
        else
            <div class="document">{$article}</div>
};

(:~
 : Recursively transform exist:match elements to HTML <mark>,
 : preserving all other structure.
 :)
declare function local:render-highlighted($node as node()) as node()* {
    typeswitch ($node)
        case element(exist:match) return
            <mark>{$node/node() ! local:render-highlighted(.)}</mark>
        case element() return
            element {node-name($node)} {
                $node/@*,
                $node/node() ! local:render-highlighted(.)
            }
        default return
            $node
};

local:highlight-document("1", "fox morning")
```

**Output:**
```xml
<div class="document">
    <article id="1">
        <title>The Quick Brown <mark>Fox</mark></title>
        <body>
            <p>The quick brown <mark>fox</mark> jumps over the lazy dog in the
            meadow. Birds sing their <mark>morning</mark> songs while the sun
            rises above the distant mountains.</p>
            <p>In the forest near the river bank, tall trees with green leaves
            provide shade. Wild flowers grow along the ancient path through the
            woods.</p>
            <p>The <mark>fox</mark> returned to its den as evening fell. Stars
            appeared one by one in the darkening sky above the quiet meadow.</p>
        </body>
    </article>
</div>
```

Every occurrence of "fox" and "morning" is wrapped in `<mark>`, while the
full document structure is preserved intact.

---

## 6. Combining Field Queries with Highlighting

When your index uses named fields, you can combine content queries with
field-specific queries and highlight both:

### Index configuration with fields

```xml
<collection xmlns="http://exist-db.org/collection-config/1.0">
    <index>
        <lucene>
            <analyzer id="keyword"
                      class="org.apache.lucene.analysis.core.KeywordAnalyzer"/>
            <text qname="article">
                <field name="title" expression="title"/>
                <field name="year" expression="metadata/year" analyzer="keyword"/>
            </text>
        </lucene>
    </index>
</collection>
```

### Query and highlight

```xquery
(: Search for "fox" in articles from 1985 :)
let $hits := collection("/db/docs")//article[
    ft:query(., 'fox AND year:1985')
]
for $hit in $hits
return
    <result>
        { ft:get-passages($hit, 2) }
        <field-year>{ ft:highlight-field-matches($hit, "year") }</field-year>
    </result>
```

The text query ("fox") highlights in passages via `ft:get-passages`, while
the field value ("1985") highlights via `ft:highlight-field-matches`. The
highlighting engine correctly strips non-content field clauses so that
"year:1985" does not produce spurious highlights in the article text.

---

## 7. Advanced Query Types and Their Highlights

The Matches API correctly highlights all Lucene query types. Here is a
quick reference:

| Query | Syntax | Highlight Behavior |
|-------|--------|--------------------|
| Term | `"fox"` | Single word highlighted |
| Phrase | `'"quick brown fox"'` | Entire phrase as one span |
| Boolean AND | `"+fox +river"` | Each term highlighted separately |
| Boolean OR | `"fox OR bear"` | Matching terms highlighted |
| Wildcard | `"morn*"` | Expanded matches highlighted |
| Prefix | `"for*"` | Expanded matches highlighted |
| Fuzzy | `"quik~"` | Closest match highlighted |
| Regex | `<query><regex>qu.*k</regex></query>` | Matching terms highlighted |
| Proximity | `<query><near slop="3">fox river</near></query>` | Entire matching span highlighted |

### Overlapping matches are merged

When a boolean query produces overlapping match spans (e.g., searching for
both `"quick"` and `"quick brown fox"`), the highlights are automatically
merged into a single `<exist:match>` element — no nested or duplicate markers.

---

## Quick Reference

| Function | Purpose | Use Case |
|----------|---------|----------|
| `ft:query($node, $query)` | Full-text search | Find matching nodes |
| `ft:score($node)` | Relevance score | Sort results by relevance |
| `ft:highlight($nodes)` | Full-element highlighting | Detail/document view |
| `ft:get-passages($nodes, $max?, $opts?)` | Ranked passage extraction | Search result snippets |
| `ft:highlight-field-matches($nodes, $field)` | Field value highlighting | Field-specific display |
| `kwic:summarize($hit, $config)` | KWIC context extraction | Legacy character-counting approach |
