package org.exist.xquery.test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import junit.framework.TestCase;

import org.exist.xmldb.XPathQueryServiceImpl;
import org.xmldb.api.DatabaseManager;
import org.xmldb.api.base.Collection;
import org.xmldb.api.base.Database;
import org.xmldb.api.base.Resource;
import org.xmldb.api.base.ResourceIterator;
import org.xmldb.api.base.ResourceSet;
import org.xmldb.api.base.XMLDBException;
import org.xmldb.api.modules.CollectionManagementService;
import org.xmldb.api.modules.XMLResource;
import org.xmldb.api.modules.XPathQueryService;

public class XPathQueryTest extends TestCase {

	private final static String URI = "xmldb:exist:///db";

	private final static String nested =
		"<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
			+ "<test><c></c><b><c><b></b></c></b><b></b><c></c></test>";

	private final static String numbers =
		"<test>"
			+ "<item id='1'><price>5.6</price><stock>22</stock></item>"
			+ "<item id='2'><price>7.4</price><stock>43</stock></item>"
			+ "<item id='3'><price>18.4</price><stock>5</stock></item>"
			+ "<item id='4'><price>65.54</price><stock>16</stock></item>"
			+ "</test>";

	private final static String namespaces =
		"<test xmlns='http://www.foo.com'>"
			+ "<section>"
			+ "<title>Test Document</title>"
			+ "<c:comment xmlns:c='http://www.other.com'>This is my comment</c:comment>"
			+ "</section>"
			+ "</test>";

	private final static String strings =
		"<test>"
			+ "<string>Hello World!</string>"
			+ "<string value='Hello World!'/>"
			+ "<string>Hello</string>"
			+ "</test>";

	private Collection testCollection;

	public XPathQueryTest(String arg0) {
		super(arg0);
	}

	protected void setUp() {
		try {
			// initialize driver
			Class cl = Class.forName("org.exist.xmldb.DatabaseImpl");
			Database database = (Database) cl.newInstance();
			database.setProperty("create-database", "true");
			DatabaseManager.registerDatabase(database);
			
			Collection root =
				DatabaseManager.getCollection(
					"xmldb:exist:///db",
					"admin",
					null);
			CollectionManagementService service =
				(CollectionManagementService) root.getService(
					"CollectionManagementService",
					"1.0");
			testCollection = service.createCollection("test");
			assertNotNull(testCollection);
			
//			XMLResource doc =
//				(XMLResource) root.createResource("r_and_j.xml", "XMLResource");
//			doc.setContent(new File("samples/shakespeare/r_and_j.xml"));
//			root.storeResource(doc);
		} catch (ClassNotFoundException e) {
		} catch (InstantiationException e) {
		} catch (IllegalAccessException e) {
		} catch (XMLDBException e) {
			e.printStackTrace();
		}
	}

	public void testStarAxis() {
		ResourceSet result;
		try {
			XPathQueryService service = 
				storeXMLStringAndGetQueryService("numbers.xml", numbers);

			result = service.queryResource(
					"numbers.xml", "/*/item" );
			System.out.println("testStarAxis 1: ========" ); 		printResult(result);
			assertEquals( "XPath: /*/item", 4, result.getSize() );

			result = service.queryResource(
					"numbers.xml", "/test/*" );
			System.out.println("testStarAxis  2: ========" ); 		printResult(result);
			assertEquals( "XPath: /test/*", 4, result.getSize() );

			result = service.queryResource(
				"numbers.xml", "/test/descendant-or-self::*" );
			System.out.println("testStarAxis  3: ========" ); 		printResult(result);
			assertEquals( "XPath: /test/descendant-or-self::*", 12, result.getSize() );

			System.out.println("testStarAxis 4: ========" ); 		printResult(result);
			// TODO: needs to be fixed:
			assertEquals( "XPath: /*/*", 12, result.getSize() );

		} catch (XMLDBException e) {
			System.out.println("testStarAxis(): XMLDBException: "+e);
			fail(e.getMessage());
		}
	}
	
