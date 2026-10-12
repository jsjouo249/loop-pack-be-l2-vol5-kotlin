-- 기존 DB 전환용. 운영 DB에 자동 실행하지 않는다.
-- 좋아요·상품 쓰기를 중단하고 백업 및 기존 스키마를 확인한 뒤 한 번 실행한다.
-- DDL은 implicit commit이다. 아래 보정 transaction의 검증 이후 COMMIT/ROLLBACK을 직접 선택한다.
ALTER TABLE products ADD COLUMN like_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE products ADD CONSTRAINT ck_products_like_count CHECK (like_count >= 0);
CREATE INDEX idx_products_active_latest ON products (deleted_at, created_at DESC, id DESC);
CREATE INDEX idx_products_active_brand_latest ON products (deleted_at, brand_id, created_at DESC, id DESC);
CREATE INDEX idx_products_active_price ON products (deleted_at, price ASC, id DESC);
CREATE INDEX idx_products_active_brand_price ON products (deleted_at, brand_id, price ASC, id DESC);
CREATE INDEX idx_products_active_likes ON products (deleted_at, like_count DESC, id DESC);
CREATE INDEX idx_products_active_brand_likes ON products (deleted_at, brand_id, like_count DESC, id DESC);

START TRANSACTION;
-- 삭제 상품의 기존 관계도 보존하며 0건 상품도 정확히 보정한다. UPDATE 자체는 재실행 가능하다.
UPDATE products p
LEFT JOIN (SELECT product_id, COUNT(*) AS actual_count FROM product_likes GROUP BY product_id) l ON l.product_id = p.id
SET p.like_count = COALESCE(l.actual_count, 0);

-- 0이어야 한다. 관계·재고·가격·업무 수정 시각은 보정으로 바뀌지 않는다.
SELECT COUNT(*) AS mismatched_products
FROM products p
LEFT JOIN (SELECT product_id, COUNT(*) AS actual_count FROM product_likes GROUP BY product_id) l ON l.product_id = p.id
WHERE p.like_count <> COALESCE(l.actual_count, 0);

-- COMMIT;
-- ROLLBACK;
-- 검증/COMMIT 후 새 코드를 배포하고 쓰기를 재개한다.
-- 이전 unnamed 정렬 인덱스는 information_schema.statistics에서 실제 이름과 FK 사용 여부를
-- 확인한 후 별도로 제거한다. 이 스크립트는 알 수 없는 기존 인덱스를 임의로 삭제하지 않는다.
