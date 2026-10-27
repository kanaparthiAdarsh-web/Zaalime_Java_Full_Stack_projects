package com.zaalime.orders.service.catalog;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    /**
     * @param availableStock live stock in Redis - what can still be bought right now
     * @param committedStock stock in Postgres, null when the catalog is served from the Redis fallback
     */
    public record ProductView(long id, String sku, String name, String description, BigDecimal price,
                              long availableStock, Integer committedStock) {
    }

    public record CreateProductRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9_-]+") String sku,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal price,
            @Min(0) @Max(1_000_000) int initialStock) {
    }

    public record UpdateProductRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal price) {
    }

    public record RestockRequest(@Min(1) @Max(1_000_000) int quantity) {
    }
}
