# 6주차 실행과 관측

계약·실패 정책은 [설계](backend-design.md), 범위는 [체크리스트](implementation-checklist.md)를 따른다.
Outbox·랭킹 계산·누락 자동 복구는 구현하지 않는다.

## 로컬 실행

JDK 21과 사용자가 실행한 Docker 엔진을 사용한다. 기존 운영/개인 DB는 테스트에 사용하지 않는다.
기본 JPA local/test는 ddl-auto=create이므로 기존 DB를 해당 profile로 재시작하면 안 된다.
기존 DB에는 [DDL](sql/create-purchase-activities.sql)을 검토·적용하고 ddl-auto=validate를 사용한다.
새 로컬 DB로 실습할 때는 먼저 인프라와 기존 주차의 API 스키마를 준비하고 구매 활동 DDL을 적용한다.
API 스키마를 자동 생성할 경우에도 데이터가 없는 새 DB에서만 create를 1회 사용한다.
이후 재시작은 아래와 같이 validate로 실행한다. 주문 ID가 초기화되면 Kafka에 남은 과거 주문 ID와 충돌할 수 있다.

```bash
docker compose -f docker/infra-compose.yml up -d mysql redis-master redis-readonly kafka kafka-ui
docker exec kafka /opt/bitnami/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists --topic order-confirmed-v1 --partitions 2 --replication-factor 1 --config min.insync.replicas=1
./gradlew :apps:commerce-api:bootRun --args='--spring.jpa.hibernate.ddl-auto=validate'
./gradlew :apps:commerce-streamer:bootRun --args='--server.port=8081 --spring.jpa.hibernate.ddl-auto=validate'
```

KafkaAdmin은 API/streamer 양쪽에서 topic 선언을 확인한다. 기존 topic이 2개 미만이면 partition을 2개로 늘릴 수 있으므로
기존 topic의 key 매핑과 순서 영향은 배포 전에 확인한다. 수를 줄이지는 않는다.
새 consumer 테이블이 아직 없다면 SQL을 먼저 적용해야 한다. API와 streamer가 같은 DB를 사용해도
streamer의 local 기본값도 validate이며 test에서만 create를 사용한다.

## 자동 검증과 실제 broker 실험

```bash
./gradlew :modules:events:check :modules:kafka:check :apps:commerce-api:check :apps:commerce-streamer:check --console=plain
./gradlew :apps:commerce-streamer:test --tests '*KafkaAssignmentExperimentTest' --rerun-tasks --console=plain
./gradlew :apps:commerce-streamer:test --tests '*PurchaseActivityIntegrationTest' --rerun-tasks --console=plain
```

EmbeddedKafkaKraftBroker는 mock이 아닌 실제 단일 Kafka broker다. 임시 port/topic/group을 쓰며 Docker Kafka를 요구하지 않는다.
MySQL 통합 검사와 기존 회귀 검사는 Testcontainers로 격리된 DB를 만들므로 실행 중인 Docker 엔진이 필요하다.
실험 출력은 각 Gradle test-results/test XML의 system-out에서 WEEK6_ 접두사로 찾는다.

- WEEK6_PRODUCER: broker가 기록한 실제 topic/partition/offset/key. 소비 완료는 아니다.
- WEEK6_ASSIGNMENT: partition 2개에 consumer 1→2→3개가 참여한 실제 배정. 연속 3회 조건을 확인하고 유한 시간에 실패한다.
- WEEK6_INDEPENDENT_GROUP: 별도 group이 동일 partition 둘을 독립적으로 배정받는다.
- WEEK6_REDELIVERY: 같은 record n을 DB 저장 후 미commit 종료, 같은 group의 새 consumer가 재수신한다.
  활동 수는 유지되고 완료 뒤 committed offset은 n+1이다. position과 committed를 별도로 출력한다.
- WEEK6_FAILURE: 실제 listener의 DB 오류 뒤 컨테이너 중단과 실패 offset 미진행을 확인한다.
  DB 오류 제거 뒤 재시작하여 활동 1회와 n+1 commit까지 확인한다.
- WEEK6_OFFSET_REDELIVERY: DB를 사용하지 않고 전송 경계만 분리한 실험으로, 같은 group 새 consumer의
  동일 record 재수신과 n+1 commit을 확인한다. 이 결과만으로 DB 멱등성을 주장하지 않는다.
- WEEK6_LISTENER_FAILURE/RECOVERED: 실제 listener와 production factory/error handler를 사용하되 저장 서비스의
  실패를 주입한다. 중단·실패 offset 미진행·서비스 복구 뒤 재처리를 확인하며 실제 MySQL 검사는 별도로 둔다.

## 운영 관측과 복구

producer는 order_confirmed broker_ack 로그와 order.confirmed.publish counter의 acknowledged/unresolved를 분리한다.
consumer는 purchase_activity completed/failed 로그와 실제 group offset을 함께 확인한다.
실패 로그에는 topic/partition/offset, producer 로그에는 eventId/orderId가 있다. 카드·PG 비밀은 payload에 없다.

```bash
docker exec kafka /opt/bitnami/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group purchase-activity-v1
```

구매 결과는 DB의 purchase_activities를 order_id/product_id 기준으로 조회하고 receipt/alias와 함께 확인한다.
lag=0만으로 DB 성공을 판단하지 않는다. 실패한 컨테이너는 원인을 수정한 뒤 프로세스를 재시작한다.
실패 offset을 넘기는 수동 reset은 업무 누락을 만들 수 있으므로 복구의 기본 절차로 사용하지 않는다.
재전달 가능한 기간은 topic retention 안으로 제한된다. 장기 장애가 retention을 넘기기 전에 알림과 대응이 필요하다.

발행 전 종료는 producer 실패 로그조차 남기지 않을 수 있다. 따라서 이 구현의 발행 로그/counter만으로 누락을 탐지하거나
복구할 수 있다고 주장하지 않는다. 보정이 필요하면 확정 주문과 소비 DB를 orderId 기준으로 대조하고,
원본 확정 시각·품목·stable eventId로 재발행하는 별도 운영 절차가 필요하다. 이번 주에는 이 자동화나 관리 API를 추가하지 않는다.
