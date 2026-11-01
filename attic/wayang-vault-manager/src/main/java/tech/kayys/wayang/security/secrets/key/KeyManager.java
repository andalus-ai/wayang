package tech.kayys.wayang.security.secrets.key;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.util.*;

/**
 * Encryption key management service.
 * Handles master key rotation, key derivation, and encryption/decryption.
 */
@ApplicationScoped
public class KeyManager {

    private static final Logger LOG = Logger.getLogger(KeyManager.class);
    private static final String CIPHER_ALGORITHM = "AES";
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_SIZE = 256;

    @ConfigProperty(name = "secret.master-key")
    String masterKeyString;

    @ConfigProperty(name = "secret.key-rotation-enabled", defaultValue = "true")
    boolean keyRotationEnabled;

    @ConfigProperty(name = "secret.key-rotation-days", defaultValue = "90")
    int keyRotationDays;

    private SecretKey masterKey;

    public KeyManager() {
    }

    /**
     * Initialize the master key from configuration
     */
    public void initializeMasterKey() {
        try {
            byte[] decodedKey = Base64.getDecoder().decode(masterKeyString);
            masterKey = new SecretKeySpec(decodedKey, 0, decodedKey.length, KEY_ALGORITHM);
            LOG.info("Master key initialized successfully");
        } catch (IllegalArgumentException e) {
            throw new KeyManagementException("Invalid master key format", e);
        }
    }

    /**
     * Encrypt data using the master key
     */
    public String encrypt(String plaintext) {
        try {
            if (masterKey == null) {
                throw new KeyManagementException("Master key not initialized");
            }

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey);
            
            byte[] encryptedBytes = cipher.doFinal(plaintext.getBytes());
            return Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            throw new KeyManagementException("Encryption failed", e);
        }
    }

    /**
     * Decrypt data using the master key
     */
    public String decrypt(String ciphertext) {
        try {
            if (masterKey == null) {
                throw new KeyManagementException("Master key not initialized");
            }

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, masterKey);
            
            byte[] decodedBytes = Base64.getDecoder().decode(ciphertext);
            byte[] decryptedBytes = cipher.doFinal(decodedBytes);
            return new String(decryptedBytes);
        } catch (Exception e) {
            throw new KeyManagementException("Decryption failed", e);
        }
    }

    /**
     * Generate a new encryption key
     */
    public SecretKey generateKey() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM);
            keyGenerator.init(KEY_SIZE, new SecureRandom());
            return keyGenerator.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new KeyManagementException("Failed to generate key", e);
        }
    }

    /**
     * Derive a key from a password
     */
    public SecretKey deriveKey(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((password + salt).getBytes());
            byte[] keyBytes = digest.digest();
            return new SecretKeySpec(keyBytes, 0, keyBytes.length, KEY_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new KeyManagementException("Failed to derive key", e);
        }
    }

    /**
     * Get the current key version
     */
    public int getCurrentKeyVersion() {
        return 1;
    }

    /**
     * Check if key rotation is needed
     */
    public boolean isKeyRotationNeeded() {
        if (!keyRotationEnabled) {
            return false;
        }
        // Implementation would check last rotation timestamp
        return false;
    }

    /**
     * Rotate the master key
     */
    public void rotateMasterKey(String newMasterKey) {
        try {
            byte[] decodedKey = Base64.getDecoder().decode(newMasterKey);
            SecretKey newKey = new SecretKeySpec(decodedKey, 0, decodedKey.length, KEY_ALGORITHM);
            
            // In production, would re-encrypt all data with new key
            masterKey = newKey;
            LOG.info("Master key rotated successfully");
        } catch (Exception e) {
            throw new KeyManagementException("Failed to rotate master key", e);
        }
    }

    /**
     * Exception for key management errors
     */
    public static class KeyManagementException extends RuntimeException {
        public KeyManagementException(String message) {
            super(message);
        }

        public KeyManagementException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
