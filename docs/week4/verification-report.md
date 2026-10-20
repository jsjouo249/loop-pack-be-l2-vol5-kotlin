# 4주차 구현·검증 결과

2026-10-15, HEAD `491803e` 위의 미커밋 작업 트리에서 구현했다. 시작 시 존재한 커머스 설계 19절과
구현 계획을 보존했다. 이 기록은 이번에 실행한 결과만 사용하며 과거 122건 기록을 재사용하지 않는다.

## 구현과 설계 대조

- `[요구/사용자 결정]` 공개 상품 목록만 cache-aside하고 입력·정렬·동률·페이지·정확한 COUNT를 유지한다.
  상품 상세·개인 조회·주문 판단은 DB 기반이다. 설계 19절의 제품 정책과 계약은 변경하지 않았다.
- `[코드 확인/측정]` 기존 like_count projection·정렬 인덱스 6개와 covering ID 페이지 조회를 재사용했다.
  최초 SQL 5회 중 상품 행이 이미 like_count를 읽으며 별도 카운터 조회를 다시 수행하는 것을 확인했다.
- `[구현 선택]` 기술 중립 `ProductListQuery`/`ProductListItem`에 목록 조회용 데이터를 정의하고
  JPA adapter가 ID 선택 후 필요한 상품·브랜드·카운터만 한 SELECT로 조합한다. COUNT는 기존 한 번이다.
  Aggregate 업무 상태에 조회 카운터를 추가하지 않았다. 새 인덱스·테이블·잠금·FK는 없다.
- `[구현 선택]` domain의 `ProductListCache`, key, snapshot, Hit/Miss/Unavailable과 infrastructure의
  `RedisProductListCache`를 분리했다. Redis adapter는 application/HTTP 타입에 의존하지 않는다.
  application이 입력 검증·DB 협력·응답 조합·cache-aside를 담당한다.
- `[확정 계약 구현]` 정규화 입력으로 `products:v1:list:brand={brandId|all}:sort={sort}:page={page}:size={size}`를
  만든다. 저장 시점 TTL 30초이며 hit에서 연장하지 않는다. ZonedDateTime의 offset/zone과 빈 목록도 왕복한다.
- `[확정 계약 구현]` 실제 주입된 기존 MASTER template의 연결·명령 timeout이 각각 200ms다.
  hit에서 상품·브랜드 활성 ID를 MySQL JOIN 한 번으로 검사하고 하나라도 누락되면 페이지와 COUNT 전체를 다시 읽는다.
  빈 hit는 검사할 ID가 없어 SQL 0회다. 비어 있지 않은 hit는 SQL 1회다.
- `[확정 계약 구현]` 공개 list 프록시 진입점의 readOnly REPEATABLE_READ 안에서 hit 확인·fallback·miss·COUNT를
  조합한다. DB 예외는 기존 HTTP 오류로 전달하며 캐시 처리로 삼키지 않는다.
- `[확정 계약 구현]` 기존 트랜잭션에서 DB 변경 뒤 `ProductListInvalidation.afterCommit()`을 등록한다.
  `product.create/details/stock/delete`, `brand.rename/delete`, `product.like/unlike`, `order.confirm`의 9개다.
  rollback callback은 실행하지 않고 정상 경로는 쓰기 반환 전에 공통 영역을 삭제한다.
  브랜드 생성·DRAFT 생성·포인트 충전은 제외했다.
- `[확정 계약 구현]` SCAN과 최대 200개 단위 DEL로 해당 prefix만 무효화한다. FLUSHDB/FLUSHALL을 쓰지 않는다.
  Redis 읽기 실패는 DB로 우회하고 해당 요청의 put을 생략한다. put/삭제 실패도 관측하고 업무 결과·DB 재시도와 격리한다.
- `[유지]` 기존 snapshot, 부분 저장, 잠금 순서, rollback 확인·deadlock 1회 재시도와 오류 분류를 변경하지 않았다.
  기존 순서 검증 테스트에는 추가 생성자 의존을 mock으로 전달했고, 목록 snapshot 회귀의 지연 훅은 실제 새 조회 계약으로 옮겼다.
  기존 기대값과 ArchUnit/lint 규칙은 완화하지 않았다.
