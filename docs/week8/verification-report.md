# 8주차 Redis SSOT 전환 검증 기록

검증일: 2026-11-13. Redis 세마포어를 유지하면서 발급 원본을 Redis로 옮기고 DB를 비동기 projection으로 변경했다.
이 기록은 통과한 실행과 환경 때문에 본문에 진입하지 못한 실행을 구분한다.

## 통과한 검증

| 검증 | 결과 |
| --- | --- |
| 전체 API test (ArchitectureTest·쿠폰 통합 포함) | 250건, 실패·오류·skip 0 |
| 쿠폰 MySQL·HTTP·Redis Testcontainers 통합 (위 250건에 포함) | 18건, 실패·오류·skip 0 |
| W7 Outbox E2E | 2건, 실패·오류·skip 0 |
| 실제 JVM Redis 어댑터 (`couponRedisLocalTest`) | 27건, 실패·오류·skip 0 |
| commerce-api ktlintCheck | BUILD SUCCESSFUL |
| 실제 Redis의 production Lua smoke | 종료 0, 한도·중복·부분 오류·Stream·AOF 확인 |
| Compose와 같은 Redis 7.0 Docker Lua smoke | Redis 7.0.15, 종료 0, 같은 불변식·부분 오류·정상 AOF 재시작 확인 |
| Compose 설정 파싱 | 종료 0, 기존 version 필드 폐기 경고만 있음 |
| git diff --check | 종료 0 |

초기 선별 32건은 회귀 62건의 부분집합이며, 회귀 62·쿠폰 통합 18건은 최종 API 250건에 포함된다.
최종 중복 없는 합계는 **API 250·W7 E2E 2·JVM Redis 27로 279건**이다.
[쿠폰 단위](unit-test-summary.json), [domain 회귀](unit-regression-summary.json),
[실제 JVM Redis](local-redis-test-summary.json)에 suite별 건수를 보관했다.

2026-11-13 후속 검증에서는 사용자 허용으로 Docker Desktop을 시작해 아래 Gradle 명령을 실행했다.
`test` 80건(회귀 62·통합 18)과 `couponRedisLocalTest` 27건을 실제 실행해 BUILD SUCCESSFUL·종료 코드 0을 확인했다.
실패·오류·skip은 모두 0이며, 변경한 테스트의 컴파일·lint도 실행했다.
이후 임시 JDK 21.0.13을 준비해 전체 API check와 W7 E2E도 완료했다. 아래 최종 결과와 초기 차단 기록을 구분한다.

```shell
./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:couponRedisLocalTest :apps:commerce-api:test --tests 'com.loopers.domain.*' --tests 'com.loopers.application.coupon.*' --tests 'com.loopers.interfaces.coupon.*' --tests 'com.loopers.architecture.ArchitectureTest' --tests 'com.loopers.coupon.*' --console=plain --continue
python3 docs/week8/tooling/verify-permit-lua.py
python3 docs/week8/tooling/verify-lua-docker.py
```

설치된 Redis 8.10.1을 테스트 소유 process·임시 디렉터리·AOF로 실행했다. 기존 로컬 Redis에는 연결하지 않았다.
같은 production Lua를 Docker의 `redis:7.0`(실제 7.0.15)에서도 재검증해 Compose 버전과의 차이를 확인했다.
Docker probe는 임의 이름의 전용 컨테이너 하나를 만들고 종료 시 정리하며 기존 Redis·DB·Compose에는 연결하지 않는다.
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

## 추가 Lua 경계 검증과 테스트 가독성

기존 쿠폰 테스트 56개 이름을 검증하는 동작이 드러나도록 한글로 정리했다.
mock 기반 application 검사와 실제 Redis·DB 검사의 범위를 클래스 주석에 구분했고, 복잡한 경쟁·실패 주입 절차에도 설명을 보완했다.

실제 JVM Redis 검사에 다음 20개 parameterized 사례를 추가했다. 오류 전후 META·캠페인·보유·Stream·permit·state의 DUMP를 대조한다.

- 키 타입 오류 6건: META·캠페인·보유·Stream·permit·state를 하나씩 훼손하면 기존 결과도 반환하지 않고 데이터를 보존한다.
- TTL 설정 6건: 같은 키들에 TTL이 있으면 발급을 거절하고 데이터를 보존한다.
- sequence 오류·상한 5건: 음수·소수·비숫자·안전 상한 도달·상한 초과에서 발급 사실을 추가하지 않는다.
- 재개 증거 유실 3건: Stream 유실·sequence/길이 불일치·consumer group 유실은 permit 초기화 전에 재개를 거절한다.

