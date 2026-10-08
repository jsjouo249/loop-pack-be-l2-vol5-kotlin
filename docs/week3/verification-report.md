# 3주차 구현·실행 검증 기록

2026-09-26, 기준 `jsjouo249` / `928d17dd5608fb157b4c9f22a7d1d472ff508514`.
시작 시 사용자 변경은 `docs/week2/commerce-design.md` 수정과 미추적 `docs/week3/`였다.
기존 내용을 보존하면서 구현·검증 결과를 반영했다. 커밋·푸시·PR과 운영 DB 변경은 하지 않았다.

## 최종 결과

`ktlintCheck`와 commerce-api 전체 `check` **종료 코드 0**.
JUnit XML 합계 **111 tests / 0 failures / 0 errors / 0 skipped**.
ArchitectureTest 1건도 이 실행에 포함되어 통과했다. 문서 로컬 링크 61개와 `git diff --check`도 오류 없이 확인했다. 테스트 중 의도적으로 유발한 업무·기술
예외와 테스트 자체 실패를 구분한다. 최종 실행의 suite별 건수·SQL·요청별 결과·시도 기록은
[원본 추출 JSON](verification-results.json)에 보존했다.

- DB: 기존 Testcontainers `mysql:8.0`, 실제 MySQL **8.0.46**.
- Driver/pool: Connector/J **9.1.0**, HikariCP **5.1.0**.
- Runtime: 임시 경로의 Temurin **21.0.12.1**, Gradle **8.13**.
- 실제 두 pool connection에서 `innodb_lock_wait_timeout=2`, 동일 값 UPDATE matched row=1 확인.
- transaction timeout 5초로 `SLEEP(7)`이 취소됨. 최종 실행 측정 **5,185ms**.
  Hikari connection 폐기로 rollback 완료를 확인할 수 없는 경로는 UNKNOWN/500으로 기록하고 재시도하지 않았다.
  이후 새 transaction의 `FOR UPDATE` 재조회에서 앞 재고 변경이 남지 않았음을 확인했다.

## 구현한 책임

- domain Aggregate·중립 저장 데이터·JPA adapter 경계를 유지했다. 전체 snapshot `save/updateFrom`을
  생성, 정보 수정, 재고 설정/차감, 삭제, 주문 확정 계약으로 나눴다.
- Order → PointAccount → Brand → Product ID 오름차순의 상대 잠금 순서를 적용했다.
  일반 조회와 단순 지정값 변경에 선행 잠금을 추가하지 않았다.
- 브랜드 삭제는 재고 0 포함 활성 Product에 각각 domain 삭제를 요청하고 전체를 한 transaction으로 저장한다.
  상품 등록도 같은 Brand 잠금을 사용한다. 주문·좋아요 관계는 보존한다.
- 내부 User 초기화는 잔액 0 PointAccount와 함께 commit/rollback한다.
  [보정 SQL](sql/backfill-point-accounts.sql)은 요청/시작 경로와 분리했고 기존 잔액 보존·재실행을 검증했다.
- 보호하는 쓰기 8개를 executor/transaction attempt 프록시에 연결했다. 완료된 rollback과 driver 오류를
  확인한 deadlock만 50ms 대기 후 한 번 재시도한다. 원 요청과 내부 시도 관측은 별도다.
- 삭제된 Product의 변경 거절은 domain에서도 보장한다. DB 활성 조건 UPDATE가 경쟁 중 삭제를 보호한다.

주요 코드:
[주문 협력](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderApplicationService.kt),
[브랜드 삭제](../../apps/commerce-api/src/main/kotlin/com/loopers/application/brand/BrandApplicationService.kt),
[사용자 초기화](../../apps/commerce-api/src/main/kotlin/com/loopers/application/user/UserInitializationService.kt),
[재시도](../../apps/commerce-api/src/main/kotlin/com/loopers/application/transaction/RetryingWriteExecutor.kt),
[transaction attempt](../../apps/commerce-api/src/main/kotlin/com/loopers/application/transaction/WriteTransactionAttempt.kt),
[상품 부분 저장](../../apps/commerce-api/src/main/kotlin/com/loopers/infrastructure/product/JpaProductEntityRepository.kt),
[현재값 refresh](../../apps/commerce-api/src/main/kotlin/com/loopers/infrastructure/common/JpaWriteOperations.kt).