	public void testNumbers() {
		try {
			XPathQueryService service = 
				storeXMLStringAndGetQueryService("numbers.xml", numbers);
			
			ResourceSet result = queryResource(service, "numbers.xml", "sum(/test/item/price)", 1);
			assertEquals( "96.94", result.getResource(0).getContent() );

			result = queryResource(service, "numbers.xml", "round(sum(/test/item/price))", 1);
			assertEquals( "97.0", result.getResource(0).getContent() );

			result = queryResource(service, "numbers.xml", "floor(sum(/test/item/stock))", 1);
			assertEquals( "86.0", result.getResource(0).getContent());

			queryResource(service, "numbers.xml", "/test/item[round(price + 3) > 60]", 1);

			result = queryResource(service, "numbers.xml", "min( 123456789123456789123456789, " +
					          "123456789123456789123456789123456789123456789 )", 1);
			assertEquals("minimum of big integers",
					"123456789123456789123456789", 
					result.getResource(0).getContent() );
		} catch (XMLDBException e) {
			fail(e.getMessage());
		}
	}

	public void testStrings() {
		try {
			XPathQueryService service = 
				storeXMLStringAndGetQueryService("strings.xml", strings);
			
			ResourceSet result = queryResource(service, "strings.xml", "substring(/test/string[1], 1, 5)", 1);
			assertEquals( "Hello", result.getResource(0).getContent() );

			queryResource(service, "strings.xml", "/test/string[starts-with(string(.), 'Hello')]", 2);
			
			result = queryResource(service, "strings.xml", "count(/test/item/price)", 1,
					"Query should return an empty set (wrong document)");
			assertEquals("0", result.getResource(0).getContent());
		} catch (XMLDBException e) {
			System.out.println("testStrings(): XMLDBException: "+e);
			fail(e.getMessage());
		}
	}

	public void testNot() {
		try {
			XPathQueryService service = 
				storeXMLStringAndGetQueryService("strings.xml", strings);
			
			queryResource(service, "strings.xml", "/test/string[not(@value)]", 2);

			ResourceSet result = queryResource(service, "strings.xml",	"not(/test/abcd)", 1);
			Resource r = result.getResource(0);
			assertEquals("true", r.getContent().toString());
			
			result = queryResource(service, "strings.xml",	"not(/test)", 1);
			r = result.getResource(0);
			assertEquals("false", r.getContent().toString());
			
			result = queryResource(service, "strings.xml", "/test/string[not(@id)]", 3);
			r = result.getResource(0);
			assertEquals("<string>Hello World!</string>", r.getContent().toString());
			
			// test with non-existing items
			queryResource(	service, "strings.xml", "document()/blah[not(blah)]", 0);
			queryResource(service, "strings.xml", "//*[string][not(@value)]", 1);
			queryResource(service, "strings.xml", "//*[string][not(@blah)]", 1);
			queryResource(service, "strings.xml", "//*[blah][not(@blah)]", 0);
		} catch (XMLDBException e) {
			System.out.println("testStrings(): XMLDBException: "+e);
			fail(e.getMessage());
		}
	}
	
	private ResourceSet queryResource(XPathQueryService service, String resource, String query, 
		int expected) throws XMLDBException {
		return queryResource(service, resource, query, expected, null);
	}
	
	/**
	 * @param service
	 * @throws XMLDBException
	 */
	private ResourceSet queryResource(XPathQueryService service, String resource, String query, 
		int expected, String message) throws XMLDBException {
		ResourceSet result = service.queryResource(resource, query);
		if(message == null)
			assertEquals(expected, result.getSize());
		else
			assertEquals(message, expected, result.getSize());
		return result;
	}

	/**
	 * @return
	 * @throws XMLDBException
	 */
	private XPathQueryService storeXMLStringAndGetQueryService(String documentName,
			String content) throws XMLDBException {
		XMLResource doc =
			(XMLResource) testCollection.createResource(
					documentName, "XMLResource" );
		doc.setContent(content);
		testCollection.storeResource(doc);
		XPathQueryService service =
			(XPathQueryService) testCollection.getService(
				"XPathQueryService",
				"1.0");
		return service;
	}

