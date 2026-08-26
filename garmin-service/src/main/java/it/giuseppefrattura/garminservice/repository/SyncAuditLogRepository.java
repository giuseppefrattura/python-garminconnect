package it.giuseppefrattura.garminservice.repository;

import it.giuseppefrattura.garminservice.model.SyncAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SyncAuditLogRepository extends JpaRepository<SyncAuditLog, Long> {
    Optional<SyncAuditLog> findFirstByOrderByStartedAtDesc();
}
