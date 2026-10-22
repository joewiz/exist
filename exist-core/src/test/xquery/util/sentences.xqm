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
 : util:sentences splits text into sentences with ICU's rules and CLDR's abbreviations, and the rules a caller turns
 : on or gives: its abbreviations, initials, quoted continuations, dashes as spaces, and ICU rules of its own.
 :)
module namespace us = "http://exist-db.org/xquery/test/util/sentences";

declare namespace test = "http://exist-db.org/xquery/xqsuite";
declare namespace util = "http://exist-db.org/xquery/util";
declare namespace map = "http://www.w3.org/2005/xpath-functions/map";

(: a paragraph in the style of diplomatic documents, with abbreviations, initials, a quotation and a dateline :)
declare variable $us:PARAGRAPH :=
    'Mr. Dulles told Amb. Bohlen that the U.S. Government would act. The Secretary (J. F. Dulles) left at 3 p.m. for Paris. "Is this final?" he asked. The vote was 51.5 percent; see No. 12. Embtel 345 reported that Gen. Clay agreed... The meeting ended at 5:30 p.m. Washington, June 6, 1996.';

declare variable $us:ABBREVIATIONS := ("Amb.", "Gen.", "No.", "U.S.");

(: the merge rules, which are off by default :)
declare variable $us:MERGES := map { "merge-initials": true(), "merge-quoted-continuation": true() };

(: ICU rules that end a sentence only at a semicolon; the semicolon a rule matches is escaped, as one ends a rule :)
declare variable $us:SEMICOLON_RULES := "[^\;]* \; [\p{White_Space}]* ;&#10;[^\;]+ ;";

