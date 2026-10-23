# 5주차 구현과 검증 기록

## 현재 결과

구현·설계·작은 HTTP fixture를 반영했다. 최종 MySQL 전체 검증은 아직 완료하지 못했다.
이전 전체 검사에서는 Docker Desktop 엔진이 `Docker Desktop is unable to start`를 반환했다.
2026-10-23 PR 준비 시에도 Docker 소켓이 없어 DB 통합 검사를 재실행하지 못했다.
성공한 최종 비DB 검증과 실패한 전체 검증을 구분하며, 이전 주차 통과 수치를 이번 결과로 재사용하지 않는다.

- 최종 domain / HTTP 계약 / 협력 / DB retry 단위 / ArchUnit: **51 tests / 0 failures / 0 errors / 0 skipped**, exit 0.
- 이 중 실제 HTTP adapter 계약은 **8건**, ArchitectureTest는 **1건**이다.
- 최종 `:apps:commerce-api:ktlintCheck`: exit 0. 모든 테스트 소스 compileTestKotlin도 성공했다.
- 전체 check 시도: **176 tests / 120 failures / 0 errors / 0 skipped**, exit 1.
  Docker 환경을 찾지 못해 Spring Testcontainers 초기화부터 실패했다. 전체 통과로 계산하지 않는다.
- 전체 check 이후 추가한 삭제 후 성공/미확인 422/반환 overflow 통합 테스트 3건과
  Order 부분 UPDATE 강화는 컴파일·lint만 확인했다. Docker 복구 후 최종 전체 DB 재검사가 필요하다.

집계: [비DB 최종 결과](unit-http-architecture-results.json), [전체 검사 환경 실패](check-environment-failure.json).

## PR 준비 검증 (2026-10-23)

- 설치된 IntelliJ IDEA의 JDK 21.0.9로 `ktlintCheck`, `assemble`, 아래 비DB 테스트를 실행했다.
- `BUILD SUCCESSFUL`, exit 0, 24초. 45개 task 중 3개 실행, 42개 UP-TO-DATE였다.
  `test`와 `bootJar`는 실행했으며 lint·컴파일은 동일 입력의 기존 통과 결과를 재사용했다.
- JUnit XML 재집계: **51 tests / 0 failures / 0 errors / 0 skipped**, 12 suites.
  HTTP adapter 계약 8건과 ArchitectureTest 1건을 포함한다. 비DB 집계 JSON도 이번 실행으로 갱신했다.
- `docker info`는 `unix:///Users/joseongjun/.docker/run/docker.sock`에 연결하지 못했고 `no such file or directory`를 반환했다.
  Docker를 자동 시작하지 않았으며 전체 `check`와 MySQL·Redis 통합 테스트는 재실행하지 않았다.
- 따라서 아래 이전 전체 검사 176건·120건 실패는 과거 환경 실패 기록이며, 이번 실행 결과와 구분한다.

## 구현

- 신규 DRAFT의 confirm을 PG로 교체했다. 포인트 충전·조회는 유지하고 주문에서는 포인트를 읽거나 차감하지 않는다.
- Order / PaymentAttempt / StockReservation의 상태 전이와 DB unique/check/FK를 추가했다.
- TX A 가용 재고 차감·예약 및 결제 시도·PAYMENT_PENDING 저장 → TX 밖 PG 생성 요청 → TX B 결과 반영으로 분리했다.
  TX A에서 새 시도를 생성하고 commit 성공을 확인한 실행만 PG 생성 요청을 최대 1회 시도한다.
  TX A commit 직후 중단되면 실제 호출은 0회일 수 있다. 재시작·동시 재요청은 저장된 시도로 수렴하며 새 POST 권한을 얻지 못한다.
- PG와 로컬 DB 변경의 원자적 성공을 보장하지 않는다. 로컬 결과 반영 실패는 TX B rollback과 예외로 처리한다.
  이후 명시적인 reconcile 요청이나 PG 콜백 처리에서 기존 거래를 조회하고 같은 결과 적용 경계로 다시 반영한다.
  PG 생성 요청은 재발송하지 않으며 주기적인 자동 대사 스케줄러는 구현하지 않았다.
- create의 정확한 422 무효과 거절만 FAILED로 종결한다. 응답 유실·0건·404·일반 오류는 예약 반환 근거가 아니다.
- 콜백·대사는 외부 조회 후 같은 결과 적용 경계를 쓴다. 중복 종결·반환과 늦은 응답의 상태 역행을 막는다.
- 복수 거래는 영구 격리하며 이후 단일 거래가 보여도 자동 해제하지 않는다.
- 삭제 상품 반환은 재고만 부분 갱신한다. 관리자 수량은 가용 수량이고 성공 때 추가 차감하지 않는다.
- 확보·반환 commit 뒤 4주차 캐시 무효화를 연결했다. 캐시 실패를 DB retry·PG 재발송으로 연결하지 않는다.
- POINT 전환 SQL과 실제 기존 스키마 전환 테스트를 제공했다. 운영 DB에는 실행하지 않았다.

