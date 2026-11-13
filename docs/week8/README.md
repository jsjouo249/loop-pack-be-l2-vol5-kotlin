# 8주차 쿠폰 발급 — Redis SSOT와 Redis 세마포어

[설계](backend-design.md) · [검증 기록](verification-report.md)

## 구현 흐름

```text
사용자 확인 → Redis 기존 결과 조회 → Redis 세마포어 획득
  → 발급 Lua(중복·한도·발급 사실·Stream) → 자기 슬롯 반환 → 201/200

worker → Stream pending/새 이벤트 → DB TX로 멱등 반영 → commit 후 ACK
```

발급 성공 원본과 보유 목록은 Redis다. DB 반영 전에도 같은 couponId를 확인한다.
DB는 coupon_campaigns·issued_coupons에 비동기 반영한다. 사용자 존재 확인을 위한 DB 조회는 남는다.
최초 201, 중복 200, 품절·비활성 409다. 슬롯이 가득 차면 최대 30초 기다린다.
대기 만료·대기 인원 초과는 429, 원본 상태 불명·Redis 장애는 503이다.
Redis 장애에서 GET도 503이며 DB projection을 최신 결과로 대신 제공하지 않는다.

## 슬롯 대기 설정

```yaml
coupon:
  admission:
    capacity: 3
    wait-timeout-ms: 30000
    max-waiters: 50
    retry-interval-ms: 200
```

30초는 슬롯 획득 대기 한도이며 token TTL이 아니다. 반환 알림이나 jitter를 준 재검사에서 자리가 나면
기다리던 같은 요청이 발급을 계속한다. timeout·interrupt·닫힘에서 대기 수를 정리한다.
Redis 오류를 FULL로 바꿔 30초 반복하지 않는다. 기본 50명의 대기 상한을 넘으면 즉시 429다.
이는 로컬 HTTP 요청 대기이며 영속 대기실·순번 예약·FIFO는 제공하지 않는다.
클라이언트·프록시 timeout도 획득 대기와 실제 처리시간을 고려해 맞춰야 한다.
연결 종료 자체가 서버 발급 취소를 의미하지 않으므로 재요청에서 기존 결과를 확인한다.

## 실행 전 준비

기존 로컬 MySQL과 Redis primary/replica를 사용하며 Docker Desktop·Compose를 자동 실행하지 않는다.
단일 쿠폰 발급 인스턴스만 실행한다. Redis에는 다음 설정이 필요하며 앱이 준비/재개 시 확인한다.

```text
appendonly yes
appendfsync always
maxmemory-policy noeviction
```

현재 연결 주소·ACL을 확인하고 해당 서버 설정은 운영자가 준비한다. 이 문서의 명령은 Redis를 초기화하지 않는다.
발급 데이터에 TTL을 설정하거나 만료/eviction이 가능한 cache처럼 운영하지 않는다.
local의 기존 ddl-auto=create는 전체 로컬 DB를 초기화하므로 기존 데이터가 있으면 ddl-auto=none으로 보존한다.
신규 테이블은 [schema.sql](schema.sql)에 있다. 기존 DB 쿠폰의 Redis 이관은 자동 제공하지 않는다.
기존 쿠폰이 있다면 Redis 원본과 ID sequence·Stream·DB의 일관성을 먼저 이관·검증해야 한다.

