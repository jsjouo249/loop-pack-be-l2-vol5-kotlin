# 7주차 구현·검증 기록

## 최종 결과 — 2026-11-05

합의한 Outbox·단일 relay·명시 대상 복구를 구현했다. JDK 21.0.13, Gradle 8.13, Spring Boot 3.4.4에서
전체 check와 API/streamer assemble이 **BUILD SUCCESSFUL / exit 0**으로 종료됐다.
최종 JUnit XML은 **223건 / 실패 0 / 오류 0 / skip 0**이다.

| 범위 | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| modules:events | 3 | 0 | 0 | 0 |
| commerce-api:test | 201 | 0 | 0 | 0 |
| commerce-streamer:test | 17 | 0 | 0 | 0 |
| commerce-api:outboxE2e | 2 | 0 | 0 | 0 |
| 합계 | 223 | 0 | 0 | 0 |

```bash
./gradlew :apps:commerce-api:outboxE2e :modules:events:check :modules:kafka:check :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-api:assemble :apps:commerce-streamer:assemble --console=plain --continue
```

실행 시 JAVA_HOME을 임시 JDK 21 경로로, DOCKER_HOST를 Docker Desktop의 실제 endpoint로,
DOCKER_CONFIG를 빈 auths의 임시 설정으로 지정했다. 개인 JDK/Docker 설정은 수정하지 않았다.
API/streamer ktlintCheck(신규 source set 포함)와 각각의 ArchitectureTest도 통과했다.
kafka:check는 lint·빌드 성공이며 테스트 소스가 없으므로 업무 테스트 건수에 넣지 않는다.
일부 변경 없는 Gradle 작업은 UP-TO-DATE이며, 최종 XML 집계는 [full-check-results.json](measurements/full-check-results.json)에 있다.
이전 독립 검사 23건은 별도 실행으로 [independent-results.json](measurements/independent-results.json)에 보존하며 223건에 추가 합산하지 않는다.

## 원자성과 실패 검증

OutboxAtomicityTest 9건은 실제 격리 MySQL과 로컬 PG HTTP fixture를 사용한다.

- Outbox insert 전·실제 flush 뒤·serialization 실패에서 확정 TX를 rollback한다.
- 기존 주문 1행·PAYMENT_PENDING, REQUESTED 시도·RESERVED 예약과 확보된 가용 재고를 유지하며 새 Outbox는 0이다.
- 외부 PG SUCCESS는 남고 같은 거래를 조회하여 로컬 확정을 다시 반영한다. PG create는 1회다.
- 최초 callback/reconcile 성공과 중복 결과 적용에서 CONFIRMED·CONSUMED·Outbox가 한 번만 남는다.
- PENDING·UNKNOWN·확정 실패 관찰은 Outbox를 만들지 않고, 기록 서비스는 외부 TX 없이는 실행되지 않는다.

OutboxDeliveryIntegrationTest 4건은 실제 MySQL에서 전달 상태 TX를 확인한다.

- timeout 뒤 같은 identity/payload·attemptCount·nextAttemptAt·안전한 오류 종류를 보존하고 다른 정상 대상은 진행한다.
- PUBLISHED flush 뒤 오류를 주입해 상태 TX rollback과 같은 사건의 재개를 검증한다.
- 실제 KafkaOutboxSender의 get 대기가 끝난 뒤 mock KafkaTemplate의 CompletableFuture를 늦게 성공시켜도 DB 상태가 덮어써지지 않는다.
  이 검사는 미래 완료의 경계이며 실제 broker 응답 유실·네트워크 분할 시험은 아니다.
- 기존 Outbox의 충돌 payload·미완료 원본은 복구하지 않는다. 도메인/application 단위 검사는 결제 결과·금액·요청자·확정 시각·POINT 충돌도 거절한다.

대표 도메인 Red는 재시도 시각이 현재 시각으로 남는 실패였고 backoff 구현 후 통과했다.
시도 시작과 실패 확인 이후의 backoff를 함께 보장하므로 긴 전송 대기가 실패 후 간격을 소모하지 않는다.
단위 검사로 scheduler/수동 실행의 중첩 방지도 확인한다. 메모리 가드를 분산 잠금이라고 주장하지 않는다.

## 실제 주문→Outbox→Kafka→구매 활동

실제 주문 API·PG HTTP fixture·MySQL 8.0·단일 KRaft broker·production parser/listener/factory/service/repository를 연결했다.
API runtime에 streamer 의존성을 추가하지 않고 독립 outboxE2e source set에서만 연결한다.

