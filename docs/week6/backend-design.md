# 6주차 주문 확정 이벤트와 구매 활동

## 근거와 범위

- [기획서] PG 주문 최초 확정을 commit한 뒤 Kafka로 직접 발행한다. key는 orderId이며 품목을 한 이벤트에 포함한다.
- [기획서] DB 효과 뒤 offset commit, 중복 전달 허용, 구매 효과 한 번. 충돌 payload는 실패한다.
- [기획서] Outbox, 주간 랭킹 계산, 결제·재고의 재수행은 범위 밖이다.
- [사용자 결정] 공유한 본문을 기준으로 코드·테스트·실험까지 구현한다.
- [코드 확인] confirm/callback/reconcile은 PaymentApplicationService.apply의 최초 SUCCESS 전이에 모인다.
- [코드 확인] PG·재고의 기존 잠금 순서와 HTTP 계약은 유지한다. 과거 POINT 주문을 재발행하지 않는다.

## 이벤트와 발행

- [설계 결정] 공통 modules:events에 전송 기술을 모르는 OrderConfirmed v1 계약을 둔다.
  필드는 eventId(UUID), orderId(양수), confirmedAt(최초 확정 Instant), items(productId, 양수 quantity), schemaVersion=1이다.
  중복 상품·빈 품목·잘못된 UUID·미지원 버전을 거절한다. 소비 시 record key와 orderId도 일치해야 한다.
- [설계 결정] 주문당 최초 확정은 한 번이므로 고정 namespace와 orderId로 eventId를 결정한다.
  payload·발송 시각·schemaVersion을 ID 생성에 사용하지 않는다. 확정 시각은 기존 orders.confirmed_at에 보존한다.
  PG 최초 확정 시각은 MySQL 정밀도인 microsecond로 맞춰 최초 이벤트와 DB 재조회 시각을 일치시킨다.
- [설계 결정] 최초 SUCCESS 로컬 TX 안에서 immutable snapshot을 application event로 발행한다.
  AFTER_COMMIT listener가 Kafka 전송 결과를 기다린다. rollback·종결 no-op은 전송하지 않는다.
- [설계 결정] topic은 order-confirmed-v1, partition=2(초기 생성), 로컬 replication=1/min ISR=1,
  producer acks=all, enable.idempotence=true, retries=3, max.in.flight=5로 호환 조건을 명시한다.
  KafkaAdmin은 기존 topic이 2개 미만이면 늘릴 수 있으므로 배포 전 key 매핑 영향을 확인한다.
  다중 broker 장애 보장을 주장하지 않는다.
- [설계 결정] 발행 실패는 주문을 실패시키거나 PG/DB 유스케이스를 재시도하지 않는다.
  eventId/orderId와 오류를 로그·counter로 노출한다. timeout은 발행 결과 불명일 수 있다.
  broker 확인 성공 로그에 topic/partition/offset을 남긴다. 이 값은 업무 완료가 아니다.
  직접 after-commit 발행이므로 요청 지연과 commit→발행 누락 구간은 남는다. 자동 누락 복구는 이번 범위 밖이다.

## 소비와 저장

- [설계 결정] commerce-streamer의 전용 record listener, group=purchase-activity-v1, concurrency=2.
  auto commit=false, MANUAL_IMMEDIATE + sync commit. DB 서비스 반환은 TX commit 이후이며 그 뒤 ack한다.
- [설계 결정] 예외는 listener 밖으로 전파하고 CommonContainerStoppingErrorHandler로 컨테이너를 중단한다.
  실패 record를 recover/skip/DLQ로 넘기지 않는다. 원인 해결 뒤 프로세스 재시작 시 미완료 위치부터 재처리한다.
- [설계 결정] purchase_receipts(order_id PK, fingerprint, confirmed_at),
  purchase_event_receipts(event_id PK, order_id), purchase_activities(order_id+product_id PK, quantity, confirmed_at)를 사용한다.
  receipt·event alias·모든 품목은 한 TX에 저장한다. 다른 Aggregate인 주문·상품과 객체 연관/FK는 없다.
  과거 활동은 상품 삭제나 현재 상품 정보와 무관한 snapshot이다. 원본 주문/상품을 소비 시 조회하지 않는다.
- [설계 결정] fingerprint는 schemaVersion/orderId/confirmedAt와 productId순 품목의 canonical SHA-256이다.
  eventId는 제외한다. 같은 orderId+같은 사실의 다른 eventId는 alias만 추가하며 활동을 늘리지 않는다.
  이미 수락한 alias도 영속 저장하여 나중에 다른 주문에 그 eventId를 재사용하면 실패한다.
  같은 eventId의 다른 주문, 같은 orderId의 다른 사실은 계약 충돌이다.
- [설계 결정] 사전 조회 외에 PK와 양수 CHECK로 경쟁·무결성을 보호한다. 명시적 DB lock은 추가하지 않는다.
  unique 경합 예외는 반드시 TX 밖에서 받고 rollback 이후 새 TX로 재조회한다.
  경쟁 삽입은 최대 3번의 독립 시도로 처리하고, 일치 여부 확인 없이 성공으로 삼키지 않는다.
  장애·CHECK 위반은 성공으로 취급하지 않는다. 이미 완료된 동일 사실만 no-op이다.

## 검증과 실험

- domain은 Spring/DB 없이 계약·동일 사실 판단을 검증한다.
- producer는 실제 TX commit/rollback, 최초 SUCCESS, 재콜백/reconcile, broker 오류의 결제 비전파를 검증한다.
- 소비 통합 검사는 MySQL에서 flush/clear 후 재조회, 여러 품목 원자성, 동시 중복, 충돌/alias를 검증한다.
- 실제 Kafka broker에서 producer 결과의 위치 n을 얻고 DB 저장 뒤 미commit 종료 → 같은 group 재전달 → n+1 commit을 확인한다.
- partition 2개와 consumer 1/2/3개, 별도 group의 실제 assignment를 유한 시간 안에 기다리고 확인한다.
- 실패 record 이후 committed offset이 진행하지 않는지 실제 listener/error handler로 검증한다.
- 단일 테스트 broker는 실제 Kafka 로그/offset 실험이며 다중 broker·OS 강제 종료·네트워크 분할 실험이 아니다.

## 공식 근거

- [Spring transaction-bound events](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html)
- [Spring Kafka error handling](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)
- [KafkaConsumer position/commit/close](https://kafka.apache.org/35/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html)
- [Producer settings](https://kafka.apache.org/35/generated/producer_config.html)
