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
package org.exist.http.urlrewrite;

import java.util.HashMap;
import java.util.Map;

import com.googlecode.junittoolbox.ParallelRunner;
import org.easymock.EasyMock;
import jakarta.servlet.http.HttpServletRequest;
import org.exist.http.urlrewrite.XQueryURLRewrite.RequestWrapper;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

/**
 *
 * @author aretter
 */
@RunWith(ParallelRunner.class)
public class XQueryURLRewriteTest
{
    @Test
    public void adjustPathForSourceLookup_fullXmldbUri() {
        XQueryURLRewrite rewriter = new XQueryURLRewrite();


        String basePath = "xmldb:exist:///db/adamretter.org.uk/";
        String path = "/db/adamretter.org.uk/blog/entries/some-entry.xml?edit";

        String adjustedPath = rewriter.adjustPathForSourceLookup(basePath, path);

        assertEquals(adjustedPath, "blog/entries/some-entry.xml?edit");
    }

    @Test
    public void adjustPathForSourceLookup_dbUri() {
        XQueryURLRewrite rewriter = new XQueryURLRewrite();


        String basePath = "/";
        String path = "/db/adamretter.org.uk/blog/entries/some-entry.xml?edit";

        String adjustedPath = rewriter.adjustPathForSourceLookup(basePath, path);

        assertEquals(adjustedPath, "adamretter.org.uk/blog/entries/some-entry.xml?edit");
    }

    @Test
    public void adjustPathForSourceLookup_fsUri() {
        XQueryURLRewrite rewriter = new XQueryURLRewrite();


        String basePath = "/";
        String path = "/xquery/functions.xql";

        String adjustedPath = rewriter.adjustPathForSourceLookup(basePath, path);

        assertEquals(adjustedPath, "xquery/functions.xql");
    }

    @Test
    public void requestWrapper_copiesRequestParams() {

        final Map<String, String[]> testParameterMap = new HashMap<String, String[]>();
        testParameterMap.put("paramName1", new String[] {"value1", "value1.1"});
        testParameterMap.put("paramName2", new String[] {"value2", "value2.1"});

        HttpServletRequest mockHttpServletRequest = EasyMock.createMock(HttpServletRequest.class);

        //standard request wrapper stuff
        expect(mockHttpServletRequest.getContentType()).andReturn("text/xml");
        //end standard request wrapper stuff

        expect(mockHttpServletRequest.getParameterMap()).andReturn(testParameterMap);


        replay(mockHttpServletRequest);
        RequestWrapper wrapper = new RequestWrapper(mockHttpServletRequest);
        verify(mockHttpServletRequest);

        assertEquals(testParameterMap.size(), wrapper.getParameterMap().size());

        for(String paramName : testParameterMap.keySet()) {
            assertArrayEquals(testParameterMap.get(paramName), wrapper.getParameterMap().get(paramName));
        }
    }

    @Test
    public void requestWrapper_exposesIfModifiedSinceHeaderValueWhenCachingDisabled() {
        // During the controller -> view handover caching is disabled on the wrapped
        // request. The raw If-Modified-Since header value must still be readable
        // (e.g. from request:get-header('If-Modified-Since') in view.xql).
        final String ifModifiedSince = "Wed, 21 Oct 2026 07:28:00 GMT";

        final HttpServletRequest mockHttpServletRequest = EasyMock.createMock(HttpServletRequest.class);
        expect(mockHttpServletRequest.getContentType()).andReturn("text/xml");
        expect(mockHttpServletRequest.getParameterMap()).andReturn(new HashMap<>());
        expect(mockHttpServletRequest.getHeader("If-Modified-Since")).andReturn(ifModifiedSince);

        replay(mockHttpServletRequest);
        final RequestWrapper wrapper = new RequestWrapper(mockHttpServletRequest);
        wrapper.allowCaching(false);
        final String actual = wrapper.getHeader("If-Modified-Since");
        verify(mockHttpServletRequest);

        assertEquals(ifModifiedSince, actual);
    }

    @Test
    public void requestWrapper_suppressesIfModifiedSinceDateHeaderWhenCachingDisabled() {
        // The parsed If-Modified-Since date drives conditional-GET (304) handling, so it
        // must be hidden while caching is disabled to avoid a premature "304 Not Modified"
        // during the controller -> view pipeline.
        final HttpServletRequest mockHttpServletRequest = EasyMock.createMock(HttpServletRequest.class);
        expect(mockHttpServletRequest.getContentType()).andReturn("text/xml");
        expect(mockHttpServletRequest.getParameterMap()).andReturn(new HashMap<>());

        replay(mockHttpServletRequest);
        final RequestWrapper wrapper = new RequestWrapper(mockHttpServletRequest);
        wrapper.allowCaching(false);
        final long actual = wrapper.getDateHeader("If-Modified-Since");
        verify(mockHttpServletRequest);

        assertEquals(-1L, actual);
    }

    @Test
    public void requestWrapper_passesThroughIfModifiedSinceDateHeaderWhenCachingEnabled() {
        // With caching enabled (the default) the parsed date header is passed through.
        final long ifModifiedSince = 1_761_031_680_000L;

        final HttpServletRequest mockHttpServletRequest = EasyMock.createMock(HttpServletRequest.class);
        expect(mockHttpServletRequest.getContentType()).andReturn("text/xml");
        expect(mockHttpServletRequest.getParameterMap()).andReturn(new HashMap<>());
        expect(mockHttpServletRequest.getDateHeader("If-Modified-Since")).andReturn(ifModifiedSince);

        replay(mockHttpServletRequest);
        final RequestWrapper wrapper = new RequestWrapper(mockHttpServletRequest);
        final long actual = wrapper.getDateHeader("If-Modified-Since");
        verify(mockHttpServletRequest);

        assertEquals(ifModifiedSince, actual);
    }

    @Test
    public void requestWrapper_addsParamAftercopiesRequestParams() {

        final Map<String, String[]> testParameterMap = new HashMap<String, String[]>();
        testParameterMap.put("paramName1", new String[] {"value1", "value1.1"});
        testParameterMap.put("paramName2", new String[] {"value2", "value2.1"});

        final String newRequestParamName = "newParamName";
        final String newRequestParamValue = "newParamValue";

        HttpServletRequest mockHttpServletRequest = EasyMock.createMock(HttpServletRequest.class);

        //standard request wrapper stuff
        expect(mockHttpServletRequest.getContentType()).andReturn("text/xml");
        //end standard request wrapper stuff

        expect(mockHttpServletRequest.getParameterMap()).andReturn(testParameterMap);


        replay(mockHttpServletRequest);
        RequestWrapper wrapper = new RequestWrapper(mockHttpServletRequest);
        wrapper.addParameter(newRequestParamName, newRequestParamValue);
        verify(mockHttpServletRequest);

        final Map<String, String[]> newTestParameterMap = new HashMap<String, String[]>();
        newTestParameterMap.putAll(testParameterMap);
        newTestParameterMap.put(newRequestParamName, new String[] {newRequestParamValue });

        assertEquals(newTestParameterMap.size(), wrapper.getParameterMap().size());

        for(String paramName : newTestParameterMap.keySet()) {
            assertArrayEquals(newTestParameterMap.get(paramName), wrapper.getParameterMap().get(paramName));
        }
    }
}