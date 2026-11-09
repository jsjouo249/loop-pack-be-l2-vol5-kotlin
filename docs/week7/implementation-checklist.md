# 7주차 구현·검증 체크리스트

코드 작성과 실제 검증 완료를 구분한다. 미검증 항목은 결과가 통과하기 전까지 체크하지 않는다.

- [x] 사용자와 Outbox 모델·PENDING 유지·단일 relay·재시도·W6 revision 재현·보존 정책 합의
- [x] 기존 확정 TX 안의 Outbox serialization/insert, after-commit 직접 발행 제거
- [x] immutable identity/key/JSON, eventId PK·주문별 unique·상태 CHECK·조회 인덱스
- [x] 짧은 DB TX와 직렬 전송, broker future 확인 후 PUBLISHED, 단일 프로세스 실행 가드
- [x] 실패 확인 이후 backoff, 안전한 오류 종류·backlog 관측, 명시 대상 로컬 복구·재개
- [x] 실제 broker에서 동일 JSON·eventId의 별도 offset 관측
- [x] 도메인·계층·DB 없는 소비자 회귀, 빌드·lint 확인
- [x] W6 실제 revision 실행물·준비 fixture와 E2E 소스 컴파일
- [x] 독립 검토 후 복구 원본 검증·manifest 준비 경쟁 보완
- [x] 최종 코드의 실제 MySQL 원자성·실패·late future·DB 상태 저장 오류 검증
- [x] 실제 주문부터 consumer까지 중단·재시작 E2E 통과
- [x] 같은 격리 실행에서 W6 두 사례 준비→복구·두 번째 복구·PG 추가 호출 0 검증
- [x] JDK 21에서 관련 전체 check·건수·실패·skip·종료 결과 확인
- [x] 실행·관측·보존·단일 relay 제한 문서화