- `[테스트 지원]` DB를 직접 seed/truncate하는 기존 테스트 사이에 캐시가 섞이지 않도록 test listener가 목록 prefix만 정리한다.
  측정은 별도 `performance` tag/task로 실행하고 통상 test/check에는 포함하지 않는다.

운영 코드는 [CustomerProductQueryService](../../apps/commerce-api/src/main/kotlin/com/loopers/application/product/CustomerProductQueryService.kt),
[JpaProductListQuery](../../apps/commerce-api/src/main/kotlin/com/loopers/infrastructure/product/JpaProductListQuery.kt),
[RedisProductListCache](../../apps/commerce-api/src/main/kotlin/com/loopers/infrastructure/product/RedisProductListCache.kt),
[ProductListInvalidation](../../apps/commerce-api/src/main/kotlin/com/loopers/application/product/ProductListInvalidation.kt)를 중심으로 확인할 수 있다.

## 환경과 재현 조건

- Temurin 21.0.12.1, Gradle 8.13, 사용자 실행 Docker 29.7.2, Testcontainers MySQL 8.0.46.
- 기존 테스트 Redis `redis:latest`는 이번 환경에 캐시된 8.0.2 소스 이미지였다.
  로컬 image ID는 `sha256:c1ae424e8e63f698a382358b0fb25999514601acedd945590322b7dc82029d3d`다.
  중단 실험은 별개 `redis:7.2` (7.2.16), image ID
  `sha256:1e3872c44615c5574ead2261f2f165807ba28875fecdb2a4aada4aacf7bfbe8b`만 사용했다.
  버전은 로컬 image metadata로 확인했다.
- 브랜드 10·상품 10,000·좋아요 관계 45,000건. projection=관계 수를 확인하고 ANALYZE TABLE 후 실행했다.
  브랜드 순번 7의 실제 ID는 1007이다. 가격과 카운터는 고정 공식, 시각은 고정값이며 제약을 유지한다.
- 실제 HTTP 서버의 `GET /api/v1/products?brandId=1007&sort=price_asc&page=0&size=20`을 호출한다.
  각 모드 준비 20회·SQL/응답 표본 1회·측정 100회, 동시성 1이다. 클라이언트 왕복·JSON 읽기·응답 검증을 포함한다.
  별도 SQL 표본은 100회 통계 밖이다.
- 최종 DB-only는 테스트 spy로 cache read를 Unavailable로 바꿔 Redis GET/SET을 우회한다.
  실제 실패 지연을 DB-only 비용에 넣지 않으며 운영용 우회 API/설정은 추가하지 않았다.
  miss는 각 요청 전에 실제 영역을 삭제하고, hit는 실제 저장된 값으로 검증한다.
- 최초 기준은 운영 코드 변경 전에 얻었다. 재현 도구는 491803e를 별도 임시 폴더로 export하여 현재 작업 트리를 건드리지 않는다.
  모든 측정 응답의 정렬 ID·가격·좋아요·브랜드·페이지 필드를 원본 기준과 직접 비교했고 모두 일치했다.
- seed/실행 방법: [tooling/README.md](tooling/README.md). 원시 호출별 지연·SQL·실행 계획·응답: [measurements](measurements/).

## 성능 측정

단위 ms, 로컬 단일 실행의 표본 백분위이며 운영 처리량·유의성을 증명하는 결과는 아니다.

| 측정 | 요청당 SELECT | p50 | p95 | p99 |
|---|---:|---:|---:|---:|
| [최초 기준 (운영 변경 전)](measurements/baseline.json) | 5 | 16.72 | 26.78 | 38.54 |
| [조회 개선만 적용](measurements/improved-db-only.json) | 3 | 11.91 | 16.57 | 19.90 |
| [기준 재현 (491803e export)](measurements/baseline-reproduced.json) | 5 | 22.50 | 36.11 | 40.79 |
| [최종 DB-only](measurements/final-db-only.json) | 3 | 12.30 | 17.01 | 20.02 |
| [최종 cache miss](measurements/final-miss.json) | 3 | 16.60 | 24.56 | 29.30 |
| [최종 cache hit](measurements/final-hit.json) | 1 | 8.98 | 11.94 | 15.24 |

