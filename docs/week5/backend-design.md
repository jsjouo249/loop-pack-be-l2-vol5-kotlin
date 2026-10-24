# 5주차 PG 결제 설계

## 근거와 결정

- [기획서] 기존 DRAFT는 PG 결제를 사용한다. 주문당 한 시도, create 자동 재시도 금지. 과거 POINT 사실 보존.
- [사용자 결정] TEST / 0000-0000-0000-0000만 허용. 복수 외부 거래는 영구 격리. 코드·설계·검증을 Draft PR로 제출하며 블로그 문서는 이 PR에 포함하지 않는다.
- [사용자 결정] 클라우드 운영 환경 설명은 GCP 기준으로 작성한다. 현재 구현·검증 환경은 로컬이며 GCP 배포 검증과 구분한다.
- [코드 확인] 기존 주문은 Order → PointAccount → Product 순으로 잠그고 한 TX에서 확정한다. 상품은 논리 삭제다.
- [승인된 변경] PG에서는 포인트 잠금·차감을 제거하고 아래 짧은 TX들로 교체한다. 기존 DB retry 정책은 각 로컬 TX에만 적용한다.

## 상태·불변식·잠금

Order: DRAFT → PAYMENT_PENDING → CONFIRMED / PAYMENT_FAILED.
PaymentAttempt: REQUESTED / PENDING / UNKNOWN / SUCCESS / FAILED. REQUESTED는 발송 시작이 영속 기록된 상태이며 재발송 가능한 상태가 아니다.
Reservation: RESERVED → CONSUMED / RELEASED. 가용 재고는 확보 때만 차감하며 성공은 추가 차감 없이 소비한다.

기존 행 잠금 순서는 Order → PaymentAttempt → Reservation(productId순) → Product(ID순)이다. 새 행은 잠근 주문 아래 생성한다.
같은 주문의 경쟁 실행은 새 시도 생성 실행 하나만 발송 권한을 얻는다. 전역 요청 키와 주문별 시도 유일 제약도 보호한다.
DB CHECK는 음수 금액·재고, 0 이하 예약 수량을 막는다. 식별자 FK는 미완료 참조의 유실을 막기 위해 RESTRICT이며 cascade delete는 없다.

## 트랜잭션

1. TX A: 소유권·입력·DRAFT·활성 상품·재고 검사 → 가용 재고 차감 및 예약 기록 저장 → 시도·PAYMENT_PENDING 저장 → commit.
2. TX 밖: TX A에서 새 시도를 생성하고 commit 성공을 확인한 실행만 PG 생성 요청을 최대 1회 시도한다.
   TX A commit 직후 프로세스가 종료되면 실제 PG 호출은 0회일 수 있다. commit 결과 불명·재시작·동일 요청 재호출에서는 create하지 않는다.
3. TX B: 검증한 관찰을 잠근 시도·주문·예약에 적용. SUCCESS는 예약 소비, FAILED/REJECTED_NO_EFFECT만 반환. 같은 종결 결과는 no-op.

이 분리는 PG와 로컬 DB 변경의 원자적 성공을 보장하지 않는다. 외부 결제 결과와 로컬 결과 반영의 성공 여부를 구분한다.
PG 중 DB TX·잠금을 유지하지 않는다. 로컬 결과 반영에 실패하면 해당 TX B를 rollback하고 예외를 전파한다.
이후 명시적인 reconcile 요청이나 PG 콜백 처리에서 저장된 providerOrderId와 transactionKey(확보된 경우)로 기존 거래를 조회하고,
같은 결과 적용 경계에서 로컬 반영을 다시 시도한다. PG 생성 요청은 재발송하지 않으며 주기적인 자동 대사 스케줄러는 구현하지 않았다.
가용 재고는 TX A의 예약 시점에 차감하며 결제 성공 때 추가 차감하지 않는다. timeout·0건·404는 예약 반환 근거가 아니다.
삭제 이후 예약은 기존 품목 snapshot으로 종결한다. 반환 전용 부분 UPDATE는 삭제 행도 갱신하되 deleted_at을 보존한다.
관리자 지정 재고는 예약 제외 가용량이다. 반환은 현재 가용량에 더하며 overflow면 TX 전체 rollback 후 재고 조정·재대사가 필요하다.
가용 재고 확보·반환 commit 이후 기존 목록 캐시를 무효화한다. Redis 실패는 DB retry/PG 재발송에 포함하지 않는다.

## 반례 검토