Redis 7.0.15와 설치된 8.10.1의 smoke 모두 60개 permit 경쟁에서 수용 3개,
8명/5장 경쟁에서 발급 5·품절 3, 동일 쿠폰 재요청·Stream pending/ACK·XADD 부분 오류 DIRTY·정상 AOF 재시작을 통과했다.
Lua 내부 ACL 거절의 오류 문구는 버전별로 달라 해당 오류 변형을 허용하되 부분 변경·DIRTY 검증은 유지했다.
이는 처리량·응답 지연 benchmark나 실제 HA failover 비교가 아니다.

## MySQL·HTTP 통합 — 초기 차단과 후속 검증

```shell
./gradlew :apps:commerce-api:test --tests 'com.loopers.coupon.*'
```

초기 실행 결과는 13건·실패 13·오류 0·skip 0, 종료 코드 1이었다.
Docker daemon socket 부재로 `Could not find a valid Docker environment`와 Spring context 초기화 실패가 발생했다.
Redis Testcontainers suite 두 개는 initializationError로 끝났고, DB·HTTP suite도 본문 assertion에 진입하지 못했다.
[초기 차단 요약](integration-test-summary-before-docker.json)에 당시 JUnit 건수를 보존했다.

사용자 허용 후 Docker에서 본문을 실행한 최종 결과는 **18건·실패 0·오류 0·skip 0**이다.
초기에는 Redis Testcontainers 클래스가 initializationError 한 건씩으로 집계됐으므로 실제 본문 실행 건수와 다르다.
[현재 통합 실행 요약](integration-test-summary.json)에 최종 suite별 건수를 기록했다.

후속 실행에서 실제 MySQL·Redis·HTTP로 확인한 핵심 장면:

- DB 반영 전 HTTP 201·본인 목록, 재요청 200의 동일 전체 결과와 사용자 격리.
- HTTP 세 실행을 슬롯 획득 직후 유지 → 네 번째 대기 → 반환 후 같은 요청 201, 별도 deadline 429.
- 실제 Redis 확정 5건을 DB에 나중에 반영해 쿠폰 5·projection count 5.
- projection insert 실패의 TX 전체 rollback, pending 유지, 이후 재처리.
- DB commit 이후 ACK 실패 재처리의 쿠폰·counter 중복 효과 0.
- application이 사용하는 실제 Redis endpoint 중단·복구와 DB projection 보존.

본문 실행으로 드러난 테스트 구성 문제 두 가지를 수정했다. 운영 코드는 변경하지 않았다.

- MANDATORY repository 프록시를 spy 설정 중 호출해 실패하던 경로는 AOP 내부 spy에 실패를 주입하도록 수정했다.
  실제 worker의 DB 트랜잭션과 rollback·재처리 검증은 그대로 유지한다.
- Docker 29.9.2에서 같은 컨테이너를 재시작해도 임의 호스트 포트가 바뀌는 것을 확인했다(관측: 62882 → 62909).
  테스트 전용 빈 포트를 명시적으로 바인딩하고 재시작 전후 같은 endpoint인지 검사하도록 보완했다.

## 전체 API check와 W7 E2E — 최종 완료

```shell
./gradlew -Dorg.gradle.java.home="$LOOPPACK_JAVA21_HOME" :apps:commerce-api:check --continue --console=plain
```

명령의 LOOPPACK_JAVA21_HOME은 실행 기기에 준비한 JDK 21 홈이다. 이번에는 Temurin 21.0.13+9를 임시 디렉터리에 준비하고
공식 배포 metadata의 SHA-256과 대조했다. 전역 JDK 등록이나 개인 Gradle/Docker 설정은 변경하지 않았다.

최종 `:apps:commerce-api:check`는 **BUILD SUCCESSFUL·종료 코드 0**이다.
JDK 21에서 API test 250건을 실제 실행해 실패·오류·skip 0을 확인했고, 최종 check에서는 같은 입력의 통과 결과를 재사용했다.
W7 E2E 2건은 보완 후 실제 재실행해 실패·오류·skip 0을 확인했다. API lint·ArchitectureTest도 포함한다.
[최종 API 실행 요약](check-test-summary.json), [W7 E2E 요약](outbox-e2e-test-summary.json)에 suite별 건수를 보관했다.

