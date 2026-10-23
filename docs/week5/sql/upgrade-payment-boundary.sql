-- 기존 4주차 DB 전환용. 자동 실행하지 않는다.
-- 쓰기를 중지하고 백업/스키마/기존 CONFIRMED=POINT임을 확인한 뒤 1회 실행한다.
-- MySQL DDL은 implicit commit이므로 rollback으로 스키마 변경을 취소할 수 없다.
ALTER TABLE orders ADD COLUMN payment_method VARCHAR(20) NULL;
CREATE TABLE payment_attempts (
    order_id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    attempt_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount BIGINT NOT NULL,
    provider_order_id VARCHAR(64) NOT NULL,
    provider_account VARCHAR(20) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    status VARCHAR(20) NOT NULL,
    transaction_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    quarantined BOOLEAN NOT NULL,
    last_check VARCHAR(64) NULL,
    CONSTRAINT uk_payment_attempt_key UNIQUE (attempt_key),
    CONSTRAINT uk_payment_provider_order UNIQUE (provider_account, provider_order_id),
    CONSTRAINT uk_payment_transaction UNIQUE (provider_account, transaction_key),
    CONSTRAINT ck_payment_amount CHECK (amount >= 0),
    CONSTRAINT ck_payment_status CHECK (status IN ('REQUESTED','PENDING','UNKNOWN','SUCCESS','FAILED')),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE RESTRICT
) DEFAULT CHARACTER SET utf8mb4;
CREATE TABLE stock_reservations (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    CONSTRAINT uk_reservation_product UNIQUE (order_id, product_id),
    CONSTRAINT ck_reservation_quantity CHECK (quantity > 0),
    CONSTRAINT ck_reservation_status CHECK (status IN ('RESERVED','CONSUMED','RELEASED')),
    CONSTRAINT fk_reservation_attempt FOREIGN KEY (order_id) REFERENCES payment_attempts(order_id) ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE RESTRICT
) DEFAULT CHARACTER SET utf8mb4;
CREATE TABLE payment_issues (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    reason VARCHAR(64) NOT NULL,
    observed_transaction_key VARCHAR(128) NULL,
    observed_order_id VARCHAR(128) NULL,
    observed_amount BIGINT NULL,
    observed_status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_payment_issue FOREIGN KEY (order_id) REFERENCES payment_attempts(order_id) ON DELETE RESTRICT
) DEFAULT CHARACTER SET utf8mb4;
