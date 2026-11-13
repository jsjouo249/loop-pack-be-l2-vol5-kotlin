# 8주차 쿠폰 발급 설계 — Redis SSOT

## 근거와 변경 계약

- 최초 실습: Redis 세마포어로 동기 DB 발급 진입을 제한하고 DB가 한도·중복·발급 결과를 확정했다.
- 최초 실습의 수용 정책: 세마포어 자리가 가득 차면 대기하지 않고 즉시 실패한다.
- 사용자 승인: Redis 세마포어를 유지하면서 Lua로 Redis를 쿠폰 발급 SSOT로 전환한다.
- 사용자 승인에 따른 변경: Redis의 발급 결과 기록을 성공 기준으로 삼고 DB는 Stream의 비동기 projection으로 바꾼다.
- 설계 결정: GET 보유 목록도 Redis primary에서 읽어 DB 반영 전의 성공 결과를 확인한다. Redis 장애에는 조회도 503이다.
- 유지: 최초 201·같은 결과 재요청 200, 숫자 couponId, 사용자별 한 장, 본인 목록, 품절·비활성 409, 슬롯 부족 429.
- 유지: 기간·할인 적용·사용·관리 CRUD는 제외하고 로컬 fixture로 캠페인을 준비한다.
- 사용자 변경: 슬롯은 기본 3이며, 원발제의 즉시 실패를 최대 30초 획득 대기로 확장했다. 서버 자원 보호를 위해 로컬 대기는 최대 50명으로 제한한다.
- 유지: TTL 회수 없음, 단일 로컬 발급 인스턴스의 명시적 복구.
- 코드 확인: 기존 X-USER-ID·사용자 존재 확인은 유지한다. 따라서 사용자 확인 DB 조회는 남으며 전체 DB 장애 독립성을 주장하지 않는다.
- 코드 확인: 기본 Redis는 REPLICA_PREFERRED이므로 쿠폰 어댑터는 redisTemplateMaster와 String serializer만 사용한다.

## API와 성공 기준

| API | 계약 |
| --- | --- |
| POST `/api/v1/coupon-campaigns/{campaignId}/issues` | 신규 Redis 발급 201, 같은 사용자·캠페인의 기존 Redis 결과 200 |
| GET `/api/v1/coupons` | Redis의 본인 쿠폰만 issuedAt DESC·couponId DESC로 반환 |

응답은 couponId·campaignId·userId·issuedAt·benefitDescription이다. userId를 body나 query로 받지 않는다.
잘못된 요청자·없는 사용자는 기존 인증/오류 규칙을 따르고, 캠페인 없음 404, 품절·비활성 409,
획득 대기 만료·대기 인원 초과는 429, Redis 오류·DIRTY·닫힌 신규 수용은 503이다.
로컬 대기 thread가 중단되면 COUPON_ADMISSION_CANCELLED/503이며 건강한 전체 수용을 닫지는 않는다. Retry-After 1은 발급 예약이 아니다.
닫힌 수용에서도 Redis가 정상이라면 기존 결과와 본인 목록은 조회할 수 있다.
Redis에 접근할 수 없을 때 지연된 DB projection을 최신 발급 결과처럼 대신 응답하지 않는다.

## 데이터와 불변식

| Redis 키 | 자료형·역할 |
| --- | --- |
| `coupon:admission:{issuance}:active` | Set: 현재 발급 실행 UUID token |
| `coupon:admission:{issuance}:state` | String: 명시적 열기에서 확인한 primary run_id |
| `coupon:ssot:{issuance}:meta` | Hash: schema, health READY/DIRTY, sequence, campaign:<id> 등록 표식, owner:<id> 쿠폰 수 |
| `coupon:ssot:{issuance}:campaign:<id>` | Hash: name, benefit, enabled, limit, issued |
| `coupon:ssot:{issuance}:owner:<id>` | Hash: campaignId → 발급 사실 JSON |
| `coupon:ssot:{issuance}:events` | Stream: DB에 반영할 발급 사실 |

모든 키는 같은 hash tag를 쓴다. Cluster 자동 발견은 도입하지 않으며 현재 static primary/replica 연결을 유지한다.
실제 보장은 한 Redis primary에서의 Lua 원자 실행과 단일 로컬 발급 인스턴스 범위다.

