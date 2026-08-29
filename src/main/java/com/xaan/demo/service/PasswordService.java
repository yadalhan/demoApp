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
    private final EnvelopeCryptoService userPiiCryptoService;
    private final BlindIndexService phoneBlindIndexService;
    private final BlindIndexService rrnBlindIndexService;

    public PasswordService(
            @Qualifier("boardCryptoService") EnvelopeCryptoService boardCryptoService,
            @Qualifier("userPiiCryptoService") EnvelopeCryptoService userPiiCryptoService,
            @Qualifier("phoneBlindIndexService") BlindIndexService phoneBlindIndexService,
            @Qualifier("rrnBlindIndexService") BlindIndexService rrnBlindIndexService) {
        this.passwordHasher = new PasswordHasher();
        this.boardCryptoService = boardCryptoService;
        this.userPiiCryptoService = userPiiCryptoService;
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
     * 목록/검색 화면 표시 전용 복호화 - 실패해도 예외를 던지지 않고 대체 문자열을 반환한다.
     * 여러 행을 한 번에 보여주는 화면(UserService.search() 등)에서, 한 행의 ciphertext가
     * 현재 로드된 DEK와 맞지 않아도(예: 과거 키 재발급으로 무효화된 값) 그 행만 표시를 대체하고
     * 나머지 행은 정상적으로 보여주기 위한 것 - 단일 레코드 조회(회원가입 직후 확인 등)처럼
     * 실패를 곧바로 알아야 하는 곳에는 쓰지 않는다.
     */
    public String decryptUserPiiForDisplay(String encryptedText) {
        if (encryptedText == null || encryptedText.isEmpty()) {
            return encryptedText;
        }
        try {
            return userPiiCryptoService.decrypt(encryptedText);
        } catch (RuntimeException e) {
            logger.warn("Failed to decrypt user-pii value for display: {}", e.getMessage());
            return "(복호화 실패)";
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
