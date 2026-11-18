package tech.kayys.wayang.rag;

import java.io.ByteArrayInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** XML reading for office files with DTDs and external entities switched off (no XXE, no entity expansion). */
final class Xml {

    private Xml() {
    }

    static XMLStreamReader reader(byte[] xml) {
        try {
            XMLInputFactory f = XMLInputFactory.newFactory();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
            f.setProperty(XMLInputFactory.IS_COALESCING, true);
            return f.createXMLStreamReader(new ByteArrayInputStream(xml));
        } catch (XMLStreamException | RuntimeException e) {
            throw new RagException("the file contains XML that could not be read", e);
        }
    }

    static String attr(XMLStreamReader r, String local) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (local.equals(r.getAttributeLocalName(i))) {
                return r.getAttributeValue(i);
            }
        }
        return null;
    }
}