첫 W7 E2E 실행에서는 W6 상태를 보존한 DB에 쿠폰 테이블이 없어 `Schema-validation: missing table [coupon_campaigns]`가 발생했다.
테스트 준비에서 기존 Outbox DDL과 W8 쿠폰 DDL을 함께 적용하도록 보완했다. 기존 주문·결제·구매 활동 데이터는 보존하며,
schema validation과 기존 복구·재복구·PG 추가 호출 0 검증도 유지했다. 운영 코드는 변경하지 않았다.

실제 관측:

```text
WEEK7_E2E orderId=1 eventId=f71ead3e-c60c-395f-b157-f90ff291e9fc offsets=[0, 1] deliveries=2 activities=2 quantities=2,1 pgCreates=1 status=PUBLISHED
WEEK7_W6_RECOVERED missing=1 consumed=2 outbox=2 activities=4 pgCreateDelta=0 pgLookupDelta=0 externalTransactionDelta=0 secondRecoveryDelta=0
```

초기 전체 API test 245건·통과 96·실패 149·오류/skip 0과 당시 check 종료 1은
[초기 전체 검사 요약](check-test-summary-before-docker.json)에 보존했다.
당시 Docker·Java 21 부재로 실행하지 못한 기록이며, 현재 완료 상태와 구분한다.

저장소 전체 ktlintCheck의 기존 commerce-batch 오류도 유지한다.
StepMonitorListener.kt:25의 trailing comma와 DemoJobE2ETest.kt:31의 빈 블록 공백은 HEAD에도 있으며 이번 범위에서 수정하지 않았다.
commerce-api lint는 통과했다. 초기에는 Docker를 시작하지 않았고 후속에는 사용자 허용으로 Docker Desktop을 시작했다. Compose는 시작하지 않았다.

## 독립 검토와 수정

읽기 전용 독립 검토가 발견한 두 경로를 수정했다.

- 캠페인 hash 유실 시 issued=0으로 재준비할 수 있었던 경로 → META 등록 표식으로 거절하고 실제 Redis 회귀로 확인.
- 기본 local fixture의 Redis 오류가 앱 전체 시작을 중단하는 경로 → 쿠폰 수용을 닫고 오류를 기록하며 다른 기능의 시작을 유지.

실제 JVM Redis 테스트가 발견한 조회 ARGV 누락도 수정했다. 이 검토는 MySQL 통합 실행을 대신하지 않는다.
획득 대기 변경도 읽기 전용 동시성 검토를 거쳤고, FIFO 없음·전송 중 Redis 명령은 별도 timeout이라는 경계를 문서화했다.

## PR 리뷰포인트

### 1. Redis를 발급 원본으로 쓰고, 30초 대기를 두었습니다

원래 발제는 Redis 세마포어로 발급 요청의 진입을 제한하고, DB에서 발급을 확정하는 흐름이었습니다.
세마포어 자리가 가득 차면 기다리지 않고 바로 실패하는 조건이었습니다.

이번에는 Lua로 중복 확인과 한도 검사부터 ID 할당, 발급 결과 기록까지 한 곳에서 처리해보고 싶었습니다.
그래서 Redis를 발급의 SSOT로 두고, DB에는 그 결과를 나중에 반영하도록 변경했습니다.
Redis에 기록되면 신규 발급은 201로 응답하고, 같은 사용자가 다시 요청하면 저장된 같은 쿠폰으로 200을 반환합니다.

이렇게 바꾸고 나니, 목록을 어디에서 읽을지도 함께 생각해야 했습니다.
DB 반영이 늦으면 이미 발급에 성공한 쿠폰이 목록에는 보이지 않을 수 있기 때문입니다.
그래서 보유 목록도 Redis primary에서 읽도록 했고, Redis를 읽지 못하면 GET도 503으로 응답합니다.
다만 사용자 존재를 확인하는 DB 조회는 여전히 남아있습니다.

또 하나 바꾼 부분은 자리가 없을 때의 처리입니다.
**원발제의 즉시 실패 대신 최대 30초 동안 기다리도록 했습니다.**
시간 안에 슬롯이 반환되면, 같은 요청에서 발급을 이어갈 수 있도록 해보고 싶었습니다.

다만...대기를 허용하면 그동안 Servlet thread를 점유하고 응답도 늦어집니다.
그래서 로컬 대기는 최대 50명으로 제한하고, 반환 알림과 200ms ±25% jitter 재검사를 함께 사용했습니다.
대기 시간이 끝나거나 인원 상한을 넘으면 429를 반환하며, FIFO나 순번 예약은 보장하지 않습니다.
여기서 30초는 슬롯 획득을 기다리는 시간입니다. token TTL이나 전체 HTTP 응답 시간의 상한은 아닙니다.
즉시 실패하는 방식보다 처리량이나 성공률이 좋아졌는지는 아직 측정하지 않았습니다.

