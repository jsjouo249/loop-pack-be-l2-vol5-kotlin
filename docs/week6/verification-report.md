# 6주차 구현·검증 기록

## 상태

공유한 6주차 본문을 기준으로 API 발행, streamer 구매 활동 저장, 테스트와 실제 Kafka 실험을 구현했다.
2026-10-30 기존 Docker 엔진에서 실제 MySQL 통합 검증과 전체 회귀 검사를 완료했다.
Outbox, 주간 랭킹 계산, 누락 자동 복구와 관리 API는 이번 변경에 포함하지 않는다.

## 재검증 — 2026-10-30

JDK 21.0.13, Gradle 8.13과 실행 중인 Docker 엔진을 사용했다.
Docker Desktop/Compose를 시작하거나 기존 개인 DB를 변경하지 않고, 기존 Testcontainers 설정의 격리된 MySQL/Redis를 사용했다.

| 범위 | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| modules:events | 3 | 0 | 0 | 0 |
| modules:kafka | 0 | 0 | 0 | 0 |
| commerce-api 전체 | 190 | 0 | 0 | 0 |
| commerce-streamer 전체 | 17 | 0 | 0 | 0 |
| 합계 | 210 | 0 | 0 | 0 |

변경 4개 모듈의 `check`와 API/streamer의 `assemble`은 **BUILD SUCCESSFUL / exit 0**이다.
`ktlintCheck`, API/streamer 각각의 `ArchitectureTest`, 실행 JAR 빌드를 포함한다.
결과 집계는 [PR 검증 결과](measurements/pr-check-results-20261030.json)에 보존했다.
최초 전체 실행 이후 테스트만 보완했으며, 동일 입력의 event 계약 검사와 main 소스 빌드는 최종 실행에서 재사용했다.

```bash
./gradlew :modules:events:check :modules:kafka:check :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-api:assemble :apps:commerce-streamer:assemble --continue --console=plain
```

이날 최초 실행은 210건 중 새 통합 테스트 3건이 실패했다. 데이터 불변식이 깨진 것이 아니라 예외 타입 기대가 실제 변환과 달랐다.

- repository의 실제 flush 뒤 주입한 `IllegalStateException`은 Spring의 `InvalidDataAccessApiUsageException`으로 변환된다.
  API 발행 rollback과 streamer 저장 rollback 테스트는 변환된 예외와 원래 원인·메시지를 함께 확인하도록 수정했다.
  주문/예약 상태, Kafka 미발행, 모든 품목·receipt rollback 및 이후 재처리 검증은 유지했다.
- MySQL CHECK 위반은 이 JDBC 경로에서 `UncategorizedSQLException`으로 전달된다.
  해당 예외와 원본 SQL 오류 코드 `3819`, SQLState `HY000`, 삽입 결과 0건을 함께 확인하도록 수정했다.

실제 MySQL에서 최초 PG 성공 발행, 확정 시각 보존, 반복 callback/reconcile 미발행과 rollback을 확인했다.
구매 활동의 flush/clear 재조회, 동일/대체 eventId, 충돌·동시 경합·CHECK 제약·모든 품목 rollback도 통과했다.
DB commit 뒤 offset 미commit 종료 → 같은 group 재전달 → 활동 유지와 n+1 commit,
실제 DB 오류 시 listener 중단 → 원인 제거 → 재시작 후 활동 저장과 n+1 commit을 확인했다.
이 검증은 consumer 종료·재시작과 실패 주입이며 OS 강제 종료나 네트워크 분할 실험을 의미하지 않는다.

## 최초 실행 결과 — 2026-10-28

JDK 21, Gradle 8.13, Spring Boot 3.4.4의 의존성을 사용했다.
Kafka 실험은 spring-kafka-test의 실제 단일 KRaft broker로 실행했다. mock broker가 아니며 다중 broker 실험은 아니다.

| 범위 | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| modules:events | 3 | 0 | 0 | 0 |
| commerce-api 전체 | 190 | 130 | 0 | 0 |
| commerce-streamer 전체 | 17 | 7 | 0 | 0 |
| 합계 | 210 | 137 | 0 | 0 |

전체 check는 exit 1이다. 성공한 73건을 전체 성공으로 계산하지 않는다.
137건은 Spring context/Testcontainers 초기화 실패이며 테스트 본문에서 DB 효과를 확인한 결과가 아니다.
새 MySQL 검사도 발행 경로 7건과 구매 활동 7건 모두 환경 차단 상태다.
테스트 기대값·ArchUnit·lint 규칙은 완화하지 않았다.

- `:modules:events:check`, `:modules:kafka:check`: 성공.
- 변경 4개 모듈의 `ktlintCheck`: 성공. 빈 줄 보완 뒤 lint/테스트 소스 compile도 별도 실행해 exit 0을 확인했다.
- API/streamer의 `ArchitectureTest`: 각각 1건, 모두 성공.
- API의 실제 Spring transaction synchronization 2건: commit 이후 호출, rollback/무TX 미호출, 발행 오류의 rollback 비전파 확인.
  이 검사는 DB 없는 transaction manager를 사용하며 실제 MySQL 확정 검사를 대신하지 않는다.
