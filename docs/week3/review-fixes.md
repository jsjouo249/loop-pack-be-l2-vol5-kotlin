# 전체 코드 리뷰 후 보완

## 출처와 범위

- 사용자 요청: 계층 책임·동시성·성능 리뷰에서 정리한 9개 항목을 전체적으로 수정한다.
- 코드 확인: 좋아요의 조회 후 INSERT, 조회 조합 사이의 삭제 경합, 중복 잠금 조회,
  상품별 flush, Page 내부/외부 중복 COUNT, 정렬과 불일치하는 인덱스, 전체 좋아요 집계를 확인했다.
- 설계 결정: 아래 내용으로 기존 2·3주차 설계를 확장한다. API 경로·입력·성공 응답·권한,
  주문 가격 snapshot·삭제 관계 보존·원자적 브랜드 삭제는 유지한다.
- 검증: 아래 실행 결과와 [JUnit/실행 계획 추출](review-fix-results.json)을 따른다.
  이전 verification-report.md는 이전 구현의 실행 기록으로 보존한다.

## 저장·잠금 계약

- 상품의 `like_count BIGINT NOT NULL DEFAULT 0 CHECK (like_count >= 0)`은 조회용 projection이다.
  좋아요 관계가 source of truth이며 Product domain 상태에는 카운터를 추가하지 않는다.
  JPA의 insertable/updatable=false로 일반 merge/dirty checking이 낡은 projection을 덮지 못하게 한다.
- 좋아요 등록·취소는 공통 쓰기 executor에서 Product → ProductLike 순서로 잠근다.
  같은 상품의 모든 좋아요 변경을 직렬화하여 관계와 카운터를 같은 transaction으로 변경한다.
  취소는 삭제된 상품도 잠글 수 있으며 기존 관계를 제거할 수 있다.
- 관계 존재 검사는 current locking read로 수행한다. 기존 REPEATABLE READ snapshot을 사용하지 않는다.
  중복 등록/없는 관계 취소는 관계와 카운터를 변경하지 않고 성공한다.
  서로 다른 사용자의 변경, 중복 등록·취소, 실패 rollback 후에도 projection = 실제 관계 수를 보장한다.
- 좋아요 쓰기도 완료된 rollback을 확인한 deadlock 1회 재시도와 확인된 잠금 실패의
  `503 TRANSACTION_BUSY` 계약을 적용한다. 외부 요청 멱등성 키는 추가하지 않는다.
- 정렬은 `(deleted_at, like_count DESC, id DESC)`와 브랜드별 동일 인덱스를 사용한다.
  최신순/가격순도 브랜드 필터 유무와 실제 정렬 방향에 맞는 인덱스를 갖춘다.
  정확한 전체 건수와 OFFSET 계약은 유지하므로 깊은 페이지의 OFFSET 비용은 남는다.
- 기존 데이터 전환 SQL은 컬럼·인덱스 추가와 관계 수 보정을 제공한다.
  운영 DB에 실행하지 않으며, 쓰기 중단 상태에서 보정·검증 후 새 코드를 배포해야 한다.

## 조회·저장 효율과 계층 책임

- 상품·브랜드·좋아요·주문 목록 응답은 application의 readOnly REPEATABLE_READ transaction에서
  조합한다. 조회에 FOR UPDATE를 추가하지 않는다. 목록과 전체 건수도 같은 snapshot으로 읽는다.
- pagination 내부 내용은 List로 조회한다. 전체 COUNT는 application이 요청한 한 번만 실행한다.
  상품 페이지는 정렬 인덱스로 ID만 선택한 뒤 해당 페이지의 행만 batch 조회하고 ID 순서로 복원한다.
  실제 MySQL에서 SELECT *는 작은 fixture에서도 table scan/sort를 선택했으므로 covering ID 조회로 바꾼다.
- 잠금 helper는 Hibernate context를 PK로 O(1) 조회하여 이미 관리 중인지 SQL 없이 확인한다.
  최초 조회는 잠금 SELECT 한 번, 이미 관리 중인 객체만 refresh하여 current state를 보장한다.
  final JPA 클래스에서는 getReference도 SELECT하므로 이 판별에 사용하지 않는다.
  provider 내부 API 의존은 infrastructure helper 하나에 한정하고 SQL/최신화 회귀 테스트로 보호한다.
- 브랜드 삭제는 모든 상품을 ID순으로 잠그고 각각 domain.delete()를 수행한 뒤
  일괄 저장 계약으로 flush 한 번, ID순 부분 UPDATE를 실행한다. commit과 rollback은 한 번이다.
  JPA context는 변경된 객체만 refresh한다. 전체 clear나 cascade remove를 추가하지 않는다.
