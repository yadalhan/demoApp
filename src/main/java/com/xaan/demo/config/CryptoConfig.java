package com.xaan.demo.config;

import com.xaan.vault.crypto.blindindex.BlindIndexKeyProvider;
import com.xaan.vault.crypto.blindindex.BlindIndexService;
import com.xaan.vault.crypto.blindindex.VaultBlindIndexKeyProvider;
import com.xaan.vault.crypto.envelope.DekProvider;
import com.xaan.vault.crypto.envelope.DekRotationSupport;
import com.xaan.vault.crypto.envelope.EnvelopeCryptoService;
import com.xaan.vault.crypto.envelope.KeyWrapper;
import com.xaan.vault.crypto.envelope.TransitKeyWrapper;
import com.xaan.vault.crypto.envelope.TransitKekRotationSupport;
import com.xaan.vault.crypto.envelope.VaultDekProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.vault.core.VaultOperations;

/**
 * KEK-DEK envelope encryption wiring.
 *
 * <p>2.1.0부터 KEK는 OpenBao Transit 엔진에 있다(exportable=false). 앱은 wrapped DEK를
 * {@code transit/decrypt}로 풀어 DEK만 메모리에 캐시하며, KEK 평문은 앱/백업에 남지 않는다.
 * 각 도메인 DEK는 도메인별로 분리되어 있고, 암호문 헤더의 버전으로 회전 후에도 계속 복호화된다.
 * 절차는 KEY_ROTATION_RUNBOOK.md 참고.
 */
@Configuration
public class CryptoConfig {

    public static final byte BOARD_DOMAIN_CODE = 1;
    public static final byte USER_PII_DOMAIN_CODE = 2;

    /** KEK는 Transit 안에 있고 여기로 내려오지 않는다. wrapped DEK만 transit/decrypt로 푼다. */
    @Bean
    public KeyWrapper keyWrapper(
            VaultOperations vaultOperations,
            @Value("${vault.transit.mount:transit}") String transitMount,
            @Value("${vault.transit.key:kek}") String transitKey) {
        return new TransitKeyWrapper(vaultOperations, transitMount, transitKey);
    }

    @Bean
    public DekProvider dekProvider(
            VaultOperations vaultOperations,
            @Value("${vault.dek.base-path:ebiz_service/data/ebiz_db/dek}") String dekBasePath) {
        return new VaultDekProvider(vaultOperations, dekBasePath);
    }

    @Bean
    public EnvelopeCryptoService boardCryptoService(KeyWrapper keyWrapper, DekProvider dekProvider) {
        return EnvelopeCryptoService.forDomain(BOARD_DOMAIN_CODE, "board", keyWrapper, dekProvider);
    }

    @Bean
    public EnvelopeCryptoService userPiiCryptoService(KeyWrapper keyWrapper, DekProvider dekProvider) {
        return EnvelopeCryptoService.forDomain(USER_PII_DOMAIN_CODE, "user-pii", keyWrapper, dekProvider);
    }

    @Bean
    public DekRotationSupport dekRotationSupport(KeyWrapper keyWrapper, DekProvider dekProvider) {
        return new DekRotationSupport(keyWrapper, dekProvider);
    }

    /** KEK 회전: transit/keys/kek/rotate + 각 도메인 wrapped DEK rewrap (데이터 재암호화 없음). */
    @Bean
    public TransitKekRotationSupport transitKekRotationSupport(
            VaultOperations vaultOperations,
            @Value("${vault.transit.mount:transit}") String transitMount,
            @Value("${vault.transit.key:kek}") String transitKey) {
        return new TransitKekRotationSupport(vaultOperations, transitMount, transitKey);
    }

    @Bean
    public BlindIndexKeyProvider blindIndexKeyProvider(
            VaultOperations vaultOperations,
            @Value("${vault.blind-index.base-path:ebiz_service/data/ebiz_db/blind-index}") String basePath) {
        return new VaultBlindIndexKeyProvider(vaultOperations, basePath);
    }

    // 전화번호/주민등록번호 검색용 - 필드마다 독립된 HMAC 키를 쓰므로 도메인 코드 없이 문자열 이름으로 구분
    @Bean
    public BlindIndexService phoneBlindIndexService(BlindIndexKeyProvider blindIndexKeyProvider) {
        return BlindIndexService.forIndex("user-phone", blindIndexKeyProvider);
    }

    @Bean
    public BlindIndexService rrnBlindIndexService(BlindIndexKeyProvider blindIndexKeyProvider) {
        return BlindIndexService.forIndex("user-rrn", blindIndexKeyProvider);
    }
}
