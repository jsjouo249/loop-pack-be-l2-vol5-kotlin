-- Apply to the consumer DB. Do not restart an existing DB with ddl-auto=create.
CREATE TABLE purchase_receipts (
    order_id BIGINT NOT NULL PRIMARY KEY,
    fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    confirmed_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT chk_purchase_receipt_order CHECK (order_id > 0)
);

CREATE TABLE purchase_event_receipts (
    event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    CONSTRAINT chk_purchase_event_order CHECK (order_id > 0)
);

CREATE TABLE purchase_activities (
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    confirmed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (order_id, product_id),
    INDEX idx_purchase_activity_time_product (confirmed_at, product_id),
    CONSTRAINT chk_purchase_activity_positive CHECK (order_id > 0 AND product_id > 0 AND quantity > 0)
);
