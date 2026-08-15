package com.xaan.demo.service;

import com.xaan.vault.crypto.VaultCryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.vault.core.VaultOperations;

@Service
public class PasswordService {

    private static final Logger logger = LoggerFactory.getLogger(PasswordService.class);

    private final BCryptPasswordEncoder bCryptPasswordEncoder;
    private final VaultCryptoService vaultCryptoService;

    public PasswordService(
            VaultOperations vaultOperations,
            @Value("${vault.secret.path:ebiz_service/data/ebiz_db/data-enc-key}") String vaultSecretPath) {
        this.bCryptPasswordEncoder = new BCryptPasswordEncoder();
        this.vaultCryptoService = new VaultCryptoService(vaultOperations, vaultSecretPath);
        logger.info("PasswordService initialized with vault-crypto (path: {})", vaultSecretPath);
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
     * AES-GCM 양방향 암호화 - 게시글 비밀번호용 (vault-crypto 사용)
     */
    public String encryptBoardPassword(String password) {
        return vaultCryptoService.encrypt(password);
    }

    /**
     * AES-GCM 복호화 - 게시글 비밀번호 검증용 (vault-crypto 사용)
     */
    public String decryptBoardPassword(String encryptedPassword) {
        return vaultCryptoService.decrypt(encryptedPassword);
    }

    /**
     * 게시글 비밀번호 검증 (constant-time comparison via vault-crypto)
     */
    public boolean validateBoardPassword(String rawPassword, String encryptedPassword) {
        return vaultCryptoService.validate(rawPassword, encryptedPassword);
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
