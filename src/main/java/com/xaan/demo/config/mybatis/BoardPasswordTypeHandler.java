package com.xaan.demo.config.mybatis;

import com.xaan.vault.crypto.envelope.EnvelopeCryptoService;
import com.xaan.vault.crypto.mybatis.EnvelopeCryptoTypeHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Encrypts/decrypts {@code board.password} via the {@code board} domain DEK. Only wired
 * onto the write side of {@link com.xaan.demo.domain.mapper.BoardMapper}'s SQL (insert/
 * update parameters) - deliberately NOT on {@code @Select} result mappings, since
 * ~46k pre-KEK-DEK legacy rows still have non-envelope-format passwords and decrypting
 * them on every ordinary list/view read would throw {@code CryptoException} out of
 * routine page loads instead of only when a password is actually being checked.
 *
 * <p>Deliberately has no {@code @MappedTypes} - this must only ever apply where a mapper
 * references it by class name (e.g. {@code #{password,typeHandler=BoardPasswordTypeHandler}}),
 * never as the implicit default handler for every {@code String} column/parameter.
 */
@Component
public class BoardPasswordTypeHandler extends EnvelopeCryptoTypeHandler {
    public BoardPasswordTypeHandler(@Qualifier("boardCryptoService") EnvelopeCryptoService boardCryptoService) {
        super(boardCryptoService);
    }
}
