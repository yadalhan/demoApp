package com.xaan.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.support.VaultResponse;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Vault/OpenBao health check that works inside a namespace.
 *
 * <p>Older versions of this class called {@code sys/health}. That endpoint is node-level
 * and root-namespace only: with {@code spring.cloud.vault.namespace} set, Vault answers
 * {@code 400 operation unavailable in namespaces}, so the component reported DOWN even
 * though encryption worked fine (and /actuator/health answered 503).
 *
 * <p>This version calls {@code auth/token/lookup-self} instead. It is namespace-scoped,
 * read-only, cheap and it validates the app token itself - the failure mode we actually
 * hit twice (a revoked/expired token only shows up when DEKs are read, i.e. at startup).
 *
 * <ul>
 *   <li>DOWN - token invalid/expired/revoked, namespace mismatch, or no response</li>
 *   <li>OUT_OF_SERVICE - token valid but expires within {@code vault.health.token-warn-ttl}</li>
 *   <li>UP - otherwise; details carry ttlSeconds, policies, renewable, namespace</li>
 * </ul>
 *
 * <p>Disable with {@code vault.health.enabled=false} (no code change needed).
 */
@Component
@ConditionalOnProperty(name = "vault.health.enabled", havingValue = "true", matchIfMissing = true)
public class VaultHealthIndicator implements HealthIndicator {

    private final VaultOperations vaultOperations;
    private final String expectedNamespace;
    private final long warnTtlSeconds;

    public VaultHealthIndicator(VaultOperations vaultOperations,
                                @Value("${spring.cloud.vault.namespace:}") String expectedNamespace,
                                @Value("${vault.health.token-warn-ttl:6h}") Duration warnTtl) {
        this.vaultOperations = vaultOperations;
        this.expectedNamespace = expectedNamespace;
        this.warnTtlSeconds = warnTtl.toSeconds();
    }

    @Override
    public Health health() {
        VaultResponse response;
        try {
            // Namespace-scoped, read-only probe: proves connectivity, resolution of the
            // namespace header and that the token is still valid. Does not extend the TTL.
            response = vaultOperations.read("auth/token/lookup-self");
        } catch (Exception e) {
            return Health.down().withDetail("reason", "auth/token/lookup-self failed").withException(e).build();
        }

        Map<String, Object> data = response == null ? null : response.getData();
        if (data == null) {
            return Health.down().withDetail("reason", "empty response from auth/token/lookup-self").build();
        }

        long ttl = data.get("ttl") instanceof Number n ? n.longValue() : 0L;
        String namespace = String.valueOf(data.getOrDefault("namespace_path", ""));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ttlSeconds", ttl);
        details.put("renewable", data.get("renewable"));
        details.put("policies", data.get("policies"));
        details.put("namespace", namespace.isBlank() ? "root" : namespace);

        if (!expectedNamespace.isBlank() && !namespace.equals(expectedNamespace + "/")) {
            details.put("reason", "namespace mismatch, expected " + expectedNamespace + "/");
            return Health.status(Status.DOWN).withDetails(details).build();
        }
        if (ttl <= warnTtlSeconds) {
            details.put("reason", "token expires in " + ttl + "s (warn threshold " + warnTtlSeconds + "s)");
            return Health.status(Status.OUT_OF_SERVICE).withDetails(details).build();
        }
        return Health.up().withDetails(details).build();
    }
}
