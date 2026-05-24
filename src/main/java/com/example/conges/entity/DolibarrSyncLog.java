package com.example.conges.entity;

import java.time.LocalDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "dolibarr_sync_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DolibarrSyncLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String entityType;

    @Column(length = 50)
    private String operation;

    private Long localEntityId;

    private Long remoteEntityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncStatus status;

    @Column(length = 1000)
    private String message;

    @Column(name = "payload", columnDefinition = "LONGTEXT")
    private String payload;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (entityType == null || entityType.isBlank()) entityType = "UNKNOWN";
        if (direction == null) direction = SyncDirection.INBOUND;
        if (status == null) status = SyncStatus.FAILED;
    }
}