Lua가 `issued <= limit`, 동일 사용자·캠페인 중복 방지, 숫자 ID 할당, 발급 사실·이벤트 기록을 담당한다.
ID·사용자 ID·캠페인 ID·한도는 JSON에서 문자열로 보관하고 JVM에서 Long으로 읽는다.
Lua 계산에 사용하는 sequence·한도는 9,007,199,254,740,990 이하의 정수로 제한한다.
발급 시각은 서버에서 생성해 microsecond로 자르며 최초 응답과 DB 반영 후 시각이 같다.

등록 표식은 캠페인 키 유실을 신규 캠페인으로 오해해 issued=0으로 다시 만드는 것을 막는다.
owner 수와 HLEN을 비교해 보유 hash 전체·일부 필드 유실도 빈 보유 상태로 해석하지 않는다.
임의의 변조·여러 키의 동시 손실을 모두 탐지하는 checksum이나 자동 복원 시스템은 아니다.

## 요청과 Lua 경계

1. 사용자 확인 뒤 Redis의 기존 발급 결과를 조회한다. 있으면 새 슬롯 없이 200으로 반환한다.
2. OPEN인 로컬 수용 경계에서 실행을 추적하고 Redis Lua로 슬롯을 획득한다. 가득 차면 아래 대기 정책을 적용한다.
3. 발급 Lua가 메타데이터·키 타입·TTL·permit 소유권·입력·중복·캠페인·한도를 검사한다.
4. JSON·응답·다음 ID 계산까지 완료한 후 meta.health를 DIRTY로 변경한다.
5. sequence·issued·owner 수·발급 JSON·Stream을 기록하고 마지막에 READY로 바꾼다.
6. 확정 성공·업무 거절이면 자기 token을 반환한다. Redis 실행 결과 불명은 점유를 유지하고 신규 수용을 닫는다.

DIRTY는 자동 rollback의 대체물이 아니라, 부분 변경을 정상 결과처럼 사용하지 못하게 하는 표식이다.
WRONGTYPE·비정상 숫자·잘못된 입력 등은 쓰기 전에 거절한다. 후속 명령 권한 오류 같은 런타임 오류는
앞선 쓰기를 남길 수 있으므로 DIRTY에서 조회·신규 발급·worker를 막는다.
redis.pcall이나 무조건 INCR로 일반 rollback을 구현하지 않는다. 타임아웃도 발급 실패의 증거로 보지 않는다.
응답 유실 후 READY에 저장된 같은 결과는 재요청에서 그대로 반환한다.

세마포어와 발급 Lua는 별도다. 획득 호출은 모든 요청이 Redis에 보내므로, 전체 Redis 부하 감소는
발급 Lua 비용·거절 비율·반환 비용을 포함해 측정해야 한다. 초당 요청 수 제한이나 성능 개선을 자동 보장하지 않는다.

## 슬롯 획득 대기

| 서버 설정 | 기본값 | 의미 |
| --- | --- | --- |
| coupon.admission.capacity | 3 | Redis 활성 발급 실행 상한 |
| coupon.admission.wait-timeout-ms | 30000 | 슬롯 획득 대기 한도. 0이면 즉시 판정 |
| coupon.admission.max-waiters | 50 | 한 앱에서 대기 등록할 실행 수 상한 |
| coupon.admission.retry-interval-ms | 200 | 반환 알림이 없을 때 Redis 재검사 간격. ±25% jitter |

30초는 획득 대기시간이며 획득한 token의 TTL이나 작업 실행 제한시간이 아니다.
안정된 수용량 상태에서 Lua가 확정적으로 FULL을 반환한 경우만 같은 실행 token으로 재시도한다.
대기 중에는 DB TX를 열거나 쿠폰을 발급하지 않고, Redis 활성 Set에도 token이 없다.
기존 결과를 반환하는 중복 요청은 대기 없이 반환한다.

