package tech.kayys.wayang.api;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The master keys of the secret store: key id to 32 bytes, plus which one seals new secrets. Where it comes from is the
 * operator's choice (config, a mounted file, HashiCorp Vault); this is the common shape. Text form, one entry per line:
 * <pre>
 * # comment
 * active=k2
 * k1:&lt;base64 of 32 bytes&gt;
 * k2:&lt;base64 of 32 bytes&gt;
 * </pre>
 */
public record Keyring(Map<String, byte[]> keys, String active) {

    public Keyring {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        keys.forEach((k, v) -> copy.put(k, v.clone()));
        keys = java.util.Collections.unmodifiableMap(copy);
        if (!keys.containsKey(active)) {
            throw new IllegalArgumentException("The active key '" + active + "' is not in the keyring");
        }
    }

    public SecretCipher cipher() {
        return new SecretCipher(keys, active);
    }

    /** @throws IllegalArgumentException with a message that names the line but never shows key material */
    public static Keyring parse(String text) {
        if (text == null || text.length() > 64 * 1024) {
            throw new IllegalArgumentException("Keyring is missing or too large");
        }
        Map<String, byte[]> keys = new LinkedHashMap<>();
        String active = null;
        int n = 0;
        for (String raw : text.split("\\R")) {
            n++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("active=")) {
                if (active != null) {
                    throw new IllegalArgumentException("Keyring line " + n + ": 'active' is given twice");
                }
                active = line.substring(7).strip();
                continue;
            }
            int i = line.indexOf(':');
            if (i <= 0) {
                throw new IllegalArgumentException("Keyring line " + n + ": expected id:base64");
            }
            String id = line.substring(0, i).strip();
            if (!id.matches("[A-Za-z0-9_.-]{1,32}")) {
                throw new IllegalArgumentException("Keyring line " + n + ": bad key id");
            }
            if (keys.containsKey(id)) {
                throw new IllegalArgumentException("Keyring line " + n + ": key '" + id + "' is given twice");
            }
            byte[] k;
            try {
                k = Base64.getDecoder().decode(line.substring(i + 1).strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Keyring line " + n + ": key '" + id + "' is not base64");
            }
            if (k.length != 32) {
                throw new IllegalArgumentException("Keyring line " + n + ": key '" + id + "' must be 32 bytes, not " + k.length);
            }
            keys.put(id, k);
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("Keyring has no keys");
        }
        if (active == null) {
            if (keys.size() > 1) {
                throw new IllegalArgumentException("Keyring has several keys: say which one is 'active=<id>'");
            }
            active = keys.keySet().iterator().next();
        }
        return new Keyring(keys, active);
    }

    /** Reads a mounted file. Refuses one that other users can read, unless told the platform's mount cannot do better. */
    public static Keyring fromFile(java.nio.file.Path path, boolean allowLoosePermissions) {
        try {
            if (!allowLoosePermissions) {
                try {
                    var perms = java.nio.file.Files.getPosixFilePermissions(path);
                    if (perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ)
                            || perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)
                            || perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)) {
                        throw new IllegalStateException("Keyring file " + path + " is readable by other users or writable by its group: chmod 0400 it"
                                + " (or set wayang.secrets.key-file-allow-loose-permissions=true if the platform mount cannot)");
                    }
                } catch (UnsupportedOperationException e) {
                    // not a POSIX file system: nothing to check
                }
            }
            return parse(java.nio.file.Files.readString(path));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read keyring file " + path + ": " + e.getClass().getSimpleName());
        }
    }
}
