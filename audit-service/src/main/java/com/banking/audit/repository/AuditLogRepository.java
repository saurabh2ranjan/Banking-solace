package com.banking.audit.repository;

import com.banking.audit.model.AuditLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends MongoRepository<AuditLog, String> {

    List<AuditLog> findByEventType(String eventType);

    List<AuditLog> findBySourceService(String sourceService);

    List<AuditLog> findByTopic(String topic);

    List<AuditLog> findBySeverity(String severity);

    List<AuditLog> findByReceivedAtBetween(LocalDateTime start, LocalDateTime end);

    List<AuditLog> findByEventTypeAndReceivedAtBetween(
            String eventType, LocalDateTime start, LocalDateTime end);

    long countByEventType(String eventType);

    List<AuditLog> findTop50ByOrderByReceivedAtDesc();
}
