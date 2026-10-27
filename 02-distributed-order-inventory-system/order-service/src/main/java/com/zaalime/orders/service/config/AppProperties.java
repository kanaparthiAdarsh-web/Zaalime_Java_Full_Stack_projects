package com.zaalime.orders.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Admin admin, Checkout checkout, List<String> allowedOrigins) {

    public record Jwt(String secret, Duration ttl, String issuer) {
    }

    public record Admin(String username, String password) {
    }

    /**
     * @param idempotencyTtl how long a used Idempotency-Key is remembered
     * @param pendingOrderTtl how long an accepted-but-not-yet-persisted order snapshot is kept
     * @param maxLines        max distinct products in one order
     * @param maxQuantity     max units of one product per order
     */
    public record Checkout(Duration idempotencyTtl, Duration pendingOrderTtl, int maxLines, int maxQuantity) {
    }
}
