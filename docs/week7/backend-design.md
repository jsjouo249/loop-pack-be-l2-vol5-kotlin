# 7주차 Transactional Outbox 설계

## 근거와 합의

- [기획서] W6 OrderConfirmed 계약·topic·group·소비 멱등성을 유지하고 after-commit 직접 발행을 교체한다.
- [사용자 결정] 별도 Outbox 도메인/저장 모델, PENDING/PUBLISHED, commerce-api 지정 인스턴스의 단일 relay를 사용한다.
- [사용자 결정] 최대 20건 직렬 처리, 회차 종료 후 1초, 재시도 5초부터 최대 5분, 자동 삭제 없음.
- [사용자 결정] W6 revision 실행물로 두 사례를 재현하고 명시한 두 주문만 복구한다. 공개 관리자 API는 만들지 않는다.
- [코드 확인] W6 기준은 59fafe1(통합 브랜치 jsjouo249), 기능 revision은 4dfa06b다. main에는 W5/W6 기반이 없으므로 현재 통합 HEAD에서 기능 브랜치를 만들었다.
- [코드 확인] payment.apply는 Order → PaymentAttempt → Reservation(productId순) → 필요 시 Product(ID순) 잠금 순서를 사용한다.
- [설계 결정] 기존 잠금 순서 아래 최초 SUCCESS에서 snapshot 직렬화와 Outbox insert를 함께 수행한다. 추가 DB lock은 도입하지 않는다.

## 원자성과 책임

PaymentApplicationService의 기존 로컬 TX 안에서 OrderConfirmedOutbox가 immutable JSON을 직렬화하고 저장한다.
Outbox 기록 서비스는 MANDATORY로 외부 TX를 요구하며 같은 데이터 소스·트랜잭션 매니저를 사용한다.
직렬화/insert/후속 저장 실패는 주문·시도·예약·Outbox를 모두 rollback한다. 이미 commit된 예약 확보나 외부 PG 성공은 되돌리지 않는다.
callback/reconcile로 같은 거래를 재조회하고 결과 적용만 다시 실행한다. POINT 전체 소급은 하지 않는다.

Outbox는 eventId PK, 주문별 사건 유일 제약, immutable eventType/schemaVersion/topic/key/payload/createdAt을 가진다.
domain은 전달 상태 전이를 소유하고 JPA 모델은 infrastructure에 둔다. 주문과 객체 연관·FK·cascade는 없다.
PENDING 조회는 (status, next_attempt_at, created_at, event_id) 인덱스와 같은 정렬을 사용한다.

## Relay와 실패

활성화 기본값은 false다. 지정된 단일 인스턴스에서만 명시적으로 켜며 수동 실행도 같은 프로세스 가드를 공유한다.
이 가드는 분산 잠금이 아니다. TX 밖에서 한 루프씩 실행하고 DB TX는 조회·시도 시작·결과 저장에만 사용한다.
시도 시작을 commit하면서 attemptCount와 다음 시각을 남긴다. 횟수는 실제 broker 수신 수가 아니다.
실패를 확인하면 실패 확인 시각부터의 backoff도 보장하도록 다음 시각을 뒤로 조정한다. 긴 전송 대기가 대기 간격을 소모하지 않는다.
저장된 JSON은 StringSerializer로 그대로 전송하여 이중 JSON 인코딩을 피한다. acks=all과 기존 단일 broker 제한을 유지한다.
전송 future 대기는 기본 12초, 늦은 완료 callback은 DB 상태를 변경하지 않는다.
명시 실패·timeout·상태 저장 실패는 PENDING과 같은 eventId를 보존한다. lastError에는 예외 종류만 남겨 민감한 메시지를 배제한다.
영구 오류도 지연 재시도하며 미해결 목록에 남는다. 최대 횟수로 완료 처리하지 않는다.
broker 성공 이후 PUBLISHED 저장 실패/실행 중단은 별도 record 재발행으로 이어질 수 있다.

## 복구와 보존

명시한 주문의 PG SUCCESS·CONFIRMED·CONSUMED 예약과 확정 시각/품목을 검증한 뒤 없는 Outbox만 추가한다.
이미 존재하면 identity와 내용 일치를 검증한다. 복구는 PG를 호출하거나 주문·예약을 변경하지 않는다.
동일 주문 잠금 아래 신규 확정과 복구를 직렬화한다. 복구도 위 잠금 순서를 지킨다.
로컬 전용 실행 profile에서 eventId로 PENDING 한 회차를 재개할 수 있다. PUBLISHED 강제 재전송/상태 덮어쓰기는 제공하지 않는다.

PENDING/PUBLISHED/consumer 완료는 별개다. SQL과 eventId/orderId 구조화 로그로 backlog·시도·결과를 확인한다.
이번 과제에서는 Outbox와 receipt/alias/활동 기록을 자동 삭제하지 않는다. Kafka retention 밖 복구에는 저장 Outbox가 필요하다.
향후 정리 정책은 재발행 가능 기간 동안 dedup 기록이 남도록 함께 결정해야 한다. 현재는 보존량 증가를 받아들인다.

## 검증과 제출

domain Red→Green, 실제 MySQL 로컬 원자성, 실제 broker/consumer 중단·재시작 E2E, W6 revision 재현→복구를 분리한다.
예외 hook은 완료된 단계 뒤에 두며 OS 강제 종료라고 표현하지 않는다. 재시작은 새 relay와 기존 DB·broker를 사용한다.
W6 준비와 복구 사이에 DB/PG/consumer를 정리하지 않는다. 복구 전 PG 호출 기준값을 기록하고 증가 0을 검증한다.
실제 offset과 품목별 수량까지 검증하며, 테스트 자원만 정리한다. 결과와 실행 건수는 verification-report.md에 기록한다.
