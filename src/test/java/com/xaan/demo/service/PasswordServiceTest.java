package com.xaan.demo.service;

import org.junit.jupiter.api.Test;
import org.springframework.vault.core.VaultOperations;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PasswordServiceTest {

    @Test
    void encryptBoardPasswordCanBeDecrypted() throws Exception {
        PasswordService passwordService = new PasswordService(mock(VaultOperations.class), "test/path");
        Field encryptionKeyField = PasswordService.class.getDeclaredField("encryptionKey");
        encryptionKeyField.setAccessible(true);
        encryptionKeyField.set(passwordService, "0123456789abcdef0123456789abcdef".getBytes());

        String encryptedPassword = passwordService.encryptBoardPassword("900101123456");

        assertThat(encryptedPassword).isNotEqualTo("900101123456");
        assertThat(passwordService.decryptBoardPassword(encryptedPassword)).isEqualTo("900101123456");
    }
}
