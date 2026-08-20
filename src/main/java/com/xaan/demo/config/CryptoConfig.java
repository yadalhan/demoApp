package com.xaan.demo.config;

import com.xaan.vault.crypto.envelope.DekProvider;
import com.xaan.vault.crypto.envelope.EnvelopeCryptoService;
import com.xaan.vault.crypto.envelope.KekProvider;
import com.xaan.vault.crypto.envelope.KekService;
import com.xaan.vault.crypto.envelope.VaultDekProvider;
import com.xaan.vault.crypto.envelope.VaultKekProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.vault.core.VaultOperations;

/**
 * KEK-DEK envelope encryption wiring. The KEK stays in Vault and only wraps DEKs;
 * each service domain gets its own DEK, unwrapped once here at startup and cached
 * in the resulting {@link EnvelopeCryptoService} bean so request-time encrypt/decrypt
 * never calls Vault. Both the KEK and each domain's DEK are versioned in Vault so a
 * rotation can add a new version without breaking data encrypted under an older one
 * - see KEY_ROTATION_RUNBOOK.md for the operational procedure.
 */
@Configuration
public class CryptoConfig {

    public static final byte BOARD_DOMAIN_CODE = 1;
    public static final byte USER_PII_DOMAIN_CODE = 2;

    @Bean
    public KekProvider kekProvider(
            VaultOperations vaultOperations,
            @Value("${vault.kek.path:ebiz_service/data/ebiz_db/kek}") String kekPath) {
        return new VaultKekProvider(vaultOperations, kekPath);
    }

    @Bean
    public KekService kekService(KekProvider kekProvider) {
        return KekService.load(kekProvider);
    }

    @Bean
    public DekProvider dekProvider(
            VaultOperations vaultOperations,
            @Value("${vault.dek.base-path:ebiz_service/data/ebiz_db/dek}") String dekBasePath) {
        return new VaultDekProvider(vaultOperations, dekBasePath);
    }

    @Bean
    public EnvelopeCryptoService boardCryptoService(KekService kekService, DekProvider dekProvider) {
        return EnvelopeCryptoService.forDomain(BOARD_DOMAIN_CODE, "board", kekService, dekProvider);
    }

    @Bean
    public EnvelopeCryptoService userPiiCryptoService(KekService kekService, DekProvider dekProvider) {
        return EnvelopeCryptoService.forDomain(USER_PII_DOMAIN_CODE, "user-pii", kekService, dekProvider);
    }
}
