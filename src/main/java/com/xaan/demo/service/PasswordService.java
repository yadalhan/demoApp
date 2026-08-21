package com.xaan.demo.service;

import com.xaan.vault.crypto.PasswordHasher;
import com.xaan.vault.crypto.envelope.EnvelopeCryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class PasswordService {

    private static final Logger logger = LoggerFactory.getLogger(PasswordService.class);

    private final PasswordHasher passwordHasher;
    private final EnvelopeCryptoService boardCryptoService;
    private final EnvelopeCryptoService userPiiCryptoService;

    public PasswordService(
            @Qualifier("boardCryptoService") EnvelopeCryptoService boardCryptoService,
            @Qualifier("userPiiCryptoService") EnvelopeCryptoService userPiiCryptoService) {
        this.passwordHasher = new PasswordHasher();
        this.boardCryptoService = boardCryptoService;
        this.userPiiCryptoService = userPiiCryptoService;
        logger.info("PasswordService initialized with KEK-DEK envelope encryption (board, user-pii domains)");
    }

    /**
     * BCrypt 단방향 해시 - 사용자 비밀번호용 (vault-crypto의 PasswordHasher 위임)
     */
    public String hashUserPassword(String password) {
        return passwordHasher.hash(password);
    }

    /**
     * BCrypt 검증 - 사용자 비밀번호 검증용 (vault-crypto의 PasswordHasher 위임)
     */
    public boolean validateUserPassword(String rawPassword, String hashedPassword) {
        return passwordHasher.matches(rawPassword, hashedPassword);
    }

    /**
     * AES-GCM 봉투 암호화 (board 도메인 DEK) - 게시글 비밀번호용
     */
    public String encryptBoardPassword(String password) {
        return boardCryptoService.encrypt(password);
    }

    /**
     * AES-GCM 복호화 - 게시글 비밀번호 검증용
     */
    public String decryptBoardPassword(String encryptedPassword) {
        return boardCryptoService.decrypt(encryptedPassword);
    }

    /**
     * 게시글 비밀번호 검증 (constant-time comparison)
     */
    public boolean validateBoardPassword(String rawPassword, String encryptedPassword) {
        return boardCryptoService.validate(rawPassword, encryptedPassword);
    }

    /**
     * AES-GCM 봉투 암호화 (user-pii 도메인 DEK) - 주민등록번호 등 개인정보용
     */
    public String encryptUserPii(String plainText) {
        return userPiiCryptoService.encrypt(plainText);
    }

    /**
     * AES-GCM 복호화 - 개인정보 컬럼용
     */
    public String decryptUserPii(String encryptedText) {
        return userPiiCryptoService.decrypt(encryptedText);
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
