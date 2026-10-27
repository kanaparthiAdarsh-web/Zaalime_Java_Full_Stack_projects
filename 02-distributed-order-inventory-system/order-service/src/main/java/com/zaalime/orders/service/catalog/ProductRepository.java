package com.zaalime.orders.service.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsBySkuIgnoreCase(String sku);

    List<Product> findAllByOrderByIdAsc();
}
