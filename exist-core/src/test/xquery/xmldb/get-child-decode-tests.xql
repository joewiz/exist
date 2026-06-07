(:
 : eXist-db Open Source Native XML Database
 : Copyright (C) 2001 The eXist-db Authors
 :
 : info@exist-db.org
 : http://www.exist-db.org
 :
 : This library is free software; you can redistribute it and/or
 : modify it under the terms of the GNU Lesser General Public
 : License as published by the Free Software Foundation; either
 : version 2.1 of the License, or (at your option) any later version.
 :
 : This library is distributed in the hope that it will be useful,
 : but WITHOUT ANY WARRANTY; without even the implied warranty of
 : MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 : Lesser General Public License for more details.
 :
 : You should have received a copy of the GNU Lesser General Public
 : License along with this library; if not, write to the Free Software
 : Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 :)
xquery version "3.1";

(:~
 : Tests for the optional $decode argument of xmldb:get-child-resources and
 : xmldb:get-child-collections. Child names are stored percent-encoded; the two-argument
 : form with $decode = true() returns the human-readable, percent-decoded names.
 :)
module namespace t = "http://exist-db.org/testsuite/xmldb-get-child-decode";

declare namespace test = "http://exist-db.org/xquery/xqsuite";

declare variable $t:COLLECTION := "/db/get-child-decode-test";

declare
    %test:setUp
function t:setup() {
    let $col := xmldb:create-collection("/db", "get-child-decode-test")
    return (
        (: names are supplied already in stored (percent-encoded) form :)
        xmldb:store($col, "with%20space.xml", <doc/>),
        xmldb:create-collection($col, "child%20space")
    )
};

declare
    %test:tearDown
function t:tearDown() {
    if (xmldb:collection-available($t:COLLECTION)) then xmldb:remove($t:COLLECTION) else ()
};

(: one-argument form: stored (percent-encoded) resource name :)
declare
    %test:assertEquals("with%20space.xml")
function t:resources-encoded-default() {
    xmldb:get-child-resources($t:COLLECTION)[contains(., "space")]
};

(: two-argument form, decode = false(): unchanged (encoded) :)
declare
    %test:assertEquals("with%20space.xml")
function t:resources-encoded-explicit() {
    xmldb:get-child-resources($t:COLLECTION, false())[contains(., "space")]
};

(: two-argument form, decode = true(): human-readable name :)
declare
    %test:assertEquals("with space.xml")
function t:resources-decoded() {
    xmldb:get-child-resources($t:COLLECTION, true())[contains(., "space")]
};

(: one-argument form: stored (percent-encoded) child collection name :)
declare
    %test:assertEquals("child%20space")
function t:collections-encoded-default() {
    xmldb:get-child-collections($t:COLLECTION)[contains(., "space")]
};

(: two-argument form, decode = true(): human-readable child collection name :)
declare
    %test:assertEquals("child space")
function t:collections-decoded() {
    xmldb:get-child-collections($t:COLLECTION, true())[contains(., "space")]
};