	public void testNamespaces() {
		try {
			XPathQueryService service = 
				storeXMLStringAndGetQueryService("namespaces.xml", namespaces);

			service.setNamespace("t", "http://www.foo.com");
			ResourceSet result =
				service.queryResource("namespaces.xml", "//t:section");
			assertEquals(1, result.getSize());

			result =
				service.queryResource("namespaces.xml", "/t:test//c:comment");
			assertEquals(1, result.getSize());

			result = service.queryResource("namespaces.xml", "//c:*");
			assertEquals(1, result.getSize());

			result = service.queryResource("namespaces.xml", "//*:comment");
			assertEquals(1, result.getSize());
		} catch (XMLDBException e) {
			fail(e.getMessage());
		}
	}

	public void testNestedElements() {
		try {
			XPathQueryService service = 
				storeXMLStringAndGetQueryService("nested.xml", nested);
			
			ResourceSet result = service.queryResource("nested.xml", "//c");
			printResult(result);
			assertEquals( 3, result.getSize() );
		} catch (XMLDBException e) {
			fail(e.getMessage());
		}
	}

	public void testStaticVariables() {
		ResourceSet result = null;
		try {
			XMLResource doc =
				(XMLResource) testCollection.createResource(
						"numbers.xml", "XMLResource" );
			doc.setContent(numbers);
			testCollection.storeResource(doc);
			XPathQueryService service =
				(XPathQueryService) testCollection.getService(
					"XPathQueryService",
					"1.0");
			
			XPathQueryServiceImpl service2 = (XPathQueryServiceImpl) service;
			service2.declareVariable("name", "MONTAGUE");
			service2.declareVariable("name", "43");
			
			//ResourceSet result = service.query("//SPEECH[SPEAKER=$name]");
			result = service2.query( doc, "//item[stock=$name]");
			
			System.out.println( "testStaticVariables 1: ========" ); 		printResult(result);
			result = service2.query("$name");
			assertEquals( 1, result.getSize() );

			System.out.println("testStaticVariables 2: ========" ); 		printResult(result);
			result = service2.query( doc, "//item[stock=43]");
			assertEquals( 1, result.getSize() );

			System.out.println("testStaticVariables 3: ========" ); 		printResult(result);
			result = service2.query( doc, "//item");
			assertEquals( 4, result.getSize() );

			// assertEquals( 10, result.getSize() );
		} catch (XMLDBException e) {
			System.out.println("testStaticVariables(): XMLDBException: "+e);
			fail(e.getMessage());
		}
	}

	/**
	 * @param result
	 * @throws XMLDBException
	 */
	private void printResult(ResourceSet result) throws XMLDBException {
		for (ResourceIterator i = result.getIterator();
			i.hasMoreResources();
			) {
			Resource r = i.nextResource();
			System.out.println(r.getContent());
		}
	}

	public void testMembersAsResource() {
		try {
//			XPathQueryService service =
//				(XPathQueryService) testCollection.getService(
//					"XPathQueryService",
//					"1.0");
//			ResourceSet result = service.query("//SPEECH[LINE &= 'marriage']");
			XPathQueryService service = 
				storeXMLStringAndGetQueryService("numbers.xml", numbers);
			ResourceSet result = service.query("//item/price");
		
			Resource r = result.getMembersAsResource();
			String content = (String)r.getContent();
			System.out.println(content);
			
			Pattern p = Pattern.compile( ".*(<price>.*){4}", Pattern.DOTALL);
			Matcher m = p.matcher(content);
			assertTrue( "get whole document numbers.xml", m.matches() );
		} catch (XMLDBException e) {
			fail(e.getMessage());
		}
	}

	public static void main(String[] args) {
		junit.textui.TestRunner.run(XPathQueryTest.class);
	}
}
