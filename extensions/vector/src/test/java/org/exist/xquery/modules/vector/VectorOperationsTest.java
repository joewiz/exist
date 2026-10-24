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
package org.exist.xquery.modules.vector;

import org.exist.EXistException;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.test.ExistEmbeddedServer;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQuery;
import org.exist.xquery.value.Sequence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The vector operations, evaluated as XQuery: each test states the property it checks. Results are compared as
 * strings rounded to six decimals, so that floating-point noise in the last bits does not matter.
 */
public class VectorOperationsTest {

    @RegisterExtension
    public static final ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(true, true);

    @Test
    void cosineOfVectorsPointingTheSameWayIsOne() throws Exception {
        assertEquals("1", eval("round(vector:cosine([1, 2, 3], [2, 4, 6]), 6)"),
                "cosine similarity ignores length, so a vector and its multiple have similarity 1");
    }

    @Test
    void cosineOfOrthogonalVectorsIsZero() throws Exception {
        assertEquals("0", eval("round(vector:cosine([1, 0], [0, 5]), 6)"));
    }

    @Test
    void cosineOfOppositeVectorsIsMinusOne() throws Exception {
        assertEquals("-1", eval("round(vector:cosine([1, 2], [-1, -2]), 6)"));
    }

    @Test
    void cosineOfKnownVectors() throws Exception {
        // (1·4 + 2·5 + 3·6) / (√14 · √77) = 32 / 32.8329… = 0.974632
        assertEquals("0.974632", eval("round(vector:cosine([1, 2, 3], [4, 5, 6]), 6)"));
    }

    @Test
    void dotProductOfKnownVectors() throws Exception {
        assertEquals("32", eval("vector:dot-product([1, 2, 3], [4, 5, 6])"));
    }

    @Test
    void dotProductOfNormalizedVectorsEqualsCosine() throws Exception {
        assertEquals("true", eval("""
                let $a := [1, 2, 3] let $b := [4, -5, 6]
                return abs(vector:dot-product(vector:normalize($a), vector:normalize($b)) - vector:cosine($a, $b)) lt 1e-12"""));
    }

    @Test
    void magnitudeOfThreeFourIsFive() throws Exception {
        assertEquals("5", eval("vector:magnitude([3, 4])"));
    }

    @Test
    void normalizeScalesToLengthOne() throws Exception {
        assertEquals("0.6 0.8", eval("string-join(vector:normalize([3, 4])?* ! string(round(., 6)), ' ')"));
        assertEquals("1", eval("round(vector:magnitude(vector:normalize([1, -7, 2.5, 0])), 6)"));
    }

    @Test
    void meanIsTheMemberwiseAverage() throws Exception {
        assertEquals("2 3", eval("string-join(vector:mean(([1, 2], [3, 4]))?* ! string(.), ' ')"));
    }

    @Test
    void weightedMeanDividesByTheSumOfTheWeights() throws Exception {
        // (3·[1, 0] + 1·[0, 1]) / 4
        assertEquals("0.75 0.25", eval("string-join(vector:mean(([1, 0], [0, 1]), (3, 1))?* ! string(.), ' ')"));
    }

    @Test
    void weightedMeanWithEqualWeightsIsTheMean() throws Exception {
        assertEquals("true", eval("""
                let $vs := ([1, 2, 3], [4, 5, 6], [7, 8, 10])
                return deep-equal(vector:mean($vs, (2, 2, 2))?*, vector:mean($vs)?*)"""));
    }

    @Test
    void meanOfNoVectorsIsEmpty() throws Exception {
        assertEquals("0", eval("count(vector:mean(()))"));
    }

    @Test
    void euclideanDistanceOfThreeFourIsFive() throws Exception {
        assertEquals("5", eval("vector:euclidean-distance([0, 0], [3, 4])"));
    }

    @Test
    void euclideanDistanceIsZeroForEqualVectorsAndSymmetric() throws Exception {
        assertEquals("0 true", eval("""
                let $a := [1, -2, 3.5] let $b := [4, 0, -1]
                return vector:euclidean-distance($a, $a) || " "
                    || (vector:euclidean-distance($a, $b) eq vector:euclidean-distance($b, $a))"""));
    }

    @Test
    void euclideanDistanceOfUnitVectorsFollowsCosine() throws Exception {
        // for vectors of length 1, distance squared = 2 - 2 × cosine
        assertEquals("true", eval("""
                let $a := vector:normalize([1, 2, 3]) let $b := vector:normalize([4, -5, 6])
                return abs(math:pow(vector:euclidean-distance($a, $b), 2) - (2 - 2 * vector:cosine($a, $b))) lt 1e-12"""));
    }

    @Test
    void dimensionCountsTheMembers() throws Exception {
        assertEquals("3 xs:integer", eval("""
                let $d := vector:dimension([0.5, 1, -2])
                return $d || " " || (if ($d instance of xs:integer) then "xs:integer" else "other")"""));
    }

    @Test
    void resultsAreArraysOfDoubles() throws Exception {
        assertEquals("true", eval("every $d in (vector:normalize([1, 1])?*, vector:mean(([1, 1], [3, 3]))?*) satisfies $d instance of xs:double"));
    }

    @ParameterizedTest(name = "{0} raises {1}")
    @CsvSource(delimiter = '|', value = {
            "vector:cosine([1, 2], [1, 2, 3])              | EXVECTOR0005",
            "vector:dot-product([1, 2], [1])                           | EXVECTOR0005",
            "vector:euclidean-distance([1, 2], [1, 2, 3])      | EXVECTOR0005",
            "vector:mean(([1, 2], [1, 2, 3]))                  | EXVECTOR0005",
            "vector:cosine([], [])                         | EXVECTOR0006",
            "vector:magnitude(['a', 'b'])                           | EXVECTOR0006",
            "vector:dot-product([1, (2, 3)], [1, 2])                   | EXVECTOR0006",
            "vector:cosine([0, 0], [1, 2])                 | EXVECTOR0007",
            "vector:dimension([])                              | EXVECTOR0006",
            "vector:normalize([0, 0, 0])                       | EXVECTOR0007",
            "vector:mean(([1, 2], [3, 4]), (1))                | EXVECTOR0008",
            "vector:mean(([1, 2], [3, 4]), (1, -1))            | EXVECTOR0008",
            "vector:mean(([1, 2], [3, 4]), (0, 0))             | EXVECTOR0008"
    })
    void invalidInputRaisesTheModuleError(final String expression, final String code) throws Exception {
        assertEquals(code, eval("try { " + expression + " } catch * { local-name-from-QName($err:code) }"));
    }

    private static String eval(final String expression) throws EXistException, PermissionDeniedException, XPathException {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        final XQuery xquery = pool.getXQueryService();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            final Sequence result = xquery.execute(broker, expression, null);
            return result.getStringValue();
        }
    }
}
