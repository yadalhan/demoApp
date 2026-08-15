package com.xaan.demo.service;

import org.junit.jupiter.api.Test;
import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.support.VaultResponse;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PasswordServiceTest {

    @Test
    void encryptBoardPasswordCanBeDecrypted() {
        // Mock VaultOperations to return a proper kv-v2 response
        VaultOperations vaultOps = mock(VaultOperations.class);
        VaultResponse response = mock(VaultResponse.class);

        // 32-byte key encoded as Base64URL
        String keyBase64 = Base64.getUrlEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes());

        Map<String, Object> innerData = new HashMap<>();
        innerData.put("fernet-key", keyBase64);
        Map<String, Object> outerData = new HashMap<>();
        outerData.put("data", innerData);

        when(response.getData()).thenReturn(outerData);
        when(vaultOps.read("test/path")).thenReturn(response);

        PasswordService passwordService = new PasswordService(vaultOps, "test/path");

        String encryptedPassword = passwordService.encryptBoardPassword("900101123456");

        assertThat(encryptedPassword).isNotEqualTo("900101123456");
        assertThat(passwordService.decryptBoardPassword(encryptedPassword)).isEqualTo("900101123456");
    }
}