설정상 Redis는 primary 1개 + replica 1개다. Sentinel/Cluster가 없어 자동 failover는 하지 않는다.
Compose 두 노드에 위 AOF/noeviction 설정을 명시했으며 파일 변경만으로 기존 서버의 설정이 바뀌지는 않는다.
AOF 로컬 복구와 replica 승격은 별개이고, 비동기 복제의 승격에는 최근 쓰기 유실 가능성이 있다.
자세한 범위는 [현재 Redis 구성](backend-design.md#현재-redis-구성과-failover-범위)을 참고한다.

## 최초 빈 실습 데이터셋 준비

쿠폰 원본과 기존 쿠폰 projection이 모두 없는 새 실습 데이터셋에서만 bootstrap 확인을 사용한다.
캠페인 1(한도 100, 고정 혜택)을 준비하며 사용자 생성은 기존 로컬 기능을 사용한다.
모든 이전 발급 실행·Redis 작업이 없음을 확인한 뒤 명시적으로 수용을 연다.

```shell
./gradlew :apps:commerce-api:bootRun --args='--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=none --coupon.ssot.bootstrap-confirmed=true --coupon.admission.open-after-quiescence=true --coupon.projection.enabled=true'
```

기존 DB를 보존하는 예시이므로 빈 DB라면 테이블을 먼저 준비해야 한다. 기존 전체 스키마를 삭제해서 해결하지 않는다.
bootstrap·open 확인 플래그를 상시 설정이나 자동 재시작 설정에 true로 저장하지 않는다.

## 정상 재시작과 장애 재개

앱을 종료하고 이전 발급 실행·Redis 작업의 종료와 실제 데이터의 READY·수량·발급 사실·Stream을 확인한다.
정상 Redis 데이터는 재시작 시 그대로 사용한다. bootstrap 확인은 다시 넘기지 않는다.

```shell
./gradlew :apps:commerce-api:bootRun --args='--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=none --coupon.admission.open-after-quiescence=true --coupon.projection.enabled=true'
```

Redis 준비 실패·DIRTY·설정 불일치는 local_coupon_setup 오류 로그를 남기고 쿠폰 수용을 닫는다.
쿠폰 준비 실패 때문에 상품·주문 등 전체 로컬 앱의 시작을 중단하지 않는다.
기본 수용은 CLOSED다. open 플래그 없이 시작하면 새 발급은 503이지만 건강한 Redis의 기존 결과·보유 목록은 읽는다.
projection scheduler도 기본 비활성이며 위 enabled=true에서만 1초 간격으로 최대 20개씩 처리한다.
발급 기간·할인 적용·관리 CRUD·사용자 대기실·Kafka는 이번 변경에 포함하지 않는다.

- Redis 획득/발급 결과 불명·반환 실패: 신규 수용을 닫고 token을 성급히 재사용하지 않는다.
- 응답 유실: 저장된 READY 발급 결과를 확인한다. 임의로 재고를 증가시키지 않는다.
- DB 반영 실패: 발급은 유지하고 pending을 재처리한다. DB commit 후 ACK 유실도 중복 증가 없이 재처리한다.
- DIRTY: worker와 발급을 멈추고 부분 변경을 조사한다. 재개·PONG·세마포어 초기화로 DIRTY를 지우지 않는다.
- 기존 캠페인/owner 키 유실: 빈 데이터로 다시 준비하지 않는다. 원본 복구가 필요하다.

## API

```shell
curl -X POST http://localhost:8080/api/v1/coupon-campaigns/1/issues -H 'X-USER-ID: 1'
curl http://localhost:8080/api/v1/coupons -H 'X-USER-ID: 1'
```

## 검증 명령

```shell
./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:test --tests 'com.loopers.domain.*' --tests 'com.loopers.application.coupon.*' --tests 'com.loopers.architecture.ArchitectureTest'
./gradlew :apps:commerce-api:couponRedisLocalTest
python3 docs/week8/tooling/verify-permit-lua.py
python3 docs/week8/tooling/verify-lua-docker.py
./gradlew :apps:commerce-api:test --tests 'com.loopers.coupon.*'
./gradlew :apps:commerce-api:check --continue
```

couponRedisLocalTest는 설치된 redis-server를 사용하는 전용 task로 일반 test와 분리한다.
테스트 소유 process·임시 AOF만 준비·종료하며 기존 endpoint에는 연결하지 않는다.
Python도 격리된 실제 Redis에서 production Lua를 읽는다.
verify-lua-docker.py는 Compose와 같은 Redis 7.0의 전용 컨테이너에서 같은 smoke와 정상 AOF 재시작을 검증하고 컨테이너를 정리한다.
최종 API 250·W7 E2E 2·JVM Redis 27건과 전체 API check 통과 결과는 [검증 기록](verification-report.md)에 있다.
전체 check의 W7 E2E에는 JDK 21이 필요하며, 실행 기기의 JDK 홈을 `org.gradle.java.home`으로 지정할 수 있다.
실제 HA failover와 성능 비교는 검증하지 않았다.
MySQL·HTTP·primary/replica·endpoint 중단의 Testcontainers 검증에는 Docker daemon이 필요하다.
