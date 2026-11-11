package tech.kayys.wayang.publish;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tech.kayys.wayang.runtime.json.Json;

class ReleaseOfflinePackageVerifierTest {
    @TempDir Path temp;

    @Test
    void verifiesPackageAndSignsInstallationReceipt() throws Exception {
        Fixture fixture = fixture();
        var verified = ReleaseOfflinePackageVerifier.verify(fixture.packageFile(), fixture.releaseSigner().getPublic());
        assertEquals(fixture.lock().artifacts().size(), verified.artifactCount());
        assertEquals(fixture.lock().sha256(), verified.lockSha256());
        assertEquals(fixture.lock().artifacts().size() * 2, verified.artifactDigests().size());

        KeyPair installationSigner = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var receipt = ReleaseInstallationReceipt.create(verified, installationSigner.getPrivate(), installationSigner.getPublic());
        var checked = ReleaseInstallationReceipt.verify(receipt.json(), installationSigner.getPublic());
        assertEquals(verified.packageSha256(), checked.packageSha256());
        assertEquals(verified.lockSha256(), checked.lockSha256());
        assertEquals(verified.artifactCount(), checked.artifactCount());
        assertThrows(IllegalArgumentException.class,
                () -> ReleaseInstallationReceipt.verify(receipt.json(), fixture.releaseSigner().getPublic()));
    }

    @Test
    void rejectsUnexpectedTraversalEntry() throws Exception {
        Fixture fixture = fixture();
        Path malicious = temp.resolve("traversal.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(malicious))) {
            ZipEntry entry = new ZipEntry("../escape.txt");
            zip.putNextEntry(entry);
            zip.write("escape".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThrows(IOException.class,
                () -> ReleaseOfflinePackageVerifier.verify(malicious, fixture.releaseSigner().getPublic()));
    }

    @Test
    void rejectsModifiedArtifactBytes() throws Exception {
        Fixture fixture = fixture();
        Path tampered = temp.resolve("tampered.zip");
        try (java.util.zip.ZipFile source = new java.util.zip.ZipFile(fixture.packageFile().toFile());
                ZipOutputStream target = new ZipOutputStream(Files.newOutputStream(tampered))) {
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                var original = entries.nextElement();
                target.putNextEntry(new ZipEntry(original.getName()));
                try (var input = source.getInputStream(original)) {
                    byte[] bytes = input.readAllBytes();
                    if (original.getName().endsWith(".jar")) bytes = "tampered".getBytes(StandardCharsets.UTF_8);
                    target.write(bytes);
                }
                target.closeEntry();
            }
        }
        assertThrows(IOException.class,
                () -> ReleaseOfflinePackageVerifier.verify(tampered, fixture.releaseSigner().getPublic()));
    }

    private Fixture fixture() throws Exception {
        var lock = ReleaseArtifactResolver.verify(
                ReleaseArtifactLockfile.create(List.of("wayang.bpmn.control-flow"), "1.2.3", false).json());
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Map<ReleaseArtifactResolver.Coordinate, VerifiedArtifactFetcher.CachedArtifact> files = new LinkedHashMap<>();
        for (var artifact : lock.artifacts()) {
            Path pom = temp.resolve(artifact.coordinate().artifactId() + ".pom");
            Path jar = temp.resolve(artifact.coordinate().artifactId() + ".jar");
            Files.writeString(pom, "pom:" + artifact.coordinate().artifactId());
            Files.writeString(jar, "jar:" + artifact.coordinate().artifactId());
            files.put(artifact.coordinate(), new VerifiedArtifactFetcher.CachedArtifact(artifact.coordinate(), pom, jar));
        }
        String lockJson = ReleaseArtifactLockfile.create(List.of("wayang.bpmn.control-flow"), "1.2.3", false).json();
        String manifest = signedManifest(lock, signer);
        Path packageFile = temp.resolve("release.zip");
        ReleaseOfflinePackageAssembler.assemble(lockJson, manifest, signer.getPublic(), files, packageFile);
        return new Fixture(lock, signer, packageFile);
    }

    private static String signedManifest(ReleaseArtifactResolver.VerifiedLock lock, KeyPair signer) throws Exception {
        List<Map<String, Object>> artifacts = new ArrayList<>();
        for (var artifact : lock.artifacts()) {
            var coordinate = artifact.coordinate();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("groupId", coordinate.groupId());
            item.put("artifactId", coordinate.artifactId());
            item.put("version", coordinate.version());
            item.put("scope", coordinate.scope());
            item.put("pomSha256", sha("pom:" + coordinate.artifactId()));
            item.put("jarSha256", sha("jar:" + coordinate.artifactId()));
            artifacts.add(item);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", "wayang.artifact-digests");
        document.put("formatVersion", 1);
        document.put("lockSha256", lock.sha256());
        document.put("artifacts", artifacts);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(signer.getPrivate());
        signature.update(Json.write(canonical(document)).getBytes(StandardCharsets.UTF_8));
        document.put("signature", Base64.getEncoder().encodeToString(signature.sign()));
        return Json.write(document);
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nested) -> sorted.put(String.valueOf(key), canonical(nested)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(ReleaseOfflinePackageVerifierTest::canonical).toList();
        return value;
    }

    private static String sha(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private record Fixture(ReleaseArtifactResolver.VerifiedLock lock, KeyPair releaseSigner, Path packageFile) {}
}
