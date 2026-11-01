package tech.kayys.wayang.bpmn.xml;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ProcessModel;
import tech.kayys.wayang.bpmn.SequenceFlow;

/**
 * BPMN 2.0 XML import/export for the subset Wayang supports.
 *
 * <p>
 * Import maps: {@code startEvent}, {@code endEvent}, {@code exclusiveGateway},
 * {@code parallelGateway}, {@code inclusiveGateway}, {@code serviceTask} (and
 * {@code sendTask}/{@code task}), {@code userTask}, {@code businessRuleTask},
 * {@code intermediateCatchEvent} with a timer ({@code timeDuration}/
 * {@code timeDate}) or message definition, and {@code sequenceFlow} with an
 * optional {@code conditionExpression} and gateway {@code default}.
 *
 * <p>
 * Wayang extensions use the namespace {@value #WAYANG_NS}: attribute
 * {@code wayang:type="agentTask|eipTask"} on a task, and
 * {@code <extensionElements><wayang:property name=".." value=".."/></extensionElements>}
 * for the element configuration (values are strings; numbers and booleans are
 * kept as such when they look like them).
 *
 * <p>
 * Anything else that would change execution semantics (sub-processes, boundary
 * events, loops, script tasks, event-based gateways, non-FEEL condition
 * languages, ...) makes the import fail with a list of what was found. DOCTYPE
 * declarations and external entities are refused.
 */
public final class BpmnXml {

    public static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    public static final String WAYANG_NS = "http://wayang.kayys.tech/bpmn";
    private static final int MAX_BYTES = 5_000_000;

    private static final Set<String> IGNORED = Set.of("documentation", "incoming", "outgoing", "textAnnotation",
            "association", "group", "laneSet", "extensionElements", "BPMNDiagram", "message", "definitions",
            "process", "conditionExpression", "timerEventDefinition", "messageEventDefinition", "signalEventDefinition");

    private BpmnXml() {
    }

    // ------------------------------------------------------------------ import

    public static ProcessModel parse(String xml) {
        byte[] b = xml.getBytes(StandardCharsets.UTF_8);
        return parse(new ByteArrayInputStream(b), null);
    }

