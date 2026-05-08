package com.xaan.demo.service;

import com.xaan.vault.crypto.VaultCryptoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.vault.core.VaultOperations;

@Service
public class PasswordService {

    private final VaultCryptoService vaultCryptoService;

    public PasswordService(VaultOperations vaultOperations,
                           @Value("${vault.secret.path:ebiz_service/data/ebiz_db/data-enc-key}") String vaultSecretPath) {
        this.vaultCryptoService = new VaultCryptoService(vaultOperations, vaultSecretPath);
    }

    public String encryptPassword(String password) {
        return vaultCryptoService.encrypt(password);
    }

    public String decryptPassword(String encryptedPassword) {
        return vaultCryptoService.decrypt(encryptedPassword);
    }

    public boolean validatePassword(String inputPassword, String storedEncryptedPassword) {
        return vaultCryptoService.validate(inputPassword, storedEncryptedPassword);
    }
}
