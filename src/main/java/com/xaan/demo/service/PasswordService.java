package com.xaan.demo.service;

import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Service
public class PasswordService {
    
    private static final String AES_ALGORITHM = "AES";
    private static final String ENV_ENCRYPTION_KEY = "ENCRYPTION_SECRET_KEY";
    private static final byte[] SECRET_KEY;
    
    static {
        String keyFromEnv = System.getenv(ENV_ENCRYPTION_KEY);
        if (keyFromEnv != null && !keyFromEnv.isEmpty()) {
            SECRET_KEY = keyFromEnv.getBytes();
        } else {
            // Fallback for development only - CHANGE THIS IN PRODUCTION
            SECRET_KEY = "CHANGE_ME_PRODUCTION_KEY".getBytes();
        }
    } // Configure via ENCRYPTION_SECRET_KEY environment variable
    
    public String encryptPassword(String password) {
        try {
            SecretKeySpec secretKey = new SecretKeySpec(SECRET_KEY, AES_ALGORITHM);
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
            SecretKeySpec secretKey = new SecretKeySpec(SECRET_KEY, AES_ALGORITHM);
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