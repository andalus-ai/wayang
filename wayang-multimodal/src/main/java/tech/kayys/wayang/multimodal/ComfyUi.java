package tech.kayys.wayang.multimodal;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import tech.kayys.wayang.connectors.MediaHttp;
import tech.kayys.wayang.connectors.MediaStore;
import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code comfyui}: runs a ComfyUI workflow (API format) and stores the first image it produces.
 *
 * <p>Config: {@code workflow} (the exported API-format JSON object; every string value that equals or contains {@code {{prompt}}} gets the
 * input prompt substituted, value by value, so the prompt can never change the workflow's structure), optional {@code baseUrl} (default
 * {@code http://127.0.0.1:8188}; a private address needs {@code WAYANG_CONNECTORS_ALLOW_PRIVATE=true}), {@code timeoutSeconds} (default 120,
 * max 600). Input: {@code prompt}. Output: {@code mediaId}, {@code mimeType}, {@code bytes}, {@code promptId}.
 */
final class ComfyUi implements ServiceHandler.Connector {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._ -]{1,200}");
    private static final Pattern SUB = Pattern.compile("[A-Za-z0-9._ /-]{0,200}");

    private final MediaHttp http;
    private final MediaStore store;

    ComfyUi(UrlGuard guard, MediaStore store) {
        this.http = new MediaHttp(guard);
        this.store = store;
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
        throw new ServiceHandler.ConnectorException("comfyui needs node configuration", false);
    }

    /** Copy of the workflow with the prompt put into matching string values. */
    static Object fill(Object v, String prompt) {
        if (v instanceof String s) {
            return s.replace("{{prompt}}", prompt);
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), fill(x, prompt)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(x -> out.add(fill(x, prompt)));
            return out;
        }
        return v;
    }

    @Override
    public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
        String prompt = inputs.get("prompt") instanceof String p ? p : null;
        if (prompt == null || prompt.isBlank() || prompt.length() > 4000) {
            throw new ServiceHandler.ConnectorException("comfyui needs a 'prompt' of 1..4000 characters", false);
        }
        if (!(config.get("workflow") instanceof Map<?, ?> wf) || wf.isEmpty()) {
            throw new ServiceHandler.ConnectorException("comfyui needs a 'workflow' (the API-format JSON object)", false);
        }
        String base = config.get("baseUrl") instanceof String b && !b.isBlank() ? b : "http://127.0.0.1:8188";
        long seconds = config.get("timeoutSeconds") instanceof Number n ? Math.max(5, Math.min(600, n.longValue())) : 120;
        Object submitted = http.postJson(http.uri(base, "/prompt"), Map.of(),
                Map.of("prompt", fill(wf, prompt), "client_id", UUID.randomUUID().toString()), 15_000, 1 << 20);
        if (!(submitted instanceof Map<?, ?> sm) || !(sm.get("prompt_id") instanceof String pid) || !pid.matches("[A-Za-z0-9-]{1,64}")) {
            throw new ServiceHandler.ConnectorException("ComfyUI did not accept the workflow", false);
        }
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (true) {
            Object hist = http.getJson(http.uri(base, "/history/" + pid), Map.of(), 15_000);
            if (hist instanceof Map<?, ?> hm && hm.get(pid) instanceof Map<?, ?> entry) {
                Map<String, Object> img = firstImage(entry);
                if (img != null) {
                    String qs = "?filename=" + enc((String) img.get("filename")) + "&subfolder=" + enc((String) img.get("subfolder"))
                            + "&type=" + enc((String) img.get("type"));
                    byte[] bytes = http.getBytes(http.uri(base, "/view" + qs), Map.of(), 60_000, MediaStore.MAX_BYTES);
                    Map<String, Object> out = new LinkedHashMap<>(store.save(bytes));
                    out.put("promptId", pid);
                    return out;
                }
                if (entry.get("status") instanceof Map<?, ?> st && "error".equals(st.get("status_str"))) {
                    throw new ServiceHandler.ConnectorException("ComfyUI reported an error running the workflow", false);
                }
            }
            if (System.nanoTime() > deadline) {
                throw new ServiceHandler.ConnectorException("ComfyUI did not finish within " + seconds + " seconds", true);
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceHandler.ConnectorException("Interrupted", false);
            }
        }
    }

    private static Map<String, Object> firstImage(Map<?, ?> entry) throws ServiceHandler.ConnectorException {
        if (!(entry.get("outputs") instanceof Map<?, ?> outs)) {
            return null;
        }
        for (Object node : outs.values()) {
            if (node instanceof Map<?, ?> n && n.get("images") instanceof List<?> imgs) {
                for (Object o : imgs) {
                    if (o instanceof Map<?, ?> i && i.get("filename") instanceof String f && NAME.matcher(f).matches()
                            && !f.contains("..") && (i.get("subfolder") instanceof String s ? SUB.matcher(s).matches() && !s.contains("..") : true)
                            && i.get("type") instanceof String t && (t.equals("output") || t.equals("temp"))) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("filename", f);
                        r.put("subfolder", i.get("subfolder") instanceof String s ? s : "");
                        r.put("type", t);
                        return r;
                    }
                }
            }
        }
        return null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
