# 6주차 실습 체크리스트

설계 근거·정책은 [backend-design.md](backend-design.md), 실행 결과는 verification-report.md에 기록한다.
완료 표시는 구현뿐 아니라 해당 검증의 실제 성공까지 확인한 뒤 갱신한다.

- [x] 안정적인 OrderConfirmed v1 신원·품목 snapshot과 입력 검증
- [x] Spring 실제 TX synchronization으로 commit 이후 발행, rollback/무TX 미발행과 발행 오류 비전파
- [x] 실제 Kafka broker의 orderId key·payload 확인, 발행 확인/오류 metric 분리
- [x] producer acks/idempotence/retries와 단일 broker의 한계 명시
- [x] 실제 MySQL에서 최초 PG 성공 전이·confirmedAt 보존·callback/reconcile 중복 미발행 확인
- [x] 구매 활동·중복 방지 기록의 원자적 DB 저장, 양수/유일 제약 검증
- [x] 같은 eventId/다른 eventId의 동일 주문 중복, 식별자/payload 충돌, 동시 경합 검증
- [x] 모든 품목 rollback, flush/clear 후 재조회
- [x] listener의 서비스 성공 후 ack 순서, 실제 broker에서 오류 시 소비 중단과 실패 offset 미진행·재시작
- [x] DB를 사용하지 않은 실제 broker의 같은 group 재전달과 committed offset n+1
- [x] 실제 MySQL commit 뒤 미commit 종료·재전달에서 DB 효과 유지·n+1 확인
- [x] partition 2개와 consumer 1/2/3개, 별도 group 배정 실험
- [x] 관련 테스트·ktlintCheck·ArchitectureTest·전체 check 실행과 건수/실패 원인 기록
- [x] 전체 MySQL 회귀 검사 통과 (2026-10-30: 210건, 실패·오류·skip 0)
- [x] commit→발행 누락, retention, 복구·재시작 방법과 관측 위치 기록
