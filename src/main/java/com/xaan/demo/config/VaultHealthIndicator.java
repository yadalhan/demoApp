package com.xaan.demo.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.vault.core.VaultOperations;

@Component
public class VaultHealthIndicator implements HealthIndicator {
    private final VaultOperations vaultOperations;

    public VaultHealthIndicator(VaultOperations vaultOperations) {
        this.vaultOperations = vaultOperations;
    }

    @Override
    public Health health() {
        try {
            var response = vaultOperations.read("sys/health");
            if (response != null && response.getData() != null) {
                return Health.up().withDetail("status", "Vault is reachable").build();
            }
            return Health.down().withDetail("error", "Vault returned null response").build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}