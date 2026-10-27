package com.zaalime.orders.service.catalog;

import com.zaalime.orders.service.catalog.CatalogDtos.CreateProductRequest;
import com.zaalime.orders.service.catalog.CatalogDtos.ProductView;
import com.zaalime.orders.service.catalog.CatalogDtos.RestockRequest;
import com.zaalime.orders.service.catalog.CatalogDtos.UpdateProductRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/** GET is public, writes need ROLE_ADMIN (see SecurityConfig). */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final CatalogService catalog;

    public ProductController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<ProductView> list() {
        return catalog.listProducts();
    }

    @GetMapping("/{id}")
    public ProductView get(@PathVariable long id) {
        return catalog.getProduct(id);
    }

    @PostMapping
    public ResponseEntity<ProductView> create(@Valid @RequestBody CreateProductRequest request) {
        ProductView created = catalog.createProduct(request);
        return ResponseEntity.created(URI.create("/api/products/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public ProductView update(@PathVariable long id, @Valid @RequestBody UpdateProductRequest request) {
        return catalog.updateProduct(id, request);
    }

    @PostMapping("/{id}/restock")
    public ProductView restock(@PathVariable long id, @Valid @RequestBody RestockRequest request) {
        return catalog.restock(id, request.quantity());
    }
}
