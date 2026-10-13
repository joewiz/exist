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
package org.exist.xquery;

import org.exist.xquery.value.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for XQuery atomic type conversion performance.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
public class TypeConversionBenchmark {

    private IntegerValue integerValue;
    private DoubleValue doubleValue;
    private StringValue stringValue;
    private DecimalValue decimalValue;

    @Setup(Level.Trial)
    public void setUp() throws XPathException {
        integerValue = new IntegerValue(12345);
        doubleValue = new DoubleValue(3.14159);
        stringValue = new StringValue("12345");
        decimalValue = new DecimalValue("3.14159");
    }

    @Benchmark
    public IntegerValue stringToInteger() throws XPathException {
        return new IntegerValue("12345");
    }

    @Benchmark
    public StringValue integerToString() throws XPathException {
        return new StringValue(integerValue.getStringValue());
    }

    @Benchmark
    public DoubleValue castDecimalToDouble() throws XPathException {
        return (DoubleValue) decimalValue.convertTo(Type.DOUBLE);
    }

    @Benchmark
    public IntegerValue castDoubleToInteger() throws XPathException {
        return (IntegerValue) doubleValue.convertTo(Type.INTEGER);
    }

    @Benchmark
    public DoubleValue stringToDouble() throws XPathException {
        return new DoubleValue("3.14159265358979");
    }

    @Benchmark
    public StringValue doubleToString() throws XPathException {
        return new StringValue(doubleValue.getStringValue());
    }

    @Benchmark
    public BooleanValue stringToBoolean() throws XPathException {
        return (BooleanValue) new StringValue("true").convertTo(Type.BOOLEAN);
    }

    @Benchmark
    public DateTimeValue createDateTime() throws XPathException {
        return new DateTimeValue("2024-01-15T10:30:00Z");
    }

    @Benchmark
    public boolean integerComparison() throws XPathException {
        final IntegerValue a = new IntegerValue(42);
        final IntegerValue b = new IntegerValue(99);
        return a.compareTo(null, Constants.Comparison.LT, b);
    }
}
