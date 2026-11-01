package tech.kayys.wayang.connectors;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code soap}: call operations of a SOAP 1.1 / 1.2 web service described by a WSDL 1.1 document
 * (document/literal and rpc/literal; the usual enterprise case).
 *
 * <p>Config: {@code wsdl} (URL, or the WSDL text itself when it starts with {@code <}), <b>{@code allowedOperations}
 * (required, exact names)</b>, {@code operation} (what to call), optional {@code mode: describe} (list the operations instead),
 * {@code endpoint} (override the address in the WSDL), {@code port} (pick a port by name), {@code username}/{@code password}
 * (HTTP Basic; refused over plain http unless {@code allowInsecure: true}), {@code headers}, {@code timeoutMs}.
 *
 * <p>Inputs: the content of the request element as JSON. Object keys become child elements, {@code "@name"} keys become
 * attributes, {@code "#text"} is the element text, a list repeats its element, {@code null} is skipped. For a multi-part message,
 * or rpc style, inputs are keyed by part name. Output: {@code result} (the response element as JSON, element names without
 * namespaces, every value a string because no XSD types are applied), {@code status}, {@code operation}.
 *
 * <p>Limits, deliberately: no WS-Security, WS-Addressing, MTOM or SOAP encoding, no XSD validation of the request, no
 * {@code import}/{@code include} of other documents (flatten the WSDL; remote imports are refused so a WSDL cannot make the
 * server fetch other URLs), WSDL 1.1 only. A SOAP fault becomes a non-retryable failure carrying the fault code and text.
 */
public final class Soap {

