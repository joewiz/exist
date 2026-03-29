xquery version "3.1";

(:~ Tests for XQuery 4.0 combined axes :)
module namespace axes="http://exist-db.org/xquery/test/xq4-axes";

declare namespace test="http://exist-db.org/xquery/xqsuite";

declare variable $axes:DATA :=
    <root>
        <a id="1">
            <b id="2"/>
            <c id="3">
                <d id="4"/>
            </c>
            <e id="5"/>
        </a>
        <f id="6"/>
    </root>;

(: === following-or-self axis === :)

declare
    %test:assertEquals("3", "4", "5", "6")
function axes:following-or-self-from-c() {
    $axes:DATA//c/following-or-self::*/@id/string()
};

declare
    %test:assertEquals("1", "2", "3", "4", "5", "6")
function axes:following-or-self-from-root-child() {
    $axes:DATA/a/following-or-self::*/@id/string()
};

(: === following-sibling-or-self axis === :)

declare
    %test:assertEquals("3", "5")
function axes:following-sibling-or-self-from-c() {
    $axes:DATA/a/c/following-sibling-or-self::*/@id/string()
};

declare
    %test:assertEquals("2", "3", "5")
function axes:following-sibling-or-self-from-b() {
    $axes:DATA/a/b/following-sibling-or-self::*/@id/string()
};

(: === preceding-or-self axis === :)

declare
    %test:assertEquals("1", "2", "3")
function axes:preceding-or-self-from-c() {
    $axes:DATA//c/preceding-or-self::*/@id/string()
};

(: === preceding-sibling-or-self axis === :)

declare
    %test:assertEquals("2", "3")
function axes:preceding-sibling-or-self-from-c() {
    $axes:DATA/a/c/preceding-sibling-or-self::*/@id/string()
};

declare
    %test:assertEquals("5")
function axes:preceding-sibling-or-self-from-e-only() {
    (: e has no preceding siblings that are elements named 'e' :)
    $axes:DATA/a/e/preceding-sibling-or-self::e/@id/string()
};

(: === node test with new axes === :)

declare
    %test:assertExists
function axes:following-or-self-node-test() {
    $axes:DATA//c/following-or-self::node()
};

declare
    %test:assertEquals("3")
function axes:following-sibling-or-self-name-test() {
    $axes:DATA/a/c/following-sibling-or-self::c/@id/string()
};
