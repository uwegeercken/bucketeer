package io.github.uwegeercken.bucketeer.infrastructure.config.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * Resolves the encryption key used for credential storage.
 *
 * Resolution order:
 *   1. Environment variable BUCKETEER_ENCRYPTION_KEY  (explicit override)
 *   2. ~/.bucketeer/encryption.key                    (persisted generated key)
 *   3. Generate new random key, save to file          (first run)
 */
@Component
public class EncryptionKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(EncryptionKeyProvider.class);

    private static final String ENV_VAR   = "BUCKETEER_ENCRYPTION_KEY";
    private static final String KEY_FILE  = ".bucketeer/encryption.key";
    private static final int    KEY_BYTES = 32; // 256 bit

    private final String key;

    public EncryptionKeyProvider() {
        this.key = resolveKey();
    }

    public String getKey() {
        return key;
    }

    private String resolveKey() {
        // 1. environment variable
        String envKey = System.getenv(ENV_VAR);
        if (envKey != null && !envKey.isBlank()) {
            log.info("Using encryption key from environment variable {}", ENV_VAR);
            return envKey;
        }

        // 2. key file
        Path keyPath = Path.of(System.getProperty("user.home"), KEY_FILE);
        if (Files.exists(keyPath)) {
            try {
                String fileKey = Files.readString(keyPath).trim();
                if (!fileKey.isBlank()) {
                    log.info("Using encryption key from {}", keyPath);
                    return fileKey;
                }
            } catch (IOException e) {
                log.error("Failed to read encryption key from {}: {}", keyPath, e.getMessage());
            }
        }

        // 3. generate and persist new key
        return generateAndSave(keyPath);
    }

    private String generateAndSave(Path keyPath) {
        byte[] randomBytes = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(randomBytes);
        String newKey = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        try {
            Files.createDirectories(keyPath.getParent());
            Files.writeString(keyPath, newKey);

            // restrict file permissions to owner-only on POSIX systems
            try {
                Files.setPosixFilePermissions(keyPath, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE
                ));
            } catch (UnsupportedOperationException e) {
                // Windows – POSIX permissions not supported, skip silently
            }

            log.info("Generated new encryption key and saved to {}", keyPath);
            log.warn("Keep {} safe – losing it means existing server credentials cannot be decrypted.", keyPath);
        } catch (IOException e) {
            log.error("Failed to save encryption key to {}: {}", keyPath, e.getMessage());
            log.warn("Encryption key will not be persisted. Server credentials may not survive a restart.");
        }

        return newKey;
    }
}