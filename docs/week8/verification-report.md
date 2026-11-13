# 8주차 Redis SSOT 전환 검증 기록

검증일: 2026-11-13. Redis 세마포어를 유지하면서 발급 원본을 Redis로 옮기고 DB를 비동기 projection으로 변경했다.
이 기록은 통과한 실행과 환경 때문에 본문에 진입하지 못한 실행을 구분한다.

## 통과한 검증

| 검증 | 결과 |
| --- | --- |
| 쿠폰 domain·application·local fixture + ArchitectureTest | 32건, 실패·오류·skip 0 |
| 기존 domain 전체 + 쿠폰 application·fixture + ArchitectureTest | 62건, 실패·오류·skip 0 |
| 실제 JVM Redis 어댑터 (`couponRedisLocalTest`) | 7건, 실패·오류·skip 0 |
| commerce-api ktlintCheck | BUILD SUCCESSFUL |
| 실제 Redis의 production Lua smoke | 종료 0, 한도·중복·부분 오류·Stream·AOF 확인 |
| Compose 설정 파싱 | 종료 0, 기존 version 필드 폐기 경고만 있음 |
| git diff --check | 종료 0 |

32건은 62건의 부분집합이다. JVM Redis 7건은 별도 suite다.
[쿠폰 단위](unit-test-summary.json), [domain 회귀](unit-regression-summary.json),
[실제 JVM Redis](local-redis-test-summary.json)에 suite별 건수를 보관했다.

2026-11-13 PR 준비에서도 아래 Gradle 명령을 다시 실행해 BUILD SUCCESSFUL·종료 코드 0을 확인했다.
`test`와 `couponRedisLocalTest`는 실제 실행했으며 JUnit XML은 각각 62건·7건,
실패·오류·skip 모두 0이다. API lint와 컴파일은 동일 입력의 UP-TO-DATE 결과를 재사용했다.
아래 MySQL·HTTP 통합과 전체 check의 실패 기록은 이전 실행 결과이며, 이번 선별 재검증이 이를 대체하지 않는다.

```shell
./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:couponRedisLocalTest :apps:commerce-api:test --tests 'com.loopers.domain.*' --tests 'com.loopers.application.coupon.*' --tests 'com.loopers.interfaces.coupon.*' --tests 'com.loopers.architecture.ArchitectureTest'
python3 docs/week8/tooling/verify-permit-lua.py
```

설치된 Redis 8.10.1을 테스트 소유 process·임시 디렉터리·AOF로 실행했다. 기존 로컬 Redis에는 연결하지 않았다.
Python은 Unix socket, JVM은 임시 loopback 포트와 실제 Spring Data MASTER 연결을 사용한다.
JVM 전용 local-redis tag는 설치 환경을 명시적으로 사용하는 별도 task로 실행하며 일반 test에서 중복 실행하지 않는다.
기존 MySQL·HTTP Testcontainers 테스트를 이 tag로 제외하거나 skip 처리하지 않았다.

실제 Lua 출력:

```json
{"aof_restart":"passed","contenders":60,"capacity":3,"accepted":3,"ssot_quota":"5/8","partial_write":"DIRTY","stream_replay_ack":"passed","checks":"passed"}
```

실제로 확인한 범위:

- 슬롯을 유지한 60개 경쟁에서 C=3, 자기·미지·반복 token 반환, TTL 없음.
- Redis 발급 경쟁에서 한도 5에 8명 참여 → 발급 5·품절 3, 같은 사용자 재요청의 동일 couponId.
- 실제 JVM 어댑터에서 동시 중복 실행의 생성 효과 1, 목록 즉시 조회, JSON·문자열 serializer·primary 연결.
- Stream의 새 이벤트 → pending 재전달 → ACK → 빈 결과, 이벤트 본문 보존.
- XADD 권한만 거절하는 실제 ACL 오류로 issued/owner 일부 변경 후 실패를 재현. DIRTY가 조회·신규 수용·worker·재개를 차단.
- 세마포어 초기화가 DIRTY·쿠폰·수량·Stream을 지우지 않음.
- 등록 캠페인/owner 유실을 신규·빈 보유 상태로 초기화하지 않음.
- AOF 재시작 뒤 사실·pending·DIRTY 보존, 명시적 재개 전 신규 수용 거절.
- Redis 오류가 local fixture의 전체 앱 시작을 중단시키지 않고 로그·닫힌 쿠폰 수용으로 남음.
- 실제 Redis token 3개를 유지한 동안 네 번째 발급 대기 → 반환 알림으로 같은 요청 진행.
- 대기 상한·deadline의 거절은 쿠폰을 만들거나 다른 실행 token을 제거하지 않음.
- 단위 검사에서 deadline 뒤 추가 획득 없음, 동일 token 재시도, close의 대기 해제, 대기/실행 추적 정리.
- 로컬 timedWait interrupt는 CANCELLED/건강한 수용 유지, Redis I/O interrupt는 결과 불명/수용 닫힘으로 구분.