(: from a telegram in the Foreign Relations of the United States, used in https://joewiz.org/2013/06/29/one-paragraph-many-sentences/ :)
declare variable $us:TELEGRAM :=
    '154613. You should arrange to deliver following note to North Vietnamese Embassy. If in your opinion it can be done without creating an issue, we would prefer that you ask North Vietnamese Charge to come to your Embassy to receive note. “The U.S. Government agrees with the statement of the Government of the DRV, in its note of April 27, that it is necessary for Hanoi and Washington to engage in conversations promptly. The U.S. Government notes that the DRV has now agreed that representatives of the two countries should hold private discussions for the sole purpose of agreeing on a location and date. The U.S. Government notes that the DRV did not respond to its suggestion of April 23 that we meet for this limited purpose in a ‘capital not previously considered by either side.’ The U.S. Government suggested the DRV might wish to indicate three appropriate locations suitable for this limited purpose. The U.S. Government does not consider that the suggestion of Warsaw is responsive or acceptable. The U.S. Government is prepared for these limited discussions on April 30 or several days thereafter. The U.S. Government would welcome the prompt response of the DRV to this suggestion.”';

declare
    %test:assertEquals(
        "Mr. Dulles told Amb. Bohlen that the U.S. Government would act.",
        "The Secretary (J. F. Dulles) left at 3 p.m. for Paris.",
        '"Is this final?" he asked.',
        "The vote was 51.5 percent; see No. 12.",
        "Embtel 345 reported that Gen. Clay agreed...",
        "The meeting ended at 5:30 p.m.",
        "Washington, June 6, 1996.")
function us:paragraph-with-abbreviations() {
    util:sentences($us:PARAGRAPH, map:merge(($us:MERGES, map { "abbreviations": $us:ABBREVIATIONS })))
};

declare
    %test:assertEquals(
        "154613.",
        "You should arrange to deliver following note to North Vietnamese Embassy.",
        "If in your opinion it can be done without creating an issue, we would prefer that you ask North Vietnamese Charge to come to your Embassy to receive note.",
        "“The U.S. Government agrees with the statement of the Government of the DRV, in its note of April 27, that it is necessary for Hanoi and Washington to engage in conversations promptly.",
        "The U.S. Government notes that the DRV has now agreed that representatives of the two countries should hold private discussions for the sole purpose of agreeing on a location and date.",
        "The U.S. Government notes that the DRV did not respond to its suggestion of April 23 that we meet for this limited purpose in a ‘capital not previously considered by either side.’",
        "The U.S. Government suggested the DRV might wish to indicate three appropriate locations suitable for this limited purpose.",
        "The U.S. Government does not consider that the suggestion of Warsaw is responsive or acceptable.",
        "The U.S. Government is prepared for these limited discussions on April 30 or several days thereafter.",
        "The U.S. Government would welcome the prompt response of the DRV to this suggestion.”")
function us:telegram() {
    util:sentences($us:TELEGRAM, map { "abbreviations": "U.S." })
};

(: by default, as in ICU, the initial and the quoted question end sentences :)
declare
    %test:assertEquals("The Secretary (J.", "F. Dulles) left.", '"Is this final?"', "he asked.")
function us:merge-rules-off-by-default() {
    util:sentences('The Secretary (J. F. Dulles) left. "Is this final?" he asked.')
};

declare
    %test:assertEquals("The Secretary (J. F. Dulles) left.", '"Is this final?" he asked.')
function us:merge-rules-on() {
    util:sentences('The Secretary (J. F. Dulles) left. "Is this final?" he asked.', $us:MERGES)
};

declare
    %test:assertEquals("The Secretary (J.", "F. Dulles) left.", '"Is this final?" he asked.')
function us:merge-quoted-continuation-alone() {
    util:sentences('The Secretary (J. F. Dulles) left. "Is this final?" he asked.',
        map { "merge-quoted-continuation": true() })
};

(: max-initials: the most letters of a run of initials, each with a period :)
declare
    %test:assertEquals(2, 1)
function us:max-initials() {
    let $text := "He met Major General R.N. Ginsburgh there."
    return (
        count(util:sentences($text, map { "merge-initials": true() })),
        count(util:sentences($text, map { "merge-initials": true(), "max-initials": 2 }))
    )
};

(: an em dash joined to the next word hides the initials after it, unless the dash is a space :)
declare
    %test:assertEquals(2, 1)
function us:dash-as-space() {
    let $text := "Negotiations Working Group—C.D. Corbett, USA"
    let $initials := map { "merge-initials": true(), "max-initials": 2 }
    return (
        count(util:sentences($text, $initials)),
        count(util:sentences($text, map:merge(($initials, map { "dash-as-space": true() }))))
    )
};

declare
    %test:assertEquals("First, a clause;", "then. Another;", "and the rest")
function us:rules() {
    util:sentences("First, a clause; then. Another; and the rest", map { "rules": $us:SEMICOLON_RULES })
};

(: the other options apply to the caller's rules: here an abbreviation that ends with the rules' break :)
declare
    %test:assertEquals("Ask J.; F. Dulles came;", "Done")
function us:rules-with-abbreviations() {
    util:sentences("Ask J.; F. Dulles came; Done", map { "rules": $us:SEMICOLON_RULES, "abbreviations": "J.;" })
};

declare
    %test:assertError("EXXQDY0008")
function us:invalid-rules() {
    util:sentences("A. B.", map { "rules": "[unclosed ;" })
};

declare
    %test:assertError("EXXQDY0008")
function us:max-initials-out-of-range() {
    util:sentences("A. B.", map { "merge-initials": true(), "max-initials": 0 })
};

declare
    %test:assertError("XPTY0004")
function us:max-initials-must-be-an-integer() {
    util:sentences("A. B.", map { "max-initials": "2" })
};

declare
    %test:assertError("XPTY0004")
function us:rules-must-be-a-string() {
    util:sentences("A. B.", map { "rules": 1 })
};

declare
    %test:assertEquals("Mr.", "Smith arrived.")
function us:cldr-abbreviations-off() {
    util:sentences("Mr. Smith arrived.", map { "cldr-abbreviations": false() })
};

declare
    %test:assertEmpty
function us:empty-string() {
    util:sentences("")
};

declare
    %test:assertEmpty
function us:empty-sequence() {
    util:sentences(())
};

declare
    %test:assertEmpty
function us:only-whitespace() {
    util:sentences("  &#10; ")
};

declare
    %test:assertEquals("First sentence.", "No final period")
function us:no-final-period() {
    util:sentences("First sentence. No final period")
};

declare
    %test:assertEquals("It cost 3.5 million dollars.", "Then it rose.")
function us:decimals() {
    util:sentences("It cost 3.5 million dollars. Then it rose.")
};

(: ICU ends a sentence at an ellipsis followed by a capital :)
declare
    %test:assertEquals("Wait...", "What happened?", "Nothing.")
function us:ellipsis-before-capital() {
    util:sentences("Wait... What happened? Nothing.")
};

declare
    %test:assertEquals("Wait... what happened?", "Nothing.")
function us:ellipsis-before-lowercase() {
    util:sentences("Wait... what happened? Nothing.")
};

(: CLDR's German abbreviations are not its English ones :)
declare
    %test:assertEquals(2, 3)
function us:language() {
    let $text := "Dr. Müller kam ca. um fünf. Danach folgte mehr."
    return (count(util:sentences($text, map { "language": "de" })), count(util:sentences($text, map { "language": "en" })))
};

(: an abbreviation with several periods, which ICU's filter doesn't apply :)
declare
    %test:assertEquals("He said the U.S. Government would act.", "Then he left.")
function us:abbreviation-with-several-periods() {
    util:sentences("He said the U.S. Government would act. Then he left.", map { "abbreviations": "U.S." })
};

declare
    %test:assertTrue
function us:offsets-select-the-sentences() {
    let $text := "  First one.   Second, with ünïcode and 😀 an emoji. Third."
    let $sentences := util:sentences($text, map { "offsets": true() })
    return count($sentences) = 3
        and (every $s in $sentences satisfies substring($text, $s?start, $s?end - $s?start) = $s?text)
};

declare
    %test:assertEquals(3, 13)
function us:offsets-are-positions-from-1() {
    let $first := util:sentences("  First one. Second.", map { "offsets": true() })[1]
    return ($first?start, $first?end)
};

declare
    %test:assertError("XPTY0004")
function us:boolean-option-must-be-boolean() {
    util:sentences("A. B.", map { "offsets": "yes" })
};

(: in the text of XML documents a line break is whitespace :)
declare
    %test:assertEquals("He turned to the&#10;    Kashmir debate.", "Then he left.")
function us:line-breaks-are-whitespace() {
    util:sentences("He turned to the&#10;    Kashmir debate.&#10;Then he left.")
};

declare
    %test:assertEquals(3)
function us:break-at-newlines() {
    count(util:sentences("He turned to the&#10;    Kashmir debate.&#10;Then he left.", map { "break-at-newlines": true() }))
};