## 실행 명령과 TDD

환경의 `openjdk@21` symlink가 실제 Java 26을 가리켜 첫 Gradle 실행은 테스트 전 실패했다.
별도 JDK 21을 `/tmp/week3-jdk/`에 준비했고 시스템 Java나 사용자 설정은 변경하지 않았다.
Docker는 사용자가 데몬을 실행했다. 개인 credential helper 대기를 피하려고 공개 이미지용 임시
Docker config만 사용했다. 실제 실행 환경 prefix는 다음과 같다.

```bash
export JAVA_HOME=/tmp/week3-jdk/jdk-21.0.12.1+1/Contents/Home
export DOCKER_CONFIG=/tmp/week3-docker-client
export DOCKER_HOST=unix:///Users/joseongjun/.docker/run/docker.sock
# 위 DOCKER_CONFIG/config.json은 {"auths":{}}. 개인 Docker 설정은 수정하지 않음.
```

| 실행 | 테스트 / 실패 / skip | 종료 | 결과 |
|---|---:|---:|---|
| `./gradlew :apps:commerce-api:test --tests '*ProductTest'` Red | 3 / 1 / 0 | 1 | 삭제된 상품 변경에서 CommerceException이 발생하지 않아 의도대로 실패 |
| 같은 명령 Green | 3 / 0 / 0 | 0 | Product의 활성 검증 추가 후 통과 |
| `./gradlew :apps:commerce-api:compileTestKotlin` retry 계약 Red | 컴파일 실패 | 1 | 아직 없는 executor/attempt 계약을 먼저 작성 |
| `./gradlew :apps:commerce-api:test --tests '*RetryingWriteExecutorTest'` 초기 Green | 5 / 0 / 0 | 0 | 경계·횟수·예산·기본 오류 분류 |
| 아래 순수 테스트·정적 검사 명령 | 29 / 0 / 0 | 0 | 도메인·retry 8건·Architecture·ktlint |
| `./gradlew :apps:commerce-api:test --tests '*AggregateCoordinationTest'` | 3 / 0 / 0 | 0 | 잠금 호출 순서, domain 행동, 변경 전 전체 검사 |
| 아래 MySQL 기본 검증 명령 | 13 / 0 / 0 | 0 | 삭제·부분 저장·초기화·SQL 이후 rollback |
| 아래 MySQL 경쟁 검증 첫 실행 | 27 / 3 / 0 | 1 | driver timeout 분류, 원인 보존, connection 관측 문제 발견 |
| `./gradlew :apps:commerce-api:test --tests '*RetryingWriteExecutorTest'` driver 회귀 Red | 10 / 2 / 0 | 1 | 1205/40001 분류와 덮인 원인 보존이 실패 |
| 같은 명령 Green | 10 / 0 / 0 | 0 | 운영 분류·suppressed 원인 보존 보완 후 통과 |
| `./gradlew :apps:commerce-api:test --tests '*MySqlTransactionPolicyTest'` 중간 확인 | 9 / 1 / 0 | 1 | 503 처리는 정상. 기존 NON_NULL envelope에 대한 테스트 가정 수정 필요 |
| `./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` 최종 | **111 / 0 / 0** | **0** | 전체 회귀·새 DB 테스트·Architecture·lint 통과 |

```bash
# 순수 테스트·정적 검사
./gradlew :apps:commerce-api:test --tests '*ProductTest' --tests '*StockTest' \
  --tests '*BrandTest' --tests '*PointAccountTest' --tests '*OrderTest' \
  --tests '*RetryingWriteExecutorTest' --tests '*ArchitectureTest' :apps:commerce-api:ktlintCheck

# MySQL 기본 검증
./gradlew :apps:commerce-api:test --tests '*BrandRemovalTransactionTest' \
  --tests '*WriteRollbackTest' --tests '*PartialStorageTest' --tests '*PointInitializationTest'

# MySQL 경쟁·기술 정책·HTTP·SQL 검증
./gradlew :apps:commerce-api:test --tests '*OrderConcurrencyTest' --tests '*LostUpdateControlTest' \
  --tests '*AdministrativeConcurrencyTest' --tests '*MySqlTransactionPolicyTest' \
  --tests '*SqlContractTest' --tests '*BrandDeletionHttpTest'

# 최종 통합 검증
./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check
```