대기는 JVM monitor의 timedWait로 monitor를 해제하고, 다른 실행의 반환 후 notify로 한 요청을 깨운다.
외부 client의 반환도 확인하도록 주기적 Redis 재검사를 유지한다. FIFO·선착순 예약은 보장하지 않는다.
최초 획득 시도부터 단조 시계로 deadline을 계산하며, deadline 뒤 새 Redis 획득 시도를 시작하지 않는다.
이미 시작한 Redis 명령은 별도 command timeout 정책을 따른다. 사용자 확인·기존 결과 조회·실제 발급까지 포함한
전체 HTTP 시간이 정확히 30초 이하라는 의미는 아니다.

시간이 만료되거나 대기 등록 50명이 차면 429다. Redis 접근 오류·불확실한 응답·DIRTY·닫힌 수용은
30초 동안 반복하지 않고 503으로 종료한다. 닫힘은 대기자를 모두 깨우고 새 시도를 차단한다.
로컬 대기 interrupt는 interrupt flag를 복원하고 자기 대기만 정리한다. Redis 호출 중 오류는
이미 token이 기록됐을 수 있으므로 일반 취소처럼 반환하지 않고 수용을 닫는다.

대기·획득 호출·발급·반환은 모두 로컬 추적에 포함돼 재개가 진행 중 실행을 추월하지 못한다.
waiter는 Servlet thread를 점유하므로 기본 50명으로 제한한다. 이 상한은 인스턴스별이며
별도 실행 풀의 격리나 HTTP 유입 전체의 자원 상한을 보장하지 않는다.
재시작 후 복원되는 사용자 대기실·Redis Sorted Set queue는 아니다.

coupon.admission.waiting gauge로 현재 대기 수를, outcome=waiting/wait_timeout/waiting_full/wait_cancelled로
등록·만료·상한·중단을 관찰한다. 대기가 Redis 재검사 부하와 응답 지연을 늘리는 비용도 측정해야 한다.

## DB write-behind

Stream group은 coupon-db, consumer는 단일 로컬 worker용 고정 이름 local이다.
최초 bootstrap에서만 XGROUP CREATE ... 0 MKSTREAM으로 만든다. worker가 없는 Stream을 자동 재생성하지 않는다.
각 tick은 자신의 pending을 먼저 읽고, 없으면 새 이벤트를 최대 20개 읽는다. BLOCK·자동 trimming은 사용하지 않는다.

DB TX는 캠페인 행을 먼저 잠그고 사실 ID·소유자 조합을 재검사한다.
신규이면 projection의 발급 수와 쿠폰 insert를 같은 TX로 반영한다. 동일한 사실이 이미 있으면 증가 없이 종료한다.
ID·소유자·시각·혜택이 다른 충돌은 성공으로 바꾸지 않는다. DB unique/CHECK는 projection의 최종 방어선이다.
DB 캠페인 메타데이터는 이벤트 당시의 snapshot이며 현재 발급 가능 여부의 원본은 Redis다.
Redis에서 부여한 캠페인·쿠폰 ID를 DB에 그대로 저장하며 DB의 AUTO_INCREMENT를 사용하지 않는다.

worker는 DB TX proxy가 commit을 마친 뒤 XACK한다. DB 실패는 Redis 발급을 취소하지 않고 pending을 남긴다.
commit 뒤 ACK 실패도 다음 tick에서 같은 사실을 재처리한다. ACK 오류·Redis 읽기 오류는 신규 수용을 닫는다.
DB 장애만으로 이미 확정한 Redis 결과를 되돌리지 않는다. 사용자 확인을 위한 DB 조회는 여전히 필요하다.
이벤트 삭제·DLQ·다중 worker claim·Kafka는 구현하지 않는다. 미해결 첫 이벤트는 로그를 남기고 batch를 중단한다.

## 영속성·준비·복구

최초 준비와 명시적 재개에서 appendonly=yes, appendfsync=always, maxmemory-policy=noeviction을 검사한다.
코드가 기존 Redis 서버의 설정을 바꾸지는 않는다. ACL은 CONFIG GET·INFO·사용하는 자료형 명령과 scripting 권한이 필요하다.
AOF와 noeviction은 로컬 실습의 전제이며 비동기 복제 failover나 모든 저장장치 장애에서 무손실을 보장하지 않는다.

