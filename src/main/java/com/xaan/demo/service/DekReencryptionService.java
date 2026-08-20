package com.xaan.demo.service;

import com.xaan.demo.domain.entity.Board;
import com.xaan.demo.domain.entity.User;
import com.xaan.demo.domain.repository.BoardRepository;
import com.xaan.demo.domain.repository.UserRepository;
import com.xaan.vault.crypto.CryptoException;
import com.xaan.vault.crypto.envelope.EnvelopeCryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Post-DEK-rotation backfill (see KEY_ROTATION_RUNBOOK.md §2.1 step 4): after
 * {@code DekRotationSupport.rotate(domain)} issues a new DEK version and the app has
 * picked it up, existing rows are still encrypted under the old version until this
 * runs. Each row's version is read straight from its ciphertext header
 * ({@link EnvelopeCryptoService#versionOf}) so already-current rows are skipped
 * instead of being decrypted/re-encrypted for no reason. Safe to re-run.
 *
 * <p>Rows still in the pre-KEK-DEK legacy single-key format (deliberately never
 * migrated - see KEK_DEK_ENCRYPTION_PLAN.md's 2026-08-19 decision) have no
 * domainCode/keyVersion header at all, so reading one produces effectively random
 * bytes; {@code decrypt()} then throws a domain-mismatch or missing-version
 * {@link CryptoException} for them. That is expected, not a real failure, so those
 * rows are counted separately rather than logged one by one (there can be tens of
 * thousands of them).
 */
@Service
public class DekReencryptionService {

    private static final Logger logger = LoggerFactory.getLogger(DekReencryptionService.class);

    private final BoardRepository boardRepository;
    private final UserRepository userRepository;
    private final EnvelopeCryptoService boardCryptoService;
    private final EnvelopeCryptoService userPiiCryptoService;

    public DekReencryptionService(
            BoardRepository boardRepository,
            UserRepository userRepository,
            @Qualifier("boardCryptoService") EnvelopeCryptoService boardCryptoService,
            @Qualifier("userPiiCryptoService") EnvelopeCryptoService userPiiCryptoService) {
        this.boardRepository = boardRepository;
        this.userRepository = userRepository;
        this.boardCryptoService = boardCryptoService;
        this.userPiiCryptoService = userPiiCryptoService;
    }

    @Transactional
    public MigrationResult reencryptBoardPasswords() {
        List<Board> boards = boardRepository.findAll();
        int migrated = 0;
        int skipped = 0;
        int notEnvelopeFormat = 0;
        int failed = 0;
        for (Board board : boards) {
            String password = board.getPassword();
            if (password == null || password.isEmpty()) {
                skipped++;
                continue;
            }
            try {
                if (boardCryptoService.versionOf(password) == boardCryptoService.currentVersion()) {
                    skipped++;
                    continue;
                }
                String plain = boardCryptoService.decrypt(password);
                board.updatePassword(boardCryptoService.encrypt(plain));
                migrated++;
            } catch (CryptoException e) {
                notEnvelopeFormat++;
            } catch (RuntimeException e) {
                failed++;
                logger.error("Failed to reencrypt board password for id={}: {}", board.getId(), e.getMessage());
            }
        }
        return new MigrationResult(migrated, skipped, notEnvelopeFormat, failed);
    }

    @Transactional
    public MigrationResult reencryptUserPii() {
        List<User> users = userRepository.findAll();
        int migrated = 0;
        int skipped = 0;
        int notEnvelopeFormat = 0;
        int failed = 0;
        for (User user : users) {
            String rrn = user.getResidentRegistrationNumber();
            if (rrn == null || rrn.isEmpty()) {
                skipped++;
                continue;
            }
            try {
                if (userPiiCryptoService.versionOf(rrn) == userPiiCryptoService.currentVersion()) {
                    skipped++;
                    continue;
                }
                String plain = userPiiCryptoService.decrypt(rrn);
                user.updateResidentRegistrationNumber(userPiiCryptoService.encrypt(plain));
                migrated++;
            } catch (CryptoException e) {
                notEnvelopeFormat++;
            } catch (RuntimeException e) {
                failed++;
                logger.error("Failed to reencrypt user PII for id={}: {}", user.getId(), e.getMessage());
            }
        }
        return new MigrationResult(migrated, skipped, notEnvelopeFormat, failed);
    }

    public record MigrationResult(int migrated, int skipped, int notEnvelopeFormat, int failed) {
    }
}