대표 domain 규칙과 driver 회귀는 실행 실패를 확인한 Red → Green을 수행했다.
중립 저장 DTO/부분 저장 공통 동작 정리 후 최종 check로 Refactor 검증을 마쳤다.
Docker 미기동 단계의 초기 check는 106건 중 69건이 context 초기화에서 실패했고 skip은 0이었다.
그 결과를 성공으로 간주하지 않았으며 환경 복구 후 실제 DB 전체 검증으로 대체했다.

## 경쟁 결과와 최종 DB 상태

다음은 최종 전체 check에서 실제 관측한 요청별 결과다. 성공 순서는 스케줄링에 따라 바뀔 수 있지만
성공·거절 건수와 최종 상태 assertion은 고정했다. 모든 fixture는 worker 시작 전 commit되며,
서비스 경쟁에는 시작 latch만 사용했다. post-read 장벽은 갱신 유실 대조군에만 있다.

| 사례 | 요청별 결과 | 최종 DB 상태 |
|---|---|---|
| 재고 5 / 주문 8 | 주문 2·3·4·7·8 성공, 1·5·6 `INSUFFICIENT_STOCK` | 재고 0, CONFIRMED 5, DRAFT 3, 결제 합 5,000, 구매자 잔액 합 75,000 |
| 잔액 10,000 / 4,000 주문 3 | 주문 1·2 성공, 3 `INSUFFICIENT_POINTS` | 잔액 2,000, 재고 8, 결제 합 8,000 |
| 충전 +2,000 / 결제 7,000 | charge·주문 1 모두 성공 | 잔액 5,000, 재고 0 |
| 동일 주문 확정 4회 | request-1 성공, request-2·3·4 `ORDER_ALREADY_CONFIRMED` | 잔액 8,000, 재고 4, 확정·결제 한 번 |
| 동시 충전 5회 | charge-1~5 모두 성공 | 잔액 10,000, 계정 1개 |
| 갱신 유실 대조군 | control-1·2 성공 | 재고 **4**, `2+4 != 5` 위반을 assertion으로 확인 |
| 관리자 최종 재고 10 / 주문 1개 | set·confirm 모두 성공 | 재고 **9**, 잔액 9,000. 허용 직렬 결과는 9 또는 10 |
| 상품 정보 수정 / 주문 | details·confirm 모두 성공 | 재고 4, 상품 가격 2,000, 주문 snapshot 단가 1,000 |
| 상품 삭제 / 주문 | delete 성공, confirm `PRODUCT_NOT_FOUND` | 상품 삭제, 재고 5, 잔액 10,000, DRAFT 유지 |
| 상품 정보 수정 / 삭제 | details·delete 모두 성공 | 상품 삭제, 재고 5, 가격 2,000 |
| 브랜드 이름 변경 / 삭제 | rename `BRAND_NOT_FOUND`, delete 성공 | 브랜드·연결 상품 삭제 유지 |
| 상품 등록 / 브랜드 삭제 | register·delete 모두 성공 | 새 상품 포함 모두 삭제, 활성 자식 0 |

주문/포인트 서비스 경쟁 5개 사례는 원 요청 **22건 = 성공 15 + 업무 거절 7 + 기술 실패 0**.
내부 시도도 22회로 deadlock 0, timeout 0, 추가 retry 0이었다. 관리자 경쟁에서도 기술 오류는 없었다.
브랜드 등록→삭제와 삭제→등록, 관리자 설정/수정→주문과 주문→설정/수정의 양쪽 직렬 순서도
별도로 assertion했다.

## 내부 기술 오류와 재시도