- 거래 생성 후 응답 유실을 실패로 보고 재발송하면 이중 거래가 된다: UNKNOWN·예약 유지, 기존 거래만 조회한다.
- PG SUCCESS 뒤 DB 저장 실패를 PG 실패로 바꾸면 외부 성공과 재고가 갈라진다: 로컬 rollback과 외부 효과를 분리한다.
- callback SUCCESS 뒤 늦은 PENDING/timeout이 오면 상태가 역행할 수 있다: 종결 전이를 도메인에서 차단한다.
- 중복 FAILED가 매번 재고를 더하면 5→3→5→7이 된다: 예약 RELEASED와 재고 복구를 한 TX에 묶는다.

## API·호환

confirm은 paymentAttemptKey(ASCII 영숫자/_/- 1~128), cardType, cardNo를 받는다. 금액은 주문 저장값이다. POINT 확정 오류·소유권을 먼저 판정한다.
confirm/reconcile은 미완료 202(+조회/reconcile 경로), PG 종결 200이다. 일반 GET은 항상 기존 200 조회다.
callback(providerOrderId,transactionKey,status)은 알림일 뿐이며 권위 조회·단일 거래·금액·식별을 확인한다.
주문 조회에 paymentMethod, paymentAttempt, 조회 링크를 추가한다. 목록의 시도는 일괄 조회한다.
0원 주문도 PG로 전송한다. 이전 CONFIRMED는 명시적 전환 SQL로 POINT를 기록하며 품목·결제액·시각은 보존한다.

## HTTP·회복

고정 제공자 계정 X-USER-ID=135135. 로컬 base/callback URL만 사용. 카드·원문 body를 DB/로그에 남기지 않는다.
RestClient + Apache HttpClient5, 자동 retry·redirect 해제. 연결 200ms / pool 100ms / 응답 500ms.
422 + TEST_PG_REJECTED_BEFORE_CREATE + transactionCreated=false(boolean) + orderId/amount 일치만 REJECTED_NO_EFFECT다.
조회당 최초 포함 2회, 100ms 대기. 연결/timeout, 502/503/504에만 재시도. 전체 3초는 추가 조회 진입 예산이며 HTTP 전체 SLA가 아니다.
조회 전용 circuit: count 4/min 4, 실패율 50%, slow 400ms/50%, OPEN 5초, HALF_OPEN 1회. 실제 GET마다 집계한다.
정상 FAILED/PENDING/404/빈 목록은 통신 장애가 아니다. OPEN은 마지막 상태와 확인 불가를 반환한다.
복수 거래는 영구 격리한다. 잘못된 callback만으로 정상 시도를 격리하지 않는다. 종결 충돌은 기존 결과를 바꾸지 않고 기록한다.

## 범위와 비용

수동 reconcile이 기본 복구 경로다. 장기 미확인 예약 점유와 복수 거래 격리의 운영자 해결은 남는다.
재결제·취소·환불·상시 스케줄러·기술 글·운영 migration 실행은 이 PR 범위에서 제외한다.

## 전환 실행 절차

쓰기 중지·백업 → 기존 확정 주문의 POINT 사실 확인 → sql/upgrade-payment-boundary.sql 1회 →
sql/backfill-point-method.sql 실행 → 미보정 CONFIRMED 0건, 기존 주문/품목/잔액 비교 → 새 코드 배포·쓰기 재개.
DDL은 implicit commit이다. 되돌리기는 임의 결제/예약 삭제가 아니라 백업·배포 전환 판단이 필요하다.
local/test의 기존 ddl-auto=create는 데이터 유지용 전환 실행 방법이 아니다. 이 설정으로 기존 DB를 재시작하지 않는다.

## 책임과 변경 파일

Order는 주문 상태, PaymentAttempt는 관찰/종결/거래 바인딩, StockReservation은 확보 수량의 종결을 소유한다.
PaymentApplicationService가 두 로컬 TX와 TX 밖 호출을 조율한다. OrderApplicationService는 기존 생성/조회와 결제 결과 조합을 유지한다.
PG JSON·URL·회복 도구는 infrastructure에만 있다. 새 domain은 JPA·HTTP·Spring 트랜잭션에 의존하지 않는다.

## 실제 검증으로 보완한 구현

초기 동시 주문 검증에서 존재하지 않는 payment_attempts PK의 FOR UPDATE가 InnoDB gap lock을 만들었다.
Order 행을 이미 잠갔으므로 DRAFT에서는 시도 부재를 상태 불변식으로 판단하고, 기존 시도가 있는 상태에서만 해당 행을 잠근다.
교착 재시도 횟수·실패 기대를 완화하지 않는다. 외부 transactionKey는 UTF-8 binary collation으로 저장해 문자열 계약을 보존한다.