1. 주문을 확정하고, relay 전 CONFIRMED·CONSUMED·PENDING과 구매 활동 0을 DB에서 확인했다.
2. 첫 상품을 삭제해도 저장 payload로 발행한다. 첫 broker 확인 metadata 확보 뒤 PUBLISHED TX 전에 예외 hook으로 중단했다.
3. PENDING을 덮어쓰지 않고 새 relay를 만들어 같은 DB의 같은 행을 재발행했다.
4. 별도 observer가 같은 사건의 두 broker record를 직접 읽고 서로 다른 offset을 확인했다.
5. 실제 consumer 수신 2회, 완료 receipt/alias 각 1행, 품목별 활동 2행·수량 2/1을 확인했다.
6. Outbox 1행 최종 PUBLISHED·publishedAt, 외부 PG 거래/create 각 1회, 예약 추가 소비·재고 추가 차감·포인트 변경 없음도 확인했다.

```text
WEEK7_E2E orderId=1 eventId=f71ead3e-c60c-395f-b157-f90ff291e9fc offsets=[0, 1] deliveries=2 activities=2 quantities=2,1 pgCreates=1 status=PUBLISHED
```

## W6 상태 재현→같은 실행의 복구

실제 W6 revision 4dfa06b를 git archive로 빌드한다. 준비용 fixture는 이 시험 실행물에만 추가되고 W7 제품에 우회 스위치는 없다.
격리 MySQL·Kafka·Redis·PG의 수명주기를 한 테스트가 소유한다.
실제 W6 API/PG/확정/after-commit 경로로 합성 주문 A/B를 만들고, A만 commit 후 send 직전에서 중단했다.
B는 실제 발행·소비를 끝냈다. 두 주문 CONFIRMED·예약 소비, Outbox 테이블 없음, A 활동 0·B 활동 2를 DB에서 확인했다.

이번 실행의 orderId·stable eventId·confirmedAt·items·PG 거래 식별자를 기록한 뒤 W6 실행물만 종료했다.
DB·broker·PG를 초기화하지 않고 Outbox DDL을 적용하고 W7을 validate로 연결했다.
원본 snapshot 그대로 없는 Outbox만 추가했고, B의 재발행도 활동을 늘리지 않았다.
두 번째 복구 후에도 Outbox 2행·전체 활동 4행과 각 품목 수량을 유지했다.
주문 확정 시각·품목·예약 전체 행·재고·포인트·PG 거래 내용과 create/조회 기준값은 모두 불변이었다.

```text
WEEK7_W6_RECOVERED missing=1 consumed=2 outbox=2 activities=4 pgCreateDelta=0 pgLookupDelta=0 externalTransactionDelta=0 secondRecoveryDelta=0
```

이는 새 실행의 W6 누락 재현·복구이며 역사적 원본 DB 복원은 아니다. 과거 POINT 주문 전체를 소급하지 않는다.
원본 관측은 [kafka-observations.txt](measurements/kafka-observations.txt)에 보존했다.

## 독립 검토·초기 실패와 보완

읽기 전용 독립 검토로 복구 시 결제 사실의 불일치 검증을 보완했다.
Order.confirmedPurchase가 주문의 상태·결제 결과·금액·시각을 검증하고 application이 PG 시도/예약 일치를 확인한다.
후속 검토에서 W6 manifest의 생성/읽기 경쟁을 찾아 임시 파일 작성 후 atomic move로 준비 완료를 공개했다.

초기 Docker 엔진은 CLI /version에 500을 반환하고 소켓 info도 유한 5초 안에 응답하지 않았다.
사용자가 엔진을 재시작한 뒤 정상화됐다. 초기 연결 실패는 성공으로 계산하지 않았고 Desktop/Compose를 자동 재시작하지 않았다.
최초 실제 E2E에서는 기대 Map의 숫자 타입이 Kotlin 공통 타입 추론으로 Long이 되어 JDBC Int 수량 비교가 실패했다.
JDBC getLong/getInt의 명시적 Pair로 바꿨으며 기대 행·수량·원자성 검증은 유지했다. 이후 관련 15건과 최종 전체 검사를 통과했다.
테스트 기대값·lint·ArchUnit을 삭제하거나 완화하지 않았다.

## 운영·보장 범위

relay 기본값은 비활성이다. 지정한 한 인스턴스만 활성화하고 scheduler/수동 진입은 같은 실행 가드를 사용한다.
20건 직렬·회차 종료 후 1초·future 대기 12초·5초부터 최대 5분 backoff다. 영구 오류도 PENDING 미해결 목록에 남는다.
PUBLISHED는 broker 확인과 상태 commit이며 소비 완료는 별도로 확인한다. 오류는 원인 예외 종류만 남기고 민감한 메시지는 제외한다.
로컬 profile의 명시 대상 복구/재개 명령, SQL 관측, 보존 정책은 [README](README.md)에 있다.

새 DB lock·다중 relay·CDC·다중 broker 장애 보장·OS 강제 종료·네트워크 분할은 구현/실험 범위 밖이다.
Outbox와 dedup 기록을 자동 삭제하지 않으며 미래 정리 정책은 재발행 가능 기간과 함께 결정해야 한다.
검증은 자신이 만든 consumer·broker·DB·PG 자원만 정리하며 기존 개인/공유 인프라는 변경하지 않았다.