## 검증 과정과 수리

1. 대표 Red는 PG 상태 전이 API 부재에 따른 compileTestKotlin 실패(exit 1)였다. JUnit 실행 성공으로 세지 않았다.
2. 실제 DB 첫 묶음은 23건 중 1건 실패였다. 주입 IllegalStateException의 Spring 예외 변환을 기대값에 반영하고 rollback 상태 검증은 유지했다.
3. 독립 read-only reviewer가 콜백 오류 표시의 잔존, 잘못된 입력의 500, create 식별 불일치의 REQUESTED 잔존,
   외부 Unicode 거래 키의 ASCII 저장 문제를 지적했다. 모두 수정하고 대응 테스트를 추가했다. 후속 검토에서 추가 결함은 없었다.
4. 중간 전체 실행 170건 중 5건 실패에서 실제 동시 주문의 gap lock 교착을 발견했다.
   잠근 DRAFT의 불변식으로 시도 부재를 판단해 없는 PK의 FOR UPDATE를 제거했다. DB 재시도 횟수와 기대 성공 수는 완화하지 않았다.
   나머지 기존 포인트 차감 기대는 PG 잔액 보존으로 갱신했다. 신규 POINT 과거 데이터 전환 검증을 추가했다.
5. Kotlin expression-body 테스트 일부가 assertion 반환형 때문에 JUnit에 등록되지 않았다.
   Unit 반환을 명시하고 최종 XML에서 HTTP 계약 8건의 실제 실행을 확인했다. 초기에 말한 예상 건수는 검증 수치로 사용하지 않는다.
6. gap lock 수정 후 묶음은 41건 중 39건 성공, 2건 transaction timeout으로 실패했다.
   매우 긴 실행 경과가 기록되었으며 5초 DB transaction 정책은 유지했다.
7. 이후 최종 전체 실행은 Docker 엔진 불가로 초기화부터 실패했다. Docker Desktop/Compose는 에이전트가 자동 시작하지 않았다.
8. 최종 비DB 51건과 lint를 다시 실행해 exit 0을 확인했다. git diff --check도 성공했다.

## 테스트가 검증하는 대표 효과

| 장면 | 외부 거래 / POST | 주문·예약·가용 재고·포인트 |
|---|---|---|
| 5개 중 2개 확보 후 성공 | 1 / 1 | CONFIRMED / CONSUMED / 3 / 10000 유지 |
| 효과 전 확정 거절 | 0 / 1 | PAYMENT_FAILED / RELEASED / 5 / 10000 유지 |
| 거래 생성 후 응답 유실 | 1 / 1 | UNKNOWN·RESERVED 유지 후 같은 거래 조회로 종결 |
| 중복 실패 callback/reconcile | 1 / 1 | 반환 1회, 5 유지; 7이 되지 않음 |
| 발송 시작 저장 뒤 HTTP 전 중단 | 0 / 0 | 새 서비스도 재발송하지 않고 예약 유지 |
| 복수 거래 | 2 / 1 | 격리·예약 유지, 자동 종결 없음 |

이 표는 테스트의 기대 결과다. 중간 DB 실행의 통과가 최종 소스 전체 통과를 대신하지 않는다.
각 통합 테스트는 flush·clear 후 다시 읽으며 실제 controller callback과 실제 HTTP adapter를 사용한다.
HTTP fixture는 worker를 유한 시간 내 종료하고 request factory를 닫는다.
현재 Docker 엔진이 응답하지 않아 최종 컨테이너 목록 확인은 불가능하다.
기존 Redis 장애 테스트가 생성한 week4 측정 파일은 기존 커밋 내용으로 복원했다.

## 재실행

JDK 21과 사용자가 실행한 Docker 엔진이 필요하다. PR 준비에서 확인한 JDK를 사용한다.

```bash
env JAVA_HOME='/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home' \
  DOCKER_CONFIG=/private/tmp/week4-docker \
  DOCKER_HOST=unix:///Users/joseongjun/.docker/run/docker.sock \
  ./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check --console=plain
```

전환·상태·잠금은 [설계](backend-design.md)를 따른다. 상시 스케줄러·자동 예약 만료·환불·재결제·운영자 격리 해제는 범위 밖이다.
미확인 예약의 장기 점유와 수동 판단 비용은 남는다. 블로그 문서는 이 PR에 포함하지 않는다.