| 강제 경합/오류 사례 | 내부 시도 | 원 요청 최종 결과와 DB |
|---|---|---|
| 실제 MySQL 충전 deadlock | DEADLOCK → SUCCESS, retry 1회 | 성공 1건, 상대 transaction +500과 충전 +2,000을 반영한 잔액 12,500 |
| 실제 MySQL 주문 deadlock | DEADLOCK → SUCCESS, retry 1회 | 성공 1건, 잔액 9,500, 재고 4, 결제 1,000 한 번 |
| 실제 MySQL 브랜드 deadlock | DEADLOCK → SUCCESS, retry 1회 | 성공 1건, 재시도 사이 등록된 새 자식까지 상품 2개 모두 삭제 |
| 실제 lock timeout, 앞 상품 SQL 이후 | LOCK_TIMEOUT 1회, retry 0회 | 503, DRAFT·재고 5·잔액 10,000·null 결제 필드·품목 snapshot 유지 |
| 실제 5초 query timeout | UNKNOWN_COMPLETION 1회, retry 0회 | 500 대상 예외, 원래 QueryTimeout 원인 보존, 새 잠금 조회에서 재고 5 |
| 주입한 driver deadlock 2회 | DEADLOCK → DEADLOCK | 503, 실제 충전 SQL 두 번 모두 rollback, 잔액 10,000 |
| 주입 deadlock 후 잔액 변경 | DEADLOCK → BUSINESS_REJECTION | 기존 409 INSUFFICIENT_POINTS, DRAFT·재고 유지 |
| 주입한 connection 결과 불명 | UNKNOWN_COMPLETION 1회 | 기존 500, retry 없음 |

실제 교착 실험의 상대 transaction과 충돌 신호는 테스트 코드에만 있다. 원 요청 성공 1건을
시도 수 2건으로 중복 집계하지 않는다. 고정 50ms는 fake sleeper로 요청값과 경계를 검증하며,
실제 scheduler가 정확히 50ms에 깨어난다는 assertion은 없다. 실제 충전 교착에서 sleeper 진입 전
풀 connection이 닫혀 반환되고 Spring 자원이 해제됐음을 확인했다.

## T01~T14 추적성

테스트 경로는 모두 `apps/commerce-api/src/test/kotlin/com/loopers/` 아래다.

| 계획 | 실제 검증 | 상태 |
|---|---|---|
| T01 | BrandRemovalTransactionTest, BrandDeletionHttpTest | 통과 |
| T02 | WriteRollbackTest: 첫 삭제 SQL 이후 다음 저장 실패, 전체 테이블 snapshot 재조회 | 통과 |
| T03 | OrderMockMvcTest, WriteRollbackTest: 재고·포인트 SQL 이후 확정 저장 실패 | 통과 |
| T04 | LostUpdateControlTest | 통과 |
| T05~T08 | OrderConcurrencyTest | 통과 |
| T09 | AdministrativeConcurrencyTest, PartialStorageTest, SqlContractTest | 통과 |
| T10 | PointInitializationTest, WriteRollbackTest, OrderConcurrencyTest | 통과 |
| T11 | BrandDeletionHttpTest, 기존 MockMvc 회귀, MySqlTransactionPolicyTest | 통과 |
| T12 | ArchitectureTest, ktlintCheck, 전체 commerce-api check | 통과 |
| T13 | AdministrativeConcurrencyTest, MySqlTransactionPolicyTest의 재조회 | 통과 |
| T14 | RetryingWriteExecutorTest 10건, MySqlTransactionPolicyTest 9건, SqlContractTest | 통과 |

## 설계 보완과 남은 범위

[Backend 설계의 기술 보완](backend-design.md#9-구현에서-구체화한-기술-사항)에 driver SQLState 매핑,
Hikari query timeout 폐기, 덮인 예외 보존, UPDATE 후 현재 읽기 refresh, 기존 null 필드 생략을 기록했다.
제품 정책, API 입력·성공 응답, Aggregate/DB schema는 확정된 범위에서 유지했다.

요청된 검증 중 실행하지 못한 항목은 없다. 다만 운영 부하·대규모 브랜드 처리 성능과 실제
네트워크 단절/commit 응답 유실 실험은 수행하지 않았다. 결과 불명 분기는 단위/HTTP driver 예외
주입과 실제 query timeout에 따른 connection 폐기 사례로 검증했다. deadlock 소진의 503은
driver 예외 주입이며 실제 MySQL deadlock은 세 유스케이스의 한 번 재시도로 별도 검증했다.
15초는 추가 시도 진입 예산이며 네트워크 장애까지 포함하는 HTTP 전체 상한이 아니다.
보정 SQL은 테스트 DB에서만 실행했고 운영 전환은 하지 않았다. 충전 요청의 외부 재전송 멱등성도
추가하지 않았다.
