package com.xaan.demo.config.mybatis;

import com.xaan.vault.crypto.envelope.EnvelopeCryptoService;
import com.xaan.vault.crypto.mybatis.EnvelopeCryptoTypeHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Encrypts/decrypts {@code users.id_no} and {@code users.phone} via the {@code user-pii}
 * domain DEK. Unlike {@link BoardPasswordTypeHandler}, this is safe to wire onto both
 * the read and write side - the users table has no legacy pre-envelope data (it was
 * reset when KEK-DEK envelope encryption was introduced), so every row a SELECT can
 * return is guaranteed to be in this library's format.
 *
 * <p>Deliberately has no {@code @MappedTypes} - see {@link BoardPasswordTypeHandler}'s
 * Javadoc for why (must only apply where a mapper references it by class name).
 */
@Component
public class UserPiiTypeHandler extends EnvelopeCryptoTypeHandler {
    public UserPiiTypeHandler(@Qualifier("userPiiCryptoService") EnvelopeCryptoService userPiiCryptoService) {
        super(userPiiCryptoService);
    }
}
