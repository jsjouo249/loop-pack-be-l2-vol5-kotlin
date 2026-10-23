-- 스키마 전환 후 쓰기 중지 상태에서 실행. NULL인 과거 확정 주문만 보정하며 재실행 가능하다.
UPDATE orders SET payment_method = 'POINT' WHERE status = 'CONFIRMED' AND payment_method IS NULL;
