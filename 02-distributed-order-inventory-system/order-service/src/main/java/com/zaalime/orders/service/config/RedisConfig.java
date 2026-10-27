package com.zaalime.orders.service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

@Configuration
public class RedisConfig {

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    RedisScript<List<Long>> reserveStockScript() {
        return (RedisScript) RedisScript.of(new ClassPathResource("scripts/reserve_stock.lua"), List.class);
    }

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    RedisScript<List<Long>> releaseStockScript() {
        return (RedisScript) RedisScript.of(new ClassPathResource("scripts/release_stock.lua"), List.class);
    }
}
