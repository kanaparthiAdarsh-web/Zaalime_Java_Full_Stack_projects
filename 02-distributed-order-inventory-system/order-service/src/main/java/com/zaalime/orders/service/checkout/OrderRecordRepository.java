package com.zaalime.orders.service.checkout;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrderRecordRepository extends JpaRepository<OrderRecord, UUID> {

    Optional<OrderRecord> findByIdAndUserId(UUID id, long userId);

    Page<OrderRecord> findByUserIdOrderByCreatedAtDesc(long userId, Pageable pageable);
}
