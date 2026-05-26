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
 : Tests for fn:unix-dateTime (XQuery 4.0). Adapted from
 : https://github.com/qt4cg/qt4tests/blob/master/fn/unix-dateTime.xml.
 :)
module namespace t = "http://exist-db.org/xquery/test/fn-unix-dateTime";
declare namespace test = "http://exist-db.org/xquery/xqsuite";

(: Unix epoch 0 = 1970-01-01T00:00:00Z :)
declare %test:assertEquals("1970-01-01T00:00:00Z") function t:unix-epoch() { fn:string(fn:unix-dateTime(xs:nonNegativeInteger("0"))) };

(: 1 second after epoch :)
declare %test:assertEquals("1970-01-01T00:00:01Z") function t:unix-one-second() { fn:string(fn:unix-dateTime(xs:nonNegativeInteger("1000"))) };

(: 1 minute after epoch :)
declare %test:assertEquals("1970-01-01T00:01:00Z") function t:unix-one-minute() { fn:string(fn:unix-dateTime(xs:nonNegativeInteger("60000"))) };

(: 1 hour after epoch :)
declare %test:assertEquals("1970-01-01T01:00:00Z") function t:unix-one-hour() { fn:string(fn:unix-dateTime(xs:nonNegativeInteger("3600000"))) };

(: arity-0 form returns a dateTime; just check it's UTC-shaped :)
declare %test:assertEquals("Z") function t:unix-zero-arity-utc() {
    fn:substring(fn:string(fn:unix-dateTime()), 20, 1)
};