최초 데이터셋만 `coupon.ssot.bootstrap-confirmed=true`로 준비한다. 기존 META가 없지만 대상 캠페인·Stream이 남아 있으면
빈 상태로 만들지 않는다. 기존 DB SSOT 쿠폰의 자동 이관은 하지 않으므로 기존 발급 데이터가 있으면 먼저 별도 이관이 필요하다.
정상 재시작은 기존 캠페인·발급 결과·Stream을 그대로 확인하며 issued를 초기화하지 않는다.

로컬 fixture의 Redis 준비 실패는 오류 로그와 닫힌 쿠폰 수용으로 남기며 다른 기능의 앱 시작을 중단하지 않는다.
수용은 기본 CLOSED다. 기존 발급 실행과 Redis 작업의 종료, READY 상태와 실제 쿠폰·수량·Stream을 확인한 뒤
명시적으로 연다. 재개 시 Stream 길이와 sequence, consumer group 존재도 검사한다. Redis 재접속·PONG만으로 열지 않는다. 준비·재개는 열린/진행 중 실행이 있는 로컬 상태에서 거절한다.
세마포어 초기화는 active/state만 다루며 SSOT 데이터·DIRTY·이벤트를 삭제하거나 고치지 않는다.
DIRTY라면 worker와 발급을 멈추고 기록을 대조해 수동 복구한다. 보존된 부분 사실에 근거 없이 수량을 증가시키지 않는다.
일반적인 DIRTY 자동 복구 명령은 제공하지 않는다. 정합성을 확인할 수 없으면 닫힌 상태를 유지한다.

## 현재 Redis 구성과 failover 범위

저장소 Compose는 redis-master:6379와 redis-readonly(호스트 6380) 두 프로세스다.
애플리케이션은 RedisStaticMasterReplicaConfiguration으로 이 주소를 고정한다.
쿠폰은 MASTER 전용 연결을 쓰며 Sentinel/Cluster의 primary 자동 발견·승격은 없다.
단일 로컬 발급 인스턴스라는 복구 범위는 Redis 프로세스 수와 다른 개념이다.

한 Redis 프로세스만 있다면 승격할 replica가 없으므로 failover 대신 그 서버·영속 데이터의 복구가 필요하다.
현재의 primary+replica도 별도 Sentinel/관리형 failover가 없으면 자동 승격되지 않는다.
Compose의 두 노드는 SSOT 전제인 appendfsync=always/noeviction을 명시한다. 기존 실행 중 서버 설정은 자동 변경하지 않는다.

AOF always는 해당 노드의 로컬 영속성 정책이며 replica로의 동기 복제 보장이 아니다.
Redis 기본 복제는 비동기이므로 Sentinel을 도입해도 성공 응답한 쓰기가 승격 과정에서 유실될 수 있다.
WAIT도 이 구성을 강한 일관성 시스템으로 만들지는 않는다.
같은 Redis에 있는 발급 사실·수량·Stream·permit 모두 영향을 받으므로 확인 없이 새 primary에서 자동 재개하지 않는다.

근거: [Redis replication](https://redis.io/docs/latest/operate/oss_and_stack/management/replication/),
[Sentinel](https://redis.io/docs/latest/operate/oss_and_stack/management/sentinel/),
[AOF 정책](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/).

## 검증

- domain/application: 기존 결과, 신규·중복·업무 거절, 슬롯 반환·결과 불명, projection replay·ACK 순서.
- 실제 Lua: C=3 경쟁, 8명/5장, 중복, 원본 조회, pending 재전달·ACK, 실제 XADD 권한 오류의 부분 변경과 DIRTY, AOF 보존.
- 실제 JVM 어댑터: MASTER·serializer·JSON 해석·동시 발급·Stream·부분 실패·AOF 재시작·등록 키 유실 감지.
- 대기: 반환 후 동일 요청 수용, deadline 뒤 추가 시도 없음, 대기 상한·interrupt·닫힘·Redis 재시도 오류·대기 수 정리.
- MySQL/HTTP: DB 반영 전 201/목록, quota 경쟁, projection rollback·ACK 실패 재처리, 사용자 격리, 실제 endpoint 중단.
- W7 기능은 변경하지 않는다. 실행 결과와 환경 제한은 [검증 기록](verification-report.md)에 기록한다.