    /** @param processId which process to import when the file holds several (null = it must hold exactly one) */
    public static ProcessModel parse(InputStream in, String processId) {
        Document doc = read(in);
        Element defs = doc.getDocumentElement();
        if (!"definitions".equals(defs.getLocalName()) || !BPMN_NS.equals(defs.getNamespaceURI())) {
            throw new BpmnXmlException("Root element must be bpmn:definitions in namespace " + BPMN_NS);
        }
        Map<String, String> messages = new LinkedHashMap<>();
        for (Element m : children(defs, "message")) {
            messages.put(m.getAttribute("id"), m.getAttribute("name"));
        }
        List<Element> processes = children(defs, "process");
        Element proc = null;
        if (processId == null) {
            if (processes.size() != 1) {
                throw new BpmnXmlException("Expected exactly one process but found " + processes.size()
                        + "; pass the process id to import");
            }
            proc = processes.get(0);
        } else {
            for (Element p : processes) {
                if (processId.equals(p.getAttribute("id"))) {
                    proc = p;
                }
            }
            if (proc == null) {
                throw new BpmnXmlException("No process with id '" + processId + "'");
            }
        }
        List<String> problems = new ArrayList<>();
        ProcessModel.Builder b = ProcessModel.builder(proc.getAttribute("id"));
        if (!proc.getAttribute("name").isBlank()) {
            b.name(proc.getAttribute("name"));
        }
        Map<String, String> defaults = new LinkedHashMap<>();
        for (Node n = proc.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element e) || !BPMN_NS.equals(e.getNamespaceURI())) {
                continue;
            }
            String name = e.getLocalName();
            String id = e.getAttribute("id");
            if (id.isBlank() && !IGNORED.contains(name)) {
                problems.add(name + " without id");
                continue;
            }
            switch (name) {
                case "startEvent" -> element(b, e, ElementType.START_EVENT, problems, true);
                case "endEvent" -> element(b, e, ElementType.END_EVENT, problems, true);
                case "exclusiveGateway" -> {
                    element(b, e, ElementType.EXCLUSIVE_GATEWAY, problems, false);
                    defaults.put(id, e.getAttribute("default"));
                }
                case "inclusiveGateway" -> {
                    element(b, e, ElementType.INCLUSIVE_GATEWAY, problems, false);
                    defaults.put(id, e.getAttribute("default"));
                }
                case "parallelGateway" -> element(b, e, ElementType.PARALLEL_GATEWAY, problems, false);
                case "serviceTask", "sendTask", "task" -> element(b, e, taskType(e, problems), problems, false);
                case "userTask" -> element(b, e, ElementType.USER_TASK, problems, false);
                case "businessRuleTask" -> element(b, e, ElementType.BUSINESS_RULE_TASK, problems, false);
                case "intermediateCatchEvent" -> catchEvent(b, e, messages, problems);
                case "sequenceFlow" -> { /* second pass, once defaults are known */ }
                case "laneSet", "textAnnotation", "association", "group", "documentation", "extensionElements" -> { }
                default -> problems.add("unsupported element <" + name + "> (" + id + ")");
            }
            if (e.getElementsByTagNameNS(BPMN_NS, "multiInstanceLoopCharacteristics").getLength() > 0) {
                problems.add("multi-instance activities are not supported (" + id + ")");
            }
            if (e.getElementsByTagNameNS(BPMN_NS, "standardLoopCharacteristics").getLength() > 0) {
                problems.add("loop activities are not supported (" + id + ")");
            }
        }
        for (Element f : children(proc, "sequenceFlow")) {
            String fid = f.getAttribute("id");
            String src = f.getAttribute("sourceRef");
            String cond = condition(f, problems);
            boolean dflt = fid.equals(defaults.get(src));
            b.flow(fid, src, f.getAttribute("targetRef"), cond, dflt);
        }
        if (!problems.isEmpty()) {
            throw new BpmnXmlException(problems);
        }
        return b.build();
    }

    private static void element(ProcessModel.Builder b, Element e, ElementType t, List<String> problems, boolean event) {
        if (event && !children(e, "timerEventDefinition", "messageEventDefinition", "signalEventDefinition",
                "errorEventDefinition", "escalationEventDefinition", "conditionalEventDefinition",
                "terminateEventDefinition", "compensateEventDefinition", "cancelEventDefinition",
                "linkEventDefinition").isEmpty()) {
            problems.add("typed start/end events are not supported (" + e.getAttribute("id") + ")");
        }
        String name = e.getAttribute("name");
        b.element(e.getAttribute("id"), t, name.isBlank() ? e.getAttribute("id") : name, properties(e, problems));
    }

    private static ElementType taskType(Element e, List<String> problems) {
        String t = e.getAttributeNS(WAYANG_NS, "type");
        if (t.isBlank()) {
            return ElementType.SERVICE_TASK;
        }
        return switch (t) {
            case "agentTask" -> ElementType.AGENT_TASK;
            case "eipTask" -> ElementType.EIP_TASK;
            case "serviceTask" -> ElementType.SERVICE_TASK;
            default -> {
                problems.add("unknown wayang:type '" + t + "' on " + e.getAttribute("id"));
                yield ElementType.SERVICE_TASK;
            }
        };
    }

    private static void catchEvent(ProcessModel.Builder b, Element e, Map<String, String> messages, List<String> problems) {
        String id = e.getAttribute("id");
        Map<String, Object> cfg = new LinkedHashMap<>(properties(e, problems));
        List<Element> timers = children(e, "timerEventDefinition");
        List<Element> msgs = children(e, "messageEventDefinition");
        if (timers.size() + msgs.size() != 1) {
            problems.add("intermediateCatchEvent " + id + " must have exactly one timer or message definition"
                    + " (signal/link/conditional are not supported)");
            return;
        }
        ElementType type;
        if (!timers.isEmpty()) {
            type = ElementType.TIMER_EVENT;
            List<Element> d = children(timers.get(0), "timeDuration");
            List<Element> date = children(timers.get(0), "timeDate");
            if (d.size() + date.size() != 1) {
                problems.add("timer " + id + " needs exactly one timeDuration or timeDate (cycles are not supported)");
                return;
            }
            if (!d.isEmpty()) {
                cfg.put("duration", d.get(0).getTextContent().strip());
            } else {
                cfg.put("until", date.get(0).getTextContent().strip());
            }
        } else {
            type = ElementType.MESSAGE_CATCH_EVENT;
            String ref = msgs.get(0).getAttribute("messageRef");
            String name = messages.get(ref);
            if (name == null || name.isBlank()) {
                problems.add("message event " + id + " refers to unknown or unnamed message '" + ref + "'");
                return;
            }
            cfg.put("message", name);
        }
        String nm = e.getAttribute("name");
        b.element(id, type, nm.isBlank() ? id : nm, cfg);
    }

    private static String condition(Element flow, List<String> problems) {
        List<Element> c = children(flow, "conditionExpression");
        if (c.isEmpty()) {
            return null;
        }
        Element ce = c.get(0);
        String lang = ce.getAttribute("language");
        if (!lang.isBlank() && !lang.toLowerCase(java.util.Locale.ROOT).contains("feel")
                && !lang.equalsIgnoreCase("wayang")) {
            problems.add("flow " + flow.getAttribute("id") + ": condition language '" + lang
                    + "' is not supported (use FEEL/Wayang expressions)");
        }
        String text = ce.getTextContent().strip();
        return text.isEmpty() ? null : text;
    }

    /** Reads {@code <extensionElements><wayang:property name value/>} into a config map. */
    private static Map<String, Object> properties(Element e, List<String> problems) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Element ext : children(e, "extensionElements")) {
            for (Node n = ext.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element p && WAYANG_NS.equals(p.getNamespaceURI()) && "property".equals(p.getLocalName())) {
                    String name = p.getAttribute("name");
                    if (name.isBlank()) {
                        problems.add("wayang:property without name on " + e.getAttribute("id"));
                        continue;
                    }
                    out.put(name, typed(p.getAttribute("value")));
                }
            }
        }
        return out;
    }

    private static Object typed(String v) {
        if (v.equals("true") || v.equals("false")) {
            return Boolean.valueOf(v);
        }
        if (v.matches("-?\\d{1,9}")) {
            return Integer.valueOf(v);
        }
        return v;
    }

    private static List<Element> children(Element parent, String... localNames) {
        List<Element> out = new ArrayList<>();
        List<String> wanted = List.of(localNames);
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && BPMN_NS.equals(e.getNamespaceURI()) && wanted.contains(e.getLocalName())) {
                out.add(e);
            }
        }
        return out;
    }

    private static Document read(InputStream in) {
        try {
            byte[] data = in.readNBytes(MAX_BYTES + 1);
            if (data.length > MAX_BYTES) {
                throw new BpmnXmlException("BPMN file exceeds " + MAX_BYTES + " bytes");
            }
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            var db = f.newDocumentBuilder();
            db.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() { // keep parser errors off stderr
                @Override
                public void error(org.xml.sax.SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(org.xml.sax.SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            return db.parse(new ByteArrayInputStream(data));
        } catch (ParserConfigurationException | SAXException e) {
            throw new BpmnXmlException("Invalid BPMN XML: " + e.getMessage(), e);
        } catch (java.io.IOException e) {
            throw new BpmnXmlException("Cannot read BPMN XML: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ export

    /** Writes the model as BPMN 2.0 XML (semantic model only; no diagram interchange). */
    public static String write(ProcessModel model) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            Document doc = f.newDocumentBuilder().newDocument();
            Element defs = doc.createElementNS(BPMN_NS, "bpmn:definitions");
            defs.setAttribute("xmlns:bpmn", BPMN_NS);
            defs.setAttribute("xmlns:wayang", WAYANG_NS);
            defs.setAttribute("targetNamespace", WAYANG_NS);
            doc.appendChild(defs);
            Map<String, String> messageIds = new LinkedHashMap<>();
            for (var el : model.elements().values()) {
                if (el.type() == ElementType.MESSAGE_CATCH_EVENT) {
                    String name = String.valueOf(el.config().get("message"));
                    messageIds.computeIfAbsent(name, n -> "msg_" + (messageIds.size() + 1));
                }
            }
            messageIds.forEach((name, id) -> {
                Element m = doc.createElementNS(BPMN_NS, "bpmn:message");
                m.setAttribute("id", id);
                m.setAttribute("name", name);
                defs.appendChild(m);
            });
            Element proc = doc.createElementNS(BPMN_NS, "bpmn:process");
            proc.setAttribute("id", model.id());
            proc.setAttribute("name", model.name());
            proc.setAttribute("isExecutable", "true");
            defs.appendChild(proc);
            Map<String, String> defaultOf = new LinkedHashMap<>();
            for (SequenceFlow sf : model.flows()) {
                if (sf.isDefault()) {
                    defaultOf.put(sf.source(), sf.id());
                }
            }
            for (var el : model.elements().values()) {
                String tag = switch (el.type()) {
                    case START_EVENT -> "startEvent";
                    case END_EVENT -> "endEvent";
                    case EXCLUSIVE_GATEWAY -> "exclusiveGateway";
                    case INCLUSIVE_GATEWAY -> "inclusiveGateway";
                    case PARALLEL_GATEWAY -> "parallelGateway";
                    case USER_TASK -> "userTask";
                    case BUSINESS_RULE_TASK -> "businessRuleTask";
                    case TIMER_EVENT, MESSAGE_CATCH_EVENT -> "intermediateCatchEvent";
                    default -> "serviceTask";
                };
                Element x = doc.createElementNS(BPMN_NS, "bpmn:" + tag);
                x.setAttribute("id", el.id());
                x.setAttribute("name", el.name());
                if (defaultOf.containsKey(el.id())) {
                    x.setAttribute("default", defaultOf.get(el.id()));
                }
                if (el.type() == ElementType.AGENT_TASK) {
                    x.setAttributeNS(WAYANG_NS, "wayang:type", "agentTask");
                } else if (el.type() == ElementType.EIP_TASK) {
                    x.setAttributeNS(WAYANG_NS, "wayang:type", "eipTask");
                }
                Map<String, Object> props = new LinkedHashMap<>(el.config());
                Object duration = el.type() == ElementType.TIMER_EVENT ? props.remove("duration") : null;
                Object until = el.type() == ElementType.TIMER_EVENT ? props.remove("until") : null;
                Object message = el.type() == ElementType.MESSAGE_CATCH_EVENT ? props.remove("message") : null;
                if (!props.isEmpty()) {
                    Element ext = doc.createElementNS(BPMN_NS, "bpmn:extensionElements");
                    for (Map.Entry<String, Object> p : props.entrySet()) {
                        if (p.getValue() instanceof String || p.getValue() instanceof Number
                                || p.getValue() instanceof Boolean) {
                            Element pe = doc.createElementNS(WAYANG_NS, "wayang:property");
                            pe.setAttribute("name", p.getKey());
                            pe.setAttribute("value", String.valueOf(p.getValue()));
                            ext.appendChild(pe);
                        } else {
                            throw new BpmnXmlException("Property '" + p.getKey() + "' of " + el.id()
                                    + " is a nested structure and cannot be written as a BPMN property");
                        }
                    }
                    x.appendChild(ext);
                }
                if (duration != null || until != null) {
                    Element t = doc.createElementNS(BPMN_NS, "bpmn:timerEventDefinition");
                    Element v = doc.createElementNS(BPMN_NS, duration != null ? "bpmn:timeDuration" : "bpmn:timeDate");
                    v.setTextContent(String.valueOf(duration != null ? duration : until));
                    t.appendChild(v);
                    x.appendChild(t);
                }
                if (message != null) {
                    Element m = doc.createElementNS(BPMN_NS, "bpmn:messageEventDefinition");
                    m.setAttribute("messageRef", messageIds.get(String.valueOf(message)));
                    x.appendChild(m);
                }
                proc.appendChild(x);
            }
            for (SequenceFlow sf : model.flows()) {
                Element x = doc.createElementNS(BPMN_NS, "bpmn:sequenceFlow");
                x.setAttribute("id", sf.id());
                x.setAttribute("sourceRef", sf.source());
                x.setAttribute("targetRef", sf.target());
                if (sf.condition() != null) {
                    Element c = doc.createElementNS(BPMN_NS, "bpmn:conditionExpression");
                    c.setAttribute("language", "wayang");
                    c.setTextContent(sf.condition());
                    x.appendChild(c);
                }
                proc.appendChild(x);
            }
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter w = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(w));
            return w.toString();
        } catch (ParserConfigurationException | javax.xml.transform.TransformerException e) {
            throw new BpmnXmlException("Cannot write BPMN XML: " + e.getMessage(), e);
        }
    }
}
