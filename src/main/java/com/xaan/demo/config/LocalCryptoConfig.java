package com.xaan.demo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xaan.vault.crypto.blindindex.BlindIndexKeyProvider;
import com.xaan.vault.crypto.blindindex.BlindIndexService;
import com.xaan.vault.crypto.envelope.DekProvider;
import com.xaan.vault.crypto.envelope.DekRotationSupport;
import com.xaan.vault.crypto.envelope.EnvelopeCryptoService;
import com.xaan.vault.crypto.envelope.KeyWrapper;
import com.xaan.vault.crypto.envelope.WrappedDek;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local development wiring for running the app without a Vault/OpenBao server ("local" profile).
 *
 * <p>With {@code spring.cloud.vault.enabled=false} no VaultOperations bean exists, so vault-crypto's
 * auto-configuration backs off and nothing would provide the EnvelopeCryptoService beans the app
 * needs. This class fills that gap with file-backed test keys:
 *
 * <pre>
 * ./gradlew bootRun --args='--spring.profiles.active=local'
 * </pre>
 *
 * <p>The keys live in {@code .local-crypto/keys.json} (created on first start, keep it out of git).
 * They are stored as raw bytes and are wrapped/unwrapped in-process - this is NOT the production
 * KEK/DEK scheme (no Transit, no wrapping). Data encrypted with these keys can never be decrypted
 * by the Vault-backed environment, and vice versa, so use a separate local database.
 *
 * <p>Never enable this profile in a deployed environment: it bypasses KMS entirely.
 */
@Configuration
@Profile("local")
public class LocalCryptoConfig {

    private static final Logger log = LoggerFactory.getLogger(LocalCryptoConfig.class);
    private static final int KEY_BYTES = 32;

    /** board=1, user-pii=2 - must match the domain codes already recorded in existing ciphertext. */
    private static final byte BOARD_DOMAIN_CODE = 1;
    private static final byte USER_PII_DOMAIN_CODE = 2;

    @Bean
    public LocalKeyFile localKeyFile(@Value("${local.crypto.keys-file:.local-crypto/keys.json}") String path)
            throws IOException {
        LocalKeyFile file = LocalKeyFile.loadOrCreate(Path.of(path));
        log.warn("local profile: using file-backed test keys from {} (no KMS). Never use this outside local dev.",
                file.path());
        return file;
    }

    @Bean
    public KeyWrapper keyWrapper(LocalKeyFile keys) {
        // Raw pass-through: "wrapped" bytes are the key itself. Local-only stand-in for TransitKeyWrapper.
        return new KeyWrapper() {
            @Override
            public int currentVersion() {
                return 1;
            }

            @Override
            public WrappedDek wrap(String domain, int dekVersion, byte[] plainDek) {
                return new WrappedDek(domain, dekVersion, plainDek);
            }

            @Override
            public byte[] unwrap(WrappedDek wrapped) {
                return wrapped.wrappedBytes();
            }

            @Override
            public WrappedDek rewrap(WrappedDek wrapped) {
                return wrapped;
            }
        };
    }

    @Bean
    public DekProvider dekProvider(LocalKeyFile keys, KeyWrapper keyWrapper) {
        Map<String, byte[]> deks = keys.keys();
        Map<String, WrappedDek> board = new LinkedHashMap<>();
        board.put("board", new WrappedDek("board", 1, deks.get("board")));
        Map<String, WrappedDek> userPii = new LinkedHashMap<>();
        userPii.put("user-pii", new WrappedDek("user-pii", 1, deks.get("user-pii")));
        Map<String, Map<String, WrappedDek>> seed = new HashMap<>();
        seed.put("board", board);
        seed.put("user-pii", userPii);

        return new DekProvider() {
            private final Map<String, Map<Integer, WrappedDek>> versions = new HashMap<>();

            {
                seed.forEach((domain, byName) -> byName.forEach((name, dek) -> store(domain, dek, dek.version())));
            }

            @Override
            public List<WrappedDek> loadAll(String domain) {
                return List.copyOf(versions.getOrDefault(domain, Map.of()).values());
            }

            @Override
            public int loadCurrentVersion(String domain) {
                return 1;
            }

            @Override
            public void store(String domain, WrappedDek newVersion, int newCurrentVersion) {
                versions.computeIfAbsent(domain, key -> new LinkedHashMap<>()).put(newVersion.version(), newVersion);
            }

            @Override
            public void retire(String domain, int version) {
                versions.getOrDefault(domain, Map.of()).remove(version);
            }
        };
    }

    @Bean
    public BlindIndexKeyProvider blindIndexKeyProvider(LocalKeyFile keys) {
        Map<String, byte[]> blindIndexKeys = keys.keys();
        return new BlindIndexKeyProvider() {
            @Override
            public byte[] loadKey(String indexName) {
                byte[] key = blindIndexKeys.get(indexName);
                if (key == null) {
                    throw new IllegalStateException("no local blind-index key for " + indexName
                            + " - delete " + keys.path() + " to regenerate the local key set");
                }
                return key;
            }

            @Override
            public void storeKey(String indexName, byte[] key) {
                blindIndexKeys.put(indexName, key);
                keys.persist();
            }
        };
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
    public BlindIndexService phoneBlindIndexService(BlindIndexKeyProvider provider) {
        return BlindIndexService.forIndex("user-phone", provider);
    }

    @Bean
    public BlindIndexService rrnBlindIndexService(BlindIndexKeyProvider provider) {
        return BlindIndexService.forIndex("user-rrn", provider);
    }

    /** DekOpsRunner requires this bean; rotation is a no-op with file-backed keys. */
    @Bean
    public DekRotationSupport dekRotationSupport(KeyWrapper keyWrapper, DekProvider dekProvider) {
        return new DekRotationSupport(keyWrapper, dekProvider);
    }

    /**
     * JSON file holding the local development keys: {@code board}, {@code user-pii}, {@code user-phone},
     * {@code user-rrn} as Base64 (32 bytes each). Missing entries are generated on first load.
     */
    public static final class LocalKeyFile {

        private static final String[] REQUIRED = {"board", "user-pii", "user-phone", "user-rrn"};

        private final Path path;
        private final Map<String, byte[]> keys;

        private LocalKeyFile(Path path, Map<String, byte[]> keys) {
            this.path = path;
            this.keys = keys;
        }

        static LocalKeyFile loadOrCreate(Path path) throws IOException {
            Map<String, byte[]> keys = new LinkedHashMap<>();
            if (Files.isRegularFile(path)) {
                @SuppressWarnings("unchecked")
                Map<String, String> stored = new ObjectMapper().readValue(Files.readString(path), Map.class);
                stored.forEach((name, encoded) -> keys.put(name, Base64.getDecoder().decode(encoded)));
            }
            boolean generated = false;
            for (String name : REQUIRED) {
                if (!keys.containsKey(name)) {
                    byte[] key = new byte[KEY_BYTES];
                    new SecureRandom().nextBytes(key);
                    keys.put(name, key);
                    generated = true;
                }
            }
            LocalKeyFile file = new LocalKeyFile(path, keys);
            if (generated) {
                file.persist();
            }
            return file;
        }

        void persist() {
            Map<String, String> encoded = new LinkedHashMap<>();
            keys.forEach((name, key) -> encoded.put(name, Base64.getEncoder().encodeToString(key)));
            try {
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                Files.writeString(path, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(encoded),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("cannot write local key file " + path, e);
            }
        }

        Path path() {
            return path;
        }

        Map<String, byte[]> keys() {
            return keys;
        }
    }
}
