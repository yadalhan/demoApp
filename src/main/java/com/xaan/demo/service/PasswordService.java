package com.xaan.demo.service;

import org.springframework.stereotype.Service;
import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.support.VaultResponse;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Map;

@Service
public class PasswordService {

    private static final String AES_ALGORITHM = "AES";
    private final VaultOperations vaultOperations;
    private byte[] encryptionKey;

    public PasswordService(VaultOperations vaultOperations) {
        this.vaultOperations = vaultOperations;
        loadEncryptionKey();
    }

    private void loadEncryptionKey() {
        try {
            // kv-v2 path: ebiz_service/data/ebiz_db/data-enc-key
            VaultResponse response = vaultOperations.read("ebiz_service/data/ebiz_db/data-enc-key");
            if (response == null) {
                throw new RuntimeException("Vault read returned null response");
            }
            Map<String, Object> outerData = response.getData();
            if (outerData == null) {
                throw new RuntimeException("Vault response has null data");
            }
            // kv-v2 response wraps data in a "data" field
            Map<String, Object> secretData = (Map<String, Object>) outerData.get("data");
            if (secretData == null) {
                throw new RuntimeException("No 'data' field in Vault response. Keys: " + outerData.keySet());
            }
            String fernetKeyBase64 = (String) secretData.get("fernet-key");
            if (fernetKeyBase64 == null || fernetKeyBase64.isEmpty()) {
                throw new RuntimeException("fernet-key not found in Vault secret. Keys: " + secretData.keySet());
            }
            // Decode URL-safe Base64 fernet key (32 bytes: 16 for AES + 16 for HMAC-SHA256)
            this.encryptionKey = Base64.getUrlDecoder().decode(fernetKeyBase64);
            if (this.encryptionKey == null || this.encryptionKey.length == 0) {
                throw new RuntimeException("Decoded encryption key is empty");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load encryption key from Vault: " + e.getMessage(), e);
        }
    }
    public String encryptPassword(String password) {
        try {
            SecretKeySpec secretKey = new SecretKeySpec(encryptionKey, AES_ALGORITHM);
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] encryptedBytes = cipher.doFinal(password.getBytes());
            return Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            throw new RuntimeException("Error encrypting password", e);
        }
    }

    public String decryptPassword(String encryptedPassword) {
        try {
            SecretKeySpec secretKey = new SecretKeySpec(encryptionKey, AES_ALGORITHM);
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            byte[] encryptedBytes = Base64.getDecoder().decode(encryptedPassword);
            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
            return new String(decryptedBytes);
        } catch (Exception e) {
            throw new RuntimeException("Error decrypting password", e);
        }
    }

    public boolean validatePassword(String inputPassword, String storedEncryptedPassword) {
        String decryptedPassword = decryptPassword(storedEncryptedPassword);
        return inputPassword.equals(decryptedPassword);
    }
}
