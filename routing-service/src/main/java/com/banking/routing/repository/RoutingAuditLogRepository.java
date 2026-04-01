package com.banking.routing.repository;

import com.banking.routing.entity.RoutingAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoutingAuditLogRepository extends JpaRepository<RoutingAuditLog, Long> {

    List<RoutingAuditLog> findByEventTypeOrderByChangedAtDesc(String eventType);

    List<RoutingAuditLog> findAllByOrderByChangedAtDesc();
}
