/*
 * eXist-db Open Source Native XML Database
 * Copyright (C) 2001 The eXist-db Authors
 *
 * info@exist-db.org
 * http://www.exist-db.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package xquery.fnXq4Preview;

import org.exist.test.runner.XSuite;
import org.junit.runner.RunWith;

/**
 * XQSuite runner for the XQuery 4.0 function preview test modules.
 *
 * Each module covers one new fn:* function and ports a representative subset
 * of the corresponding QT4 conformance test cases. When the full XQuery 4.0
 * function set lands as part of v2/xq4-core-functions, these per-function
 * preview modules can be retired in favour of XQTS-driven QT4 coverage.
 */
@RunWith(XSuite.class)
@XSuite.XSuiteFiles({
        "src/test/xquery/fn-xq4-preview",
})
public class FnXq4PreviewTests {
}