DB에 바로 저장하는 방식을 유지할 수도 있었지만, 이번에는 Redis에서 발급을 확정해보는 쪽을 선택했습니다.
대신 같은 Lua가 남긴 Stream을 worker가 읽어 DB에 반영하고, commit이 끝난 뒤 ACK하도록 했습니다.
같은 ID·소유자·시각·혜택의 재전달은 다시 반영하지 않고, 같은 ID에 다른 내용이 들어오면 충돌로 처리합니다.
Redis의 발급 사실과 Stream이 함께 유실되면, 아직 DB에 반영하지 않은 쿠폰은 DB만으로 복원할 수 없다는 한계도 남아있습니다.

**제가 고민한 부분은 Redis에서 발급을 확정한 뒤 조회와 DB 반영까지 같은 결과를 유지하는 흐름입니다.
Lua의 중복·한도·ID·이벤트 기록과 commit 후 ACK 실패의 재처리에서 놓친 상황이 있는지 의견 부탁드립니다.
원발제와 다르게 30초 대기를 둔 선택도, 대기 시간과 인원 상한을 포함해 서버 자원과 응답 지연을 충분히 고려한 것인지 궁금합니다.**

실제 Redis의 한도·중복·Stream 재전달과 MySQL rollback·HTTP 계약은 테스트로 확인했습니다.
관련 코드는 아래에 연결했습니다. application에서는 포트를 호출하고, 실제 Lua는 별도 리소스로 두었습니다.

