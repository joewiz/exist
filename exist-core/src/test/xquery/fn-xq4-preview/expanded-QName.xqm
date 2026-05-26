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
 : Tests for fn:expanded-QName (XQuery 4.0). Adapted from
 : https://github.com/qt4cg/qt4tests/blob/master/fn/expanded-QName.xml.
 :)
module namespace t = "http://exist-db.org/xquery/test/fn-expanded-QName";
declare namespace ex = "http://exist-db.org/test/expanded-QName";
declare namespace test = "http://exist-db.org/xquery/xqsuite";

declare %test:assertEquals("Q{http://exist-db.org/test/expanded-QName}local")
function t:expanded-with-namespace() {
    fn:expanded-QName(xs:QName("ex:local"))
};

declare %test:assertEquals("Q{}local") function t:expanded-no-namespace() {
    fn:expanded-QName(xs:QName("local"))
};

declare %test:assertEmpty function t:expanded-empty() { fn:expanded-QName(()) };

(: Round-trip via fn:QName :)
declare %test:assertEquals("Q{urn:foo}bar") function t:expanded-roundtrip() {
    fn:expanded-QName(fn:QName("urn:foo", "p:bar"))
};
