package io.github.uwegeercken.bucketeer.infrastructure.config.server;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * Encrypts and decrypts credential strings using AES-256-GCM.
 *
 * The encryption key is provided by {@link EncryptionKeyProvider} and derived
 * per-value using PBKDF2 with a random salt.
 * Each encrypted value contains its own random salt and IV:
 *   Base64(salt[16] + iv[12] + ciphertext)
 */
@Component
public class CredentialEncryptor {

    private static final String ALGORITHM      = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM  = "PBKDF2WithHmacSHA256";
    private static final int    GCM_TAG_LENGTH = 128;
    private static final int    IV_LENGTH      = 12;
    private static final int    SALT_LENGTH    = 16;
    private static final int    KEY_LENGTH     = 256;
    private static final int    ITERATIONS     = 65536;

    private final String passphrase;

    public CredentialEncryptor(EncryptionKeyProvider keyProvider) {
        this.passphrase = keyProvider.getKey();
    }

    public String encrypt(String plaintext) {
        try {
            byte[] salt = new byte[SALT_LENGTH];
            byte[] iv   = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(salt);
            new SecureRandom().nextBytes(iv);

            SecretKey key = deriveKey(salt);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes());

            byte[] combined = new byte[SALT_LENGTH + IV_LENGTH + ciphertext.length];
            System.arraycopy(salt,       0, combined, 0,                       SALT_LENGTH);
            System.arraycopy(iv,         0, combined, SALT_LENGTH,             IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, SALT_LENGTH + IV_LENGTH, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new EncryptionException("Failed to encrypt credential", e);
        }
    }

    public String decrypt(String encrypted) {
        try {
            byte[] combined   = Base64.getDecoder().decode(encrypted);
            byte[] salt       = new byte[SALT_LENGTH];
            byte[] iv         = new byte[IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - SALT_LENGTH - IV_LENGTH];

            System.arraycopy(combined, 0,                       salt,       0, SALT_LENGTH);
            System.arraycopy(combined, SALT_LENGTH,             iv,         0, IV_LENGTH);
            System.arraycopy(combined, SALT_LENGTH + IV_LENGTH, ciphertext, 0, ciphertext.length);

            SecretKey key = deriveKey(salt);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(ciphertext));
        } catch (Exception e) {
            throw new EncryptionException("Failed to decrypt credential", e);
        }
    }

    private SecretKey deriveKey(byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(KEY_ALGORITHM);
        KeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    public static class EncryptionException extends RuntimeException {
        public EncryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}