    private static final String NS_WSDL = "http://schemas.xmlsoap.org/wsdl/";
    private static final String NS_SOAP11 = "http://schemas.xmlsoap.org/wsdl/soap/";
    private static final String NS_SOAP12 = "http://schemas.xmlsoap.org/wsdl/soap12/";
    private static final String NS_ENV11 = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String NS_ENV12 = "http://www.w3.org/2003/05/soap-envelope";
    private static final String NS_XSD = "http://www.w3.org/2001/XMLSchema";
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,63}");
    private static final int MAX_BYTES = 2 << 20;
    private static final int MAX_DEPTH = 20;
    private static final int MAX_NODES = 5000;
    private static final long TTL_MS = 10 * 60_000L;
    private static final int MAX_CACHE = 50;

    private Soap() {
    }

    // ------------------------------------------------------------------ WSDL model

    record Part(String name, String ns, String element) {
    }

    record Op(String name, String action, boolean rpc, String rpcNs, List<Part> parts) {
    }

    record Wsdl(boolean soap12, String endpoint, boolean qualified, Map<String, Op> ops) {
    }

    private record Cached(Wsdl wsdl, long expires) {
    }

    // ------------------------------------------------------------------ connector

    public static final class Client implements ServiceHandler.Connector {
        private final HttpConnector http;
        private final Map<String, Cached> cache = new ConcurrentHashMap<>();
        private final java.time.Clock clock;
        private final java.util.function.Supplier<byte[]> nonce;

        public Client(UrlGuard guard) {
            this(guard, java.time.Clock.systemUTC(), () -> {
                byte[] n = new byte[16];
                new java.security.SecureRandom().nextBytes(n);
                return n;
            });
        }

        /** For tests: a fixed clock and nonce make the WS-Security digest reproducible. */
        Client(UrlGuard guard, java.time.Clock clock, java.util.function.Supplier<byte[]> nonce) {
            this.http = new HttpConnector(guard);
            this.clock = clock;
            this.nonce = nonce;
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("soap needs node configuration", false);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            if (!(config.get("wsdl") instanceof String src) || src.isBlank()) {
                throw new ServiceHandler.ConnectorException("soap needs 'wsdl' (a URL or the WSDL text)", false);
            }
            String endpointOverride = config.get("endpoint") instanceof String e && !e.isBlank() ? e : null;
            String port = config.get("port") instanceof String p && !p.isBlank() ? p : null;
            Wsdl w = load(src.strip(), port, config, endpointOverride != null);
            String endpoint = endpointOverride != null ? endpointOverride : w.endpoint();
            if ("describe".equals(config.get("mode"))) {
                List<Map<String, Object>> ops = new ArrayList<>();
                for (Op o : w.ops().values()) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("name", o.name());
                    d.put("soapAction", o.action());
                    d.put("style", o.rpc() ? "rpc" : "document");
                    List<String> in = new ArrayList<>();
                    o.parts().forEach(pt -> in.add(pt.element() != null ? pt.element() : pt.name()));
                    d.put("input", in);
                    ops.add(d);
                }
                return Map.of("operations", ops, "endpoint", endpoint == null ? "" : endpoint, "soapVersion", w.soap12() ? "1.2" : "1.1");
            }
            String opName = Rest.segment(config.get("operation"), NAME, "operation");
            Rest.allowed(config.get("allowedOperations"), opName, "operation");
            Op op = w.ops().get(opName);
            if (op == null) {
                throw new ServiceHandler.ConnectorException("The WSDL has no operation '" + opName + "'", false);
            }
            if (endpoint == null) {
                throw new ServiceHandler.ConnectorException("The WSDL has no endpoint address; set 'endpoint'", false);
            }
            Map<String, String> headers = new LinkedHashMap<>();
            if (config.get("headers") instanceof Map<?, ?> hm) {
                hm.forEach((k, v) -> headers.put(String.valueOf(k), String.valueOf(v)));
            }
            String wsse = auth(config, endpoint, headers);
            String action = op.action() == null ? "" : op.action();
            if (action.indexOf('"') >= 0 || action.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
                throw new ServiceHandler.ConnectorException("The WSDL has an unusable soapAction", false);
            }
            if (w.soap12()) {
                headers.put("Content-Type", "application/soap+xml; charset=utf-8; action=\"" + action + "\"");
            } else {
                headers.put("Content-Type", "text/xml; charset=utf-8");
                headers.put("SOAPAction", "\"" + action + "\"");
            }
            String envelope = envelope(w, op, inputs, wsse);
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("url", endpoint);
            req.put("method", "POST");
            req.put("headers", headers);
            req.put("timeoutMs", config.get("timeoutMs") instanceof Number n ? Math.max(1000, Math.min(60_000, n.longValue())) : 30_000);
            req.put("maxResponseBytes", MAX_BYTES);
            req.put("returnErrorBody", true);
            Map<String, Object> res = http.call(req, Map.of("body", envelope));
            int status = ((Number) res.get("status")).intValue();
            Object body = res.get("body");
            if (!(body instanceof String xml) || xml.isBlank()) {
                throw new ServiceHandler.ConnectorException("SOAP endpoint answered " + status + " without a body", status >= 500 || status == 429);
            }
            Element root = parse(xml, "The response is not valid XML (a DOCTYPE is not allowed)").getDocumentElement();
            Element bodyEl = child(root, "Body");
            if (!isEnvelope(root) || bodyEl == null) {
                throw new ServiceHandler.ConnectorException("The response is not a SOAP envelope (HTTP " + status + ")", status >= 500 || status == 429);
            }
            Element first = firstElement(bodyEl);
            if (first != null && "Fault".equals(first.getLocalName())) {
                String code = clip(text(first, "faultcode", "Value"), 100);
                String msg = clip(text(first, "faultstring", "Text"), 300);
                throw new ServiceHandler.ConnectorException("SOAP fault" + (code.isEmpty() ? "" : " " + code) + ": " + msg,
                        status == 503 || status == 504 || status == 429);
            }
            if (status < 200 || status > 299) {
                throw new ServiceHandler.ConnectorException("HTTP " + status, status >= 500 || status == 429);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("result", first == null ? Map.of() : value(first, 0, new int[] { 0 }));
            out.put("status", status);
            out.put("operation", opName);
            return out;
        }

        /** Adds HTTP Basic, or returns the WS-Security header block when {@code wsSecurity} is set. */
        private String auth(Map<String, Object> config, String endpoint, Map<String, String> headers) throws Exception {
            boolean u = config.get("username") instanceof String s && !s.isBlank();
            boolean p = config.get("password") instanceof String s && !s.isEmpty();
            if (u != p) {
                throw new ServiceHandler.ConnectorException("soap needs both 'username' and 'password' (use ${secret:NAME})", false);
            }
            if (!u) {
                if (config.get("wsSecurity") != null) {
                    throw new ServiceHandler.ConnectorException("soap 'wsSecurity' needs 'username' and 'password'", false);
                }
                return null;
            }
            if (!endpoint.regionMatches(true, 0, "https://", 0, 8) && !Boolean.TRUE.equals(config.get("allowInsecure"))) {
                throw new ServiceHandler.ConnectorException("Credentials are not sent over plain http (set allowInsecure: true to override)", false);
            }
            String user = (String) config.get("username");
            Object mode = config.get("wsSecurity");
            if (mode != null) {
                return usernameToken(user, (String) config.get("password"), mode, config.get("passwordType"));
            }
            if (user.indexOf(':') >= 0) {
                throw new ServiceHandler.ConnectorException("username must not contain ':'", false);
            }
            headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + config.get("password")).getBytes(StandardCharsets.UTF_8)));
            return null;
        }

        private static final String WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
        private static final String WSU = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd";
        private static final String PW = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#";

        /** WS-Security UsernameToken: PasswordText, or PasswordDigest = base64(sha1(nonce + created + password)) as the profile requires. */
        private String usernameToken(String user, String password, Object mode, Object type) throws Exception {
            if (!"usernameToken".equals(mode)) {
                throw new ServiceHandler.ConnectorException("soap 'wsSecurity' must be usernameToken", false);
            }
            boolean digest = !"text".equals(type == null ? "digest" : type);
            if (type != null && !"text".equals(type) && !"digest".equals(type)) {
                throw new ServiceHandler.ConnectorException("soap 'passwordType' must be digest or text", false);
            }
            StringBuilder sb = new StringBuilder("<wsse:Security xmlns:wsse=\"").append(WSSE).append("\" xmlns:wsu=\"").append(WSU)
                    .append("\" soapenv:mustUnderstand=\"1\"><wsse:UsernameToken><wsse:Username>").append(esc(user)).append("</wsse:Username>");
            if (digest) {
                byte[] n = nonce.get();
                String created = java.time.format.DateTimeFormatter.ISO_INSTANT.format(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
                java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1"); // mandated by the UsernameToken profile
                sha1.update(n);
                sha1.update(created.getBytes(StandardCharsets.UTF_8));
                sha1.update(password.getBytes(StandardCharsets.UTF_8));
                sb.append("<wsse:Password Type=\"").append(PW).append("PasswordDigest\">").append(Base64.getEncoder().encodeToString(sha1.digest()))
                        .append("</wsse:Password><wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">")
                        .append(Base64.getEncoder().encodeToString(n)).append("</wsse:Nonce><wsu:Created>").append(created).append("</wsu:Created>");
            } else {
                sb.append("<wsse:Password Type=\"").append(PW).append("PasswordText\">").append(esc(password)).append("</wsse:Password>");
            }
            return sb.append("</wsse:UsernameToken></wsse:Security>").toString();
        }

        private Wsdl load(String src, String port, Map<String, Object> config, boolean endpointGiven) throws Exception {
            String key = (port == null ? "" : port + "|") + src.hashCode() + "|" + src.length() + "|" + (src.startsWith("<") ? "inline" : src);
            long now = System.currentTimeMillis();
            Cached c = cache.get(key);
            if (c != null && c.expires() > now) {
                return c.wsdl();
            }
            String xml = src;
            String host = null;
            if (!src.startsWith("<")) {
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("url", src);
                req.put("method", "GET");
                req.put("timeoutMs", 20_000);
                req.put("maxResponseBytes", MAX_BYTES);
                Object b = http.call(req, Map.of()).get("body");
                if (!(b instanceof String s)) {
                    throw new ServiceHandler.ConnectorException("The WSDL URL did not return XML text", false);
                }
                xml = s;
                host = java.net.URI.create(src).getHost();
            } else if (src.length() > MAX_BYTES) {
                throw new ServiceHandler.ConnectorException("The WSDL is larger than " + MAX_BYTES + " bytes", false);
            }
            Wsdl w = parseWsdl(xml, port);
            if (host != null && !endpointGiven && w.endpoint() != null) {
                String eh = java.net.URI.create(w.endpoint()).getHost();
                if (eh == null || !eh.equalsIgnoreCase(host)) {
                    throw new ServiceHandler.ConnectorException("The WSDL points at another host than it was fetched from; set 'endpoint' explicitly", false);
                }
            }
            if (cache.size() >= MAX_CACHE) {
                cache.clear();
            }
            cache.put(key, new Cached(w, now + TTL_MS));
            return w;
        }
    }

    // ------------------------------------------------------------------ WSDL parsing

    private static DocumentBuilder builder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }

    private static Document parse(String xml, String failure) throws Exception {
        try {
            return builder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (org.xml.sax.SAXException e) {
            throw new ServiceHandler.ConnectorException(failure, false);
        }
    }

    static Wsdl parseWsdl(String xml, String portName) throws Exception {
        Element def = parse(xml, "The WSDL is not valid XML (a DOCTYPE is not allowed)").getDocumentElement();
        if (!NS_WSDL.equals(def.getNamespaceURI()) || !"definitions".equals(def.getLocalName())) {
            throw new ServiceHandler.ConnectorException("Not a WSDL 1.1 document (expected wsdl:definitions)", false);
        }
        String tns = def.getAttribute("targetNamespace");
        for (Element e : descendants(def)) {
            String ln = e.getLocalName();
            if (("import".equals(ln) || "include".equals(ln)) && (NS_WSDL.equals(e.getNamespaceURI()) || NS_XSD.equals(e.getNamespaceURI()))
                    && (e.hasAttribute("location") || e.hasAttribute("schemaLocation"))) {
                throw new ServiceHandler.ConnectorException("The WSDL imports another document; flatten it into one WSDL (imports are not fetched)", false);
            }
        }
        // top-level schema elements: name -> namespace, and whether local elements are qualified
        Map<String, String> elementNs = new LinkedHashMap<>();
        boolean qualified = false;
        for (Element e : descendants(def)) {
            if (NS_XSD.equals(e.getNamespaceURI()) && "schema".equals(e.getLocalName())) {
                String sns = e.getAttribute("targetNamespace");
                qualified |= "qualified".equals(e.getAttribute("elementFormDefault"));
                for (Element el : children(e, NS_XSD, "element")) {
                    elementNs.put(el.getAttribute("name"), sns);
                }
            }
        }
        Map<String, List<Part>> messages = new LinkedHashMap<>();
        for (Element m : children(def, NS_WSDL, "message")) {
            List<Part> parts = new ArrayList<>();
            for (Element pt : children(m, NS_WSDL, "part")) {
                String el = pt.getAttribute("element");
                String local = el.isEmpty() ? null : local(el);
                String ns = local == null ? null : (elementNs.containsKey(local) ? elementNs.get(local) : resolve(pt, el, tns));
                parts.add(new Part(pt.getAttribute("name"), ns, local));
            }
            messages.put(m.getAttribute("name"), parts);
        }
        Map<String, Map<String, String>> portTypes = new LinkedHashMap<>(); // portType -> op -> input message
        for (Element pt : children(def, NS_WSDL, "portType")) {
            Map<String, String> ops = new LinkedHashMap<>();
            for (Element op : children(pt, NS_WSDL, "operation")) {
                Element in = firstChild(op, NS_WSDL, "input");
                if (in != null) {
                    ops.put(op.getAttribute("name"), local(in.getAttribute("message")));
                }
            }
            portTypes.put(pt.getAttribute("name"), ops);
        }
        // pick the service port
        Element chosen = null;
        Element binding = null;
        boolean s12 = false;
        for (Element svc : children(def, NS_WSDL, "service")) {
            for (Element pt : children(svc, NS_WSDL, "port")) {
                if (portName != null && !portName.equals(pt.getAttribute("name"))) {
                    continue;
                }
                Element addr = firstSoapAddress(pt);
                Element b = bindingNamed(def, local(pt.getAttribute("binding")));
                if (addr != null && b != null && chosen == null) {
                    chosen = pt;
                    binding = b;
                    s12 = NS_SOAP12.equals(addr.getNamespaceURI());
                }
            }
        }
        if (chosen == null) {
            throw new ServiceHandler.ConnectorException(portName == null ? "The WSDL has no SOAP port" : "The WSDL has no SOAP port '" + portName + "'", false);
        }
        String endpoint = firstSoapAddress(chosen).getAttribute("location");
        Element sb = null;
        for (Element e : children(binding, null, "binding")) {
            if (NS_SOAP11.equals(e.getNamespaceURI()) || NS_SOAP12.equals(e.getNamespaceURI())) {
                sb = e;
            }
        }
        boolean defaultRpc = sb != null && "rpc".equals(sb.getAttribute("style"));
        Map<String, String> typeOps = portTypes.getOrDefault(local(binding.getAttribute("type")), Map.of());
        Map<String, Op> ops = new LinkedHashMap<>();
        for (Element bo : children(binding, NS_WSDL, "operation")) {
            String name = bo.getAttribute("name");
            Element so = null;
            for (Element e : children(bo, null, "operation")) {
                if (NS_SOAP11.equals(e.getNamespaceURI()) || NS_SOAP12.equals(e.getNamespaceURI())) {
                    so = e;
                }
            }
            boolean rpc = so != null && !so.getAttribute("style").isEmpty() ? "rpc".equals(so.getAttribute("style")) : defaultRpc;
            String action = so == null ? "" : so.getAttribute("soapAction");
            String rpcNs = tns;
            Element in = firstChild(bo, NS_WSDL, "input");
            if (in != null) {
                for (Element e : children(in, null, "body")) {
                    if (e.hasAttribute("use") && "encoded".equals(e.getAttribute("use"))) {
                        throw new ServiceHandler.ConnectorException("Operation '" + name + "' uses SOAP encoding, which is not supported (use literal)", false);
                    }
                    if (e.hasAttribute("namespace")) {
                        rpcNs = e.getAttribute("namespace");
                    }
                }
            }
            String msg = typeOps.get(name);
            List<Part> parts = msg == null ? List.of() : messages.getOrDefault(msg, List.of());
            ops.put(name, new Op(name, action, rpc, rpcNs, parts));
        }
        if (ops.isEmpty()) {
            throw new ServiceHandler.ConnectorException("The WSDL binding has no operations", false);
        }
        return new Wsdl(s12, endpoint.isBlank() ? null : endpoint, qualified, ops);
    }

    private static Element bindingNamed(Element def, String name) {
        for (Element b : children(def, NS_WSDL, "binding")) {
            if (name.equals(b.getAttribute("name"))) {
                return b;
            }
        }
        return null;
    }

    private static Element firstSoapAddress(Element port) {
        for (Element e : children(port, null, "address")) {
            if (NS_SOAP11.equals(e.getNamespaceURI()) || NS_SOAP12.equals(e.getNamespaceURI())) {
                return e;
            }
        }
        return null;
    }

    private static String local(String qname) {
        int i = qname.indexOf(':');
        return i < 0 ? qname : qname.substring(i + 1);
    }

    private static String resolve(Element at, String qname, String fallback) {
        int i = qname.indexOf(':');
        String ns = at.lookupNamespaceURI(i < 0 ? null : qname.substring(0, i));
        return ns == null ? fallback : ns;
    }

    // ------------------------------------------------------------------ request

    static String envelope(Wsdl w, Op op, Map<String, Object> in, String header) throws ServiceHandler.ConnectorException {
        StringBuilder sb = new StringBuilder(512);
        String env = w.soap12() ? NS_ENV12 : NS_ENV11;
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><soapenv:Envelope xmlns:soapenv=\"").append(env).append("\">");
        if (header != null) {
            sb.append("<soapenv:Header>").append(header).append("</soapenv:Header>");
        }
        sb.append("<soapenv:Body>");
        int[] nodes = { 0 };
        if (op.rpc()) {
            sb.append("<m:").append(op.name()).append(" xmlns:m=\"").append(esc(op.rpcNs())).append("\">");
            for (Part p : op.parts()) {
                write(sb, p.name(), in.get(p.name()), 1, nodes);
            }
            sb.append("</m:").append(op.name()).append('>');
        } else if (op.parts().isEmpty()) {
            // no input message: an empty body
        } else if (op.parts().size() == 1) {
            Part p = op.parts().get(0);
            if (p.element() == null) {
                throw new ServiceHandler.ConnectorException("Operation '" + op.name() + "' has a type-based part; only element-based document/literal is supported", false);
            }
            Object content = in.get(p.element()) instanceof Map<?, ?> inner && in.size() == 1 ? inner : in;
            root(sb, p, content, w.qualified(), nodes);
        } else {
            for (Part p : op.parts()) {
                if (p.element() == null) {
                    throw new ServiceHandler.ConnectorException("Operation '" + op.name() + "' has a type-based part; only element-based document/literal is supported", false);
                }
                root(sb, p, in.get(p.name()), w.qualified(), nodes);
            }
        }
        return sb.append("</soapenv:Body></soapenv:Envelope>").toString();
    }

    private static void root(StringBuilder sb, Part p, Object content, boolean qualified, int[] nodes) throws ServiceHandler.ConnectorException {
        String name = Rest.segment(p.element(), NAME, "element name");
        String ns = p.ns() == null ? "" : p.ns();
        if (qualified) {
            sb.append('<').append(name).append(" xmlns=\"").append(esc(ns)).append("\">");
        } else {
            sb.append("<n:").append(name).append(" xmlns:n=\"").append(esc(ns)).append("\">");
        }
        body(sb, content, 1, nodes);
        sb.append("</").append(qualified ? name : "n:" + name).append('>');
    }

    /** The content of an element: a map of attributes/children/text, or a scalar. */
    private static void body(StringBuilder sb, Object v, int depth, int[] nodes) throws ServiceHandler.ConnectorException {
        if (depth > MAX_DEPTH) {
            throw new ServiceHandler.ConnectorException("Request nests deeper than " + MAX_DEPTH, false);
        }
        if (v instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (k.equals("#text")) {
                    sb.append(esc(String.valueOf(e.getValue())));
                } else if (!k.startsWith("@")) {
                    write(sb, k, e.getValue(), depth, nodes);
                }
            }
        } else if (v != null && !(v instanceof List)) {
            sb.append(esc(scalar(v)));
        }
    }

    private static void write(StringBuilder sb, String name, Object v, int depth, int[] nodes) throws ServiceHandler.ConnectorException {
        if (v == null) {
            return;
        }
        if (!NAME.matcher(name).matches()) {
            throw new ServiceHandler.ConnectorException("Invalid element name '" + clip(name.replaceAll("\\p{Cntrl}", "?"), 40) + "'", false);
        }
        if (v instanceof List<?> l) {
            for (Object o : l) {
                write(sb, name, o, depth, nodes);
            }
            return;
        }
        if (++nodes[0] > MAX_NODES) {
            throw new ServiceHandler.ConnectorException("Request has more than " + MAX_NODES + " elements", false);
        }
        sb.append('<').append(name);
        if (v instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (k.startsWith("@")) {
                    String an = k.substring(1);
                    if (!NAME.matcher(an).matches()) {
                        throw new ServiceHandler.ConnectorException("Invalid attribute name", false);
                    }
                    sb.append(' ').append(an).append("=\"").append(esc(scalar(e.getValue()))).append('"');
                }
            }
        }
        sb.append('>');
        body(sb, v, depth + 1, nodes);
        sb.append("</").append(name).append('>');
    }

    private static String scalar(Object v) throws ServiceHandler.ConnectorException {
        if (v instanceof Map || v instanceof List) {
            throw new ServiceHandler.ConnectorException("Expected a simple value", false);
        }
        return v instanceof java.math.BigDecimal b ? b.toPlainString() : String.valueOf(v);
    }

    private static String esc(String s) throws ServiceHandler.ConnectorException {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r' || c == 0x7f || c == 0xfffe || c == 0xffff) {
                throw new ServiceHandler.ConnectorException("A value contains a character that XML cannot carry", false);
            }
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ response

    private static boolean isEnvelope(Element root) {
        return "Envelope".equals(root.getLocalName()) && (NS_ENV11.equals(root.getNamespaceURI()) || NS_ENV12.equals(root.getNamespaceURI()));
    }

    private static String text(Element fault, String name11, String name12) {
        // 1.1: <faultstring>, <faultcode>; 1.2: <Reason><Text>, <Code><Value>
        for (Element e : descendants(fault)) {
            if (name11.equals(e.getLocalName()) || name12.equals(e.getLocalName())) {
                return e.getTextContent().strip().replaceAll("\\s+", " ");
            }
        }
        return "";
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /** An element as JSON: leaf -> string; otherwise a map with {@code @attr}, children (repeats become lists) and {@code #text}. */
    private static Object value(Element e, int depth, int[] nodes) throws ServiceHandler.ConnectorException {
        if (depth > 64 || ++nodes[0] > 100_000) {
            throw new ServiceHandler.ConnectorException("The response is too deep or too large", false);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            String n = a.getNodeName();
            if (!n.equals("xmlns") && !n.startsWith("xmlns:") && !n.startsWith("xsi:")) {
                out.put("@" + (a.getLocalName() == null ? n : a.getLocalName()), a.getNodeValue());
            }
        }
        StringBuilder txt = new StringBuilder();
        NodeList kids = e.getChildNodes();
        boolean hasChild = false;
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k instanceof Element ce) {
                hasChild = true;
                String name = ce.getLocalName();
                Object v = value(ce, depth + 1, nodes);
                Object prev = out.get(name);
                if (prev == null && !out.containsKey(name)) {
                    out.put(name, v);
                } else if (prev instanceof RepeatList rl) {
                    rl.add(v);
                } else {
                    RepeatList rl = new RepeatList();
                    rl.add(prev);
                    rl.add(v);
                    out.put(name, rl);
                }
            } else if (k.getNodeType() == Node.TEXT_NODE || k.getNodeType() == Node.CDATA_SECTION_NODE) {
                txt.append(k.getNodeValue());
            }
        }
        String t = txt.toString().strip();
        if (!hasChild && out.isEmpty()) {
            return t;
        }
        if (!t.isEmpty()) {
            out.put("#text", t);
        }
        out.replaceAll((k, v) -> v instanceof RepeatList rl ? List.copyOf(rl) : v);
        return out;
    }

    /** Marks a list built from repeated elements, so a real list value is never confused with it. */
    private static final class RepeatList extends ArrayList<Object> {
        private static final long serialVersionUID = 1L;
    }

    // ------------------------------------------------------------------ DOM helpers

    private static List<Element> children(Element p, String ns, String local) {
        List<Element> out = new ArrayList<>();
        for (Node n = p.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && local.equals(e.getLocalName()) && (ns == null || ns.equals(e.getNamespaceURI()))) {
                out.add(e);
            }
        }
        return out;
    }

    private static Element firstChild(Element p, String ns, String local) {
        List<Element> l = children(p, ns, local);
        return l.isEmpty() ? null : l.get(0);
    }

    private static Element child(Element p, String local) {
        return firstChild(p, null, local);
    }

    private static Element firstElement(Element p) {
        for (Node n = p.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                return e;
            }
        }
        return null;
    }

    private static List<Element> descendants(Element p) {
        List<Element> out = new ArrayList<>();
        NodeList l = p.getElementsByTagName("*");
        for (int i = 0; i < l.getLength() && i < 100_000; i++) {
            out.add((Element) l.item(i));
        }
        return out;
    }
}
