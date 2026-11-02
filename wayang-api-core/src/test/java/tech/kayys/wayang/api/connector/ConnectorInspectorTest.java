package tech.kayys.wayang.api.connector;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.Role;

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

import tech.kayys.wayang.connectors.Soap;
import tech.kayys.wayang.connectors.UrlGuard;

class ConnectorInspectorTest {

    private HttpServer server;
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private final Principal designer = new Principal("acme", "dana", Set.of(Role.DESIGNER));
    private final Principal viewer = new Principal("acme", "vic", Set.of(Role.VIEWER));

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @BeforeEach
    void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " auth=" + ex.getRequestHeaders().getFirst("Authorization"));
            String wsdl = """
                    <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/" xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                        xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:t" targetNamespace="urn:t">
                      <wsdl:types><xsd:schema targetNamespace="urn:t"><xsd:element name="Ping"/></xsd:schema></wsdl:types>
                      <wsdl:message name="In"><wsdl:part name="p" element="tns:Ping"/></wsdl:message>
                      <wsdl:portType name="T"><wsdl:operation name="Ping"><wsdl:input message="tns:In"/></wsdl:operation></wsdl:portType>
                      <wsdl:binding name="B" type="tns:T"><soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
                        <wsdl:operation name="Ping"><soap:operation soapAction="ping"/><wsdl:input><soap:body use="literal"/></wsdl:input></wsdl:operation></wsdl:binding>
                      <wsdl:service name="S"><wsdl:port name="P" binding="tns:B"><soap:address location="@@/ws"/></wsdl:port></wsdl:service>
                    </wsdl:definitions>""".replace("@@", base());
            byte[] b = wsdl.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/xml");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void down() {
        server.stop(0);
    }

    private ConnectorInspector inspector() {
        return new ConnectorInspector(Map.of("soap", new Soap.Client(new UrlGuard(true, Set.of()))));
    }

    @SuppressWarnings("unchecked")
    @Test
    void aDesignerSeesTheOperationsAndNothingIsCalled() {
        Map<String, Object> out = inspector().describe(designer, Map.of("connector", "soap", "config", Map.of("wsdl", base() + "/wsdl")));
        var ops = (List<Map<String, Object>>) out.get("operations");
        assertEquals("Ping", ops.get(0).get("name"));
        assertEquals("ping", ops.get(0).get("soapAction"));
        assertEquals(base() + "/ws", out.get("endpoint"));
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).startsWith("GET "), hits.get(0));
    }

    @Test
    void credentialsAndOtherKeysNeverReachTheConnector() {
        inspector().describe(designer, Map.of("connector", "soap", "config",
                Map.of("wsdl", base() + "/wsdl", "username", "u", "password", "p", "headers", Map.of("Authorization", "Bearer x"), "operation", "Ping")));
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).endsWith("auth=null"), hits.get(0));
    }

    @Test
    void onlyDesignersOnlySoapAndBadWsdlsAreBadRequests() {
        Map<String, Object> ok = Map.of("connector", "soap", "config", Map.of("wsdl", base() + "/wsdl"));
        assertThrows(ApiException.class, () -> inspector().describe(viewer, ok));
        assertThrows(ApiException.class, () -> inspector().describe(null, ok));
        assertThrows(ApiException.class, () -> inspector().describe(designer, Map.of("connector", "http", "config", Map.of("wsdl", "x"))));
        assertThrows(ApiException.class, () -> inspector().describe(designer, Map.of("connector", "soap", "config", Map.of())));
        assertThrows(ApiException.class, () -> inspector().describe(designer, Map.of("connector", "soap")));
        assertThrows(ApiException.class, () -> inspector().describe(designer, Map.of("connector", "soap", "config", Map.of("wsdl", "<html/>"))));
        assertEquals(0, hits.size());
    }

    @Test
    void thePrivateNetworkGuardStillApplies() {
        var strict = new ConnectorInspector(Map.of("soap", new Soap.Client(UrlGuard.strict())));
        assertThrows(ApiException.class, () -> strict.describe(designer, Map.of("connector", "soap", "config", Map.of("wsdl", base() + "/wsdl"))));
        assertEquals(0, hits.size());
    }
}