최종 100회 각각의 실제 SQL 수는 DB-only 3·miss 3·hit 1로 모두 일치했다.
DB 원본 목록 호출은 각각 100·100·0회, cache miss는 miss 모드 100회, cache hit는 hit 모드 100회였다.
SQL 총수는 각각 300·300·100회다. hit의 활성 검사를 DB 0회로 계산하지 않았다.
캐시 miss 준비(영역 삭제)는 요청 타이머 밖이며 이번 실행의 평균 준비 비용은 **6.07ms**다.

조회 개선만의 최초 비교는 5→3 SELECT다. 네트워크 왕복과 불필요한 전체 Entity 물질화를 줄였고,
새 인덱스 쓰기 비용은 늘리지 않았다. 최종 miss에는 Redis GET+직렬화+SET 비용이 추가된다.
정확한 COUNT/OFFSET 비용과 hit의 활성 검사는 남는다.

타이머의 응답 검증 포함 경계를 최초 기준과 통일해 최종 측정을 다시 실행했다.
이전 측정에서는 hit p95 20.84ms가 DB-only 19.31ms보다 높았고, 최종 실행의 순위는 달랐다.
기준 재현 실행도 최초 기준과 지연이 달랐다. [측정 이력](measurements/measurement-history.json)을 함께 남겼으며
로컬 실행 변동을 캐시 효과로 단정하지 않는다. 확실히 관측한 변화는 SQL 수와 원본 목록/COUNT 호출 감소다.

### SQL·실행 계획 근거

- 최초 ID 선택은 `idx_products_active_brand_price` covering lookup, actual rows=20/loops=1, 별도 Sort 없음.
  정확한 COUNT는 브랜드 활성 인덱스에서 actual rows=1000/loops=1이다.
- 최초 별도 카운터 SELECT는 `products` PRIMARY의 20행/loops=1이며 약 0.008..0.021ms였다.
  `product_likes` 전체 관계 집계가 아니다. 이 projection을 새 표시 JOIN에 포함해 별도 왕복을 없앴다.
- 개선의 페이지 표시 JOIN은 PRIMARY 20행/loops=1, 브랜드 PK 1행/loops=20이다.
  JPA/HTTP N+1 쿼리가 아니라 한 SQL 내부 nested-loop다. ID 선택과 최종 ID 순서 복원으로 동률/페이지 의미를 보존한다.
- 최종 hit의 활성 확인도 상품 PRIMARY 20행/loops=1 + 브랜드 PK loops=20인 단일 JOIN이다.
  목록 ID 선택·표시 필드 재조회·전체 COUNT는 실행하지 않는다.
- 각 관련 실제 Hibernate SQL의 바인딩된 EXPLAIN/EXPLAIN ANALYZE와 rows/loops/정렬 정보는 해당 JSON에 있다.
  기존 인덱스 6개 검증과 COUNT 1회/관계 집계 부재 회귀도 전체 check에서 실행한다.

### 영역 무효화 비용

목록 키 **501개**, 다른 prefix **1000개**에서 SCAN **22회** +
DEL **3회**, 실제 삭제 **501개**, **18.88ms**였다.
`INFO commandstats` 전후 차이를 사용했다. 키 수·DEL 수·대기 시간은 application Redis adapter의 관측과도 대조했다.
다른 prefix 1,000개의 값은 모두 보존됐고 마지막에 테스트가 정리했다.
이전 단일 측정은 6.56ms였으므로 이 수치를 상한/평균으로 해석하지 않는다.
전체 영역 삭제 후의 재조회 비용은 위 miss·준비 시간으로 따로 측정했다.
SCAN은 다른 prefix도 순회하므로 공유 Redis 전체 키 수에 따른 비용이 남는다.

