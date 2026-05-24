-- ============================================================
-- Script de création complète de la base de données
-- Gestion des Congés - Spring Boot Backend
-- Compatible avec toutes les entités JPA de l'application
-- ============================================================

SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS conges_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE conges_db;

-- ============================================================
-- TABLE : users (UserEntity)
-- ============================================================
CREATE TABLE IF NOT EXISTS users (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    dolibarr_id         BIGINT UNIQUE,
    email               VARCHAR(255) NOT NULL UNIQUE,
    nom                 VARCHAR(255),
    prenom              VARCHAR(255),
    role                VARCHAR(32)  NOT NULL COMMENT 'EMPLOYE|RH|MANAGER|ADMIN',
    pays                VARCHAR(100),
    country_code        VARCHAR(8),
    weekly_hours        DECIMAL(5,2),
    annual_work_days    INT,
    contract_type       VARCHAR(48),
    contract_active     BOOLEAN NOT NULL DEFAULT TRUE,
    hire_date           DATE,
    departement         VARCHAR(255),
    password_hash       VARCHAR(255) COMMENT 'BCrypt - mode standalone sans Dolibarr',
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_users_email (email),
    INDEX idx_users_role  (role),
    INDEX idx_users_pays  (pays)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLE : leave_types (LeaveType)
-- ============================================================
CREATE TABLE IF NOT EXISTS leave_types (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    dolibarr_leave_type_id  BIGINT UNIQUE,
    code                    VARCHAR(50)  NOT NULL,
    libelle                 VARCHAR(255) NOT NULL,
    description             TEXT,
    couleur                 VARCHAR(50),
    active                  BOOLEAN NOT NULL DEFAULT TRUE,
    requires_approval       BOOLEAN NOT NULL DEFAULT TRUE,
    delai                   BIGINT NOT NULL DEFAULT 0,
    sync_status             INT DEFAULT 1,
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLE : holidays (Holiday)
-- ============================================================
CREATE TABLE IF NOT EXISTS holidays (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    dolibarr_holiday_id BIGINT UNIQUE,
    libelle             VARCHAR(255) NOT NULL,
    date_jour           DATE         NOT NULL,
    duree               DOUBLE       NOT NULL DEFAULT 1.0,
    id_pays             BIGINT,
    country_code        VARCHAR(10),
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_holidays_country_date (country_code, date_jour)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLE : employee_leave_allocations (EmployeeLeaveAllocation)
-- ============================================================
CREATE TABLE IF NOT EXISTS employee_leave_allocations (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id                 BIGINT NOT NULL,
    leave_type_id           BIGINT NOT NULL,
    dolibarr_allocation_id  BIGINT UNIQUE,
    jours_initiaux          DOUBLE NOT NULL DEFAULT 0,
    jours_utilises          DOUBLE NOT NULL DEFAULT 0,
    jours_disponibles       DOUBLE NOT NULL DEFAULT 0,
    annee                   INT    NOT NULL,
    date_debut              DATE   NOT NULL,
    date_fin                DATE   NOT NULL,
    active                  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id)       REFERENCES users(id)       ON DELETE CASCADE,
    FOREIGN KEY (leave_type_id) REFERENCES leave_types(id) ON DELETE RESTRICT,
    INDEX idx_ela_user_year (user_id, annee)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLE : demandes_conge (DemandeConge)
-- ============================================================
CREATE TABLE IF NOT EXISTS demandes_conge (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id                     BIGINT  NOT NULL,
    approved_by_admin_id        BIGINT,
    type_conge                  VARCHAR(32) NOT NULL COMMENT 'PAYE|MALADIE|SANS_SOLDE|COURTE_DUREE|EXCEPTIONNEL',
    exceptional_leave_config_id BIGINT,
    date_debut                  DATE    NOT NULL,
    date_fin                    DATE    NOT NULL,
    nombre_jours                INT     NOT NULL DEFAULT 0,
    nombre_jours_exact          DOUBLE,
    start_half_day              VARCHAR(16) COMMENT 'MORNING|AFTERNOON',
    end_half_day                VARCHAR(16) COMMENT 'MORNING|AFTERNOON',
    statut                      VARCHAR(32) NOT NULL DEFAULT 'EN_ATTENTE' COMMENT 'EN_ATTENTE|ACCEPTE|REFUSE|ANNULE',
    motif                       VARCHAR(2000) NOT NULL DEFAULT '',
    commentaire_rh              VARCHAR(2000),
    date_soumission             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    date_traitement             DATETIME,
    workflow_code               VARCHAR(100),
    current_step_order          INT,
    current_step_type           VARCHAR(50),
    dolibarr_leave_request_id   BIGINT,
    permission_heure_debut      TIME,
    permission_heure_fin        TIME,
    duree_permission_minutes    INT,
    piece_jointe_nom            VARCHAR(512),
    FOREIGN KEY (user_id)             REFERENCES users(id) ON DELETE RESTRICT,
    FOREIGN KEY (approved_by_admin_id) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_dc_user_statut (user_id, statut),
    INDEX idx_dc_dates        (date_debut, date_fin),
    INDEX idx_dc_statut       (statut)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLE : demande_attachments (DemandeAttachment)
-- ============================================================
CREATE TABLE IF NOT EXISTS demande_attachments (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    demande_id   BIGINT NOT NULL,
    filename     VARCHAR(255) NOT NULL,
    content_type VARCHAR(120),
    size_bytes   BIGINT NOT NULL DEFAULT 0,
    content      LONGBLOB NOT NULL,
    uploaded_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (demande_id) REFERENCES demandes_conge(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLE : workflow_definitions (WorkflowDefinition)
-- ============================================================
CREATE TABLE IF NOT EXISTS workflow_definitions (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    code         VARCHAR(100) NOT NULL UNIQUE,
    country_code VARCHAR(10)  NOT NULL,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLE : workflow_steps (WorkflowStep)
-- ============================================================
CREATE TABLE IF NOT EXISTS workflow_steps (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    workflow_definition_id  BIGINT NOT NULL,
    step_order              INT    NOT NULL,
    step_type               VARCHAR(50) NOT NULL COMMENT 'MANAGER_APPROVAL|RH_APPROVAL|ADMIN_APPROVAL',
    approver_role           VARCHAR(20) NOT NULL COMMENT 'EMPLOYE|RH|MANAGER|ADMIN',
    required                BOOLEAN NOT NULL DEFAULT TRUE,
    min_days                INT,
    max_days                INT,
    applicable_leave_types  VARCHAR(255) COMMENT 'Comma-separated TypeConge values',
    FOREIGN KEY (workflow_definition_id) REFERENCES workflow_definitions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLE : demande_approvals (DemandeApproval)
-- ============================================================
CREATE TABLE IF NOT EXISTS demande_approvals (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    demande_id    BIGINT NOT NULL,
    step_order    INT    NOT NULL,
    step_type     VARCHAR(50) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    decision      VARCHAR(20) NOT NULL COMMENT 'APPROVED|REJECTED',
    comment       VARCHAR(1000),
    decision_date DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (demande_id)    REFERENCES demandes_conge(id) ON DELETE CASCADE,
    FOREIGN KEY (actor_user_id) REFERENCES users(id)          ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLE : history (History)
-- ============================================================
CREATE TABLE IF NOT EXISTS history (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    user_nom    VARCHAR(150),
    user_prenom VARCHAR(150),
    user_email  VARCHAR(255),
    demande_id  BIGINT,
    action_type VARCHAR(50) NOT NULL COMMENT 'CREATE|APPROVE|REJECT|CANCEL|...',
    description VARCHAR(500),
    details     LONGTEXT COMMENT 'JSON',
    pays        VARCHAR(50),
    statut      VARCHAR(50),
    action_date DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address  VARCHAR(45),
    user_agent  VARCHAR(500),
    INDEX idx_user       (user_id),
    INDEX idx_demande    (demande_id),
    INDEX idx_action_type(action_type),
    INDEX idx_action_date(action_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLE : country_leave_policies (CountryLeavePolicy)
-- ============================================================
CREATE TABLE IF NOT EXISTS country_leave_policies (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    country_code         VARCHAR(10) NOT NULL,
    type_conge           VARCHAR(32) NOT NULL,
    annual_quota         INT NOT NULL DEFAULT 0,
    monthly_accrual_rate DOUBLE,
    rtt_enabled          BOOLEAN NOT NULL DEFAULT FALSE,
    rtt_annual_days      INT,
    UNIQUE KEY uk_clp_country_type (country_code, type_conge)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLE : exceptional_leave_configs (ExceptionalLeaveConfig)
-- ============================================================
CREATE TABLE IF NOT EXISTS exceptional_leave_configs (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    country_code VARCHAR(10)  NOT NULL,
    label        VARCHAR(120) NOT NULL,
    days_per_year INT NOT NULL DEFAULT 0,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLE : work_schedule_settings (WorkScheduleSetting)
-- ============================================================
CREATE TABLE IF NOT EXISTS work_schedule_settings (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    country_code     VARCHAR(10) NOT NULL,
    active_type      VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL|SUMMER|RAMADAN',
    normal_enabled   BOOLEAN NOT NULL DEFAULT TRUE,
    summer_enabled   BOOLEAN NOT NULL DEFAULT FALSE,
    ramadan_enabled  BOOLEAN NOT NULL DEFAULT FALSE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLE : work_schedule_days (WorkScheduleDay)
-- ============================================================
CREATE TABLE IF NOT EXISTS work_schedule_days (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    country_code  VARCHAR(10) NOT NULL,
    schedule_type VARCHAR(16) NOT NULL COMMENT 'NORMAL|SUMMER|RAMADAN',
    day_of_week   INT NOT NULL COMMENT '1=lundi ... 7=dimanche (DayOfWeek Java)',
    first_start   TIME,
    first_end     TIME,
    second_start  TIME,
    second_end    TIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLE : france_rtt_settings (FranceRttSettings)
-- ============================================================
CREATE TABLE IF NOT EXISTS france_rtt_settings (
    id                  BIGINT PRIMARY KEY DEFAULT 1,
    accrual_mode        VARCHAR(32) NOT NULL DEFAULT 'MONTHLY' COMMENT 'ANNUAL_JAN1|MONTHLY|CONTRACT_HOURS',
    admin_override_days INT,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLE : employee_france_rtt_balance (EmployeeFranceRttBalance)
-- ============================================================
CREATE TABLE IF NOT EXISTS employee_france_rtt_balance (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    calendar_year   INT    NOT NULL,
    rtt_total       DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    rtt_used        DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    rtt_remaining   DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    last_rtt_update DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_user_year (user_id, calendar_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLE : dolibarr_sync_logs (DolibarrSyncLog)
-- ============================================================
CREATE TABLE IF NOT EXISTS dolibarr_sync_logs (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity_type      VARCHAR(50) NOT NULL,
    operation        VARCHAR(50),
    local_entity_id  BIGINT,
    remote_entity_id BIGINT,
    direction        VARCHAR(20) NOT NULL COMMENT 'INBOUND|OUTBOUND',
    status           VARCHAR(20) NOT NULL COMMENT 'SUCCESS|FAILED',
    message          VARCHAR(1000),
    payload          LONGTEXT,
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_sync_entity (entity_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- DONNÉES PAR DÉFAUT
-- ============================================================

-- Types de congés standards
INSERT IGNORE INTO leave_types (dolibarr_leave_type_id, code, libelle, description, active, requires_approval, delai, sync_status, created_at, updated_at) VALUES
(-1, 'CP',  'Congés Payés',      'Congés annuels payés',             TRUE, TRUE, 0, 1, NOW(), NOW()),
(-2, 'RTT', 'RTT',               'Réduction du Temps de Travail',    TRUE, TRUE, 0, 1, NOW(), NOW()),
(-3, 'MAL', 'Congé Maladie',     'Congé pour raison médicale',       TRUE, TRUE, 0, 1, NOW(), NOW()),
(-4, 'SS',  'Congé Sans Solde',  'Congé non rémunéré',               TRUE, FALSE,0, 1, NOW(), NOW()),
(-5, 'EXC', 'Congé Exceptionnel','Évènement familial exceptionnel',  TRUE, TRUE, 0, 1, NOW(), NOW());

-- Politiques de congés par pays
INSERT IGNORE INTO country_leave_policies (country_code, type_conge, annual_quota, monthly_accrual_rate, rtt_enabled, rtt_annual_days) VALUES
('TN', 'PAYE',        22,  1.83, FALSE, NULL),
('TN', 'MALADIE',      7,  NULL, FALSE, NULL),
('TN', 'SANS_SOLDE',   0,  NULL, FALSE, NULL),
('TN', 'COURTE_DUREE', 0,  NULL, FALSE, NULL),
('FR', 'PAYE',        25,  2.08, TRUE,  NULL),
('FR', 'COURTE_DUREE', 9,  0.75, TRUE,  9),
('FR', 'MALADIE',      0,  NULL, TRUE,  NULL),
('FR', 'SANS_SOLDE',   0,  NULL, TRUE,  NULL),
('MA', 'PAYE',        18,  1.50, FALSE, NULL),
('MA', 'MALADIE',      7,  NULL, FALSE, NULL),
('MA', 'SANS_SOLDE',   0,  NULL, FALSE, NULL),
('MA', 'COURTE_DUREE', 0,  NULL, FALSE, NULL);

-- Paramètre RTT France (singleton)
INSERT IGNORE INTO france_rtt_settings (id, accrual_mode, admin_override_days, updated_at) VALUES
(1, 'MONTHLY', NULL, NOW());

-- Utilisateurs par défaut (mots de passe hashés BCrypt pour "admin123", "rh123", etc.)
-- admin123  = $2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
-- rh123     = $2a$10$8.IXcbS5aPKhcqMwXSz7CeYsHpYh.aOBlPcCjV1Yk4Y/p/Bg/ZXwm
-- employe123= $2a$10$wnkPc1y6kRbEZW9KU99mFONQ/lBW1X37hYpsTz7mU9SaqEkx1.1qO
INSERT IGNORE INTO users (email, nom, prenom, role, pays, password_hash, contract_active, contract_type, annual_work_days, weekly_hours, hire_date, created_at, updated_at) VALUES
('admin@conges.local',    'Admin',    'Super',      'ADMIN',   'TN', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', TRUE, 'CDI', 218, 40.0, '2023-01-01', NOW(), NOW()),
('rh@conges.local',       'RH',       'Gestionnaire','RH',     'TN', '$2a$10$8.IXcbS5aPKhcqMwXSz7CeYsHpYh.aOBlPcCjV1Yk4Y/p/Bg/ZXwm', TRUE, 'CDI', 218, 40.0, '2023-01-01', NOW(), NOW()),
('employe@conges.local',  'Dupont',   'Jean',       'EMPLOYE', 'TN', '$2a$10$wnkPc1y6kRbEZW9KU99mFONQ/lBW1X37hYpsTz7mU9SaqEkx1.1qO', TRUE, 'CDI', 218, 40.0, '2023-01-01', NOW(), NOW()),
('employe.fr@conges.local','Martin',  'Sophie',     'EMPLOYE', 'FR', '$2a$10$wnkPc1y6kRbEZW9KU99mFONQ/lBW1X37hYpsTz7mU9SaqEkx1.1qO', TRUE, 'CDI', 218, 35.0, '2023-01-01', NOW(), NOW()),
('employe.ma@conges.local','Benali',  'Youssef',    'EMPLOYE', 'MA', '$2a$10$wnkPc1y6kRbEZW9KU99mFONQ/lBW1X37hYpsTz7mU9SaqEkx1.1qO', TRUE, 'CDI', 218, 40.0, '2023-01-01', NOW(), NOW());

-- ============================================================
-- NOTE : Les allocations de congés par défaut sont créées
-- automatiquement par DataInitializer.java au démarrage.
-- ============================================================
