package tech.kayys.wayang.api.definition;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.eip.EipException;
import tech.kayys.wayang.eip.EipLoader;
import tech.kayys.wayang.eip.Services;
import tech.kayys.wayang.runtime.json.Json;

/** The Studio's step catalog (studio/assets/pipeline_steps.json) must only offer steps the server accepts, and offer all of them. */
class StudioPipelineCatalogTest {

    private static Path root() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("studio/assets"))) {
            dir = dir.getParent();
        }
        assertNotNull(dir, "studio/assets not found above " + Path.of("").toAbsolutePath());
        return dir;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> catalog() throws Exception {
        return (List<Map<String, Object>>) (List<?>) Json.read(Files.readString(root().resolve("studio/assets/pipeline_steps.json")));
    }

    @SuppressWarnings("unchecked")
    @Test
    void everyTemplateIsAcceptedByTheRealLoader() throws Exception {
        List<Map<String, Object>> kinds = catalog();
        assertFalse(kinds.isEmpty());
        for (Map<String, Object> k : kinds) {
            Map<String, Object> tpl = (Map<String, Object>) k.get("template");
            assertEquals(k.get("type"), tpl.get("type"), "template type matches its entry");
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("steps", List.of(tpl));
            try {
                EipLoader.build(doc, Services.scratch((n, c, in) -> Map.of()));
            } catch (EipException e) {
                fail("template '" + k.get("type") + "' is rejected: " + e.getMessage());
            }
            for (String f : List.of("label", "group", "icon", "description")) {
                assertTrue(k.get(f) instanceof String s && !s.isBlank(), k.get("type") + " needs " + f);
            }
        }
    }

    @Test
    void theCatalogOffersExactlyTheStepsTheLoaderKnows() throws Exception {
        String src = Files.readString(root().resolve("wayang-integration/src/main/java/tech/kayys/wayang/eip/EipLoader.java"));
        String stepMethod = src.substring(src.indexOf("private static Step step("));
        Set<String> known = new TreeSet<>();
        Matcher m = Pattern.compile("case \"([A-Za-z]+)\":?").matcher(stepMethod.substring(0, stepMethod.indexOf("Unknown EIP step type")));
        while (m.find()) {
            known.add(m.group(1));
        }
        assertTrue(known.size() >= 30, "found the loader's step types: " + known);
        List<String> offered = new ArrayList<>();
        for (Map<String, Object> k : catalog()) {
            offered.add((String) k.get("type"));
        }
        assertEquals(offered.size(), new TreeSet<>(offered).size(), "no duplicates");
        assertEquals(known, new TreeSet<>(offered), "a step type is missing from the Studio catalog, or the catalog offers one the server lacks");
    }

    @Test
    void everyIconNameIsKnownToTheDartWidget() throws Exception {
        String dart = Files.readString(root().resolve("studio/lib/features/mapper/pipeline_builder.dart"));
        for (Map<String, Object> k : catalog()) {
            assertTrue(dart.contains("'" + k.get("icon") + "': Icons." + k.get("icon") + ","), "icon " + k.get("icon") + " is not mapped in pipeline_builder.dart");
        }
    }

    @Test
    void anUnknownTypeIsStillRejected() {
        assertThrows(EipException.class, () -> EipLoader.build(Map.of("steps", List.of(Map.of("type", "nope"))), Services.scratch(null)));
    }
}
