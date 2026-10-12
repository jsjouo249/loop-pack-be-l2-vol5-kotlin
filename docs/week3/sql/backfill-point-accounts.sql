-- 별도 전환 작업으로만 실행한다. 쓰기를 중단하고 누락 계정과 기존 잔액을 먼저 확인한다.
-- 실행자가 트랜잭션을 시작하고, 누락 계정 0건과 기존 잔액 보존을 확인한 뒤 COMMIT한다.
-- 오류가 나면 ROLLBACK한다. 애플리케이션 시작이나 HTTP 요청 중 자동 실행하지 않는다.
INSERT INTO point_balances (user_id, balance)
SELECT u.id, 0 FROM users u
LEFT JOIN point_balances p ON p.user_id = u.id
WHERE p.user_id IS NULL;
