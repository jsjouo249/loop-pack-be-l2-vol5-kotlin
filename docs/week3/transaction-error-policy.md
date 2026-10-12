> 전체 코드 리뷰 항목 수정 요청으로 [review-fixes.md](review-fixes.md)의 보완 설계를 적용한다.
> 좋아요 쓰기 2개가 공통 보호 대상에 추가되어 총 10개이며, 조회 projection 컬럼·인덱스,
> 일괄 삭제 저장과 application 읽기 transaction·Example 책임 이동은 기존 본문의 해당 결정을 확장한다.

# 트랜잭션 오류와 재시도 정책

## 1. 이 문서의 상태

- 사용자 확정: 전체 rollback이 확인된 deadlock만 1회 재시도한다(최초 포함 총 2회).
- 사용자 확정: 확인된 일시적 잠금 실패는 `503 TRANSACTION_BUSY`, 그 밖의 기술 오류는 기존 500으로 구분한다.
- 검증 상태: 공통 executor/attempt와 오류 매핑 구현, 재시도 단위 및 실제 MySQL 교착·timeout·HTTP 검증 통과. [실행 기록](verification-report.md)과 [기술 구현](backend-design.md#9-구현에서-구체화한-기술-사항)을 따른다.
- 사용자 확정: 재시도 간격은 고정 50ms이며, 보호하는 쓰기 유스케이스에 공통 적용한다.
- 범위는 단일 MySQL DB의 주문 확정·충전·브랜드 삭제·상품 등록·상품 정보 수정·재고 설정·
  상품 개별 삭제·브랜드 이름 수정이다. 일반 조회나 무관한 초기 생성까지 자동 확대하지 않는다.
  외부 PG 호출이나 새 멱등성 키 체계를 추가하지 않는다.
- [작업 계획](implementation-plan.md), [결정 목록 D4](design-discussion.md)와 함께 읽는다.

## 2. 실패 종류에 따라 판단이 달라진다

| 종류 | 의미 | 전체 결과를 아는가 | 확정된 처리 |
|---|---|---|---|
| 재고·잔액 부족, 이미 확정 | 유효한 업무 규칙에 따른 거절 | 정상 rollback 경계 종료로 확인 | 기존 업무 오류, 서버 자동 재시도 없음 |
| deadlock | 서로 잠금을 기다려 DB가 한 transaction을 중단 | DB가 희생 transaction 전체 rollback | 외부 경계에서 종료 확인 후 새 transaction으로 최대 1회 재시도 |
| lock wait timeout | 잠금을 정해진 시간 안에 얻지 못함 | DB 기본 동작만으로 전체 취소를 단정 못 함 | 예외를 밖으로 전달해 전체 rollback, 자동 재시도 없이 503 |
| commit 부근 connection 끊김 | 성공 확정 응답을 받지 못함 | commit됐는지 모를 수 있음 | 자동 재시도 금지, 상태 확인 필요 |
| 잘못된 SQL·프로그램 오류·예상하지 못한 무결성 오류 | 같은 실행을 반복해도 해결된다는 근거 없음 | rollback 성공 여부 별도 확인 | 500과 원인 기록, 재시도 없음 |

DB 근거: MySQL은 deadlock에서는 transaction 전체를 취소하지만 lock timeout은 기본적으로
해당 문장만 취소한다. 설정에 따라 timeout도 전체 rollback할 수 있다.
[MySQL 8.0 Error Handling](https://dev.mysql.com/doc/refman/8.0/en/innodb-error-handling.html)

이 서비스는 여러 저장이 함께 성공해야 하므로 timeout 이후 SQL 하나만 다시 실행하거나
정상 반환하지 않는다. runtime 데이터 접근 예외를 프록시 밖으로 전달하여 application
transaction 전체 rollback을 요청하고 종료 결과를 확인한다.
[Spring rollback 규칙](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html)

rollback 자체가 connection 장애로 실패하거나 commit 결과가 불명확하면 안전한 재시도
대상으로 분류하지 않는다. 모든 DataAccessException을 하나의 재시도 대상으로 묶지 않는다.

## 3. 가장 위험한 오해: timeout이면 앞 저장도 취소됐다는 가정

```text
한 application transaction
  상품 A 재고 변경 SQL 성공
  다음 저장에서 잠금 대기 timeout
  예외 catch 후 정상 응답 → 앞 변경이 남을 수 있음

필요한 경계
  timeout 예외 전달 → application transaction 전체 rollback
  → 프록시 호출 종료 → 새 DB 조회로 상품 A까지 복구 확인
```

예시는 실패 경계를 설명하기 위한 것이며 확정된 잠금 순서를 뜻하지 않는다.
도메인 사전 검증만 통과했다고 이 rollback이 증명되지 않는다. T14에서는 실제 앞 변경 뒤
잠금 실패를 유발하고 모든 상태가 유지되는지 확인한다. 운영 코드에 실패 장벽은 추가하지 않는다.

## 4. 안전한 서버 내부 재시도의 조건

1. 해당 시도가 commit되지 않고 전체 취소됐다고 판단할 근거가 있다.
2. 실패한 transaction과 persistence context를 종료한다.
3. transaction 바깥 조율자가 횟수·시간 한도를 확인한다.
4. 새 transaction에서 주문 상태·상품·재고·잔액을 다시 읽고 domain 규칙을 다시 적용한다.
5. 재시도 결과가 재고 부족·잔액 부족·이미 확정일 수도 있다. 이를 원래 실패 원인으로 덮지 않는다.

```text
재시도 조율자(transaction 밖)
  → Spring proxy → 확정 유스케이스 transaction #1
  ← deadlock, 전체 rollback 종료
  → 제한된 대기(잠금과 transaction을 보유하지 않음)
  → Spring proxy → 확정 유스케이스 transaction #2, 전부 재조회
  ← 성공 또는 업무 거절 또는 기술 실패
```

같은 객체의 self-invocation이나 실패한 transaction 안의 catch/loop로 구현하지 않는다.
기존 저장을 REQUIRES_NEW로 조각내는 것도 재시도가 아니다. AOP를 사용한다면 retry interceptor와
transaction interceptor의 순서가 위 경계를 실제로 만들도록 검증해야 한다.

DB 내부 충전과 위 관리자 쓰기도 확실히 rollback된 deadlock이면 동일 정책으로 재실행한다.
반면 commit 결과 불명이나 HTTP 요청 재전송은 동일한 안전 조건을 만족하지 않는다.

## 5. 서버 재시도와 사용자의 재전송은 다르다

```text
충전 +2,000이 DB에 commit됨
응답 전달 중 연결 끊김
클라이언트가 실패로 보고 +2,000 재전송
요청 중복 식별 계약이 없으면 두 번째 충전도 정상 수행될 수 있음
```

따라서 오류 응답에 무조건적인 재전송 안내나 재시도 가능 플래그를 붙이지 않는다.
확실히 rollback된 내부 시도만 재실행하는 정책이 HTTP 충전 요청의 멱등성을 만드는 것은 아니다.

주문 확정은 orderId와 상태 잠금으로 중복 차감을 방지하고 기존 주문 조회로 상태를 확인할
수 있다. 이미 확정됐으면 기존 ORDER_ALREADY_CONFIRMED를 유지하며 성공 응답 재사용 기능을
추가하지 않는다. 현재 포인트에는 거래 이력이 없어 잔액만으로 특정 충전의 성공을 식별할 수 없다.
이 한계를 감추거나 이번 범위를 요청키·원장 시스템으로 확장하지 않는다.

## 6. 선택한 정책과 대안

### A. 자동 재시도 없음 — 선택하지 않은 대안

- 모든 기술 실패에서 전체 rollback 경계와 원인을 확인하고 실패를 반환한다.
- 장점: 실패·잠금 순서가 명확히 드러나며 부하 증가나 예외 분류 오판의 여지가 적다.
- 비용: 확실히 취소됐고 재실행하면 성공할 수 있는 deadlock도 사용자 실패가 된다.
- 적합한 범위: 최초 정합성 구현과 원인 분석에 집중하는 단계.

### B. 확인된 deadlock만 제한적으로 재시도 — 사용자 확정

- `[사용자 결정]` rollback이 확인된 deadlock만 최대 1회 재시도한다. 최초 실행 포함 총 2회다.
- timeout은 대기·혼잡을 더 늘릴 수 있어 자동 재시도 대상에서 제외한다. 업무 거절·버그·
  commit 결과 불명도 제외한다.
- `[사용자 결정]` 재시도 전 transaction과 connection을 반환한 뒤 고정 50ms 대기한다.
  jitter는 추가하지 않는다. 잠금 대기 2초·transaction 5초·재시도 진입 예산 15초는
  [상세 설계 §2](backend-design.md)의 기술 기준값이다. 이는 측정된 최적값이 아니며
  네트워크 장애까지 포함한 HTTP 전체 응답 시간의 엄격한 상한을 주장하지 않는다.
- 외부 부작용이 없는 현재 유스케이스에 한정한다. 외부 결제 호출이 들어오면 전제를 재검토한다.
- 장점: 원자성을 유지하면서 일부 일시적 충돌을 복구한다.
- 비용: 오류 원인 판별, 별도 transaction 경계, 시도별 관측과 테스트가 추가된다.

MySQL도 deadlock에 의한 취소를 처리할 준비가 필요하다고 설명한다.
[MySQL 교착 처리](https://dev.mysql.com/doc/refman/8.0/en/innodb-deadlocks-handling.html)
이 문서의 횟수와 timeout 제외는 공식 기본값이 아니라 사용자가 확정한 프로젝트 정책이다.
이전의 자동 재시도 없음 기본안은 채택하지 않았으며, 확실히 취소된 일부 충돌만 복구한다.

## 7. 확정된 응답 계약

| 상황 | 기존 동작 | 확정된 3주차 계약 |
|---|---|---|
| 업무 거절 | 기존 4xx와 구체 코드 | 유지 |
| rollback 확인된 잠금 실패 / deadlock 재시도 소진 | 공통 500, 현재 code는 `Internal Server Error` | 503 + `TRANSACTION_BUSY`로 일시적 경합을 구분 |
| commit 결과 불명 | 공통 500 | 500 유지, 내부에 결과 불명 별도 기록 |
| 버그·무결성 오류 | 공통 500 | 유지, 원인 기록 |

`[사용자 결정]` rollback이 확인된 잠금 실패는 503으로 구분하고 나머지는 기존 500을 유지한다.
503은 이 서버의 일시적 처리 불가를 표현하는 API 계약이며 DB가 정해 주는 HTTP status가 아니다.
응답 code는 interfaces 계층에서 매핑한다. infrastructure가 HTTP 정책을 소유하지 않는다.
503이나 Retry-After 헤더만으로 비멱등 충전 요청의 재전송 안전성을 보장하지 않는다.

deadlock 재시도에서 업무 거절이 나오면 해당 4xx를 그대로 반환한다. 두 번째 시도의 오류가
commit 결과 불명이나 프로그램 오류라면 503으로 합치지 않고 기존 500을 반환한다.
확실한 rollback을 전제로 하지 않는 실패에는 재전송 안전성을 약속하지 않는다.

## 8. 관측과 테스트

- 원 요청별 최종 결과: 성공 / 업무 거절 / 기술 실패. 합은 원 요청 수와 같아야 한다.
- 내부 시도별 결과: 실행 횟수, deadlock, timeout, 재시도 성공/소진, rollback 실패/결과 불명.
- 재시도 후 성공은 원 요청 성공 1건이다. 첫 시도의 오류는 내부 기록에 남겨 재시도로
  불안정한 잠금 순서를 숨기지 않는다. 기술 오류 0은 최종 요청 기준이며 내부 오류와 별도 공개한다.
- 확정 정책 검증: 첫 deadlock 뒤 새 transaction으로 성공, 두 번 실패하면 종료, 두 번째 실행의
  잔액 부족은 업무 거절, 이미 확정된 주문은 추가 차감 없음, connection 결과 불명은 재시도 없음.
- 실제 MySQL에서 앞 변경 이후 잠금 실패의 전체 rollback을 검증한다. retry 분류·횟수의
  단위 테스트만으로 DB 원자성을 증명했다고 주장하지 않는다.

## 9. 상세 설계와 실행 검증

정책 선택과 [상세 설계](backend-design.md)는 완료되었다. 아래는 구현에서 검증할 항목이다.

1. 상세 설계의 잠금 대기·transaction timeout·재시도 진입 예산을 적용하고, 예산이 부족하면
   추가 시도를 시작하지 않는다. 재시도는 최대 1회이며 무조건 두 번 실행한다는 뜻이 아니다.
2. 실제 transaction manager·driver·Spring 예외 변환에서 deadlock/timeout/결과 불명을
   구분한다. 포괄적인 exception superclass 하나로 재시도 대상을 정하지 않는다.
3. 503 code와 envelope를 대표 HTTP 테스트로 확인하고, 내부 오류 원인이나 SQL을 노출하지 않는다.
4. 재시도 횟수·종료·새 transaction 재조회와 DB 전체 rollback을 각각 검증한다.

운영 코드를 바꾸기 전 이 정책을 application 경계, interfaces 오류 변환과 테스트 계획에 연결한다.
