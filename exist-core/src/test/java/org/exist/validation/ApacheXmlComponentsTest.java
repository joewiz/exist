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

package org.exist.validation;

import org.junit.*;
import static org.junit.Assert.*;

/**
 *  Class for testing XML Parser and XML Transformer configuration.
 *
 * @author Dannes Wessels (dizzzz@exist-db.org)
 */
public class ApacheXmlComponentsTest  {
    
    
    @Test
    public void parserVersion() {
        StringBuilder xmlLibMessage = new StringBuilder();
        
        boolean validParser = XmlLibraryChecker.hasValidParser(xmlLibMessage);
        
        assertTrue(xmlLibMessage.toString(), validParser);
    }

    @Test
    public void transformerVersion() {
        StringBuilder xmlLibMessage = new StringBuilder();
        
        boolean validTransformer = XmlLibraryChecker.hasValidTransformer(xmlLibMessage);
        
        assertTrue(xmlLibMessage.toString(), validTransformer);
    }
}
