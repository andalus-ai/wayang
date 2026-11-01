package tech.kayys.wayang.connectors;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tech.kayys.wayang.runtime.ServiceHandler;

/** The SOAP/WSDL connector against one fake HTTP server that records what it receives. */
class SoapTest {

    record Req(String method, String uri, Map<String, String> headers, String body) {
    }

    private HttpServer server;
    private final List<Req> reqs = new CopyOnWriteArrayList<>();
    private volatile String answer = "{}";
    private volatile String wsdlText = "";
    private volatile int status = 200;
    private volatile String ctype = "text/xml";
    private final UrlGuard local = new UrlGuard(true, Set.of());

    @BeforeEach
    void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Map<String, String> h = new java.util.HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
            reqs.add(new Req(ex.getRequestMethod(), ex.getRequestURI().toString(), h,
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] b = (ex.getRequestMethod().equals("GET") ? wsdlText : answer).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", ex.getRequestMethod().equals("GET") ? "text/xml" : ctype);
            ex.sendResponseHeaders(ex.getRequestMethod().equals("GET") ? 200 : status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                ex.getResponseBody().write(b);
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void down() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static final String DOC = """
            <?xml version="1.0"?>
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/" xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:shop" targetNamespace="urn:shop">
              <wsdl:types>
                <xsd:schema targetNamespace="urn:shop" elementFormDefault="qualified">
                  <xsd:element name="GetPrice"><xsd:complexType><xsd:sequence><xsd:element name="sku" type="xsd:string"/></xsd:sequence></xsd:complexType></xsd:element>
                  <xsd:element name="GetPriceResponse"><xsd:complexType><xsd:sequence><xsd:element name="price" type="xsd:decimal"/></xsd:sequence></xsd:complexType></xsd:element>
                </xsd:schema>
              </wsdl:types>
              <wsdl:message name="GetPriceIn"><wsdl:part name="parameters" element="tns:GetPrice"/></wsdl:message>
              <wsdl:message name="GetPriceOut"><wsdl:part name="parameters" element="tns:GetPriceResponse"/></wsdl:message>
              <wsdl:portType name="Shop"><wsdl:operation name="GetPrice"><wsdl:input message="tns:GetPriceIn"/><wsdl:output message="tns:GetPriceOut"/></wsdl:operation>
                <wsdl:operation name="Delete"><wsdl:input message="tns:GetPriceIn"/></wsdl:operation></wsdl:portType>
              <wsdl:binding name="ShopSoap" type="tns:Shop"><soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
                <wsdl:operation name="GetPrice"><soap:operation soapAction="urn:shop#GetPrice"/><wsdl:input><soap:body use="literal"/></wsdl:input></wsdl:operation>
                <wsdl:operation name="Delete"><soap:operation soapAction="urn:shop#Delete"/><wsdl:input><soap:body use="literal"/></wsdl:input></wsdl:operation>
              </wsdl:binding>
              <wsdl:service name="ShopService"><wsdl:port name="ShopPort" binding="tns:ShopSoap"><soap:address location="@@ADDR@@"/></wsdl:port></wsdl:service>
            </wsdl:definitions>""";

    private static final String RESP = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
            + "<GetPriceResponse xmlns=\"urn:shop\"><price>12.50</price><tag a=\"1\">x</tag><tag a=\"2\">y</tag></GetPriceResponse></s:Body></s:Envelope>";

    private String wsdl(String addr) {
        return DOC.replace("@@ADDR@@", addr);
    }

    private Map<String, Object> cfg(Object... kv) {
        Map<String, Object> c = m("wsdl", base() + "/wsdl", "operation", "GetPrice", "allowedOperations", List.of("GetPrice"));
        for (int i = 0; i < kv.length; i += 2) {
            c.put((String) kv[i], kv[i + 1]);
        }
        return c;
    }

    private Map<String, Object> call(Soap.Client c, Map<String, Object> config, Map<String, Object> in) throws Exception {
        return c.call(config, in);
    }

    private List<Req> posts() {
        return reqs.stream().filter(r -> r.method().equals("POST")).toList();
    }

    @Test
    void callsADocumentLiteralOperationAndConvertsTheResponse() throws Exception {
        wsdlText = wsdl(base() + "/ws");
        answer = RESP;
        Map<String, Object> out = call(new Soap.Client(local), cfg(), m("sku", "A&B<1>"));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) out.get("result");
        assertEquals("12.50", r.get("price"));
        assertEquals(2, ((List<?>) r.get("tag")).size());
        Req p = posts().get(0);
        assertEquals("POST /ws", p.method() + " " + p.uri());
        assertEquals("\"urn:shop#GetPrice\"", p.headers().get("soapaction"));
        assertTrue(p.headers().get("content-type").startsWith("text/xml"), p.headers().get("content-type"));
        assertTrue(p.body().contains("<GetPrice xmlns=\"urn:shop\"><sku>A&amp;B&lt;1&gt;</sku></GetPrice>"), p.body());
        assertTrue(p.body().contains("http://schemas.xmlsoap.org/soap/envelope/"));
    }

    @Test
    void theWsdlIsFetchedOnceAndCached() throws Exception {
        wsdlText = wsdl(base() + "/ws");
        answer = RESP;
        var c = new Soap.Client(local);
        call(c, cfg(), m("sku", "1"));
        call(c, cfg(), m("sku", "2"));
        assertEquals(1, reqs.stream().filter(r -> r.method().equals("GET")).count());
        assertEquals(2, posts().size());
    }

    @Test
    void attributesRepeatsAndNullsInTheRequest() throws Exception {
        wsdlText = wsdl(base() + "/ws");
        answer = RESP;
        call(new Soap.Client(local), cfg(), m("sku", m("@kind", "x", "#text", "v"), "item", List.of("a", "b"), "skipped", null));
        String b = posts().get(0).body();
        assertTrue(b.contains("<sku kind=\"x\">v</sku>"), b);
        assertTrue(b.contains("<item>a</item><item>b</item>"), b);
        assertFalse(b.contains("skipped"), b);
    }

    @Test
    void injectionThroughNamesAndValuesIsRefused() throws Exception {
        wsdlText = wsdl(base() + "/ws");
        answer = RESP;
        var c = new Soap.Client(local);
        for (Map<String, Object> in : List.<Map<String, Object>>of(
                m("sku>", "x"), m("a b", "x"), m("x/y", "x"), m("sku", m("@a\"b", "x")), m("sku", "bell\u0007"), m("sku", m("a:b", "x")))) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> call(c, cfg(), in), String.valueOf(in));
        }
        assertEquals(0, posts().size());
        call(c, cfg(), m("sku", "</sku><Evil/>"));
        assertTrue(posts().get(0).body().contains("&lt;/sku&gt;&lt;Evil/&gt;"), posts().get(0).body());
        assertFalse(posts().get(0).body().contains("<Evil/>"));
    }

    @Test
    void onlyAllowedOperationsAreCalled() throws Exception {
        wsdlText = wsdl(base() + "/ws");
        var c = new Soap.Client(local);
        assertThrows(ServiceHandler.ConnectorException.class, () -> call(c, cfg("operation", "Delete"), m("sku", "1")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> call(c, cfg("allowedOperations", List.of()), m("sku", "1")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> call(c, cfg("allowedOperations", null), m("sku", "1")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> call(c, cfg("operation", "Nope", "allowedOperations", List.of("Nope")), m()));
        assertThrows(ServiceHandler.ConnectorException.class, () -> call(c, cfg("operation", "GetPrice/../x"), m()));
        assertEquals(0, posts().size());
    }

    @Test
    void aFaultBecomesANonRetryableFailureWithTheFaultText() throws Exception {
        wsdlText = wsdl(base() + "/ws");
        status = 500;
        answer = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><faultcode>s:Client</faultcode>"
                + "<faultstring>Unknown SKU</faultstring></s:Fault></s:Body></s:Envelope>";
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> call(new Soap.Client(local), cfg(), m("sku", "x")));
        assertTrue(e.getMessage().contains("Client") && e.getMessage().contains("Unknown SKU"), e.getMessage());
        assertFalse(e.retryable());
    }

    @Test
    void aGatewayErrorWithoutSoapIsRetryableAndTimeoutsMapToo() throws Exception {
        wsdlText = wsdl(base() + "/ws");
        status = 503;
        answer = "<html>unavailable</html>";
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> call(new Soap.Client(local), cfg(), m("sku", "x")));
        assertTrue(e.retryable(), e.getMessage());
        status = 200;
        answer = "";
        var empty = assertThrows(ServiceHandler.ConnectorException.class, () -> call(new Soap.Client(local), cfg(), m("sku", "x")));
        assertFalse(empty.retryable());
    }

    @Test
    void soap12UsesItsContentTypeAndNamespace() throws Exception {
        wsdlText = wsdl(base() + "/ws").replace("http://schemas.xmlsoap.org/wsdl/soap/", "http://schemas.xmlsoap.org/wsdl/soap12/");
        answer = "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\"><e:Body><GetPriceResponse xmlns=\"urn:shop\"><price>1</price></GetPriceResponse></e:Body></e:Envelope>";
        Map<String, Object> out = call(new Soap.Client(local), cfg(), m("sku", "1"));
        assertEquals("1", ((Map<?, ?>) out.get("result")).get("price"));
        Req p = posts().get(0);
        assertTrue(p.headers().get("content-type").startsWith("application/soap+xml") && p.headers().get("content-type").contains("action=\"urn:shop#GetPrice\""),
                p.headers().get("content-type"));
        assertNull(p.headers().get("soapaction"));
        assertTrue(p.body().contains("http://www.w3.org/2003/05/soap-envelope"));
    }

    @Test
    void rpcLiteralWrapsPartsInTheOperationElement() throws Exception {
        String rpc = """
                <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/" xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                    xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:calc" targetNamespace="urn:calc">
                  <wsdl:message name="AddIn"><wsdl:part name="a" type="xsd:int"/><wsdl:part name="b" type="xsd:int"/></wsdl:message>
                  <wsdl:portType name="Calc"><wsdl:operation name="Add"><wsdl:input message="tns:AddIn"/></wsdl:operation></wsdl:portType>
                  <wsdl:binding name="CalcSoap" type="tns:Calc"><soap:binding style="rpc" transport="http://schemas.xmlsoap.org/soap/http"/>
                    <wsdl:operation name="Add"><soap:operation soapAction="add"/><wsdl:input><soap:body use="literal" namespace="urn:calc-ns"/></wsdl:input></wsdl:operation></wsdl:binding>
                  <wsdl:service name="S"><wsdl:port name="P" binding="tns:CalcSoap"><soap:address location="@@ADDR@@"/></wsdl:port></wsdl:service>
                </wsdl:definitions>""".replace("@@ADDR@@", base() + "/ws");
        wsdlText = rpc;
        answer = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><m:AddResponse xmlns:m=\"urn:calc-ns\"><sum>5</sum></m:AddResponse></s:Body></s:Envelope>";
        Map<String, Object> out = call(new Soap.Client(local), cfg("operation", "Add", "allowedOperations", List.of("Add")), m("a", 2, "b", 3));
        assertEquals("5", ((Map<?, ?>) out.get("result")).get("sum"));
        String b = posts().get(0).body();
        assertTrue(b.contains("<m:Add xmlns:m=\"urn:calc-ns\"><a>2</a><b>3</b></m:Add>"), b);
    }

    @Test
    void describeListsOperationsWithoutCallingAnything() throws Exception {
        wsdlText = wsdl(base() + "/ws");
        Map<String, Object> out = call(new Soap.Client(local), cfg("mode", "describe", "allowedOperations", null), m());
        assertEquals(2, ((List<?>) out.get("operations")).size());
        assertEquals(base() + "/ws", out.get("endpoint"));
        assertEquals("1.1", out.get("soapVersion"));
        assertEquals(0, posts().size());
    }

    @Test
    void importsDoctypeAndWrongDocumentsAreRefused() throws Exception {
        var c = new Soap.Client(local);
        wsdlText = wsdl(base() + "/ws").replace("<wsdl:types>", "<wsdl:import namespace=\"urn:x\" location=\"http://169.254.169.254/x\"/><wsdl:types>");
        var imp = assertThrows(ServiceHandler.ConnectorException.class, () -> call(c, cfg(), m("sku", "1")));
        assertTrue(imp.getMessage().contains("imports"), imp.getMessage());
        wsdlText = wsdl(base() + "/ws").replace("<wsdl:types>", "<wsdl:types><xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"><xsd:import schemaLocation=\"http://x/y.xsd\"/></xsd:schema>");
        assertThrows(ServiceHandler.ConnectorException.class, () -> call(new Soap.Client(local), cfg(), m("sku", "1")));
        wsdlText = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>" + wsdl(base() + "/ws");
        assertThrows(ServiceHandler.ConnectorException.class, () -> call(new Soap.Client(local), cfg(), m("sku", "1")));
        wsdlText = "<html/>";
        assertThrows(ServiceHandler.ConnectorException.class, () -> call(new Soap.Client(local), cfg(), m("sku", "1")));
        assertEquals(0, posts().size());
    }

    @Test
    void anUnusableSoapActionAndSoapEncodingAreRefused() throws Exception {
        answer = RESP;
        wsdlText = wsdl(base() + "/ws").replace("urn:shop#GetPrice", "x&#13;&#10;Evil: 1");
        assertThrows(ServiceHandler.ConnectorException.class, () -> call(new Soap.Client(local), cfg(), m("sku", "1")));
        wsdlText = wsdl(base() + "/ws").replace("urn:shop#GetPrice", "a&quot;b");
        assertThrows(ServiceHandler.ConnectorException.class, () -> call(new Soap.Client(local), cfg(), m("sku", "1")));
        wsdlText = wsdl(base() + "/ws").replace("<soap:body use=\"literal\"/></wsdl:input></wsdl:operation>\n    <wsdl:operation name=\"Delete\">",
                "<soap:body use=\"encoded\"/></wsdl:input></wsdl:operation>\n    <wsdl:operation name=\"Delete\">");
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> call(new Soap.Client(local), cfg(), m("sku", "1")));
        assertTrue(e.getMessage().contains("encoding"), e.getMessage());
        assertEquals(0, posts().size());
    }

    @Test
    void aResponseWithADoctypeIsRefused() throws Exception {
        wsdlText = wsdl(base() + "/ws");
        answer = "<?xml version=\"1.0\"?><!DOCTYPE e [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><a>&x;</a></s:Body></s:Envelope>";
        assertThrows(ServiceHandler.ConnectorException.class, () -> call(new Soap.Client(local), cfg(), m("sku", "1")));
    }

    @Test
    void aWsdlPointingAtAnotherHostNeedsAnExplicitEndpoint() throws Exception {
        wsdlText = wsdl("http://localhost:" + server.getAddress().getPort() + "/ws"); // same machine, different host name
        answer = RESP;
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> call(new Soap.Client(local), cfg(), m("sku", "1")));
        assertTrue(e.getMessage().contains("another host"), e.getMessage());
        assertEquals(0, posts().size());
        call(new Soap.Client(local), cfg("endpoint", base() + "/ws"), m("sku", "1"));
        assertEquals(1, posts().size());
    }

    @Test
    void inlineWsdlWorksAndCredentialsNeedHttps() throws Exception {
        answer = RESP;
        Map<String, Object> inline = cfg("wsdl", wsdl(base() + "/ws"));
        call(new Soap.Client(local), inline, m("sku", "1"));
        assertEquals(0, reqs.stream().filter(r -> r.method().equals("GET")).count());
        Map<String, Object> creds = cfg("wsdl", wsdl(base() + "/ws"), "username", "u", "password", "p@ss");
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> call(new Soap.Client(local), creds, m("sku", "1")));
        assertTrue(e.getMessage().contains("plain http"), e.getMessage());
        assertEquals(1, posts().size());
        creds.put("allowInsecure", true);
        call(new Soap.Client(local), creds, m("sku", "1"));
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("u:p@ss".getBytes(StandardCharsets.UTF_8)), posts().get(1).headers().get("authorization"));
        assertThrows(ServiceHandler.ConnectorException.class, () -> call(new Soap.Client(local), cfg("wsdl", wsdl(base() + "/ws"), "username", "u"), m("sku", "1")));
    }

    @Test
    void thePrivateNetworkGuardAppliesToTheWsdlAndTheEndpoint() {
        assertThrows(Exception.class, () -> call(new Soap.Client(UrlGuard.strict()), cfg(), m("sku", "1")));
        assertThrows(Exception.class, () -> call(new Soap.Client(UrlGuard.strict()), cfg("wsdl", wsdl("http://127.0.0.1:1/ws"), "endpoint", "http://127.0.0.1:1/ws"), m("sku", "1")));
    }

    private Soap.Client fixed() {
        return new Soap.Client(local, java.time.Clock.fixed(java.time.Instant.parse("2026-10-27T01:02:03.456Z"), java.time.ZoneOffset.UTC),
                () -> "0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void wsSecurityDigestMatchesTheProfileFormulaAndNoBasicHeaderIsSent() throws Exception {
        wsdlText = wsdl(base() + "/ws");
        answer = RESP;
        call(fixed(), cfg("username", "svc", "password", "s3cret", "wsSecurity", "usernameToken", "allowInsecure", true), m("sku", "1"));
        Req p = posts().get(0);
        assertNull(p.headers().get("authorization"));
        String created = "2026-10-27T01:02:03Z";
        var sha = java.security.MessageDigest.getInstance("SHA-1");
        sha.update("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        sha.update(created.getBytes(StandardCharsets.UTF_8));
        sha.update("s3cret".getBytes(StandardCharsets.UTF_8));
        String expected = java.util.Base64.getEncoder().encodeToString(sha.digest());
        assertTrue(p.body().contains(">" + expected + "</wsse:Password>"), p.body());
        assertTrue(p.body().contains("PasswordDigest") && p.body().contains("<wsu:Created>" + created + "</wsu:Created>"), p.body());
        assertTrue(p.body().contains(java.util.Base64.getEncoder().encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8))), p.body());
        assertFalse(p.body().contains("s3cret"), "a digest never carries the password");
        assertTrue(p.body().indexOf("<soapenv:Header>") < p.body().indexOf("<soapenv:Body>"));
    }

    @Test
    void wsSecurityTextEscapesCredentialsAndNeedsHttps() throws Exception {
        wsdlText = wsdl(base() + "/ws");
        answer = RESP;
        Map<String, Object> c = cfg("username", "a<b>&c", "password", "p</wsse:Password><x/>", "wsSecurity", "usernameToken", "passwordType", "text");
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> call(fixed(), c, m("sku", "1")));
        assertTrue(e.getMessage().contains("plain http"), e.getMessage());
        assertEquals(0, posts().size());
        c.put("allowInsecure", true);
        call(fixed(), c, m("sku", "1"));
        String b = posts().get(0).body();
        assertTrue(b.contains("<wsse:Username>a&lt;b&gt;&amp;c</wsse:Username>") && b.contains("PasswordText"), b);
        assertFalse(b.contains("<x/>"), b);
    }

    @Test
    void wsSecurityRejectsUnknownModesAndMissingCredentials() throws Exception {
        wsdlText = wsdl(base() + "/ws");
        answer = RESP;
        assertThrows(ServiceHandler.ConnectorException.class, () -> call(fixed(), cfg("username", "u", "password", "p", "wsSecurity", "signature", "allowInsecure", true), m("sku", "1")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> call(fixed(), cfg("username", "u", "password", "p", "wsSecurity", "usernameToken", "passwordType", "md5", "allowInsecure", true), m("sku", "1")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> call(fixed(), cfg("wsSecurity", "usernameToken"), m("sku", "1")));
        assertEquals(0, posts().size());
    }
}
