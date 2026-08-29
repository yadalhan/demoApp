package com.xaan.demo.service;

import com.xaan.vault.crypto.blindindex.BlindIndexService;
import com.xaan.vault.crypto.envelope.DekProvider;
import com.xaan.vault.crypto.envelope.EnvelopeCryptoService;
import com.xaan.vault.crypto.envelope.KekService;
import com.xaan.vault.crypto.envelope.WrappedDek;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordServiceTest {

    @Test
    void validateBoardPasswordAcceptsTheCorrectPassword() {
        EnvelopeCryptoService boardCryptoService = newBoardCryptoService();
        PasswordService passwordService = newPasswordService(boardCryptoService);

        String encrypted = boardCryptoService.encrypt("900101123456");

        assertThat(passwordService.validateBoardPassword("900101123456", encrypted)).isTrue();
        assertThat(passwordService.validateBoardPassword("wrong", encrypted)).isFalse();
    }

    @Test
    void preEnvelopeCiphertextFailsValidationInsteadOfThrowing() {
        // Legacy migration/compatibility was dropped: old single-key ciphertext is now
        // just unreadable noise rather than something the app falls back to decrypting.
        PasswordService passwordService = newPasswordService(newBoardCryptoService());
        String legacyLookingCiphertext = "JrwIlNN9YVMIxpqWvYhlNGfd7CUf1wjOgXAHLRIf0io=";

        assertThat(passwordService.validateBoardPassword("anything", legacyLookingCiphertext)).isFalse();
    }

    @Test
    void blindIndexIsDeterministicAndFieldsAreIndependent() {
        PasswordService passwordService = newPasswordService(newBoardCryptoService());

        assertThat(passwordService.computePhoneBlindIndex("01012345678"))
                .isEqualTo(passwordService.computePhoneBlindIndex("01012345678"));
        assertThat(passwordService.computePhoneBlindIndex("01012345678"))
                .isNotEqualTo(passwordService.computeRrnBlindIndex("01012345678"));
    }

    private PasswordService newPasswordService(EnvelopeCryptoService boardCryptoService) {
        return new PasswordService(
                boardCryptoService,
                BlindIndexService.withKey(randomBytes(32)),
                BlindIndexService.withKey(randomBytes(32)));
    }

    private EnvelopeCryptoService newBoardCryptoService() {
        KekService kek = new KekService(randomBytes(32));
        InMemoryDekProvider dekProvider = new InMemoryDekProvider();
        seedDek(kek, dekProvider, "board", 1);
        return EnvelopeCryptoService.forDomain((byte) 1, "board", kek, dekProvider);
    }

    private static void seedDek(KekService kek, InMemoryDekProvider dekProvider, String domain, int version) {
        byte[] plaintextDek = randomBytes(32);
        byte[] wrapped = kek.wrap(plaintextDek);
        dekProvider.store(domain, new WrappedDek(domain, version, wrapped), version);
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /** Minimal in-memory stand-in for {@code VaultDekProvider}, used to avoid a live Vault in tests. */
    private static final class InMemoryDekProvider implements DekProvider {
        private final Map<String, List<WrappedDek>> versionsByDomain = new HashMap<>();
        private final Map<String, Integer> currentVersionByDomain = new HashMap<>();

        @Override
        public List<WrappedDek> loadAll(String domain) {
            return versionsByDomain.getOrDefault(domain, List.of());
        }

        @Override
        public int loadCurrentVersion(String domain) {
            return currentVersionByDomain.get(domain);
        }

        @Override
        public void store(String domain, WrappedDek newVersion, int newCurrentVersion) {
            versionsByDomain.computeIfAbsent(domain, d -> new ArrayList<>()).add(newVersion);
            currentVersionByDomain.put(domain, newCurrentVersion);
        }

        @Override
        public void retire(String domain, int version) {
            List<WrappedDek> versions = versionsByDomain.get(domain);
            if (versions != null) {
                versions.removeIf(wrapped -> wrapped.version() == version);
            }
        }
    }
}
