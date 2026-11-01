package tech.kayys.wayang.connectors;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Trigger poller {@code rss}: one run per new entry of an RSS 2.0 or Atom feed.
 *
 * <p>Config: {@code url}, optional {@code limit} (1..50 events per poll, default 20), {@code emitExisting} (default false: the first
 * poll only remembers what is already there). Event: {@code id, title, link, summary, published, feed}. The cursor is a list of
 * hashes of the latest entry ids (at most 300), so feeds that are not sorted still work. XML with a DOCTYPE is refused.
 */
public final class Rss {

    private static final int MAX_SEEN = 300;
    private static final int MAX_TEXT = 4000;

    private Rss() {
    }

    public static final class Feed implements Poller {
        private final HttpConnector http;

        public Feed(UrlGuard guard) {
            this.http = new HttpConnector(guard);
        }

        @Override
        public Result poll(Map<String, Object> config, String cursor) throws Exception {
            if (!(config.get("url") instanceof String url) || url.isBlank()) {
                throw new ServiceHandler.ConnectorException("rss trigger needs 'url'", false);
            }
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("url", url);
            cfg.put("method", "GET");
            cfg.put("timeoutMs", 20_000);
            cfg.put("maxResponseBytes", 2 << 20);
            Object body = http.call(cfg, Map.of()).get("body");
            if (!(body instanceof String xml)) {
                throw new ServiceHandler.ConnectorException("Feed did not return XML text", false);
            }
            List<Map<String, Object>> entries = parse(xml);
            int limit = Rest.limit(config.get("limit"), 20, 50);
            Set<String> seen = new LinkedHashSet<>();
            if (cursor != null && !cursor.isBlank()) {
                seen.addAll(List.of(cursor.split(",")));
            }
            boolean first = cursor == null || cursor.isBlank();
            List<Map<String, Object>> events = new ArrayList<>();
            List<String> hashes = new ArrayList<>();
            for (Map<String, Object> e : entries) {
                String h = hash(String.valueOf(e.get("id")));
                hashes.add(h);
                if (!seen.contains(h) && (!first || Boolean.TRUE.equals(config.get("emitExisting")))) {
                    e.put("feed", url);
                    events.add(e);
                }
            }
            // oldest first, and never more than limit; entries beyond it stay unseen and come next poll
            java.util.Collections.reverse(events);
            if (events.size() > limit) {
                List<Map<String, Object>> keep = events.subList(0, limit);
                for (Map<String, Object> dropped : events.subList(limit, events.size())) {
                    hashes.remove(hash(String.valueOf(dropped.get("id"))));
                }
                events = new ArrayList<>(keep);
            }
            Set<String> next = new LinkedHashSet<>(hashes);
            next.addAll(seen);
            List<String> all = new ArrayList<>(next);
            if (all.size() > MAX_SEEN) {
                all = all.subList(0, MAX_SEEN);
            }
            return new Result(events, String.join(",", all));
        }

        static List<Map<String, Object>> parse(String xml) throws Exception {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            Element root;
            try {
                root = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
            } catch (org.xml.sax.SAXException e) {
                throw new ServiceHandler.ConnectorException("Feed is not valid XML (DOCTYPE is not allowed)", false);
            }
            List<Map<String, Object>> out = new ArrayList<>();
            NodeList items = root.getElementsByTagName("item");
            boolean atom = items.getLength() == 0;
            if (atom) {
                items = root.getElementsByTagName("entry");
            }
            for (int i = 0; i < items.getLength() && out.size() < 500; i++) {
                Element it = (Element) items.item(i);
                String link = atom ? atomLink(it) : text(it, "link");
                String id = first(text(it, atom ? "id" : "guid"), link, text(it, "title"));
                if (id == null) {
                    continue;
                }
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("id", cut(id, 500));
                e.put("title", cut(text(it, "title"), 500));
                e.put("link", cut(link, 2000));
                e.put("summary", cut(first(text(it, atom ? "summary" : "description"), text(it, "content")), MAX_TEXT));
                e.put("published", first(text(it, atom ? "updated" : "pubDate"), text(it, "published")));
                out.add(e);
            }
            return out;
        }

        private static String text(Element parent, String tag) {
            NodeList l = parent.getElementsByTagName(tag);
            for (int i = 0; i < l.getLength(); i++) {
                Node n = l.item(i);
                if (n.getParentNode() == parent) {
                    return n.getTextContent().strip();
                }
            }
            return null;
        }

        private static String atomLink(Element entry) {
            NodeList l = entry.getElementsByTagName("link");
            String any = null;
            for (int i = 0; i < l.getLength(); i++) {
                Element e = (Element) l.item(i);
                String href = e.getAttribute("href");
                if (href.isEmpty()) {
                    continue;
                }
                if (e.getAttribute("rel").isEmpty() || "alternate".equals(e.getAttribute("rel"))) {
                    return href;
                }
                any = any == null ? href : any;
            }
            return any;
        }

        private static String first(String... v) {
            for (String s : v) {
                if (s != null && !s.isBlank()) {
                    return s;
                }
            }
            return null;
        }

        private static String cut(String s, int max) {
            return s == null ? null : s.length() > max ? s.substring(0, max) : s;
        }

        private static String hash(String s) throws Exception {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)), 0, 6);
        }
    }
}
