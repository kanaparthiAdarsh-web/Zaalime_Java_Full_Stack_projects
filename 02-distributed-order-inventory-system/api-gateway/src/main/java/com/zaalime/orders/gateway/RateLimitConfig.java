package com.zaalime.orders.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

@Configuration
public class RateLimitConfig {

    /**
     * Logged-in callers are limited per token, anonymous ones per client IP.
     * The token itself is never stored - only a hash of it is used as the bucket name.
     */
    @Bean
    KeyResolver callerKeyResolver() {
        return exchange -> {
            String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (auth != null && auth.startsWith("Bearer ")) {
                return Mono.just("token:" + UUID.nameUUIDFromBytes(auth.getBytes(StandardCharsets.UTF_8)));
            }
            String ip = Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                    .map(InetSocketAddress::getAddress)
                    .map(a -> a.getHostAddress())
                    .orElse("unknown");
            return Mono.just("ip:" + ip);
        };
    }
}