## 장애·경쟁·복구 검증

- 입력 검증 전 Redis 접근 없음, 필터·sort·page·size 키 분리, 시각/빈 목록 왕복과 기본 30초 TTL·hit 미연장.
- 세 정렬·동률·필터·페이지·COUNT에 대해 DB-only/miss/hit 응답 일치.
- 삭제 무효화가 실행되지 않아 키가 남아도 상품/브랜드 삭제 후 새 요청은 삭제 대상을 반환하지 않는다.
  누락 항목만 필터링하지 않고 다음 상품으로 페이지를 채우고 COUNT를 다시 계산했다.
- hit 활성 검사 뒤 다른 transaction의 삭제 commit을 강제로 끼웠다. 같은 요청 fallback은 검사 때의 snapshot으로
  페이지·COUNT를 일관되게 반환하고, 다음 요청은 삭제를 확인한다.
- 9개 정상 변경의 반환 직후 영역이 비어 있고 순차 miss는 현재 DB와 같다. 제외된 쓰기 3개는 무효화하지 않는다.
- 실제 재고 SQL과 afterCommit 등록 뒤 예외를 주입했다. flush/clear 후 이전 재고와 기존 키가 유지됐다.
  실제 SQL 뒤 synthetic 1213/40001을 주입한 첫 시도는 rollback·무효화 0회, 두 번째 commit만 1회 무효화했고
  주문 재고/잔액은 정확히 한 번 차감됐다. 실제 MySQL deadlock/timeout/전체 rollback의 기존 정책 테스트도 전체 check에 포함한다.
- cache read 실패/깨진 JSON은 DB 응답+put 생략, put 실패는 목록 정상, evict 실패는 DB 변경 정상·업무 재시도 0회다.
  DB 예외는 기존 500으로 전달한다. 읽기/저장/삭제 실패는 별도 counter를 확인했다.
- 독립 Redis 연결 factory·adapter와 별도 서비스 객체가 같은 DB/Redis를 사용해 양방향 무효화를 확인했다.
  한 JVM의 로컬 완료 플래그나 알림으로 무효화를 대신하지 않았다.
- commit→삭제 실패→옛 표시, 이전 읽기→commit/삭제→늦은 put→옛 표시 두 장면을 래치로 재현했다.
  future 종료·pool termination을 확인한 후 테스트 TTL 250ms로 만료시키고 DB-only=miss=후속 hit를 확인했다.
  각 장면에서 개인 소유권·요청자 포인트, DB 가격으로 DRAFT 생성, 주문 단가 snapshot, 재고 재검증·삭제 거절을 확인했다.
  늦은 put이 삭제와 겹치는 상품/브랜드 장면도 각각 비노출을 검증했다.
- 별도 테스트 전용 Redis를 실제 중단/재시작했다. 중단 HTTP **226.60ms**, CLIENT PAUSE HTTP
  **220.81ms**에 DB 우회·put 생략을 확인했다. 읽기 실패 2회, 삭제 실패 1회를 관측했다.
  중단 중 DB 가격 변경은 commit됐다. 재시작 뒤 AOF의 옛 snapshot을 확인하고, 만료→현재 DB 재채움→hit 일치를 검증했다.
  연결 성공만으로 복구 판정하지 않았으며 factory/container는 finally/use에서 정리했다.
  이는 해당 환경의 관측값이며 HTTP 전체 200ms 상한을 약속하지 않는다.

관측명: `commerce.product.list.cache`의 outcome은 hit/miss/put/evict/read_failure/put_failure/evict_failure다.
Redis read hit와 최종 노출 판정은 구분하며 `commerce.product.list.visibility.rejected`, `commerce.product.list.db.loads`를 둔다.
영역 삭제는 `commerce.product.list.cache.evicted.keys`, `.delete.commands`, `.eviction`과 로그로 관측한다.
SCAN은 benchmark의 실제 Redis commandstats로 측정한다.

## 실행 검사와 수리 이력

