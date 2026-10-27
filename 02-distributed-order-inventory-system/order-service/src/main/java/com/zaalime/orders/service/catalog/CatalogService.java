package com.zaalime.orders.service.catalog;

import com.zaalime.orders.common.event.InventoryEvent;
import com.zaalime.orders.service.catalog.CatalogDtos.CreateProductRequest;
import com.zaalime.orders.service.catalog.CatalogDtos.ProductView;
import com.zaalime.orders.service.catalog.CatalogDtos.UpdateProductRequest;
import com.zaalime.orders.service.realtime.EventPublisher;
import com.zaalime.orders.service.web.ApiException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

    private final ProductRepository products;
    private final InventoryRepository inventory;
    private final StockStore stockStore;
    private final EventPublisher events;

    public CatalogService(ProductRepository products, InventoryRepository inventory, StockStore stockStore, EventPublisher events) {
        this.products = products;
        this.inventory = inventory;
        this.stockStore = stockStore;
        this.events = events;
    }

    /**
     * Reads from Postgres, live stock from Redis. If Postgres is struggling the
     * circuit opens and we serve the catalog straight from the Redis snapshots instead.
     */
    @Transactional(readOnly = true)
    @CircuitBreaker(name = "catalogDb", fallbackMethod = "listProductsFromCache")
    public List<ProductView> listProducts() {
        List<Product> all = products.findAllByOrderByIdAsc();
        Map<Long, Integer> committed = inventory.findAll().stream()
                .collect(Collectors.toMap(Inventory::getProductId, Inventory::getQuantity));
        Map<Long, Long> live = stockStore.availableStock(all.stream().map(Product::getId).toList());
        return all.stream()
                .map(p -> toView(p, live.getOrDefault(p.getId(), 0L), committed.get(p.getId())))
                .toList();
    }

    @SuppressWarnings("unused") // used by the circuit breaker
    List<ProductView> listProductsFromCache(Throwable cause) {
        log.warn("Serving catalog from Redis, database unavailable: {}", cause.toString());
        List<Long> ids = stockStore.catalogIds();
        Map<Long, Long> live = stockStore.availableStock(ids);
        return ids.stream()
                .map(stockStore::snapshot)
                .flatMap(java.util.Optional::stream)
                .map(s -> new ProductView(s.id(), s.sku(), s.name(), s.description(), s.price(),
                        live.getOrDefault(s.id(), 0L), null))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductView getProduct(long id) {
        Product p = products.findById(id).orElseThrow(() -> ApiException.notFound("Product " + id + " not found"));
        Integer committed = inventory.findById(id).map(Inventory::getQuantity).orElse(null);
        return toView(p, stockStore.availableStock(id), committed);
    }

    @Transactional
    public ProductView createProduct(CreateProductRequest req) {
        if (products.existsBySkuIgnoreCase(req.sku())) {
            throw ApiException.conflict("Duplicate SKU", "A product with SKU '" + req.sku() + "' already exists");
        }
        Product product = products.saveAndFlush(new Product(req.sku(), req.name(), req.description(), req.price()));
        inventory.save(new Inventory(product.getId(), req.initialStock()));

        afterCommit(() -> {
            stockStore.putSnapshot(product);
            stockStore.setStock(product.getId(), req.initialStock());
            events.publish(new InventoryEvent(product.getId(), product.getName(), req.initialStock(),
                    (long) req.initialStock(), "CREATED", Instant.now()));
        });
        return toView(product, req.initialStock(), req.initialStock());
    }

    @Transactional
    public ProductView updateProduct(long id, UpdateProductRequest req) {
        Product product = products.findById(id).orElseThrow(() -> ApiException.notFound("Product " + id + " not found"));
        product.update(req.name(), req.description(), req.price());
        products.flush();
        afterCommit(() -> stockStore.putSnapshot(product));
        Integer committed = inventory.findById(id).map(Inventory::getQuantity).orElse(null);
        return toView(product, stockStore.availableStock(id), committed);
    }

    @Transactional
    public ProductView restock(long id, int quantity) {
        Product product = products.findById(id).orElseThrow(() -> ApiException.notFound("Product " + id + " not found"));
        if (inventory.addStock(id, quantity) == 0) {
            inventory.save(new Inventory(id, quantity));
        }
        int committed = inventory.findById(id).map(Inventory::getQuantity).orElse(quantity);

        afterCommit(() -> {
            // if the key vanished (Redis flushed) re-seed from the DB instead of INCR-ing from zero
            long live = stockStore.initStockIfAbsent(id, committed)
                    ? committed
                    : stockStore.addStock(id, quantity);
            events.publish(new InventoryEvent(id, product.getName(), live, (long) committed, "RESTOCKED", Instant.now()));
        });
        return toView(product, stockStore.availableStock(id) + quantity, committed);
    }

    private static ProductView toView(Product p, long available, Integer committed) {
        return new ProductView(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getPrice(), available, committed);
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
