
/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2000-04,  Wolfgang Meier (wolfgang@exist-db.org)
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * 
 *  $Id$
 */
package org.exist.dom;

import java.io.UnsupportedEncodingException;
import java.util.Set;

import org.exist.storage.Signatures;
import org.exist.util.ByteArrayPool;
import org.exist.util.ByteConversion;
import org.exist.util.UTF8;
import org.w3c.dom.Attr;
import org.w3c.dom.DOMException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.TypeInfo;
import org.w3c.dom.UserDataHandler;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.AttributesImpl;

public class AttrImpl extends NamedNode implements Attr {
	
    public final static int CDATA = 0;
	public final static int ID = 1;
	
	protected int attributeType = CDATA;
    protected String value = null;

    public AttrImpl() {
    	super(Node.ATTRIBUTE_NODE);
    }
    
    public AttrImpl( long gid ) {
        super( Node.ATTRIBUTE_NODE, gid, null );
    }

    public AttrImpl( QName name, String value ) {
        super( Node.ATTRIBUTE_NODE, name);
		this.value = value;
    }

    public AttrImpl(AttrImpl other) {
        super(other);
        this.attributeType = other.attributeType;
        this.value = other.value;
    }
    
    public static NodeImpl deserialize( byte[] data, int start, int len, DocumentImpl doc, boolean pooled ) {
    	int next = start;
        byte idSizeType = (byte) ( data[next] & 0x3 );
		boolean hasNamespace = (data[next] & 0x10) == 0x10;
        int attrType = (int)( ( data[next] & 0x4 ) >> 0x2);
		short id = (short) Signatures.read( idSizeType, data, ++next );
		next += Signatures.getLength(idSizeType);
        String name = doc.getSymbols().getName( id );
        if(name == null)
            throw new RuntimeException("no symbol for id " + id);
        short nsId = 0;
        String prefix = null;
		if (hasNamespace) {
			nsId = ByteConversion.byteToShort(data, next);
			next += 2;
			int prefixLen = ByteConversion.byteToShort(data, next);
			next += 2;
			if(prefixLen > 0)
				prefix = UTF8.decode(data, next, prefixLen).toString();
			next += prefixLen;
		}
		String namespace = nsId == 0 ? "" : doc.getSymbols().getNamespace(nsId);
        String value;
        try {
            value =
                new String( data, next,
                len - (next - start),
                "UTF-8" );
        } catch ( UnsupportedEncodingException uee ) {
            value =
                new String( data, next,
               		len - (next - start));
        }
        AttrImpl attr;
        if(pooled)
            attr = (AttrImpl)NodeObjectPool.getInstance().borrowNode(AttrImpl.class);
        else
            attr = new AttrImpl();
        attr.nodeName = doc.getSymbols().getQName(Node.ATTRIBUTE_NODE, namespace, name, prefix);
        attr.value = value;
        attr.setType( attrType );
        return attr;
    }

    public String getName() {
        return nodeName.toString();
    }


	public int getType() {
		return attributeType;
	}
	
	public void setType(int type) {
		attributeType = type;
	}
	
    public String getNodeValue() {
        return value;
    }

    public Element getOwnerElement() {
        return (Element) ownerDocument.getNode( getParentGID() );
    }

    public boolean getSpecified() {
        return true;
    }

    public String getValue() {
        return value;
    }

    public byte[] serialize() {
        if(nodeName.getLocalName() == null)
            throw new RuntimeException("Local name is null");
        final short id = ownerDocument.getSymbols().getSymbol( this );
        final byte idSizeType = Signatures.getSizeType( id );
		int prefixLen = 0;
		if (nodeName.needsNamespaceDecl()) {
			prefixLen = nodeName.getPrefix() != null && nodeName.getPrefix().length() > 0 ?
				UTF8.encoded(nodeName.getPrefix()) : 0;
		}
		final byte[] data = ByteArrayPool.getByteArray(UTF8.encoded(value) +
				Signatures.getLength( idSizeType ) +
				(nodeName.needsNamespaceDecl() ? prefixLen + 4 : 0) + 1);
		int pos = 0;
        data[pos] = (byte) ( Signatures.Attr << 0x5 );
        data[pos] |= idSizeType;
        data[pos] |= (byte) (attributeType << 0x2);
        if(nodeName.needsNamespaceDecl())
			data[pos] |= 0x10;
        Signatures.write( idSizeType, id, data, ++pos );
        pos += Signatures.getLength(idSizeType);
        if(nodeName.needsNamespaceDecl()) {
        	final short nsId = 
        		ownerDocument.getSymbols().getNSSymbol(nodeName.getNamespaceURI());
        	ByteConversion.shortToByte(nsId, data, pos);
        	pos += 2;
			ByteConversion.shortToByte((short)prefixLen, data, pos);
			pos += 2;
			if(nodeName.getPrefix() != null && nodeName.getPrefix().length() > 0)
				UTF8.encode(nodeName.getPrefix(), data, pos);
			pos += prefixLen;
        }
        UTF8.encode(value, data, pos);
        return data;
    }


    public void setValue( String value ) throws DOMException {
        this.value = value;
    }

