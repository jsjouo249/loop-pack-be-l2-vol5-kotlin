# 3주차 트랜잭션·동시성 작업 계획

## 1. 문서 상태와 근거

- 상태: D1~D6 및 후속 정책 확정, Backend 상세 설계와 운영 코드·테스트 구현. 실제 MySQL 포함 111건·정적 검사·check 통과.
- 기준: `jsjouo249`, `928d17dd5608fb157b4c9f22a7d1d472ff508514` (2주차 PR #8 병합).
- 요구 출처: 사용자가 제공한 3주차 Learning 및 Implementation Quest 원문.
- 사용자 요청: Technical Writing Quest를 제외하고 적용 작업과 논의 사항을 먼저 문서화한다.
- 기존 계약: [2주차 커머스 설계](../week2/commerce-design.md).
- 확정된 결정: [설계 결정과 검증 기준](design-discussion.md).
- 오류 처리 상세: [트랜잭션 오류와 재시도 정책](transaction-error-policy.md).
- 저장·SQL·잠금·시간·API 명세: [Backend 상세 설계](backend-design.md).

표기의 의미:

| 표기 | 의미 |
|---|---|
| `[기획서]` | 제공된 3주차 요구에서 확정된 동작·검증 기준 |
| `[기존 결정]` | 2주차 문서와 코드에 있는 유지 대상 계약 |
| `[사용자 결정]` | 이번 논의에서 사용자가 선택한 3주차 방향 |
| `[코드 확인]` | 위 revision을 읽어서 확인한 사실. 실행 검증과 구분 |
| `[제안]` | 아직 사용자가 선택하지 않은 구현·설계안 |
| `[미결정]` | 구현 전 또는 관련 계약 변경 전 논의할 사항 |

이 문서는 학습 회고용 기술 글이 아닌 구현 준비 문서다. 제안을 승인된 ADR로 취급하지
않는다. 선택이 끝나면 기존 설계의 해당 API·불변식·트랜잭션·ADR에 변경 이유와 함께 반영한다.

## 2. 유지할 계약과 변경할 계약

| 구분 | 내용 |
|---|---|
| `[기존 결정]` 유지 | Brand·Product의 `deletedAt` soft delete, 행·참조 보존 |
| `[기존 결정]` 유지 | 순수 domain / 기술 중립 저장 데이터 / JPA entity 분리, 현재 패키지와 Aggregate |
| `[기존 결정]` 유지 | 독립 PointAccount와 `point_balances` 저장 구조 |
| `[사용자 결정]` 전환 | User 생성 시 잔액 0 PointAccount 동시 생성. 기존 누락은 0으로 보정, 기존 잔액 보존, 전환 이후 누락은 무결성 오류 |
| `[기존 결정]` 유지 | DRAFT 생성 시 가격·품목 snapshot 고정, 재고·포인트 무차감 |
| `[기존 결정]` 유지 | 본인 주문만 확정, 재확정은 `ORDER_ALREADY_CONFIRMED`, 별도 요청키 없음 |
| `[전환 영향]` | 0원 주문 허용은 유지. 이미 생성된 계정의 잔액 0을 유지하며 확정하도록 테스트 변경 |
| `[기존 결정]` 유지 | 삭제 상품의 새 사용 거절, 기존 자신의 좋아요 취소 가능 |
| `[기획서]` 변경 | 활성 상품이 있으면 브랜드 삭제 거절 → 브랜드와 연결된 미삭제 상품 전체 삭제 |
| `[기획서]` 추가 | 실제 SQL 이후 실패에 대한 전체 rollback 증거, 실제 주문 경쟁 제어와 결과 대조 |
| `[기획서]` 유지 | 로컬 사용자·관리자 경계, 과거 주문 정보·결제 결과, DB unique·check 제약 |
| `[사용자 결정]` 추가 | 필요한 잠금만 적용, 작업별 저장 계약, 상품 등록과 브랜드 삭제 공통 보호, domain 행동 중심 일괄 처리, 보호하는 쓰기 공통 deadlock 1회 재시도·고정 50ms 대기 |

관리자 재고 변경은 기존의 **최종 수량 설정**이다. 증감량 반영으로 의미를 바꾸지 않는다.
관리자 설정이 함께 실행되는 실험에는 단순한 `초기 재고 - 주문 수량` 식을 그대로 적용할 수
없으므로 허용된 직렬 실행 순서와 설정값을 함께 비교해야 한다.

## 3. 기준 커밋에서 확인한 호출 경로와 부족한 부분

아래는 구현 전 기준 커밋의 사실이다. 이후 코드·검증 상태는 [실행 기록](verification-report.md)을 따른다.

### 3.1 브랜드 삭제

`AdminSecurityConfig → AdminBrandController.delete → BrandApplicationService.delete
→ BrandRepository / ProductRepository → 저장 adapter → Spring Data JPA`

- `[코드 확인]` ADMIN만 관리 API에 진입하며 일반·미식별 요청은 기존 `ADMIN_FORBIDDEN`으로 거절된다.
- `[코드 확인]` application의 삭제 메서드에 `@Transactional`이 없다.
- `[코드 확인]` 현재는 `existsActiveByBrandId`가 참이면 `BRAND_HAS_ACTIVE_PRODUCTS`를 던진다.
- `[기획서]` 상품 변경과 브랜드 변경을 묶는 application 쓰기 트랜잭션을 추가해야 한다.

근거: [BrandApplicationService](../../apps/commerce-api/src/main/kotlin/com/loopers/application/brand/BrandApplicationService.kt),
[AdminBrandController](../../apps/commerce-api/src/main/kotlin/com/loopers/interfaces/api/admin/AdminBrandController.kt),
[AdminSecurityConfig](../../apps/commerce-api/src/main/kotlin/com/loopers/interfaces/api/admin/AdminSecurityConfig.kt).

### 3.2 최초 주문 확정

`OrderController.confirm → Spring proxy → OrderApplicationService.confirm
→ OrderRepository / ProductRepository / PointAccountRepository
→ *RepositoryImpl → *EntityRepository → Jpa*EntityRepository → *JpaRepository`

- `[코드 확인]` `confirm`은 이미 application `@Transactional`이며 Controller에서 별도 bean을 호출한다.
- `[코드 확인]` 사용자·주문 소유자·DRAFT·상품·재고·잔액을 검사한 뒤 변경한다.
- `[코드 확인]` domain 객체 변경 후 상품 `saveAll`, 포인트 `save`, 주문 `save`로 명시적 저장한다.
- `[코드 확인]` 관련 조회에 비관적 잠금·version·조건부 차감은 없다. 품목은 domain에서 ID순으로
  정렬하지만 이것만으로 DB의 잠금 획득 순서가 보장되지는 않는다.
- `[코드 확인]` 관련 업무 예외는 RuntimeException 계열이고, `confirm`은 저장 예외를 삼키지 않는다.
- `[기획서]` 기존 원자성 경계를 유지하면서 상태 검사 전부터 해당 상태를 보호해야 한다.

근거: [OrderApplicationService](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderApplicationService.kt),
[Order](../../apps/commerce-api/src/main/kotlin/com/loopers/domain/order/Order.kt),
[JpaOrderEntityRepository](../../apps/commerce-api/src/main/kotlin/com/loopers/infrastructure/order/JpaOrderEntityRepository.kt).

### 3.3 같은 상태를 바꾸는 다른 경로

| 경로 | 현재 상태 | 필요한 조치 |
|---|---|---|
| `PointApplicationService.charge` | 조회·계산·저장 전체의 application 트랜잭션 없음 | 사전 생성한 PointAccount를 결제와 같은 방식으로 잠금 |
| `ProductApplicationService.adjustStock` | 트랜잭션 있음, 전체 상태 저장 | 활성 조건 stock 전용 UPDATE로 주문의 행 잠금과 직렬화, 선행 FOR UPDATE는 생략 |
| `ProductApplicationService.update/delete` | domain snapshot을 읽고 다시 저장 | 재고·삭제 상태를 오래된 값으로 덮어쓰지 않도록 보호 |
| 브랜드 일괄 상품 삭제 | 신규 흐름 | 상품의 다른 변경과 같은 보호 방식 참여 |
| `BrandApplicationService.update` | 이름 변경 저장 시 삭제 상태도 전달 | 삭제와 경쟁해 브랜드를 되살리지 않도록 저장 경로 점검 |
| `ProductApplicationService.create` | Brand 비잠금 조회 후 상품 생성 | 브랜드 삭제와 동일 Brand 잠금, 활성 상태 확인·생성까지 transaction |

`ProductJpaEntity.updateFrom`은 이름·가격·재고·삭제 상태를 모두 반영한다. 이름 수정도
재고를 덮어쓸 수 있다. `BrandJpaEntity.updateFrom`도 이름과 삭제 상태를 반영한다.
저장 시점에만 잠그면 이미 읽은 오래된 domain snapshot은 고쳐지지 않는다.

근거: [PointApplicationService](../../apps/commerce-api/src/main/kotlin/com/loopers/application/point/PointApplicationService.kt),
[ProductApplicationService](../../apps/commerce-api/src/main/kotlin/com/loopers/application/product/ProductApplicationService.kt),
[ProductJpaEntity](../../apps/commerce-api/src/main/kotlin/com/loopers/infrastructure/product/ProductJpaEntity.kt),
[BrandJpaEntity](../../apps/commerce-api/src/main/kotlin/com/loopers/infrastructure/brand/BrandJpaEntity.kt).

## 4. 적용 작업과 완료 기준

| ID | 작업 | 완료 기준 | 주요 책임 |
|---|---|---|---|
| W3-01 | 브랜드 삭제 계약 확장 | 브랜드와 미삭제 상품 전체 soft delete. 재고 0 포함. 연결 상품 없어도 성공. 대상 브랜드 없음·기삭제는 기존 오류 | BrandApplicationService, ProductRepository와 저장 구현 |
| W3-02 | 브랜드 일괄 변경 원자성 | 실제 SQL 이후 저장 실패 시 전체 rollback. 다른 브랜드·상품·기존 주문 불변 | application transaction, 테스트 저장 경계 |
| W3-03 | 삭제 후 연결 동작 유지 | 고객 목록·상세·새 좋아요·내 좋아요 목록·새 주문 제외. 수정·재고 변경 거절. 기존 좋아요 취소 유지 | 기존 상품·좋아요·주문 경로, HTTP 테스트 |
| W3-04 | 기존 주문 보호 | 과거 품목·수량·단가·총액·결제액·결과 보존. 삭제 전 DRAFT도 확정 시 재검증 | Order snapshot, 조회·확정 경로 |
| W3-05 | 최초 확정 원자성 증명 | 재고·포인트·CONFIRMED·결제 결과가 전부 commit 또는 rollback | OrderApplicationService, 실제 DB 테스트 |
| W3-06 | 주문·재고·잔액 경쟁 제어 | 선택한 제어가 실제 조회·변경 SQL에 적용. 동일 주문도 중복 차감 없음 | domain 저장소 계약과 infrastructure 구현 |
| W3-07 | 우회 변경 경로 보호 | 충전·관리자 최종 재고 설정·기존 행의 수정/삭제가 보호를 우회하지 않음 | 관련 application 쓰기 유스케이스 |
| W3-08 | 경쟁 결과 집계 | 재고·포인트·충전과 결제의 성공·거절·기술 오류 및 최종 상태가 과제 기대와 일치 | 독립 worker/transaction 테스트 |
| W3-09 | 갱신 유실 대조군 | 성공 2·재고 4로 불변식 위반 재현을 정상 assertion으로 확인 | 테스트 전용 JDBC/transaction 코드 |
| W3-10 | 설계·회귀·정적 검사 | 변경 이유·잠금 순서·검증 결과 기록, 관련 회귀·ktlint·ArchUnit·check 통과 | 문서와 테스트 |
| W3-11 | 사용자·포인트 초기화 전환 | User와 잔액 0 PointAccount 동시 생성·rollback, fixture 변경, 기존 누락만 0으로 보정·재실행 시 기존 잔액 보존, 이후 누락은 500 | 내부 application 생성 경계, 초기화/테스트 지원 |
| W3-12 | 작업별 저장 계약 분리 | 이름·가격·재고·삭제·주문 확정의 저장이 무관한 필드를 덮어쓰지 않음 | domain 계약부터 infrastructure 매핑·SQL까지 |
| W3-13 | 브랜드 삭제와 상품 등록 경쟁 보호 | 등록 선행이면 새 상품도 삭제, 삭제 선행이면 등록 거절. 삭제 브랜드의 활성 상품이 남지 않음 | Brand 공통 잠금, 두 application 유스케이스 |

작업 범위는 현행 모델의 책임과 필요한 저장 계약까지 포함한다. 별도 Stock Aggregate,
거래 원장, 새 결제 모델, 전면 패키지 재배치는 도입하지 않는다.

## 5. 트랜잭션·저장 설계에서 반드시 지킬 조건

1. Controller가 호출하는 Spring bean의 public 유스케이스를 실제 프록시 진입점으로 둔다.
   Kotlin Spring 플러그인은 이미 적용되어 있다. 자기 호출을 새 경계로 오인하지 않는다.
2. 같은 성공·실패의 저장은 같은 DB transaction에 참여한다. 상품별 `REQUIRES_NEW`, 예외를
   삼킨 부분 성공, 운영 코드의 테스트 실패 switch·장벽·sleep은 사용하지 않는다.
3. domain 행동과 검증 책임을 유지한다. 잠금의 JPA 타입은 infrastructure에 두고 현재의
   `*RepositoryImpl → *EntityRepository → JPA` 경계를 유지한다.
4. `[사용자 결정 D1/D3/D5]` 필요한 비관적 잠금과 작업별 저장 계약을 적용한다.
   [상세 설계](backend-design.md)의 순서·사전 잠금/상태 조건 UPDATE 구분을 따른다. User 조정용 잠금은 없다.
5. 잠금을 얻기 전 조회한 domain/JPA 상태를 검증·저장에 재사용하지 않는지 확인한다.
   잠금 SQL과 persistence context가 반환하는 상태를 함께 검증한다.
6. 기존 상품 저장의 품목별 flush 방지 조건을 유지한다. 실패 주입에 필요한 강제 flush는
   테스트 구성에서 실행하며 운영 경로에 불필요한 flush를 추가하지 않는다.
7. 현재 DB unique·check와 주문 참조를 유지한다. cascade remove를 추가하지 않는다.
8. 일반·미식별 관리자 요청과 타인 주문 확정은 상태를 바꾸지 않는다. 기존 인증·CSRF·
   로컬 공개 범위를 변경하지 않는다. 운영 migration·rate limit·새 감사 시스템은 이번 범위 밖이다.

프록시 동작의 기술 근거: [Spring 선언적 트랜잭션](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-decl-explained.html).

## 6. 테스트 계획과 추적성

아래는 설계 시의 테스트 후보와 기대값이다. 실제 구현 파일 매핑·실행 상태는 [T01~T14 검증 기록](verification-report.md#t01t14-추적성)을 따른다.

| ID | 후보 테스트/사례 | 요구 | 기대 결과 |
|---|---|---|---|
| T01 | BrandRemovalTransactionTest 정상 | W3-01/03/04 | 상품 2개(하나는 재고 0)와 브랜드 삭제, 다른 대상·과거 주문 보존 |
| T02 | 같은 테스트 중간 실패 | W3-02 | 앞 변경 flush 후 다음 저장 예외, 별도 조회에서 브랜드·상품 모두 작업 전 상태 |
| T03 | OrderTransactionTest 정상/실패 | W3-05 | 여러 품목 정상 확정. 변경 SQL 이후 실패는 DRAFT·재고·잔액·결제 필드·품목 모두 원상태 |
| T04 | LostUpdateControlTest | W3-09 | 두 transaction이 모두 5를 읽고 상수 4 저장, 두 commit 성공과 `2 + 4 != 5` 확인 |
| T05 | OrderConcurrencyTest 재고 | W3-06/08 | 재고 5, 서로 다른 주문 8개·구매자별 충분한 잔액: 성공 5/재고 부족 3/기술 오류 0/재고 0 |
| T06 | 같은 테스트 포인트 | W3-06/08 | 한 사용자 10,000원, 서로 다른 4,000원 주문 3개·충분한 재고: 성공 2/잔액 부족 1/기술 오류 0/잔액 2,000원 |
| T07 | 같은 테스트 충전+결제 | W3-07/08 | 10,000원에서 2,000원 충전+7,000원 결제: 성공 2/기술 오류 0/잔액 5,000원 |
| T08 | 동일 주문 동시 확정 | W3-06 | 최초 확정만 차감·결제 결과 기록, 나머지는 기존 상태 오류, 최종 효과 한 번 |
| T09 | 같은 행의 우회 경로 | W3-07/12 | 관리자 수량 설정·상품 수정/삭제가 주문 차감을 덮어쓰지 않음. 부분 저장이 무관한 필드를 보존. 설정은 허용된 직렬 결과로 판정 |
| T10 | 사용자·포인트 생성 원자성/충전 경쟁 | W3-07/11, D2 | 생성 성공 시 잔액 0 계정 하나, 포인트 저장 실패 시 User도 rollback. 누락만 0으로 보정하고 재실행 시 기존 잔액 불변. 이후 누락은 500. 동시 충전액 합·0원 주문 잔액 보존 |
| T11 | 대표 MockMvc 사례 | W3-01/03/04/05 | 브랜드·주문 정상/실패 응답, 삭제 후 사용 제한, 일반·미식별 관리자/타인 주문 거절과 DB 불변 |
| T12 | 회귀·ArchitectureTest·ktlint·check | W3-10 | 기존 기능 및 경계 유지, 실제 클래스 검사, 실행 건수/실패/skip/종료 코드 기록 |
| T13 | 브랜드 삭제와 상품 등록 경쟁 | W3-13 | 등록 선행/삭제 선행의 두 순서와 동시 실행 결과 검증. 삭제 브랜드에 활성 상품이 남지 않음 |
| T14 | 기술 오류 정책 | D4 | 보호하는 쓰기 8개 경계 공통 적용. rollback 확인 deadlock 뒤 고정 50ms·최대 1회 새 transaction 재시도. timeout/소진은 503, 재판정 업무 오류는 기존 4xx, 결과 불명/무결성 오류는 재시도 없이 500 |

T08~T10과 T13은 과제의 수치 예제를 반복하는 것이 아니라 선택한 보호 경로의 누락을 확인한다.
구체적인 파일 분리는 현재 테스트 구조에 맞추며, 같은 수량 경계값을 모든 계층에 복제하지 않는다.

### DB 실험 규칙

- 기존 [MySQL 8.0 fixture](../../modules/jpa/src/testFixtures/kotlin/com/loopers/testcontainers/MySqlTestContainersConfig.kt)와
  [정리 도구](../../modules/jpa/src/testFixtures/kotlin/com/loopers/utils/DatabaseCleanUp.kt)를 사용한다.
- 초기 데이터는 worker 시작 전에 commit. 테스트 전체를 부모 transaction으로 감싸지 않는다.
- 실제 Spring application bean과 실제 repository를 사용한다. rollback 앞 단계를 모두 mock하지 않는다.
- T02/T03은 테스트용 저장 경계 decorator/spy 등의 구성에서 실제 앞 저장을 flush한 후 예외를
  던진다. 외부 프록시 호출 종료 뒤 새 transaction 또는 별도 connection으로 재조회한다.
- T04만 두 읽기 완료 후 쓰기 장벽을 쓴다. 최소 두 worker·connection과 제한 시간을 확보한다.
  `stock = stock - 1`로 바꾸지 않고, 동일 값 UPDATE의 영향 행 수 차이를 실패로 세지 않는다.
- 실제 서비스 경쟁 테스트는 시작만 맞춘다. 모든 future 결과를 수집하고 timeout·SQL 오류를
  업무 거절로 집계하지 않는다. finally에서 장벽 해제·executor 종료·connection 정리를 보장한다.
- 성공 요청의 주문 ID를 DB 상태와 연결하여 아래 식을 확인한다. 재고가 음수가 아닌지만
  확인하거나 성공 수가 적어도 통과하도록 기대치를 낮추지 않는다.

```text
초기 재고 - 성공한 주문 품목의 수량 합 = 최종 재고
초기 잔액 + 성공 충전액 합 - 성공 결제액 합 = 최종 잔액
성공 + 업무 거절 + 기술 오류 = 전체 요청
```

## 7. 구현 순서와 기존 문서 반영

1. 확정된 상세 설계의 저장 계약·잠금 순서·시간 기준값과 오류 분류·재시도 경계를 구현에 연결한다.
2. 브랜드 삭제의 repository 계약·유스케이스·정상/rollback 테스트를 작은 단위로 구현한다.
3. 사용자·포인트 동시 초기화와 작업별 저장 계약을 연결하고, 주문 확정·충전·상품 변경의 제어를 적용한다.
4. 상품 등록과 브랜드 삭제의 공통 보호를 적용하고 SQL 이후 실패·대조군·실제 경쟁·대표 HTTP 회귀를 검증한다.
5. 기존 설계의 API 계약, §12 저장·트랜잭션, ADR-008/011과 관련 테스트 설명을 갱신한다.
   브랜드 삭제의 409 거절 테스트는 새 성공 계약으로 바꾸되 다른 회귀 검증은 유지한다.
6. 관련 회귀와 정적 검사를 완료하고 실제 결과만 기록한다.

대표 domain 규칙을 바꿀 때는 AGENTS.md의 Red → Green → Refactor 절차를 따른다.
이 문서 작성 단계에서는 코드 구현·DB 실험·커밋·PR 게시를 수행하지 않는다.

## 8. 실행·완료 기록

아래 명령은 구현 후 실제 테스트 클래스 이름에 맞춰 실행한다.

```bash
./gradlew :apps:commerce-api:test --tests '*BrandRemovalTransactionTest'
./gradlew :apps:commerce-api:test --tests '*OrderTransactionTest'
./gradlew :apps:commerce-api:test --tests '*OrderConcurrencyTest'
./gradlew :apps:commerce-api:test --tests '*LostUpdateControlTest'
./gradlew :apps:commerce-api:test --tests '*ArchitectureTest'
./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check
```

| 항목 | 현재 상태 |
|---|---|
| 기준 코드·설계 읽기 | 완료 |
| 작업/검증 계획 문서화 | 완료 |
| D1~D6 사용자 결정 및 한 차례 반례 확인 | 완료 |
| 요구사항을 막는 미결정 정책 | 없음 |
| 저장 계약·SQL 방식·잠금 순서·시간·API 상세 설계 | 완료, backend-design.md |
| 운영 코드·테스트 변경 | 구현·검증 완료 |
| MySQL rollback·경쟁·회귀 테스트 | 실제 MySQL 전체 check 통과, 요청별 결과는 검증 기록 참조 |
| ktlint·ArchitectureTest·check | 모두 통과, 111 tests / 0 failures / 0 skipped / exit 0 |

완료 시 명령별 실행 건수·실패·skip·종료 코드와 경쟁 결과를 기록한다. 계획만 있는
시나리오는 검증 완료로 표시하지 않는다. PR 작성 시 트랜잭션 경계·제어 방식·핵심 결과와
실제 코드 링크를 넣고 원시 로그 전체를 복사하지 않는다. Technical Writing Quest는 제외한다.


## 9. 구현 인계

다음 워크플로는 `$codex-new-feature`다. 문서화와 결정은 완료됐으며 이번 문서 갱신에서
운영 코드를 구현하거나 DB 데이터를 보정하지 않았다.

```text
$codex-new-feature
3주차 implementation-plan.md, design-discussion.md, transaction-error-policy.md와
backend-design.md와 2주차 commerce-design.md를 읽고 현재 저장소 상태를 확인한 뒤 구현한다.
비관적 잠금, User·PointAccount 동시 생성 및 누락 계정 전환, 작업별 저장 계약,
브랜드 삭제·상품 등록 공통 보호, domain 행동 기반 일괄 삭제를 적용한다.
보호하는 쓰기 유스케이스 공통으로 전체 rollback 확인 deadlock만 고정 50ms 대기 후
새 transaction으로 최대 1회 재시도하고,
확인된 일시적 잠금 실패는 503 TRANSACTION_BUSY, 업무 거절은 기존 4xx,
무결성 오류·commit 결과 불명은 기존 500으로 처리한다.
확정된 정책을 다시 묻지 말고 상세 설계의 SQL·잠금 범위·시간 설정을 구현하고 실제 적용을 검증한다.
Technical Writing Quest, 새 회원가입 API, 요청키·성공 응답 재사용, 외부 PG,
bulk 최적화와 무관한 리팩터링은 추가하지 않는다.
작은 책임 단위로 변경하며 관련 domain 규칙은 AGENTS.md의 Red-Green-Refactor를 따른다.
계획의 T01~T14, 실제 MySQL rollback·경쟁·HTTP 회귀, ktlint·ArchitectureTest·check를 검증한다.
최종 보고에는 변경 내용, 실행 건수·실패·skip·종료 코드, 요청별/시도별 결과,
남은 한계를 기록한다. 실행하지 않은 테스트를 통과로 적지 않는다.
```
