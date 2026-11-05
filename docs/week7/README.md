# 7주차 실행·관측·복구

설계와 합의는 [backend-design.md](backend-design.md), 실제 검증 결과는 [verification-report.md](verification-report.md)를 따른다.

## 실행

JDK 21과 실행 중인 Docker 엔진이 필요하다. 테스트가 격리 MySQL 8.0·Redis와 실제 단일 KRaft broker를 소유한다.
Docker Desktop/Compose를 자동으로 시작하지 않으며 기존 개인 DB·broker를 정리하지 않는다.
Docker Desktop 소켓 자동 탐지가 실패하면 `DOCKER_HOST`에 `docker context inspect`의 실제 endpoint를 설정한다.
개인 credential helper가 시험 환경에서 동작하지 않으면 별도 임시 `DOCKER_CONFIG`에 빈 auths 설정을 사용한다.

```bash
./gradlew :apps:commerce-api:test --tests '*OutboxAtomicityTest' --tests '*OutboxDeliveryIntegrationTest'
./gradlew :apps:commerce-api:outboxE2e
./gradlew :modules:events:check :modules:kafka:check :apps:commerce-api:check :apps:commerce-streamer:check --console=plain
```

`outboxE2e`는 독립 test source set이다. streamer 의존성은 해당 테스트에만 있으며 API runtime에는 추가하지 않는다.
실제 PurchaseActivityConsumer/parser/service/repository와 production listener factory를 연결하고 topic/group은 실행마다 고유하다.
두 애플리케이션의 전체 component scan 대신 명시한 계층과 소비자 설정을 로드한다. API `check`에도 이 작업을 포함한다.
W6 baseline 실행물을 만들기 위해 저장소에 revision `4dfa06b`가 있어야 한다. 얕은 clone에서는 해당 revision을 먼저 확보한다.

로컬 relay는 기본 비활성이다. 기존 DB를 백업하고 W7 DDL을 적용한 뒤 **지정한 한 인스턴스만** 활성화한다.
다른 인스턴스는 `outbox.relay.enabled=false`로 실행한다. 프로세스 내 가드는 분산 실행을 막지 못한다.
기존 DB는 `ddl-auto=create`로 재시작하지 않는다.

```bash
./gradlew :apps:commerce-api:bootRun --args='--spring.jpa.hibernate.ddl-auto=validate --outbox.relay.enabled=true'
```

기본값: 최대 20건 직렬 처리, 회차 종료 후 1초, future 대기 12초, backoff 5초→10초→…→최대 5분.
`outbox.relay.batch-size`, `poll-delay-ms`, `send-timeout-ms`, `initial-backoff`, `maximum-backoff`로 조정한다.
broker 전송과 DB TX는 분리하며 late callback으로 DB를 변경하지 않는다.

## W6 재현과 복구

`prepareWeek6Fixture`는 실제 W6 소스를 git archive로 빌드하고 **테스트 실행물에만** 준비 fixture를 추가한다.
첫 사건은 실제 내부 확정 commit 후 publisher 호출 직전에 실패시키고, 둘째는 W6 실제 producer로 발행한다.
기존 API로 두 주문을 만들며 PG HTTP fixture가 외부 성공과 호출 횟수를 보존한다.
실제 소비자는 둘째 주문을 처리한다. 이때 Outbox 테이블은 아직 없고 첫 주문의 활동은 0이다.

W6 실행물만 종료하고 같은 MySQL·Kafka·PG 상태를 유지한다. W7 Outbox DDL을 적용하고 validate로 W7을 연결한다.
대상 주문의 저장 사실에서만 Outbox를 복구한다. 준비→복구 사이에 DB 초기화나 테스트 cleanup은 없다.
복구 전후 PG create·lookup·외부 거래, 확정 시각·품목·예약·재고·포인트를 비교하며 두 번째 복구도 효과를 늘리지 않는다.
이는 새 실행의 W6 상태 재현·복구이며 역사적 원본 DB 복원 실험이라고 부르지 않는다.

로컬 수동 작업은 지정 인스턴스의 자동 relay를 끄고 실행한다. 각 ID는 명시해야 하며 전체 주문 소급은 제공하지 않는다.
아래 ID는 예시이므로 실제 SQL 조회 결과로 바꾼다. relay는 nextAttemptAt 이전에는 실행하지 않는다.
명령 실행용 프로세스는 회차 실행 후 종료한다. 공개 HTTP 관리자 API는 없다.

```bash
./gradlew :apps:commerce-api:bootRun --args='--spring.profiles.active=local,outbox-local --server.port=0 --spring.jpa.hibernate.ddl-auto=validate --outbox.command.enabled=true --outbox.relay.enabled=false --restore-order-id=123 --relay-event-id=EVENT_UUID'
```

이미 Outbox가 있으면 내용 일치를 검증하며 PUBLISHED를 강제로 PENDING으로 되돌리지 않는다.
원본 사실 부족·payload 충돌은 오류다. 영구 오류는 PENDING 목록에 남고 호환 코드/설정 복구 뒤 같은 행으로 재개한다.

## 관측과 보존

```sql
SELECT COUNT(*) AS pending, MIN(created_at) AS oldest_created_at
FROM order_outbox WHERE status = 'PENDING';

SELECT event_id, order_id, status, attempt_count, next_attempt_at, last_error, created_at, published_at
FROM order_outbox WHERE status = 'PENDING' ORDER BY next_attempt_at, created_at, event_id LIMIT 20;

SELECT event_id, order_id, status, published_at FROM order_outbox WHERE order_id = 123;
SELECT * FROM purchase_receipts WHERE order_id = 123;
SELECT * FROM purchase_event_receipts WHERE order_id = 123;
SELECT * FROM purchase_activities WHERE order_id = 123 ORDER BY product_id;
```

`outbox broker_ack`는 broker 위치, `outbox published`는 상태 TX commit, `purchase_activity completed`는 소비 후 ack를 구분한다.
각각 eventId/orderId로 연결한다. 실패는 예외 종류만 저장·기록하며 카드·원문 payload·예외 메시지를 노출하지 않는다.
시도 시작에 횟수를 올리므로 attemptCount는 broker 수신 수가 아니다. PUBLISHED와 lag=0만으로 업무 완료를 단정하지 않는다.

이번에는 Outbox(미발행·발행 확인 모두)와 소비 receipt/alias/활동을 자동 삭제하지 않는다. 저장량은 계속 증가한다.
향후 보존 정책은 Outbox 재발행 기간·Kafka retention·dedup 보존을 함께 정해야 한다. dedup만 먼저 지우면 중복 효과가 가능하다.
다중 relay·다중 broker 장애 보장·CDC·업무 변경 이벤트의 순서 정책은 구현 범위 밖이다.
