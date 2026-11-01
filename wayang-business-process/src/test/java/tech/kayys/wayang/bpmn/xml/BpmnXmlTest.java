package tech.kayys.wayang.bpmn.xml;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.bpmn.ProcessModel;

class BpmnXmlTest {

    private static final String LOAN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:wayang="http://wayang.kayys.tech/bpmn" targetNamespace="x">
              <bpmn:message id="m1" name="paid"/>
              <bpmn:process id="loan" name="Loan" isExecutable="true">
                <bpmn:startEvent id="start"/>
                <bpmn:businessRuleTask id="assess" name="Assess">
                  <bpmn:extensionElements><wayang:property name="model" value="risk"/></bpmn:extensionElements>
                </bpmn:businessRuleTask>
                <bpmn:exclusiveGateway id="gw" default="toGrant"/>
                <bpmn:userTask id="review"/>
                <bpmn:serviceTask id="grant">
                  <bpmn:extensionElements>
                    <wayang:property name="connector" value="ledger"/>
                    <wayang:property name="retries" value="3"/>
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:task id="summarize" wayang:type="agentTask"/>
                <bpmn:intermediateCatchEvent id="await">
                  <bpmn:messageEventDefinition messageRef="m1"/>
                </bpmn:intermediateCatchEvent>
                <bpmn:intermediateCatchEvent id="pause">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:intermediateCatchEvent>
                <bpmn:exclusiveGateway id="merge"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="assess"/>
                <bpmn:sequenceFlow id="f2" sourceRef="assess" targetRef="gw"/>
                <bpmn:sequenceFlow id="toReview" sourceRef="gw" targetRef="review">
                  <bpmn:conditionExpression>assess.result = "high"</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="toGrant" sourceRef="gw" targetRef="grant"/>
                <bpmn:sequenceFlow id="f5" sourceRef="review" targetRef="merge"/>
                <bpmn:sequenceFlow id="f6" sourceRef="grant" targetRef="summarize"/>
                <bpmn:sequenceFlow id="f7" sourceRef="summarize" targetRef="merge"/>
                <bpmn:sequenceFlow id="f8" sourceRef="merge" targetRef="await"/>
                <bpmn:sequenceFlow id="f9" sourceRef="await" targetRef="pause"/>
                <bpmn:sequenceFlow id="f10" sourceRef="pause" targetRef="end"/>
              </bpmn:process>
            </bpmn:definitions>
            """;

    @Test
    void importsSupportedSubset() {
        ProcessModel m = BpmnXml.parse(LOAN);
        assertEquals("loan", m.id());
        assertEquals(ElementType.BUSINESS_RULE_TASK, m.elements().get("assess").type());
        assertEquals("risk", m.elements().get("assess").config().get("model"));
        assertEquals(3, m.elements().get("grant").config().get("retries"));
        assertEquals(ElementType.AGENT_TASK, m.elements().get("summarize").type());
        assertEquals("paid", m.elements().get("await").config().get("message"));
        assertEquals("PT1H", m.elements().get("pause").config().get("duration"));
        assertTrue(m.flows().stream().filter(f -> f.id().equals("toGrant")).findFirst().get().isDefault());
        assertEquals("assess.result = \"high\"",
                m.flows().stream().filter(f -> f.id().equals("toReview")).findFirst().get().condition());
        assertDoesNotThrow(() -> ProcessCompiler.compile(m));
    }

    @Test
    void exportImportRoundTrips() {
        ProcessModel m = BpmnXml.parse(LOAN);
        ProcessModel back = BpmnXml.parse(BpmnXml.write(m));
        assertEquals(m.elements().keySet(), back.elements().keySet());
        for (var e : m.elements().values()) {
            assertEquals(e.type(), back.elements().get(e.id()).type(), e.id());
            assertEquals(e.config(), back.elements().get(e.id()).config(), e.id());
        }
        assertEquals(m.flows(), back.flows());
    }

    @Test
    void unsupportedFeaturesAreReportedNotIgnored() {
        String xml = LOAN.replace("<bpmn:endEvent id=\"end\"/>",
                "<bpmn:endEvent id=\"end\"/><bpmn:subProcess id=\"sp\"/><bpmn:scriptTask id=\"sc\"/>");
        var e = assertThrows(BpmnXmlException.class, () -> BpmnXml.parse(xml));
        assertTrue(e.problems().stream().anyMatch(p -> p.contains("subProcess")));
        assertTrue(e.problems().stream().anyMatch(p -> p.contains("scriptTask")));
    }

    @Test
    void unsupportedConditionLanguageIsRejected() {
        String xml = LOAN.replace("<bpmn:conditionExpression>", "<bpmn:conditionExpression language=\"groovy\">");
        var e = assertThrows(BpmnXmlException.class, () -> BpmnXml.parse(xml));
        assertTrue(e.getMessage().contains("groovy"));
    }

    @Test
    void doctypeAndExternalEntitiesAreRefused() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\">&x;</bpmn:definitions>";
        assertThrows(BpmnXmlException.class, () -> BpmnXml.parse(xxe));
    }

    @Test
    void boundaryAndMultiInstanceAreRejected() {
        String xml = LOAN.replace("<bpmn:userTask id=\"review\"/>",
                "<bpmn:userTask id=\"review\"><bpmn:multiInstanceLoopCharacteristics/></bpmn:userTask>"
                        + "<bpmn:boundaryEvent id=\"b\" attachedToRef=\"review\"/>");
        var e = assertThrows(BpmnXmlException.class, () -> BpmnXml.parse(xml));
        assertTrue(e.problems().stream().anyMatch(p -> p.contains("multi-instance")));
        assertTrue(e.problems().stream().anyMatch(p -> p.contains("boundaryEvent")));
    }

    @Test
    void severalProcessesNeedAnExplicitId() {
        String xml = "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\">"
                + "<bpmn:process id=\"a\"/><bpmn:process id=\"b\"/></bpmn:definitions>";
        assertThrows(BpmnXmlException.class, () -> BpmnXml.parse(xml));
    }

    @Test
    void nestedPropertiesCannotBeExported() {
        var m = ProcessModel.builder("p").start("s")
                .task("t", ElementType.SERVICE_TASK, Map.of("inputs", Map.of("a", "b"))).end("e")
                .flow("s", "t").flow("t", "e").build();
        assertThrows(BpmnXmlException.class, () -> BpmnXml.write(m));
    }
}