- producer 2건: 실제 broker key/payload, consumer 실행 전 broker 확인 metric, 접속 불가 시 unresolved metric 확인.
- streamer 10건: 도메인 1, listener 3, 계층 1, 실제 broker 배정/재전달 2, 실제 listener 중단/복구 1, profile 설정 2.
- 최초 Red: event 계약 3건 중 수량 변경을 같은 사실로 취급하는 1건이 의도대로 실패했다.
  fingerprint 구현 뒤 3건 모두 성공했다.

초기 독립 검사 41건(exit 0)과 최종 전체 검사 결과는 별개 실행이다. 합산하지 않는다.
구체적인 suite별 집계는 [최종 집계](measurements/full-check-results.json), 초기 실행은
[독립 집계](measurements/independent-results.json)에 보존했다.

## 실제 Kafka 관측

원본 관측은 [kafka-observations.txt](measurements/kafka-observations.txt)에 있다.

- producer: `partition=1 offset=0 key=1`, stable eventId `f71ead3e-c60c-395f-b157-f90ff291e9fc`.
- consumer 1개: 두 partition 담당. 2개: 각 하나. 3개: 둘이 하나씩, 하나는 빈 assignment.
  연속 3회 조건을 확인했고 유한 deadline을 사용했다. 별도 group은 두 partition을 독립 배정받았다.
- 전송 경계 재전달: 첫 consumer는 `offset=0 position=1 committed=null` 상태에서 종료했다.
  같은 group의 새 consumer가 동일 topic/partition/offset을 다시 읽고 `committed=1`로 진행했다.
  이 실험 자체에는 DB가 없으므로 DB 멱등성을 증명하지 않는다.
- 실제 production listener/factory/error handler: 저장 서비스 예외 주입 뒤 `committed=null stopped=true`.
  서비스 복구 후 재시작하면 동일 record가 성공하고 `committed=1`이 됐다.
  실제 MySQL commit/rollback은 별도 통합 검사가 필요하다.

## 최초 실행의 환경 차단과 이후 해소

사용자가 Docker Desktop 실행을 시도한 뒤에도 CLI는 `Docker Desktop is unable to start`를 반환했다.
Testcontainers도 `Could not find a valid Docker environment`로 초기화에 실패했다.
샌드박스 밖에서 로컬 서비스 연결을 확인했으며 Kafka 19092는 거절됐다. 기존 MySQL 3306은 열려 있지만
프로젝트 문서의 application/root 계정으로 접속할 수 없어 기존 개인 DB에 스키마 변경을 하지 않았다.
Docker Desktop/Compose를 자동으로 실행하거나 테스트를 skip시키는 우회 설정을 추가하지 않았다.

당시에는 Docker 엔진 복구 후 아래 재실행이 필요했다. 2026-10-30 위 재검증에서 해소했다.

```bash
./gradlew :modules:events:check :modules:kafka:check :apps:commerce-api:check :apps:commerce-streamer:check --continue --console=plain
```

새 MySQL 검사는 다음을 검증하도록 작성했다.

- confirm/callback/reconcile 최초 SUCCESS, 확정 시각 DB 일치, 반복 요청 no-op, PG 실패 미발행.
- 이벤트 등록 뒤 DB 실패 시 주문·예약 rollback과 Kafka 미발행. broker 실패는 확정 취소/PG 재발송을 유발하지 않음.
- flush/clear 재조회, 동일·대체 eventId, alias 재사용/수량/시각 충돌, 동시 중복, CHECK constraint.
- 모든 품목·receipt·alias를 실제 flush한 뒤 실패해도 전부 rollback.
- DB commit 뒤 offset 미commit 종료, 같은 group 새 consumer의 동일 record 재수신, 활동 유지와 n+1 commit.
- 실제 DB 오류 시 listener 중단·offset 미진행, 원인 제거 뒤 재시작하여 활동 저장과 n+1 commit.

## 독립 리뷰와 보완

codex-new-feature 스킬에 따라 read-only reviewer가 계층, 최초 확정 경계, unique 경쟁 후 새 TX 재검증,
alias 충돌, listener/offset 경계를 검토했다. 새 DB lock은 추가하지 않았다.

- streamer local의 create 설정이 활동·중복 기록을 지울 수 있어 기본값을 validate로 변경했다.
  실제 ConfigData 테스트에서 local=validate, test=create를 확인했다.
- API 재시작의 주문 ID 재사용을 막도록 실행 안내도 validate를 명시하고 초기 생성과 구분했다.
- 저장 UTC 시각과 이벤트 시각을 비교할 때 JDBC Calendar를 명시했다.
- PG 확정 시각은 MySQL microsecond 정밀도로 맞춰 최초 snapshot과 재조회 결과를 일치시켰다.
- 사용자 요청에 따라 전역 AGENTS.md에 가독성을 위한 빈 줄 규칙을 추가하고 변경 블록에 적용했다.

## 남는 보장 경계

주문 commit→Kafka 발행 사이의 누락은 남는다. 로그/metric만으로 발행 전 종료를 모두 찾을 수 없다.
현재 구현에 자동 보정은 없으며 Outbox는 다음 주 범위다.
동일 업무의 새 send와 외부 DB 효과는 producer 멱등성만으로 중복 제거되지 않는다.
retention을 지난 record 복구, 다중 broker 장애·ISR 축소·OS 강제 종료·네트워크 분할은 이번 실험의 증명 범위가 아니다.
