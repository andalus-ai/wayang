package tech.kayys.wayang.publish;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.kayys.wayang.runtime.json.Json;

class ReleaseOfflinePackageAssemblerTest {
    @TempDir Path temp;

    @Test void assemblesDeterministicVerifiedOfflinePackage() throws Exception {
        var lock = ReleaseArtifactResolver.verify(ReleaseArtifactLockfile.create(List.of("wayang.bpmn.control-flow"), "1.2.3", false).json());
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String manifest = signedManifest(lock, signer);
        Map<ReleaseArtifactResolver.Coordinate, VerifiedArtifactFetcher.CachedArtifact> files = new LinkedHashMap<>();
        for (var artifact : lock.artifacts()) {
            Path pom = temp.resolve(artifact.coordinate().artifactId() + ".pom");
            Path jar = temp.resolve(artifact.coordinate().artifactId() + ".jar");
            Files.writeString(pom, "pom:" + artifact.coordinate().artifactId());
            Files.writeString(jar, "jar:" + artifact.coordinate().artifactId());
            files.put(artifact.coordinate(), new VerifiedArtifactFetcher.CachedArtifact(artifact.coordinate(), pom, jar));
        }
        Path first = temp.resolve("first.zip");
        Path second = temp.resolve("second.zip");
        var a = ReleaseOfflinePackageAssembler.assemble(ReleaseArtifactLockfile.create(List.of("wayang.bpmn.control-flow"), "1.2.3", false).json(), manifest, signer.getPublic(), files, first);
        var b = ReleaseOfflinePackageAssembler.assemble(ReleaseArtifactLockfile.create(List.of("wayang.bpmn.control-flow"), "1.2.3", false).json(), manifest, signer.getPublic(), files, second);
        assertEquals(a.sha256(), b.sha256());
        assertEquals(lock.artifacts().size(), a.artifactCount());
        try (ZipFile zip = new ZipFile(first.toFile())) {
            assertNotNull(zip.getEntry("release/lock.json"));
            assertNotNull(zip.getEntry("release/artifact-digests.json"));
            assertNotNull(zip.getEntry("release/bom.cdx.json"));
            assertEquals(lock.artifacts().size() * 2L, zip.stream().filter(e -> e.getName().endsWith(".pom") || e.getName().endsWith(".jar")).count());
        }
    }

    @Test void rejectsIncompleteArtifactSet() throws Exception {
        var lock = ReleaseArtifactResolver.verify(ReleaseArtifactLockfile.create(List.of("wayang.bpmn.control-flow"), "1.2.3", false).json());
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String manifest = signedManifest(lock, signer);
        assertThrows(IllegalArgumentException.class, () -> ReleaseOfflinePackageAssembler.assemble(
                ReleaseArtifactLockfile.create(List.of("wayang.bpmn.control-flow"), "1.2.3", false).json(),
                manifest, signer.getPublic(), Map.of(), temp.resolve("incomplete.zip")));
    }

    private static String signedManifest(ReleaseArtifactResolver.VerifiedLock lock, KeyPair signer) throws Exception {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (var artifact : lock.artifacts()) {
            var c = artifact.coordinate();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("groupId", c.groupId()); item.put("artifactId", c.artifactId());
            item.put("version", c.version()); item.put("scope", c.scope());
            item.put("pomSha256", sha("pom:" + c.artifactId()));
            item.put("jarSha256", sha("jar:" + c.artifactId()));
            entries.add(item);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", "wayang.artifact-digests"); document.put("formatVersion", 1);
        document.put("lockSha256", lock.sha256()); document.put("artifacts", entries);
        Signature signature = Signature.getInstance("Ed25519"); signature.initSign(signer.getPrivate());
        signature.update(Json.write(canonical(document)).getBytes(StandardCharsets.UTF_8));
        document.put("signature", Base64.getEncoder().encodeToString(signature.sign()));
        return Json.write(document);
    }
    private static String sha(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nested) -> sorted.put(String.valueOf(key), canonical(nested))); return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(ReleaseOfflinePackageAssemblerTest::canonical).toList();
        return value;
    }
}