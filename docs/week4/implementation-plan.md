# 4주차 상품 목록 조회 최적화와 Redis 캐시 구현 계획

상품 목록에 Redis cache-aside를 적용하고, 목록을 바꾸는 쓰기의 commit 이후 캐시를 무효화한다.
현재 조회의 기준 성능을 먼저 확보한 뒤 필요한 SQL 개선과 캐시 효과를 각각 비교한다.
다른 세션에서는 이 계획과 아래 기준 문서를 읽고 구현부터 실제 검증까지 진행한다.

## 1. 기준과 현재 상태

- `[사용자 요청]` 앞서 정리한 코드 작업을 구현할 수 있도록 계획과 다른 세션용 실행 프롬프트를 준비한다.
- `[기준 문서]` 저장소 루트의 [AGENTS.md](../../AGENTS.md),
  [커머스 설계 19절](../week2/commerce-design.md#19-4주차-상품-목록-조회와-캐시-설계).
- `[기존 계약]` 커머스 설계의 API·Aggregate·저장 경계와
  [3주차 Backend 설계](../week3/backend-design.md),
  [트랜잭션 오류 정책](../week3/transaction-error-policy.md)을 유지한다.
- `[코드 확인]` 2026-10-15 기준 브랜치는 `jsjouo249`, HEAD는 `491803e`다.
  3주차는 PR #10으로 병합됐다. 시작 시 기존 미커밋 변경은 `docs/week2/commerce-design.md`의
  4주차 설계 추가다. 이 변경을 보존한다.
- `[기록 확인]` 마지막 전체 검증은 [리뷰 보완 기록](../week3/review-fixes.md)의
  122 tests / 0 failures / 0 errors / 0 skipped다. 새 구현의 통과 결과로 재사용하지 않는다.
- `[코드 확인]` 목록 캐시와 commit 이후 무효화는 아직 없다. 기존 Redis 연결 설정은 있다.
  좋아요 projection과 정렬 인덱스 6개, 정확한 COUNT 1회, ID 페이지 후 일괄 조회는 이미 구현됐다.
- `[구현 계획]` 아래 단계와 책임 분리는 19절의 확정된 계약을 구현하기 위한 작업 순서다.
  새 타입의 구체적인 이름과 SQL 개선 방법은 구현 시 코드와 측정 결과로 정한다.

## 2. 유지할 계약

| 항목 | 구현 기준 |
|---|---|
| 대상 | 공개 `GET /api/v1/products`의 공통 목록과 페이지 정보 |
| 정렬 | `latest`, `price_asc`, `likes_desc`와 기존 동률 순서 유지. 전체 후보 정렬 후 페이지 선택 |
| 페이지 | `brandId`, `page`, `size`, OFFSET 검증과 snapshot 기준 정확한 전체 건수 유지 |
| 키 | `products:v1:list:brand={brandId\|all}:sort={sort}:page={page}:size={size}`. 입력 검증 후 생성 |
| 수명 | 저장 시점부터 TTL 30초. hit에서 연장하지 않음 |
| Redis 읽기 | 기존 `redisTemplateMaster`의 MASTER 읽기 사용 |
| 노출 확인 | hit의 상품 ID들에 대해 MySQL에서 상품·브랜드 활성 상태를 일괄 확인 |
| DB 재조회 | 삭제 대상이 있으면 페이지와 COUNT 전체를 다시 조회. 일부 항목만 제거하지 않음 |
| 읽기 일관성 | hit 노출 확인과 fallback, miss의 목록·COUNT는 같은 readOnly REPEATABLE_READ snapshot 사용 |
| 무효화 | 영향 쓰기의 기존 트랜잭션 안에서 `afterCommit` 등록. 정상 경로에서는 쓰기 응답 전에 완료 |
| 범위 | 모든 API 인스턴스가 공유하는 `products:v1:list:` 목록 영역 전체 |
| 장애 | 캐시 읽기 실패는 DB 조회로 우회하고 그 요청의 캐시 저장 생략. 저장·삭제 실패는 별도 관측 |
| 업무 결과 | commit 이후 캐시 실패로 DB 변경을 실패 처리하거나 업무를 재시도하지 않음 |
| 허용되는 오래됨 | 무효화 실패·이전 조회의 늦은 저장에서 표시 필드와 해당 목록 snapshot의 일시적인 오래됨 허용 |
| 보호할 규칙 | 삭제 비노출, 개인 기능의 소유권, 주문의 DB 가격 조회·snapshot·확정 검증 유지 |

상품 상세, 내 좋아요·주문·포인트는 기존 조회를 유지한다. DB revision, 분산 잠금, outbox,
지속 재처리 큐와 단일 JVM 상태에 의존하는 보호 대책은 이번 설계에 추가하지 않는다.
TTL 30초를 DB 변경 이후 모든 요청의 최신성 상한으로 해석하지 않는다.

## 3. 구현 순서와 완료 기준

### 3.1 기준 성능을 재현하는 코드

책임은 테스트 지원 코드와 측정 도구가 가진다. 기존
[ReviewQueryPlanTest](../../apps/commerce-api/src/test/kotlin/com/loopers/application/transaction/ReviewQueryPlanTest.kt)는
상품 2,000개에서 ID 선택 SQL의 인덱스를 확인하므로 실제 API 전체 경로 측정을 보완한다.

- 상품 10,000개 이상, 브랜드와 좋아요 관계를 재현 가능하게 생성한다.
  projection과 관계 수, unique·check 제약을 함께 만족시킨다.
- 브랜드 순번 7의 실제 ID를 출력하고 `sort=price_asc&page=0&size=20`을 고정한다.
  PK가 반드시 7이라고 가정하지 않는다.
- 준비 20회, 측정 100회, 동시 요청 1개로 현재 DB-only 기준값을 먼저 저장한다.
- 실제 HTTP 목록 요청의 SQL 수, 좋아요 조회 비용, 관련 SQL의 EXPLAIN·EXPLAIN ANALYZE,
  rows·loops·정렬, HTTP p50·p95·p99와 원본 호출 수를 수집한다.
- 정렬된 ID·가격·좋아요 수·페이지 정보를 함께 저장해 이후 결과와 직접 비교한다.
- seed와 측정 도구는 테스트/로컬 전용으로 두고 측정용 공개 HTTP API를 추가하지 않는다.

완료 기준은 같은 환경과 seed에서 기준값과 응답을 다시 얻을 수 있는 것이다.
측정 코드만 작성하고 수치가 확보되지 않은 상태를 측정 완료로 표시하지 않는다.

### 3.2 필요한 DB 조회 개선

주요 확인 대상은
[CustomerProductQueryService](../../apps/commerce-api/src/main/kotlin/com/loopers/application/product/CustomerProductQueryService.kt),
[ProductRepository](../../apps/commerce-api/src/main/kotlin/com/loopers/domain/product/ProductRepository.kt),
[ProductEntityRepository](../../apps/commerce-api/src/main/kotlin/com/loopers/infrastructure/product/ProductEntityRepository.kt),
[JpaProductEntityRepository](../../apps/commerce-api/src/main/kotlin/com/loopers/infrastructure/product/JpaProductEntityRepository.kt),
[ProductJpaRepository](../../apps/commerce-api/src/main/kotlin/com/loopers/infrastructure/product/ProductJpaRepository.kt),
[ProductLikeJpaRepository](../../apps/commerce-api/src/main/kotlin/com/loopers/infrastructure/like/ProductLikeJpaRepository.kt)다.

- 비어 있지 않은 목록의 ID 페이지 → 상품 → 브랜드 → 좋아요 카운터 → COUNT가
  코드상 SELECT 5회인 점을 실측한다.
- 별도 카운터 조회 통합 등 개선 후보를 실행 계획과 측정으로 평가한다.
  좋아요 수는 이미 `products.like_count`를 읽으므로 매번 관계 전체를 집계하는 것으로 설명하지 않는다.
- 기존 인덱스와 projection을 재구현하지 않는다. 새 인덱스는 실제 조회 계획에 근거가 있을 때만 검토한다.
- 전체 후보 정렬, 동률 정렬, 정확한 COUNT, snapshot, 삭제 제외와 Aggregate 경계를 유지한다.
  projection을 전달하려고 Product Aggregate의 업무 상태를 불필요하게 확장하지 않는다.
- 같은 조건의 캐시 없는 개선 전후를 비교하고 효과와 비용을 기록한다.
  측정 근거 없이 특정 JOIN·projection·인덱스 추가를 필수 작업으로 고정하지 않는다.

완료 기준은 응답의 의미가 같고, 선택한 개선의 효과와 비용을 실측으로 설명할 수 있는 것이다.
추가 개선이 유효하지 않으면 기존 최적화를 유지하는 근거를 남긴다.

### 3.3 캐시 계약과 Redis 구현

- 기술 중립 캐시 계약과 목록 snapshot 데이터를 정의한다. 구체적인 클래스명은 구현 선택이다.
  infrastructure가 `CustomerProductResult`나 `PageResult` 등 application 타입을 참조하지 않게 한다.
- domain의 계약·데이터에 응답 조합 행동, Redis·Spring Data·HTTP 의존을 넣지 않는다.
  조회 조율과 응답 조합은 application, 직렬화와 Redis 명령은 infrastructure가 책임진다.
- 정규화한 필터·정렬·페이지·크기로 키를 생성하고 공통 상품 필드·페이지 정보를 직렬화한다.
  시각 필드와 빈 목록도 왕복 저장·조회가 가능해야 한다.
- 캐시 miss와 읽기 실패를 구분해 읽기 실패 뒤 같은 요청에서 저장하지 않는 흐름을 지원한다.
- 기존 [RedisConfig](../../modules/redis/src/main/kotlin/com/loopers/config/redis/RedisConfig.kt)를 확인하고,
  캐시의 연결·명령 timeout 각각 200ms와 MASTER 읽기가 실제 주입된 연결에 적용되게 한다.
- 목록 전용 영역의 키를 찾아 묶어서 삭제한다. SCAN과 batch 삭제 등을 검토하되 공유 Redis의
  FLUSHDB/FLUSHALL을 사용하지 않는다. 동시 채움에서의 오래됨은 19절의 허용 범위로 검증한다.
- 기존 로그·관측 기반으로 hit, miss, 읽기·저장·삭제 실패와 실제 DB 목록 재조회 수를 구분한다.
  영역 전체 삭제의 키·명령 수와 대기 비용도 측정할 수 있게 한다.

완료 기준은 실제 Redis에서 키 분리, 데이터 왕복, 고정 TTL, MASTER 설정과 전용 영역 삭제를 검증하는 것이다.

### 3.4 목록 읽기 연결과 삭제 노출 보호

- `CustomerProductQueryService.list()`에서 기존 입력 검증을 먼저 수행한 후 캐시를 읽는다.
- hit이면 캐시의 모든 상품 ID에 대해 상품과 해당 브랜드의 활성 상태를 MySQL에서 일괄 확인한다.
  필요한 기술 중립 조회 계약과 JPA/native 조회를 추가하고 N+1 조회나 쓰기 잠금을 도입하지 않는다.
- 모두 노출 가능할 때 캐시 snapshot을 반환한다. 삭제 상품·브랜드가 있으면 페이지와 COUNT를 다시 읽는다.
  이 검사는 최신 가격·정렬·전체 건수를 다시 계산하는 검사가 아니다.
- miss와 노출 검사 후 fallback은 목록·COUNT를 같은 snapshot에서 조합해 캐시에 저장한다.
  hit 노출 검사와 같은 요청의 fallback도 같은 snapshot을 유지한다. Spring 프록시 진입점을 확인한다.
- Redis 읽기 실패는 DB로 우회하고 해당 요청의 put을 생략한다. DB 실패는 기존 오류로 전달한다.
  Redis 오류를 처리하는 코드가 업무·DB 예외까지 삼키지 않게 한다.
- 상품 상세와 개인 조회가 목록 캐시에 의존하지 않게 한다.

완료 기준은 DB-only, miss, hit의 응답 계약이 같고, 삭제 commit 뒤 시작한 새 요청에서
무효화 실패·늦은 저장이 있어도 삭제 대상이 노출되지 않는 것이다.

### 3.5 영향 쓰기 9개의 commit 이후 무효화

| 기존 서비스 | 연결할 operation |
|---|---|
| ProductApplicationService | `product.create`, `product.details`, `product.stock`, `product.delete` |
| BrandApplicationService | `brand.rename`, `brand.delete` |
| ProductLikeApplicationService | `product.like`, `product.unlike` |
| OrderApplicationService | `order.confirm` |

- 공통 application 처리를 만들어 각 쓰기의 기존 트랜잭션 안에서 `afterCommit`을 등록한다.
  유스케이스별 DB 변경 뒤 등록하되 repository에 목록 무효화 업무를 흩어 넣지 않는다.
- [WriteTransactionAttempt](../../apps/commerce-api/src/main/kotlin/com/loopers/application/transaction/WriteTransactionAttempt.kt)와
  [RetryingWriteExecutor](../../apps/commerce-api/src/main/kotlin/com/loopers/application/transaction/RetryingWriteExecutor.kt)의
  잠금 순서, rollback 확인, deadlock 1회 재시도와 관측을 유지한다.
- rollback된 시도는 삭제하지 않고 최종 commit된 시도에서 무효화한다.
  afterCommit의 Redis 실패는 격리해 업무 재시도·실패 응답으로 이어지지 않게 한다.
- 정상 경로에서는 쓰기 응답 전에 공통 Redis 영역 삭제를 끝낸다.
  인스턴스마다 별도 메모리 상태로 무효화 완료를 판단하지 않는다.
- 브랜드 생성, 주문 DRAFT 생성, 포인트 충전은 무효화 대상이 아니다.
  중복 좋아요 등 상태가 같은 성공 요청의 보수적 무효화는 허용한다.

완료 기준은 9개 경로의 정상 변경 후 순차 목록 조회가 변경을 반영하고,
rollback과 재시도에서도 업무 상태·무효화 횟수·응답이 계약을 지키는 것이다.

### 3.6 장애와 경쟁 검증

실제 controller·application·repository·MySQL·Redis를 연결하는 테스트와 필요한 단위 테스트를 작성한다.
대표 규칙은 AGENTS.md에 따라 의도한 Red를 확인한 뒤 Green으로 구현한다.

| 검증 묶음 | 확인할 결과 |
|---|---|
| 입력·키·직렬화 | 잘못된 입력은 기존 400. 필터·정렬·페이지·크기의 키 격리, 시각·빈 목록 왕복, hit에서 TTL 미연장 |
| 목록 의미 | 세 정렬·동률·브랜드 필터·페이지·COUNT, miss/hit와 DB-only 응답 일치 |
| 삭제 보호 | 상품 삭제·브랜드 일괄 삭제 뒤 캐시가 남아도 삭제 대상 비노출. 부분 필터링 대신 DB 페이지 재조회 |
| commit 경계 | 9개 정상 경로 무효화, 실제 SQL 이후 rollback에서는 미실행, deadlock 실패 시도와 최종 commit 구분 |
| 인스턴스 공유 | 서로 독립된 서비스/클라이언트가 같은 MySQL·Redis를 사용해 쓰기 응답 뒤 다른 쪽 조회에서도 무효화 효과 확인 |
| 읽기·저장·삭제 실패 | 읽기 실패 시 DB 우회·put 생략, put 실패 시 정상 목록 응답, 삭제 실패 시 DB 업무 성공 유지·재시도 없음 |
| 무효화 실패 복구 | 변경 commit → 삭제 실패 → 새 요청의 옛 표시 → TTL 만료 → 현재 DB로 재채움·후속 hit 일치 |
| 늦은 저장 복구 | 옛 DB 결과 읽기 → 변경 commit·삭제 → 옛 결과 저장 → 새 요청의 옛 표시 → 만료·재채움·후속 hit 일치 |
| 실제 Redis 중단 | 중단 중 DB 우회와 실제 대기 관측, 재시작 뒤 남은 키·만료 정책과 DB 재채움 확인 |
| 업무 회귀 | 장애·경쟁에서도 소유권, 주문 생성의 DB 가격, 기존 주문 snapshot, 확정 시 재고·포인트 검증 유지 |

경쟁 시나리오는 latch·테스트 훅과 유한한 timeout으로 순서를 재현한다. 오래된 결과를 저장할
worker가 종료된 것을 확인한 뒤 만료·무효화와 DB 재채움을 검증하고 worker·키·자원을 정리한다.
Redis 연결 성공만으로 복구 완료를 판단하지 않는다. TTL 검증은 테스트 설정과 제한 시간을 이용하되
운영 기본값 30초와 저장 시점 기준 만료 계약도 별도로 확인한다.

실제 중단·재시작은 테스트 전용 Redis에만 수행한다. DB 사전조건은 worker 시작 전에 commit하고,
변경 상태는 필요한 flush/clear 또는 별도 트랜잭션 재조회로 확인한다.

### 3.7 최종 측정과 검사

- 같은 seed·DB 버전·환경·요청으로 DB-only 개선 전후, cache miss, cache hit를 비교한다.
  miss는 매번 실제 miss인지, hit는 실제 hit인지 관측하고 조건 준비 비용과 요청 시간을 구분한다.
- hit의 MySQL 노출 검사, 전체 목록 영역 무효화와 그 뒤 재조회 비용까지 포함한다.
  캐시 hit를 DB 0회로 보고하지 않는다.
- 기존 테스트의 기대값·정적 규칙을 유지하고 관련 테스트 → `ktlintCheck` → `ArchitectureTest` →
  전체 commerce-api `check`를 실행한다. 중간에 계획한 검사까지 포함됐는지 확인한다.
- 구현 진행과 실제 결과를 `docs/week4/`에 기록한다. 테스트 건수·실패·오류·skip·종료 코드,
  SQL·실행 계획·지연시간·경쟁 결과와 남은 비용을 구분한다.
- 기준 설계와 구현이 달라져야 한다면 AGENTS.md대로 영향과 이유를 설명하고 확인 후 함께 갱신한다.

## 4. 실행 환경과 범위

- 시작 시 현재 `git status`와 HEAD, 관련 코드·설계를 다시 확인하고 기존 미커밋 변경을 보존한다.
- Java·Gradle·MySQL·Redis와 기존 Testcontainers 구성을 확인한다. Docker Desktop이나 Compose를
  자동 실행하지 않는다. 테스트 전용 컨테이너는 기존 테스트 방식과 실행 가능한 데몬을 사용한다.
- 환경 연결 실패는 권한 문제인지 확인하고 필요한 승격 절차를 따른다.
  실행 불가 항목과 이유를 남기고 미실행 검사를 통과로 기록하지 않는다.
- 요청과 무관한 패키지 재배치·공통화·API 변경·DB 잠금을 추가하지 않는다.
  기존 ArchUnit 규칙과 Aggregate RepositoryImpl의 책임을 유지한다.
- 이번 인계 범위는 구현·검증·관련 결과 문서다. 기술 글, commit·push·PR 발행과 운영 DB 변경은 포함하지 않는다.

대표 최종 명령은 다음과 같다. 실행 환경은 기존 구성을 확인해 적용한다.

```bash
./gradlew :apps:commerce-api:test --tests '*ArchitectureTest' --console=plain
./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check --console=plain
git diff --check
```

진행 중 세션을 이어야 한다면 완료한 단계, 실제 실행한 명령과 결과, 남은 단계, 수정 파일과
아직 해결하지 못한 설계·환경 문제를 이 문서 또는 별도 인계 기록에 남긴다.
