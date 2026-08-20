package com.xaan.demo.service;

import com.xaan.vault.crypto.CryptoException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordServiceTest {

    @Test
    void encryptBoardPasswordCanBeDecrypted() {
        PasswordService passwordService = newPasswordService();

        String encryptedPassword = passwordService.encryptBoardPassword("900101123456");

        assertThat(encryptedPassword).isNotEqualTo("900101123456");
        assertThat(passwordService.decryptBoardPassword(encryptedPassword)).isEqualTo("900101123456");
    }

    @Test
    void encryptUserPiiUsesASeparateDomainFromBoardPassword() {
        PasswordService passwordService = newPasswordService();

        String encryptedRrn = passwordService.encryptUserPii("900101-1234567");

        assertThat(passwordService.decryptUserPii(encryptedRrn)).isEqualTo("900101-1234567");
    }

    @Test
    void preEnvelopeCiphertextIsNoLongerDecryptable() {
        // Legacy migration/compatibility was dropped: old single-key ciphertext is now
        // just unreadable noise rather than something the app falls back to decrypting.
        PasswordService passwordService = newPasswordService();
        String legacyLookingCiphertext = "JrwIlNN9YVMIxpqWvYhlNGfd7CUf1wjOgXAHLRIf0io=";

        assertThatThrownBy(() -> passwordService.decryptBoardPassword(legacyLookingCiphertext))
                .isInstanceOf(CryptoException.class);
        assertThat(passwordService.validateBoardPassword("anything", legacyLookingCiphertext)).isFalse();
    }

    private PasswordService newPasswordService() {
        KekService kek = new KekService(randomBytes(32));
        InMemoryDekProvider dekProvider = new InMemoryDekProvider();
        seedDek(kek, dekProvider, "board", 1);
        seedDek(kek, dekProvider, "user-pii", 1);

        EnvelopeCryptoService boardCryptoService = EnvelopeCryptoService.forDomain((byte) 1, "board", kek, dekProvider);
        EnvelopeCryptoService userPiiCryptoService = EnvelopeCryptoService.forDomain((byte) 2, "user-pii", kek, dekProvider);

        return new PasswordService(boardCryptoService, userPiiCryptoService);
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