`CouponApplicationService → CouponAuthority` 또는 `CouponProjectionWorker → CouponIssueLog`를 거쳐
[RedisCouponAuthority](../../apps/commerce-api/src/main/kotlin/com/loopers/infrastructure/coupon/RedisCouponAuthority.kt)로 이어집니다.
어댑터가 `ssot-common.lua`와 각 Lua를 결합하고 [CouponRedisAccess의 eval()](../../apps/commerce-api/src/main/kotlin/com/loopers/infrastructure/coupon/CouponRedisAccess.kt#L32-L38)로 실행합니다.

| 확인할 동작 | 실제 Lua |
| --- | --- |
| 중복·한도·ID·발급 사실·Stream 기록 | [issue.lua](../../apps/commerce-api/src/main/resources/coupon/issue.lua) |
| 기존 결과·보유 목록·재개 전 검사 | [read.lua](../../apps/commerce-api/src/main/resources/coupon/read.lua) |
| pending/새 이벤트 조회 | [events.lua](../../apps/commerce-api/src/main/resources/coupon/events.lua) |
| DB commit 후 ACK | [ack.lua](../../apps/commerce-api/src/main/resources/coupon/ack.lua) |

- 설계: [API와 성공 기준](../../docs/week8/backend-design.md#api와-성공-기준), [DB write-behind](../../docs/week8/backend-design.md#db-write-behind), [대기 정책](../../docs/week8/backend-design.md#슬롯-획득-대기)
- 구현: [발급·조회 application](../../apps/commerce-api/src/main/kotlin/com/loopers/application/coupon/CouponApplicationService.kt), [DB 반영·worker](../../apps/commerce-api/src/main/kotlin/com/loopers/application/coupon/CouponProjection.kt)
- 검증: [application 멱등·ACK 호출 순서](../../apps/commerce-api/src/test/kotlin/com/loopers/application/coupon/CouponProjectionTest.kt), [실제 DB·HTTP 통합 테스트](../../apps/commerce-api/src/test/kotlin/com/loopers/coupon/CouponIntegrationTest.kt)

### 2. 발급 결과를 모를 때 슬롯을 바로 반환해도 괜찮을까 고민했습니다

Lua는 실행 중 다른 요청이 끼어들지 않지만, 중간에 오류가 나면 앞의 쓰기를 모두 되돌려주지는 않습니다.
실제로 XADD 권한만 막아보니, 발급 수와 보유 기록이 변경된 뒤 Stream 기록에서 실패하는 상황이 남았습니다.

이 상태를 정상 발급처럼 읽거나 다음 발급을 이어가면 안되겠다고 생각했습니다.
그래서 입력·타입 검사와 JSON 계산을 먼저 마친 뒤 DIRTY를 기록하고, 발급 사실·수량·Stream을 모두 쓴 뒤에만 READY로 바꾸었습니다.
DIRTY가 남으면 조회·발급·worker를 막고, 세마포어를 재개하더라도 DIRTY나 발급 데이터를 지우지 않도록 했습니다.

응답을 받지 못한 경우도 함께 고민했습니다.
Redis 명령이 timeout되었다고 해서, 슬롯 획득이나 발급이 실행되지 않았다고 단정할 수는 없었습니다.
이미 실행 중인데 token을 반환하거나 TTL로 회수하면, 이전 실행이 끝나기 전에 새 실행을 받을 수도 있을 것 같았습니다.

그래서 슬롯이 가득 찼다는 응답을 확인한 경우에만 제한 대기를 허용했습니다.
Redis 실행 결과를 알 수 없으면 점유를 유지하고 신규 발급 수용을 닫은 뒤, 기존 실행이 끝났는지 확인하고 명시적으로 재개하도록 했습니다.
세마포어는 현재 실행 수를 제한하고, 발급 Lua는 누적 발급 한도와 사용자별 중복을 지키도록 역할도 나누었습니다.

다만 이 선택으로 자동 복구가 되는 것은 아닙니다.
수동 확인이 늦어지면 발급 중단도 길어질 수 있고, 현재 재개 범위는 단일 로컬 발급 앱으로 제한했습니다.
일반적으로 수용을 닫은 경우에도 Redis가 건강한 READY 상태라면 기존 결과와 목록은 읽을 수 있습니다.
DIRTY 때문에 조회·발급·worker를 모두 막는 경우와는 구분했습니다.

또한 READY가 남았다는 사실이 replica 영속화나 장애 전환 후 쿠폰 보존까지 증명하지는 않습니다.
DIRTY로 부분 변경을 발견할 수는 있지만, 정상 완료한 데이터 전체가 유실되어 과거 READY로 돌아간 상황까지 모두 찾아내지는 못합니다.
AOF 정상 재시작은 확인했고, replica 영속화 확인·Sentinel·실제 failover는 이번 구현에 포함하지 않았습니다.

**결과를 알 수 없는 경우에는 자동으로 슬롯을 회수하기보다 멈추고 확인하는 쪽을 선택했습니다.
슬롯 부족·로컬 대기 취소·Redis 결과 불명을 나눈 처리와, 진행 중인 실행이 끝나기 전에 재개하지 못하게 한 부분에서 놓친 경합이 있는지 의견 부탁드립니다.
현재 실습 범위에서 이 정도의 중단·재개 정책이 적절한지, 그리고 DIRTY로 확인할 수 있는 것과 확인하지 못하는 것을 제가 제대로 구분했는지도 궁금합니다.**

세마포어는 `CouponAdmissionService → RedisCouponPermits → CouponRedisAccess.eval()` 순서로 호출합니다.
[acquire.lua](../../apps/commerce-api/src/main/resources/coupon/acquire.lua)에서 슬롯 획득과 자기 token을 확인하고,
[release.lua](../../apps/commerce-api/src/main/resources/coupon/release.lua)에서 자기 token을 반환합니다.
[initialize.lua](../../apps/commerce-api/src/main/resources/coupon/initialize.lua)는 명시적 재개 때 permit만 초기화합니다.

타입·TTL·READY·보유 수 검사는 [ssot-common.lua](../../apps/commerce-api/src/main/resources/coupon/ssot-common.lua),
발급 중 DIRTY/READY 전이는 [issue.lua](../../apps/commerce-api/src/main/resources/coupon/issue.lua),
최초 준비는 [prepare.lua](../../apps/commerce-api/src/main/resources/coupon/prepare.lua)에 있습니다.

- 설계·복구: [Lua 실행과 쓰기 순서](../../docs/week8/backend-design.md#요청과-lua-경계), [명시적 재개](../../docs/week8/README.md#정상-재시작과-장애-재개)
- 구현: [수용·대기·반환](../../apps/commerce-api/src/main/kotlin/com/loopers/application/coupon/CouponAdmissionService.kt)
- 검증: [실제 Redis 부분 실패·정상 재시작](../../apps/commerce-api/src/test/kotlin/com/loopers/coupon/RedisCouponAuthorityLocalTest.kt), [대기·취소·닫힘](../../apps/commerce-api/src/test/kotlin/com/loopers/application/coupon/CouponAdmissionWaitingTest.kt)
