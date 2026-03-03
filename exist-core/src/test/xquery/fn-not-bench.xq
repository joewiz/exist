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
  Performance and correctness benchmark for fn:not() set-difference optimization.
  Uses the Shakespeare hamlet.xml sample to verify that not() predicates on
  persistent node sets still use the efficient set-difference path.
~:)
module namespace fn-not-bench="http://exist-db.org/xquery/test/fn-not-bench";

declare namespace test="http://exist-db.org/xquery/xqsuite";

declare variable $fn-not-bench:collection := 'test-fn-not-bench';
declare variable $fn-not-bench:doc := 'hamlet.xml';

declare
    %test:setUp
function fn-not-bench:setup() {
    let $hamlet := fn:doc('/db/apps/doc/data/shakespeare/hamlet.xml')
    return
        if (exists($hamlet)) then (
            xmldb:create-collection('/db', $fn-not-bench:collection),
            xmldb:store('/db/' || $fn-not-bench:collection, $fn-not-bench:doc, $hamlet)
        ) else (
            (: Fall back to loading from classpath via a small inline doc :)
            xmldb:create-collection('/db', $fn-not-bench:collection),
            xmldb:store('/db/' || $fn-not-bench:collection, $fn-not-bench:doc,
                <PLAY>
                    <TITLE>Hamlet</TITLE>
                    {for $i in 1 to 200 return
                        <ACT><SCENE><TITLE>Scene {$i}</TITLE>
                            {for $j in 1 to 20 return
                                <SPEECH>
                                    <SPEAKER>{if ($j mod 3 = 0) then "HAMLET" else "OTHER"}</SPEAKER>
                                    {if ($j mod 5 != 0) then <LINE>Line {$j}</LINE> else ()}
                                </SPEECH>
                            }
                        </SCENE></ACT>
                    }
                </PLAY>
            )
        )
};

declare
    %test:tearDown
function fn-not-bench:teardown() {
    xmldb:remove('/db/' || $fn-not-bench:collection)
};

(:~
  Correctness: //SPEECH[not(LINE)] must return speeches with no LINE children.
  This exercises the set-difference optimization on persistent nodes.
~:)
declare
    %test:assertTrue
function fn-not-bench:not-line-returns-results() {
    let $dom := doc('/db/' || $fn-not-bench:collection || '/' || $fn-not-bench:doc)
    return count($dom//SPEECH[not(LINE)]) > 0
};

(:~
  Correctness: //SPEECH[not(SPEAKER = 'HAMLET')] filters correctly.
  This is a general comparison predicate inside not(), not a simple node test,
  so it goes through the boolean evaluation path.
~:)
declare
    %test:assertTrue
function fn-not-bench:not-speaker-hamlet() {
    let $dom := doc('/db/' || $fn-not-bench:collection || '/' || $fn-not-bench:doc)
    let $all := count($dom//SPEECH)
    let $hamlet := count($dom//SPEECH[SPEAKER = 'HAMLET'])
    let $not-hamlet := count($dom//SPEECH[not(SPEAKER = 'HAMLET')])
    return $not-hamlet = ($all - $hamlet)
};

(:~
  Performance: run //SPEECH[not(LINE)] 10 times and verify it completes quickly.
  This is a smoke test — if the set-difference optimization is broken and falls
  back to item-by-item boolean evaluation, this would be noticeably slower.
~:)
declare
    %test:assertTrue
function fn-not-bench:not-line-performance() {
    let $dom := doc('/db/' || $fn-not-bench:collection || '/' || $fn-not-bench:doc)
    let $start := util:system-time()
    let $results :=
        for $i in 1 to 10
        return count($dom//SPEECH[not(LINE)])
    let $end := util:system-time()
    let $elapsed := ($end - $start) div xs:dayTimeDuration('PT0.001S')
    (: Should complete 10 iterations in well under 5 seconds :)
    return $elapsed < 5000
};
