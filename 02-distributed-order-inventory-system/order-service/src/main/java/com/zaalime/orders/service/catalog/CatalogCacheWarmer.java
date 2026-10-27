package com.zaalime.orders.service.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * On startup, copy product snapshots into Redis and seed live stock for any product
 * that has no stock key yet. Existing keys are left alone: they already reflect
 * reservations that may still be sitting in the queue.
 */
@Component
public class CatalogCacheWarmer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogCacheWarmer.class);

    private final ProductRepository products;
    private final InventoryRepository inventory;
    private final StockStore stockStore;

    public CatalogCacheWarmer(ProductRepository products, InventoryRepository inventory, StockStore stockStore) {
        this.products = products;
        this.inventory = inventory;
        this.stockStore = stockStore;
    }

    @Override
    @Transactional(readOnly = true)
    public void run(ApplicationArguments args) {
        Map<Long, Integer> committed = inventory.findAll().stream()
                .collect(Collectors.toMap(Inventory::getProductId, Inventory::getQuantity));
        int seeded = 0;
        for (Product p : products.findAll()) {
            stockStore.putSnapshot(p);
            if (stockStore.initStockIfAbsent(p.getId(), committed.getOrDefault(p.getId(), 0))) {
                seeded++;
            }
        }
        log.info("Catalog cache warmed: {} products, {} stock keys seeded from Postgres", committed.size(), seeded);
    }
}
