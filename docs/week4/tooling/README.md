# 로컬 측정 재현

Java 21과 사용자가 직접 시작한 Docker 데몬이 필요하다. 실행 환경 prefix는 [진행 기록](../progress.md)을 참고한다.
도구는 Testcontainers의 임시 MySQL·Redis만 사용한다. 영구 DB 주소를 주입하지 않는다.

현재 구현의 DB-only/miss/hit:

```bash
./gradlew :apps:commerce-api:week4Benchmark -Pweek4Phase=final --console=plain
```

상품 10,000·브랜드 10·좋아요 45,000건의 고정 seed를 새 테스트 DB에 넣는다. 브랜드 순번 7은 ID 1007이다.
모드별 20회 준비, SQL/응답 표본 1회, 100회 측정, 동시성 1이다. miss 준비의 전체 영역 삭제 시간은 요청과 분리한다.
각 100회의 실제 SQL 수·hit/miss·DB 원본 목록 호출을 검증하고 SQL/EXPLAIN/EXPLAIN ANALYZE·원시 지연시간·응답을 저장한다.
`final-*.json`과 `eviction.json`은 재실행하면 갱신된다. performance tag는 통상 test/check에서 제외하고 이 task로 실행한다.

캐시 도입 전 HEAD의 기준을 다시 측정하려면 저장소 루트에서 실행한다:

```bash
bash docs/week4/tooling/measure-baseline.sh
```

스크립트는 `491803e`를 임시 폴더에 export하고, 최초 측정에서 사용한 독립 benchmark를 복사한다.
현재 작업 트리와 미커밋 설계를 바꾸지 않는다. 결과는 원본 `baseline.json`을 보존한 채 `baseline-reproduced.json`에 저장한다.
출력된 임시 폴더는 검토 후 사용자가 정리할 수 있게 남긴다. 이 스크립트는 Docker Desktop/Compose를 실행하지 않는다.

실제 중단/재시작 실험:

```bash
./gradlew :apps:commerce-api:test --tests '*ProductListCacheOutageTest' --console=plain
```

이 테스트는 공유 fixture와 별개인 `redis:7.2` 컨테이너를 만들고 해당 컨테이너만 중단·재시작한다.
재시작 때 주소가 바뀌지 않도록 테스트 전용 호스트 포트를 고정한다. AOF로 남긴 옛 snapshot의 존재,
만료 후 현재 DB 결과로의 재채움·hit를 확인한다. `CLIENT PAUSE`는 해당 전용 인스턴스에만 실행한다.
worker/connection/container는 finally/use에서 정리한다. 세부 관측은 `redis-outage.json`에 남긴다.