Python과 JVM 검증은 MySQL transaction·실제 HTTP 응답 계약을 대체하지 않는다.
AOF 테스트는 테스트 소유 Redis의 정상 종료·재시작이며 저장장치 파손·무손실 자동 failover 실험이 아니다.

## MySQL·HTTP 통합 실행 제한

```shell
./gradlew :apps:commerce-api:test --tests 'com.loopers.coupon.*'
```

결과는 13건·실패 13·오류 0·skip 0, 종료 코드 1이다.
Docker daemon socket 부재로 `Could not find a valid Docker environment`와 Spring context 초기화 실패가 발생했다.
Redis Testcontainers suite 두 개는 initializationError로 끝났고, DB·HTTP suite도 본문 assertion에 진입하지 못했다.
[통합 실행 요약](integration-test-summary.json)에 실제 JUnit 건수를 기록했다.

작성했지만 아직 실제 MySQL/HTTP로 확인하지 못한 핵심 장면:

- DB 반영 전 HTTP 201·본인 목록, 재요청 200의 동일 전체 결과와 사용자 격리.
- HTTP 세 실행을 슬롯 획득 직후 유지 → 네 번째 대기 → 반환 후 같은 요청 201, 별도 deadline 429.
- 실제 Redis 확정 5건을 DB에 나중에 반영해 쿠폰 5·projection count 5.
- projection insert 실패의 TX 전체 rollback, pending 유지, 이후 재처리.
- DB commit 이후 ACK 실패 재처리의 쿠폰·counter 중복 효과 0.
- application이 사용하는 실제 Redis endpoint 중단·복구와 DB projection 보존.

## 전체 check와 기존 문제

```shell
./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check --continue
```

전체 API test는 245건·통과 96·실패 149·오류 0·skip 0이며 전체 check의 종료 코드는 1이다.
전체 suite 결과는 [check 실행 요약](check-test-summary.json)에 기록했다.
Docker 부재로 DB 기반 suite가 초기화되지 않으며 W7 outboxE2e는 Java 21 launcher가 없어 실행되지 않는다.
현재 Java 17·18만 등록돼 있으므로 전체 check 통과나 W7 DB/Kafka E2E 회귀 통과를 주장하지 않는다.

저장소 전체 ktlintCheck의 기존 commerce-batch 오류도 유지한다.
StepMonitorListener.kt:25의 trailing comma와 DemoJobE2ETest.kt:31의 빈 블록 공백은 HEAD에도 있으며 이번 범위에서 수정하지 않았다.
commerce-api lint는 통과했다. Docker Desktop·Compose는 자동 시작하지 않았다.

## 독립 검토와 수정

읽기 전용 독립 검토가 발견한 두 경로를 수정했다.

- 캠페인 hash 유실 시 issued=0으로 재준비할 수 있었던 경로 → META 등록 표식으로 거절하고 실제 Redis 회귀로 확인.
- 기본 local fixture의 Redis 오류가 앱 전체 시작을 중단하는 경로 → 쿠폰 수용을 닫고 오류를 기록하며 다른 기능의 시작을 유지.

실제 JVM Redis 테스트가 발견한 조회 ARGV 누락도 수정했다. 이 검토는 MySQL 통합 실행을 대신하지 않는다.
획득 대기 변경도 읽기 전용 동시성 검토를 거쳤고, FIFO 없음·전송 중 Redis 명령은 별도 timeout이라는 경계를 문서화했다.

## PR 리뷰포인트

### 1. Redis를 발급 원본으로 선택한 뒤 성공 응답·조회·DB 반영의 경계

최초 실습안은 Redis 세마포어로 진입을 제한하고 DB 트랜잭션에서 발급을 확정하는 구조였다.
이번에는 Lua로 한도·사용자별 중복·ID·발급 사실을 함께 처리해 보기 위해 Redis를 SSOT로 선택했다.
이에 따라 DB commit을 기다리지 않고 Redis 기록으로 신규 201을 응답하며, 재요청은 같은 쿠폰으로 200을 반환한다.
DB 반영 전에도 성공한 쿠폰을 조회할 수 있도록 보유 목록도 Redis primary에서 읽고, Redis 오류 시 GET도 503으로 처리한다.