    public void toSAX( ContentHandler contentHandler,
                       LexicalHandler lexicalHandler, boolean first,
                       Set namespaces)
         throws SAXException {
        if ( first ) {
            AttributesImpl attribs = new AttributesImpl();
            attribs.addAttribute( "http://exist.sourceforge.net/NS/exist", "id",
                "exist:id", "CDATA", Long.toString( gid ) );
            attribs.addAttribute( "http://exist.sourceforge.net/NS/exist", "source",
                "exist:source", "CDATA", ownerDocument.getFileName() );
            attribs.addAttribute( getNamespaceURI(), getLocalName(),
                getNodeName(), "CDATA", getValue() );
            contentHandler.startElement( "http://exist.sourceforge.net/NS/exist", "attribute",
                "exist:attribute", attribs );
            contentHandler.endElement( "http://exist.sourceforge.net/NS/exist", "attribute",
                "exist:attribute" );
        }
    }

    public String toString() {
        StringBuffer buf = new StringBuffer();
        buf.append( ' ' );
        buf.append( nodeName );
        buf.append( "=\"" );
        buf.append( value );
        buf.append( '"' );
        return buf.toString();
    }

    public String toString( boolean top ) {
        if ( top ) {
            StringBuffer result = new StringBuffer();
            result.append( "<exist:attribute " );
            result.append( "xmlns:exist=\"http://exist.sourceforge.net/NS/exist\" " );
            result.append( "exist:id=\"" );
            result.append( gid );
            result.append( "\" exist:source=\"" );
            result.append( ownerDocument.getFileName() );
            result.append( "\" " );
            result.append( getNodeName() );
            result.append( "=\"" );
            result.append( getValue() );
            result.append( "\"/>" );
            return result.toString();
        }
        else
            return toString();
    }
    
    public void clear() {
        super.clear();
        attributeType = CDATA; 
    }

	/** ? @see org.w3c.dom.Attr#getSchemaTypeInfo()
	 */
	public TypeInfo getSchemaTypeInfo() {
		// maybe TODO - new DOM interfaces - Java 5.0
		return null;
	}

	/** ? @see org.w3c.dom.Attr#isId()
	 */
	public boolean isId() {
		// maybe TODO - new DOM interfaces - Java 5.0
		return false;
	}

	/** ? @see org.w3c.dom.Node#getBaseURI()
	 */
	public String getBaseURI() {
		// maybe TODO - new DOM interfaces - Java 5.0
		return null;
	}

	/** ? @see org.w3c.dom.Node#compareDocumentPosition(org.w3c.dom.Node)
	 */
	public short compareDocumentPosition(Node other) throws DOMException {
		// maybe TODO - new DOM interfaces - Java 5.0
		return 0;
	}

	/** ? @see org.w3c.dom.Node#getTextContent()
	 */
	public String getTextContent() throws DOMException {
		// maybe TODO - new DOM interfaces - Java 5.0
		return null;
	}

	/** ? @see org.w3c.dom.Node#setTextContent(java.lang.String)
	 */
	public void setTextContent(String textContent) throws DOMException {
		// maybe TODO - new DOM interfaces - Java 5.0
		
	}

	/** ? @see org.w3c.dom.Node#isSameNode(org.w3c.dom.Node)
	 */
	public boolean isSameNode(Node other) {
		// maybe TODO - new DOM interfaces - Java 5.0
		return false;
	}

	/** ? @see org.w3c.dom.Node#lookupPrefix(java.lang.String)
	 */
	public String lookupPrefix(String namespaceURI) {
		// maybe TODO - new DOM interfaces - Java 5.0
		return null;
	}

	/** ? @see org.w3c.dom.Node#isDefaultNamespace(java.lang.String)
	 */
	public boolean isDefaultNamespace(String namespaceURI) {
		// maybe TODO - new DOM interfaces - Java 5.0
		return false;
	}

	/** ? @see org.w3c.dom.Node#lookupNamespaceURI(java.lang.String)
	 */
	public String lookupNamespaceURI(String prefix) {
		// maybe TODO - new DOM interfaces - Java 5.0
		return null;
	}

	/** ? @see org.w3c.dom.Node#isEqualNode(org.w3c.dom.Node)
	 */
	public boolean isEqualNode(Node arg) {
		// maybe TODO - new DOM interfaces - Java 5.0
		return false;
	}

	/** ? @see org.w3c.dom.Node#getFeature(java.lang.String, java.lang.String)
	 */
	public Object getFeature(String feature, String version) {
		// maybe TODO - new DOM interfaces - Java 5.0
		return null;
	}

	/** ? @see org.w3c.dom.Node#setUserData(java.lang.String, java.lang.Object, org.w3c.dom.UserDataHandler)
	 */
	public Object setUserData(String key, Object data, UserDataHandler handler) {
		// maybe TODO - new DOM interfaces - Java 5.0
		return null;
	}

	/** ? @see org.w3c.dom.Node#getUserData(java.lang.String)
	 */
	public Object getUserData(String key) {
		// maybe TODO - new DOM interfaces - Java 5.0
		return null;
	}
}

