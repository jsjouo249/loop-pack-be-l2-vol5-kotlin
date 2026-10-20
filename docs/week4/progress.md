# 4주차 진행 기록

## 시작 상태 (2026-10-15)

- 사용자 요청: 구현·회귀·성능 측정·기록. commit/push/PR 및 운영 DB 변경 제외.
- HEAD `491803e`, 브랜치 `jsjouo249`. 시작 시 `docs/week2/commerce-design.md` 수정과
  미추적 `docs/week4/implementation-plan.md`를 보존했다.
- AGENTS.md, 구현 계획, 커머스 설계 19절, 3주차 Backend 설계·트랜잭션 오류 정책 및 리뷰 보완을 확인했다.
- 기존 projection, 정렬 인덱스 6개, ID 페이지 후 일괄 조회, 정확한 COUNT와
  readOnly REPEATABLE_READ가 구현돼 있다. 캐시와 무효화는 없다.
- 기존 write executor와 transaction attempt의 잠금 순서·rollback 판정·deadlock 1회 재시도를 유지한다.

## 환경과 실행

- 초기 Docker 소켓이 없었다. 사용자가 직접 Docker를 실행했고 Docker 29.7.2 연결을 확인했다.
- Docker Desktop/Compose를 에이전트가 시작하지 않았다.
- 기본 Java 18 및 `openjdk@21` 링크의 Java 26 대신 임시 경로에 Temurin 21.0.12.1을 준비했다.
- 실행 prefix: `env JAVA_HOME=/private/tmp/week4-jdk/jdk-21.0.12.1+1/Contents/Home DOCKER_CONFIG=/private/tmp/week4-docker DOCKER_HOST=unix:///Users/joseongjun/.docker/run/docker.sock ./gradlew`.
- 임시 Docker config는 공개 이미지용 `{"auths":{}}`만 포함한다. 사용자 설정은 변경하지 않았다.
- Gradle 캐시·Docker 소켓은 샌드박스 차단으로 승격 실행한다.

## 작업 단위

1. 테스트 전용 seed와 실제 HTTP benchmark 작성, 운영 코드 변경 전 baseline 측정.
2. SQL/실행 계획 확인 후 필요한 조회 개선 선택·측정.
3. domain의 기술 중립 캐시 데이터·계약 / infrastructure Redis adapter / application 조율.
4. afterCommit 공통 처리와 쓰기 9개 연결.
5. 실제 MySQL·Redis 회귀, 장애·경쟁·복구, 독립 review와 수리.
6. 동일 fixture 최종 측정, 관련 검사·lint·ArchitectureTest·commerce-api check, 결과 집계.

측정·검사는 실행 결과가 확보된 경우에만 완료로 기록한다. 이전 122건 결과는 이번 검증에 포함하지 않는다.

## 완료한 단계와 근거

- 운영 코드 변경 전 baseline: MySQL 8.0.46, SELECT 5회, 응답/SQL/실행 계획/100회 지연 확보.
- ID covering 조회와 projection·6개 인덱스 재사용. 표시 데이터 JOIN으로 SELECT 3회, DB-only 응답 일치 확인.
- 중립 domain 계약, Redis adapter, MASTER 연결 두 timeout 200ms, cache-aside·활성 검사·9개 afterCommit 연결 완료.
- 대표 Red(1건 의도 실패) 이후 캐시 Green, 관련 회귀 33건 성공. 초기 컴파일·lint·중단 실험 실패와 수리를 결과 보고에 구분했다.
- 독립 read-only reviewer 1명이 운영 경로를 검토했다. SCAN 비용 누락을 지적해 INFO commandstats 측정으로 보완했다.
- 실제 전용 Redis 중단/재시작·응답 지연, 이전 snapshot 보존·만료·재채움 검증 완료. worker/connection/container 정리 경계를 확인했다.
- 기준 재현 스크립트를 실제 491803e export에서 실행해 100회 SQL 5회·DB 목록 호출 100회와 응답 일치를 확인했다.
- 최종 비교군은 DB-only/miss/hit 각 100회 SQL과 실제 hit/miss/원본 호출을 검증했다.
  최초 기준과 최종 도구의 응답 검증 포함 타이머 경계를 맞춰 마지막 수치를 고정했다.
- ktlintCheck 및 별도 ArchitectureTest 통과. 첫 전체 commerce-api check는 143/0/0/0, 39 suites, exit 0.
  최종 측정 도구 수정까지 포함한 재실행도 143/0/0/0, 39 suites, exit 0으로 완료했다.
- 최종 작업 트리의 git diff --check, 문서 로컬 링크와 baseline shell 문법 검사도 완료했다.
- 필수 미실행 검사는 없으며 commit/push/PR·운영 DB 변경은 수행하지 않았다.

최종 상세 수치·검사 집계·한계는 [verification-report.md](verification-report.md)와 연결된 JSON을 따른다.

최종 `docker ps` 결과는 빈 목록이었다. 테스트 MySQL·Redis 컨테이너가 남지 않았고, HEAD/브랜치는 시작 상태와 같다.