DB를 성공 기준으로 유지하는 대안은 Redis를 보조 수단으로 둘 수 있지만, 이번 실습에서 선택한 발급 권위와는 다르다.
현재 DB는 같은 Lua가 남긴 Stream을 비동기로 반영하는 projection이다. worker는 pending을 먼저 읽고,
동일 ID·소유자·시각·혜택이면 재반영 효과 없이 종료한 뒤 DB commit 이후에 ACK한다.
사용자 존재 확인용 DB 조회는 여전히 남아 있으며, Redis와 Stream이 함께 유실되면 DB만으로 미반영 발급을 복원할 수 없다.

**검토 요청:** Redis에서 확정한 동일 결과를 POST·GET·DB projection까지 유지하는 책임 분리가 적절한지,
특히 DB commit 후 ACK 실패의 재처리와 식별자·발급 사실 충돌 거절에 빠진 경계가 있는지 의견을 구한다.
실제 Redis의 한도·중복·Stream 재전달은 확인했지만, MySQL rollback과 HTTP 통합 assertion은 환경 때문에 미실행이다.

근거: [API·성공 기준](backend-design.md#api와-성공-기준), [DB write-behind](backend-design.md#db-write-behind),
[application 검증](../../apps/commerce-api/src/test/kotlin/com/loopers/application/coupon/CouponProjectionTest.kt),
[작성된 DB·HTTP 통합 검증](../../apps/commerce-api/src/test/kotlin/com/loopers/coupon/CouponIntegrationTest.kt).

### 2. Lua 부분 실패·실행 결과 불명에서 자동 회수보다 중단과 명시적 재개를 선택한 이유

세마포어의 동시 실행 수 제한과 Lua의 누적 발급 한도·중복 방지는 별도 책임으로 유지했다.
슬롯 3개가 차면 최대 30초·로컬 50명까지 기다리고, 대기 만료·상한 초과는 429로 반환한다.
반면 Redis 명령 timeout은 실행 실패의 증거가 아니므로 같은 방식으로 재시도하거나 점유를 즉시 반환하지 않는다.
token TTL로 자동 회수하면 기존 실행이 끝나기 전에 다음 실행을 수용할 수 있어, TTL 대신 신규 수용을 닫고 수동 확인 후 재개한다.

발급 Lua도 오류 전 쓰기를 자동 rollback하지 않는다. 검증과 JSON 계산을 쓰기 전에 마친 뒤 DIRTY를 기록하고,
발급 사실·수량·Stream 쓰기를 모두 마쳐야 READY로 바꾼다. 실제 XADD 권한 오류로 부분 변경을 재현했으며,
DIRTY에서는 조회·발급·worker가 차단된다. 세마포어 재개는 permit만 정리하고 DIRTY나 발급 데이터를 지우지 않는다.
그 비용으로 결과 불명 이후 장시간 중단될 수 있으며, 재개 범위는 단일 로컬 발급 앱으로 제한한다.
일반적인 수용 닫힘은 신규 발급을 막지만 건강한 READY의 기존 결과·목록 조회까지 일괄 차단하지는 않는다.

**검토 요청:** 확정된 FULL·로컬 대기 취소·Redis 실행 결과 불명을 구분하고,
진행 중 실행을 추월하지 않은 뒤 permit만 재초기화하는 경계에 동시성 누락이 있는지 검토를 구한다.
또한 DIRTY로 막을 수 있는 부분 실패와 감지하지 못하는 정상 완료 데이터의 유실을 설명한 범위가 적절한지 의견을 구한다.
AOF 정상 재시작은 확인했지만 실제 HA failover는 검증하지 않았으며, READY는 replica 영속화 완료 표식이 아니다.

근거: [Lua 경계](backend-design.md#요청과-lua-경계), [대기 정책](backend-design.md#슬롯-획득-대기),
[명시적 재개](README.md#정상-재시작과-장애-재개),
[실제 Redis 부분 실패·정상 재시작 검증](../../apps/commerce-api/src/test/kotlin/com/loopers/coupon/RedisCouponAuthorityLocalTest.kt),
[대기·취소·닫힘 검증](../../apps/commerce-api/src/test/kotlin/com/loopers/application/coupon/CouponAdmissionWaitingTest.kt).
