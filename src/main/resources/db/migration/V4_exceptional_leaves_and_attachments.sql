-- Congés exceptionnels: rattachement d'une demande à une config RH
ALTER TABLE demandes_conge
    ADD COLUMN exceptional_leave_config_id BIGINT NULL;

CREATE INDEX idx_demandes_conge_exceptional_cfg
    ON demandes_conge (exceptional_leave_config_id);

-- Pièces jointes (justificatifs) pour les demandes (toutes demandes, y compris exceptionnelles)
CREATE TABLE IF NOT EXISTS demande_attachments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    demande_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(120),
    size_bytes BIGINT NOT NULL DEFAULT 0,
    content LONGBLOB NOT NULL,
    uploaded_at DATETIME NOT NULL,
    INDEX idx_demande_attach_demande (demande_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- Fix: dolibarr_allocation_id doit être nullable (allocations manuelles RH sans ID Dolibarr)
ALTER TABLE employee_leave_allocations 
    MODIFY COLUMN dolibarr_allocation_id BIGINT NULL;
