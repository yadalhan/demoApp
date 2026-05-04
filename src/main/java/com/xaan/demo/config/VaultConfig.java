package com.xaan.demo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.config.AbstractVaultConfiguration;

import java.net.URI;

@Configuration
public class VaultConfig extends AbstractVaultConfiguration {
    
    @Override
    public VaultEndpoint vaultEndpoint() {
        return VaultEndpoint.from(URI.create("http://localhost:8200"));
    }
    
    @Override
    public TokenAuthentication clientAuthentication() {
        return new TokenAuthentication("spring-boot-vault-token");
    }
}