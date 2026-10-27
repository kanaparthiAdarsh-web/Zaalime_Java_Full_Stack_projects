package com.zaalime.orders.service.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    // relative update so a restock never overwrites a concurrent decrement from the worker
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE inventory SET quantity = quantity + :delta, updated_at = now() WHERE product_id = :productId",
            nativeQuery = true)
    int addStock(@Param("productId") long productId, @Param("delta") int delta);
}