최종 집계는 [verification-results.json](verification-results.json), 관련 회귀는 [related-results.json](related-results.json),
별도 ArchUnit은 [architecture-results.json](architecture-results.json)을 따른다. 명령 prefix는 [progress.md](progress.md)와 같다.
최종 작업 트리에서 아래 명령의 종료 코드와 새 JUnit XML을 다시 확인했다.

- 대표 Red: 반복 목록 SELECT 기대 1 / 실제 5로 1건 실패, exit 1. 의도한 캐시 부재를 확인했다.
- 초기 Green 컴파일: 기존 단위 테스트 생성자 호출의 새 의존 누락으로 테스트 실행 전에 실패했다. 호출부를 보완했다.
- 초기 장애 테스트 컴파일: fixture implementation dependency가 직접 노출되지 않아 실패했다.
  기존 Testcontainers GenericContainer로 독립 테스트 Redis를 만들도록 수정했다.
- 초기 캐시 묶음: 21건 중 중단/재시작 1건 실패, 오류/skip 0, exit 1.
  Docker의 임의 포트 재바인딩으로 기존 주소가 끊겨 재시작 검증이 실패했다. 테스트 전용 고정 포트로 수리한 뒤 해당 검증 통과.
- 초기 lint: 새 줄 길이/호출 포맷 위반으로 exit 1. formatter와 줄바꿈을 적용했고 규칙은 유지했다.
- 독립 read-only reviewer는 운영 코드의 기능/transaction 결함을 발견하지 않았고,
  SCAN 명령 비용 누락 1건을 지적했다. benchmark에 실제 INFO commandstats 비교를 추가하고 근거 없는 주석을 제거했다.
- 관련 회귀: **33 tests / 0 failures / 0 errors / 0 skipped**, 7 suites, exit 0.
- 별도 `ArchitectureTest`: **1 / 0 / 0 / 0**, exit 0.
- `:apps:commerce-api:ktlintCheck :modules:redis:ktlintCheck`: exit 0.
- baseline/조회 개선/최종 3모드 benchmark는 각 실행의 JUnit 1건 통과·exit 0이며,
  baseline 재현 스크립트도 실제 별도 export에서 통과했다. 측정 100회는 JUnit 100건으로 세지 않는다.
- 전체 commerce-api check 최종 완료: **143 tests / 0 failures / 0 errors / 0 skipped**, 39 suites, exit 0.
  최종 측정 도구까지 반영한 재실행도 같은 결과다. `ArchitectureTest`와 기존 HTTP/권한/주문/잠금 회귀를 포함한다.
- 최종 performance task는 [benchmark-results.json](benchmark-results.json)의 **1 / 0 / 0 / 0**, exit 0이다.
- `git diff --check`, 문서의 로컬 링크 확인과 baseline shell의 `bash -n`도 exit 0이다.

```bash
./gradlew :apps:commerce-api:ktlintCheck :modules:redis:ktlintCheck :apps:commerce-api:check --console=plain
```

최종 `docker ps`는 빈 목록으로 테스트 컨테이너 정리를 확인했다. 검사 실행 자체를 막은 환경 제한이나 미실행 필수 검사는 남지 않았다. Kapt의 SKIPPED/NO-SOURCE task는
JUnit skip 수가 아니며 위 테스트 skipped 집계는 XML의 실제 값이다.

## 남는 비용과 범위

필수 검증의 미실행 항목은 없다. 운영 DB/배포·commit/push/PR은 수행하지 않았다.
정확한 COUNT·깊은 OFFSET, hit 활성 확인, 영역 전체 SCAN/DEL과 무효화 뒤 miss 비용은 유지된다.
무효화 실패·늦은 채움의 표시/정렬/COUNT snapshot 오래됨은 설계가 허용한다.
TTL은 저장부터 30초이고 전역 최신성 상한이 아니다. DB revision·분산 잠금·outbox·지속 재처리 큐를 추가하지 않았다.
운영 부하/다중 호스트 처리량 측정은 이번 로컬 계약 검증에서 증명한 범위를 벗어난다.
