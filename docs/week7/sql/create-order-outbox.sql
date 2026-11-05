-- Apply once to the existing W6 DB; never restart that DB with ddl-auto=create.
CREATE TABLE order_outbox (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    schema_version INT NOT NULL,
    topic VARCHAR(255) NOT NULL,
    message_key VARCHAR(64) NOT NULL,
    payload LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    last_error VARCHAR(128),
    published_at DATETIME(6),
    CONSTRAINT uk_order_outbox_fact UNIQUE (order_id, event_type),
    INDEX ix_order_outbox_due (status, next_attempt_at, created_at, event_id),
    CONSTRAINT chk_order_outbox CHECK (
        order_id > 0 AND schema_version > 0 AND attempt_count >= 0 AND
        ((status = 'PENDING' AND published_at IS NULL) OR (status = 'PUBLISHED' AND published_at IS NOT NULL))
    )
);