- Example 조회의 transaction·repository 조율은 application으로 옮기고 domain 예외와
  HTTP 매핑을 분리한다. 기존 HTTP 상태·에러 코드·메시지를 보존한다.
- ArchUnit은 infrastructure의 application/interfaces 의존과 domain의 HTTP/transaction
  정책 의존을 함께 금지한다. 기존 JPA 허용 범위와 커머스 모델 분리 검사는 유지한다.

## 실행 검증

2026-10-06, 기존 미커밋 변경을 보존한 현재 작업 트리에서 수행했다. 운영 DB는 변경하지 않았다.

```bash
env DOCKER_CONFIG=/private/tmp/looppack-review-docker ./gradlew \
  :apps:commerce-api:ktlintCheck :apps:commerce-api:check --console=plain
```

- 최종 종료 코드 **0**, JUnit XML 합계 **122 tests / 0 failures / 0 errors / 0 skipped**, 34 suites.
- 기존 테스트 111건에 회귀 9건·실행 계획/전환 SQL 2건을 추가했다.
- ArchUnit 경계 검사 강화의 Red는 기존 Example domain 의존 때문에 1건 실패했다.
  application 책임 이동과 중립 domain 예외 도입 후 최종 검사에서 통과했다.
- 같은 사용자의 좋아요 등록 6회·취소 6회는 모두 성공하며 각각 관계/카운터 1·0을 검증했다.
  사용자 6명의 동시 등록과 등록/취소 혼합 12회도 관계 수와 카운터가 일치한다.
- 좋아요 등록/취소의 실제 SQL 이후 실패를 주입해 관계와 카운터를 함께 rollback하고,
  flush/clear 후 원래 관계 ID·시각·카운터가 보존되는 것을 확인했다.
- 상품 목록/내 좋아요 목록의 조회 사이에 다른 transaction의 삭제 commit을 강제로 끼워도
  HTTP 200과 이전 snapshot의 내용·총건수가 일치했다. 실제 삭제 commit도 별도로 확인했다.
- 최초 잠금 SELECT 1회, 이미 관리 중인 객체는 다른 transaction의 재고 변경 후 최신값으로
  refresh됨을 검증했다. 존재하지 않는 ID의 잠금 조회도 null을 반환한다.
- 상품 20개의 브랜드 일괄 삭제는 상품 묶음 flush 1회 + 브랜드 flush 1회로 끝나고,
  두 번째 삭제 SQL 실패 시 전체 행이 복구된다. 무관한 상품 재고는 유지된다.
- 꽉 찬 상품 페이지의 COUNT 1회, 좋아요 정렬의 relation JOIN/GROUP BY 부재를 SQL로 확인했다.
- 상품 **2,000개 / 관계 9,000개**, 첫 페이지 20건에서 `EXPLAIN ANALYZE`가 최신·가격·좋아요순과
  브랜드 필터 유무의 **6개 인덱스**를 모두 선택했다. ID 조회는 실제 **20행**을 읽고 별도 Sort가 없다.
  이는 해당 fixture의 실행 계획 검증이며 운영 처리량 벤치마크는 아니다.
- 전환 SQL의 DDL과 보정문을 별도 테스트 테이블에 적용했다. 삭제 상품/0건 상품 포함 카운터가
  정확하고, 보정 재실행 후 관계·가격·재고·수정/삭제 시각이 보존되는 것을 확인했다.
- 기존 주문/포인트/관리자 경합, deadlock 재시도, timeout, 부분 저장, rollback, HTTP 계약과
  권한 테스트를 전체 check에서 함께 통과했다. 문서 링크와 `git diff --check`도 오류가 없다.

## 적용 조건과 남는 비용

- 기존 DB는 [전환 SQL](sql/product-like-projection.sql)을 쓰기 중단 상태에서 적용/검증하고,
  보정 transaction을 commit한 다음 새 코드를 배포해야 한다. local/test의 새 스키마는 자동 생성된다.
- 카운터 정합성을 위해 같은 상품의 좋아요 쓰기는 직렬화한다. 인덱스 추가에 따른 쓰기 비용도 있다.
  운영 데이터로 필요한 처리량을 별도 측정해야 한다.
- 정확한 전체 COUNT와 기존 OFFSET API 비용, 원자적 브랜드 삭제의 상품 수에 비례하는
  잠금/부분 UPDATE 비용은 유지한다. 큰 삭제도 같은 transaction timeout 정책을 따른다.
