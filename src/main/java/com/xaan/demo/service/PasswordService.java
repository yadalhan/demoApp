package com.xaan.demo.service;

import com.xaan.vault.crypto.PasswordHasher;
import com.xaan.vault.crypto.blindindex.BlindIndexService;
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
    private final BlindIndexService phoneBlindIndexService;
    private final BlindIndexService rrnBlindIndexService;

    public PasswordService(
            @Qualifier("boardCryptoService") EnvelopeCryptoService boardCryptoService,
            @Qualifier("phoneBlindIndexService") BlindIndexService phoneBlindIndexService,
            @Qualifier("rrnBlindIndexService") BlindIndexService rrnBlindIndexService) {
        this.passwordHasher = new PasswordHasher();
        this.boardCryptoService = boardCryptoService;
        this.phoneBlindIndexService = phoneBlindIndexService;
        this.rrnBlindIndexService = rrnBlindIndexService;
        logger.info("PasswordService initialized with KEK-DEK envelope encryption (board domain) and blind index support (phone, rrn)");
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
     * 게시글 비밀번호 검증 (constant-time comparison). 암/복호화 자체는 더 이상 이 클래스가 직접 호출하지
     * 않는다 - BoardMapper의 BoardPasswordTypeHandler가 저장/조회 시점에 처리한다(BoardMapper 참고).
     */
    public boolean validateBoardPassword(String rawPassword, String encryptedPassword) {
        return boardCryptoService.validate(rawPassword, encryptedPassword);
    }

    /**
     * 주민등록번호 검색용 blind index(HMAC) 계산. RRN 자체의 암/복호화는 이 클래스가 직접 호출하지 않는다 -
     * UserMapper의 UserPiiTypeHandler가 저장/조회 시점에 처리한다.
     */
    public String computeRrnBlindIndex(String residentRegistrationNumber) {
        return rrnBlindIndexService.compute(residentRegistrationNumber);
    }

    /**
     * 전화번호 검색용 blind index(HMAC) 계산. 정규화(숫자만 남기기)는 호출 전 UserService가 이미 끝낸 값을
     * 넘겨야 한다 - 저장 시점과 검색 시점에 다르게 정규화하면 조용히 매칭이 실패한다.
     */
    public String computePhoneBlindIndex(String phone) {
        return phoneBlindIndexService.compute(phone);
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
