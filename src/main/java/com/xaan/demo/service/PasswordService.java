package com.xaan.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.vault.core.VaultOperations;

import jakarta.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Map;

@Service
public class PasswordService {

    private static final Logger logger = LoggerFactory.getLogger(PasswordService.class);

    private final BCryptPasswordEncoder bCryptPasswordEncoder;
    private final VaultOperations vaultOperations;
    private final String vaultSecretPath;
    private byte[] encryptionKey;

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16;

    public PasswordService(
            VaultOperations vaultOperations,
            @Value("${vault.secret.path:ebiz_service/data/ebiz_db/data-enc-key}") String vaultSecretPath) {
        this.bCryptPasswordEncoder = new BCryptPasswordEncoder();
        this.vaultOperations = vaultOperations;
        this.vaultSecretPath = vaultSecretPath;
    }

    @PostConstruct
    public void init() {
        try {
            logger.info("Attempting to load encryption key from Vault at path: {}", vaultSecretPath);
            var response = vaultOperations.read(vaultSecretPath);
            
            if (response == null) {
                logger.error("Vault response is null for path: {}", vaultSecretPath);
                throw new RuntimeException("Vault response is null");
            }
            
            logger.info("Vault response data: {}", response.getData());
            
            // KV v2 returns data in nested format: data.data
            Object dataObj = response.getData();
            String fernetKeyBase64 = null;
            
            if (dataObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dataMap = (Map<String, Object>) dataObj;
                // Check both direct and nested format
                if (dataMap.containsKey("fernet-key")) {
                    fernetKeyBase64 = (String) dataMap.get("fernet-key");
                } else if (dataMap.containsKey("data") && dataMap.get("data") instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nestedData = (Map<String, Object>) dataMap.get("data");
                    fernetKeyBase64 = (String) nestedData.get("fernet-key");
                }
            }
            
            if (fernetKeyBase64 == null) {
                logger.error("Could not find fernet-key in Vault response. Data: {}", dataObj);
                throw new RuntimeException("fernet-key not found in Vault response");
            }
            
            this.encryptionKey = Base64.getUrlDecoder().decode(fernetKeyBase64);
            logger.info("Successfully loaded encryption key from Vault. Key length: {} bytes", encryptionKey.length);
            
        } catch (Exception e) {
            logger.error("Failed to load encryption key from Vault: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize PasswordService - cannot proceed without encryption key", e);
        }
    }

    /**
     * BCrypt 단방향 해시 - 사용자 비밀번호용
     */
    public String hashUserPassword(String password) {
        return bCryptPasswordEncoder.encode(password);
    }

    /**
     * BCrypt 검증 - 사용자 비밀번호 검증용
     */
    public boolean validateUserPassword(String rawPassword, String hashedPassword) {
        return bCryptPasswordEncoder.matches(rawPassword, hashedPassword);
    }

    /**
     * AES-GCM 양방향 암호화 - 게시글 비밀번호용
     */
    public String encryptBoardPassword(String password) {
        if (encryptionKey == null) {
            throw new IllegalStateException("Encryption key not loaded from Vault. Cannot encrypt board password.");
        }
        try {
            // Generate random IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            java.security.SecureRandom random = new java.security.SecureRandom();
            random.nextBytes(iv);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(encryptionKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            // Encrypt - GCM appends the auth tag to the ciphertext
            byte[] ciphertextWithTag = cipher.doFinal(password.getBytes("UTF-8"));
            
            // Extract tag from the end (last 16 bytes)
            byte[] ciphertext = new byte[ciphertextWithTag.length - GCM_TAG_LENGTH];
            byte[] tag = new byte[GCM_TAG_LENGTH];
            System.arraycopy(ciphertextWithTag, 0, ciphertext, 0, ciphertext.length);
            System.arraycopy(ciphertextWithTag, ciphertext.length, tag, 0, GCM_TAG_LENGTH);

            // Combine: IV + ciphertext + tag
            byte[] combined = new byte[GCM_IV_LENGTH + ciphertext.length + GCM_TAG_LENGTH];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, GCM_IV_LENGTH, ciphertext.length);
            System.arraycopy(tag, 0, combined, GCM_IV_LENGTH + ciphertext.length, GCM_TAG_LENGTH);

            return Base64.getUrlEncoder().encodeToString(combined);
        } catch (Exception e) {
            logger.error("Failed to encrypt board password: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to encrypt board password", e);
        }
    }

    /**
     * AES-GCM 복호화 - 게시글 비밀번호 검증용
     */
    public String decryptBoardPassword(String encryptedPassword) {
        if (encryptionKey == null) {
            throw new IllegalStateException("Encryption key not loaded from Vault. Cannot decrypt board password.");
        }
        try {
            byte[] combined = Base64.getUrlDecoder().decode(encryptedPassword);

            if (combined.length < GCM_IV_LENGTH + GCM_TAG_LENGTH) {
                throw new IllegalArgumentException("Invalid encrypted password format");
            }

            // Extract IV, ciphertext, and tag
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);

            byte[] tag = new byte[GCM_TAG_LENGTH];
            System.arraycopy(combined, combined.length - GCM_TAG_LENGTH, tag, 0, GCM_TAG_LENGTH);

            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH - GCM_TAG_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            // Combine ciphertext and tag for decryption
            byte[] ciphertextWithTag = new byte[ciphertext.length + GCM_TAG_LENGTH];
            System.arraycopy(ciphertext, 0, ciphertextWithTag, 0, ciphertext.length);
            System.arraycopy(tag, 0, ciphertextWithTag, ciphertext.length, GCM_TAG_LENGTH);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(encryptionKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
            cipher.updateAAD(tag);

            byte[] decrypted = cipher.doFinal(ciphertextWithTag);
            return new String(decrypted, "UTF-8");
        } catch (Exception e) {
            logger.error("Failed to decrypt board password: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to decrypt board password", e);
        }
    }

    /**
     * 게시글 비밀번호 검증
     */
    public boolean validateBoardPassword(String rawPassword, String encryptedPassword) {
        try {
            String decrypted = decryptBoardPassword(encryptedPassword);
            return decrypted.equals(rawPassword);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 레거시 AES-GCM 암호화된 비밀번호인지 확인 (BCrypt 해시와의 호환성)
     */
    public boolean isLegacyPassword(String storedPassword) {
        if (storedPassword == null) return true;
        return !storedPassword.startsWith("$2a$") &&
               !storedPassword.startsWith("$2b$") &&
               !storedPassword.startsWith("$2y$");
    }
}
