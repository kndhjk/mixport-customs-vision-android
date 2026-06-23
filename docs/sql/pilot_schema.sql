CREATE TABLE customs_inspection_sessions (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    container_code VARCHAR(64) NOT NULL,
    vessel_name VARCHAR(128) NOT NULL,
    operator_name VARCHAR(128) NOT NULL,
    notes TEXT NOT NULL,
    status ENUM('ACTIVE', 'READY_TO_COMPLETE', 'COMPLETED', 'PAUSED') NOT NULL DEFAULT 'ACTIVE',
    started_at DATETIME NOT NULL,
    ended_at DATETIME NULL,
    recording_uri TEXT NULL,
    source_device_id VARCHAR(128) NULL,
    container_has_remaining_cargo TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_customs_sessions_container_code (container_code),
    KEY idx_customs_sessions_started_at (started_at)
);

CREATE TABLE customs_inspection_pallets (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT UNSIGNED NOT NULL,
    sequence_number INT NOT NULL,
    status ENUM('LOADING', 'SEALED') NOT NULL DEFAULT 'LOADING',
    started_at DATETIME NOT NULL,
    closed_at DATETIME NULL,
    wrap_detected TINYINT(1) NOT NULL DEFAULT 0,
    container_empty_at_close TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_customs_pallets_session
        FOREIGN KEY (session_id) REFERENCES customs_inspection_sessions(id)
        ON DELETE CASCADE,
    UNIQUE KEY uq_customs_pallets_session_sequence (session_id, sequence_number)
);

CREATE TABLE customs_inspection_pallet_items (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    pallet_id BIGINT UNSIGNED NOT NULL,
    item_label VARCHAR(191) NOT NULL,
    color_name VARCHAR(64) NOT NULL,
    marker_text VARCHAR(128) NOT NULL DEFAULT '',
    quantity INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_customs_items_pallet
        FOREIGN KEY (pallet_id) REFERENCES customs_inspection_pallets(id)
        ON DELETE CASCADE,
    UNIQUE KEY uq_customs_items_identity (pallet_id, item_label, color_name, marker_text)
);

CREATE TABLE customs_inspection_events (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT UNSIGNED NOT NULL,
    pallet_id BIGINT UNSIGNED NULL,
    event_type VARCHAR(64) NOT NULL,
    message VARCHAR(255) NOT NULL,
    payload_json JSON NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT fk_customs_events_session
        FOREIGN KEY (session_id) REFERENCES customs_inspection_sessions(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_customs_events_pallet
        FOREIGN KEY (pallet_id) REFERENCES customs_inspection_pallets(id)
        ON DELETE SET NULL,
    KEY idx_customs_events_session_created (session_id, created_at)
);
