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
 : XQSuite tests for map:merge key enumeration regression.
 :
 : Bug: map:merge produces maps where map:keys() and map:for-each()
 : return incomplete results, while map:size(), map:contains(), and
 : ? (lookup) all work correctly.
 :
 : The corruption occurs when the first argument to map:merge is a
 : map constructed via map:merge with map:entry items from a FLWOR
 : expression. The resulting merged map has correct size but broken
 : key enumeration.
 :
 : Affects: eXist-db develop and next (7.0.0-SNAPSHOT)
 : Does NOT affect: eXist-db 6.4.1 (release)
 :
 : Run with:
 :   xst execute -f test-map-merge-bug.xqm
 :   or upload to /db/tmp and run via XQSuite
 :)
module namespace bug = "http://exist-db.org/test/map-merge-bug";

declare namespace test = "http://exist-db.org/xquery/xqsuite";

(:~
 : Basic test: map:merge with two literal maps works correctly.
 :)
declare
    %test:assertEquals(4)
function bug:merge-two-literal-maps() {
    let $m1 := map { "a": 1, "b": 2 }
    let $m2 := map { "c": 3, "d": 4 }
    let $merged := map:merge(($m1, $m2))
    return count(map:keys($merged))
};

(:~
 : Regression test: map constructed from FLWOR + map:entry
 : preserves all keys after a second map:merge.
 :
 : This is the minimal reproduction of the bug.
 :)
declare
    %test:assertEquals(4)
function bug:merge-flwor-entry-map-with-literal() {
    let $m1 := map:merge(
        for $key in ("a", "b")
        return map:entry($key, "value-" || $key)
    )
    let $m2 := map { "c": "C", "d": "D" }
    let $merged := map:merge(($m1, $m2))
    return count(map:keys($merged))
};

(:~
 : Verify map:size agrees with count(map:keys()) after merge.
 :)
declare
    %test:assertTrue
function bug:size-equals-keys-count() {
    let $m1 := map:merge(
        for $key in ("a", "b")
        return map:entry($key, "value-" || $key)
    )
    let $m2 := map { "c": "C", "d": "D" }
    let $merged := map:merge(($m1, $m2))
    return map:size($merged) = count(map:keys($merged))
};

(:~
 : Verify map:for-each iterates all keys.
 :)
declare
    %test:assertEquals(4)
function bug:for-each-iterates-all-keys() {
    let $m1 := map:merge(
        for $key in ("a", "b")
        return map:entry($key, "value-" || $key)
    )
    let $m2 := map { "c": "C", "d": "D" }
    let $merged := map:merge(($m1, $m2))
    return count(map:for-each($merged, function($k, $v) { $k }))
};

(:~
 : Verify all original keys are accessible via map:keys after merge.
 :)
declare
    %test:assertTrue
function bug:all-keys-in-keys-result() {
    let $m1 := map:merge(
        for $key in ("a", "b")
        return map:entry($key, "value-" || $key)
    )
    let $m2 := map { "c": "C", "d": "D" }
    let $merged := map:merge(($m1, $m2))
    let $keys := map:keys($merged)
    return
        $keys = "a" and $keys = "b" and $keys = "c" and $keys = "d"
};

(:~
 : Verify the bug also manifests with map:merge using duplicates option.
 :)
declare
    %test:assertEquals(4)
function bug:merge-with-duplicates-option() {
    let $m1 := map:merge(
        for $key in ("a", "b")
        return map:entry($key, "value-" || $key)
    )
    let $m2 := map { "c": "C", "d": "D" }
    let $merged := map:merge(($m1, $m2), map { "duplicates": "use-last" })
    return count(map:keys($merged))
};

(:~
 : Three-way merge: FLWOR-built map merged with two literal maps.
 :)
declare
    %test:assertEquals(6)
function bug:three-way-merge() {
    let $m1 := map:merge(
        for $key in ("a", "b")
        return map:entry($key, "value-" || $key)
    )
    let $m2 := map { "c": "C", "d": "D" }
    let $m3 := map { "e": "E", "f": "F" }
    let $merged := map:merge(($m1, $m2, $m3))
    return count(map:keys($merged))
};

(:~
 : Chained merge: merge result used as input to another merge.
 : This is the pattern used by Jinks generator:config().
 :)
declare
    %test:assertEquals(6)
function bug:chained-merge() {
    let $m1 := map:merge(
        for $key in ("a", "b", "c")
        return map:entry($key, "value-" || $key)
    )
    let $step1 := map:merge((
        $m1,
        map { "d": "D", "e": "E" }
    ))
    let $step2 := map:merge((
        $step1,
        map { "f": "F" }
    ))
    return count(map:keys($step2))
};

(:~
 : Verify map:contains works for all keys even when map:keys is broken.
 : This confirms the values ARE in the map but not enumerable.
 :)
declare
    %test:assertTrue
function bug:contains-works-despite-keys-bug() {
    let $m1 := map:merge(
        for $key in ("a", "b")
        return map:entry($key, "value-" || $key)
    )
    let $m2 := map { "c": "C" }
    let $merged := map:merge(($m1, $m2))
    return
        map:contains($merged, "a")
        and map:contains($merged, "b")
        and map:contains($merged, "c")
};

(:~
 : Verify ? lookup works for all keys even when map:keys is broken.
 :)
declare
    %test:assertEquals("value-a")
function bug:lookup-works-despite-keys-bug() {
    let $m1 := map:merge(
        for $key in ("a", "b")
        return map:entry($key, "value-" || $key)
    )
    let $m2 := map { "c": "C" }
    let $merged := map:merge(($m1, $m2))
    return $merged?a
};
