package com.xaan.demo.service;

import com.xaan.vault.crypto.envelope.DekRotationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * One-shot ops switches for DEK rotation (see KEY_ROTATION_RUNBOOK.md §2). Two ways
 * to invoke:
 *
 * <p><b>1. As part of a normal (long-running) deploy</b> - set one env var, restart
 * the server once, confirm the logged result, then unset before the next deploy.
 * Runs inline during startup while the app is already accepting web traffic.
 * <pre>
 * ROTATE_DEK_DOMAIN=board
 * </pre>
 * <pre>
 * REENCRYPT_DEK_DOMAINS=board,user-pii
 * </pre>
 *
 * <p><b>2. As a standalone manual batch run</b> (no web server, no live traffic
 * involved at all) - also set {@code DEK_OPS_BATCH_MODE=true} and launch the jar with
 * {@code --spring.main.web-application-type=none}; the process runs the requested
 * op(s) and exits instead of staying up. See {@code dek_ops_batch.sh}/{@code .bat}.
 *
 * <p><b>Never set both {@code rotate-domain} and {@code reencrypt-domains} in the same
 * run.</b> {@code EnvelopeCryptoService} reads Vault's current DEK version once when
 * the bean is built (before this runner ever executes) and caches it; a reencrypt
 * done in the same JVM run as a rotate would still see the pre-rotation version and
 * never touch the just-created one. {@link #run} refuses to do anything if both are
 * set, rather than silently running a reencrypt that can't do what it looks like it
 * does - rotate, let that run finish, then start a fresh run for the reencrypt.
 */
@Component
public class DekOpsRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(DekOpsRunner.class);

    private final DekRotationSupport dekRotationSupport;
    private final DekReencryptionService reencryptionService;
    private final ConfigurableApplicationContext context;
    private final String rotateDomain;
    private final String reencryptDomains;
    private final boolean batchMode;

    public DekOpsRunner(
            DekRotationSupport dekRotationSupport,
            DekReencryptionService reencryptionService,
            ConfigurableApplicationContext context,
            @Value("${app.dek-ops.rotate-domain:}") String rotateDomain,
            @Value("${app.dek-ops.reencrypt-domains:}") String reencryptDomains,
            @Value("${app.dek-ops.batch-mode:false}") boolean batchMode) {
        this.dekRotationSupport = dekRotationSupport;
        this.reencryptionService = reencryptionService;
        this.context = context;
        this.rotateDomain = rotateDomain;
        this.reencryptDomains = reencryptDomains;
        this.batchMode = batchMode;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!rotateDomain.isBlank() && !reencryptDomains.isBlank()) {
            throw new IllegalStateException(
                    "app.dek-ops.rotate-domain and app.dek-ops.reencrypt-domains are both set - refusing to run. " +
                    "A reencrypt in the same JVM run as a rotate would still see the pre-rotation DEK version as " +
                    "current, so it wouldn't do what it looks like it does. Run rotate, let it finish, then run " +
                    "reencrypt as a separate invocation.");
        }

        if (!rotateDomain.isBlank()) {
            int newVersion = dekRotationSupport.rotate(rotateDomain);
            logger.info("DEK rotated for domain '{}': new current version = {}. " +
                    "Unset app.dek-ops.rotate-domain before the next deploy.", rotateDomain, newVersion);
        }

        if (!reencryptDomains.isBlank()) {
            for (String domain : reencryptDomains.split(",")) {
                String trimmed = domain.trim();
                DekReencryptionService.MigrationResult result = switch (trimmed) {
                    case "board" -> reencryptionService.reencryptBoardPasswords();
                    case "user-pii" -> reencryptionService.reencryptUserPii();
                    default -> {
                        logger.warn("Unknown domain '{}' in app.dek-ops.reencrypt-domains, skipping.", trimmed);
                        yield null;
                    }
                };
                if (result != null) {
                    logger.info("DEK reencryption for domain '{}': migrated={}, skipped={}, notEnvelopeFormat={}, failed={}",
                            trimmed, result.migrated(), result.skipped(), result.notEnvelopeFormat(), result.failed());
                }
            }
            logger.info("Unset app.dek-ops.reencrypt-domains before the next deploy.");
        }

        if (batchMode) {
            logger.info("app.dek-ops.batch-mode=true - exiting now instead of starting the web server.");
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }
}
