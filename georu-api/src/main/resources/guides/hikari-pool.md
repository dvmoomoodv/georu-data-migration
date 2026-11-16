# 커넥션 풀 크기 (Hikari 튜닝)

DataSource 마다 HikariCP 풀이 하나씩 생깁니다 (`source-m` · `target-m` · `operation` …). 이 안내서는 풀 크기를 정하는 법, 시간 값, 모자라거나 남을 때의 증상과 조치를 다룹니다. 설정 자리는 `georu.datasource.<이름>.hikari.*` 이고 HikariCP 키를 그대로 씁니다.

## 1. 풀이 하는 일

- **연결 재사용**: Reader · Processor · Writer · 채번 · 점검이 연결을 빌려 쓰고 돌려줍니다.
- **동시 연결 상한**: `maximum-pool-size` 가 이 앱이 그 DB 에 동시에 여는 연결의 최대치입니다. 모자라면 `connection-timeout` 만큼 기다리다 실패합니다.
- **DB 쓰기 정책 관문**: 풀이 내주는 연결은 SQL 을 보내기 전에 역할 규칙(소스 SELECT 만 · 타겟 DELETE/TRUNCATE 금지)을 검사합니다. 크기와는 상관없지만, 풀이 곧 역할이라는 뜻입니다.
- **풀이 여러 개인 이유**: 한 DB 가 막혀도 다른 DB 풀은 영향이 없습니다 (타겟 쓰기가 몰려도 운영 저장소의 이력 · 화면은 돈다). DB 마다 크기 · 설정을 따로 줄 수 있습니다.

## 2. 크기 정하는 법

이관은 **한 번에 하나만** 돕니다 (단일 실행 규칙, `georu.batch.executor` 1/1). 그래서 풀 크기는 "Job 수" 가 아니라 **Job 하나가 동시에 쓰는 연결 수**로 정합니다.

| 기호 | 뜻 | 값 |
|---|---|---|
| J | 동시에 도는 Job | 1 (고정) |
| W | 동시에 쓰는 Writer 수 | Queue Writer(`georu.batch.queue.enabled: true`)면 `georu.batch.queue.writer-thread-count` (기본 5). Queue 를 켜지 않으면(엔진 기본) 1 |

| 풀 | 최소 | 무엇이 쓰나 |
|---|---|---|
| 소스 | **J×2 + 2** (= 4) | Reader 페이지 조회 · Processor 조회(SourceLookup) + 건수 대사 · 키 필터 · 상태 확인 |
| 타겟 | **J×W + 2** (W=5 면 7) | Writer 스레드마다 1 + 채번 선점(별도 트랜잭션, REQUIRES_NEW) · 건수 확인 |
| 채번 전용 (`sequence-*`) | 2~3 | 채번 선점만 |
| 운영 저장소 | 기본값(10)이면 충분 | 실행 이력 · 로그 · Spring Batch 메타 · 화면 조회 |

여기에 여유를 더합니다. 예제 앱(`examples/demo` dev 프로파일)의 값:

```yaml
georu:
  datasource:
    source-m:
      hikari:
        maximum-pool-size: 4      # dev. J(1)×2+2 = 4
        minimum-idle: 1
    target-m:
      hikari:
        maximum-pool-size: 8      # dev. J(1)×W(5)+2 = 7, 여유 1
        minimum-idle: 2
  batch:
    queue:
      enabled: true
      writer-thread-count: 5      # W. 올리면 타겟 풀도 같이 올린다
```

- **실행 전 점검**은 테이블을 병렬로 봅니다 (`georu.product.precheck.parallel`, 기본 4 · 최대 8). 점검을 이관과 겹쳐 돌리면 그만큼 소스 · 타겟 연결을 더 씁니다. 점검을 따로 돌리거나 풀에 그만큼 더합니다.
- **소스 풀을 키운다고 빨라지지 않습니다.** Reader 는 한 스레드로 페이지를 차례로 읽습니다. 소스는 남의 운영 DB(ASIS)인 경우가 많으니 작게 둡니다.
- **타겟 처리량은 W 로 올립니다.** 풀만 키우고 W 를 그대로 두면 연결이 놀고, W 만 올리고 풀을 그대로 두면 Writer 스레드가 연결을 기다립니다. **둘을 같이** 바꿉니다 (풀 ≥ W + 2).
- **DB 쪽 상한과 맞춥니다.** 앱 인스턴스 수 × 풀 크기가 DB 세션 상한(Oracle `processes` · `sessions`, MySQL `max_connections`, 계정별 제한)과 다른 앱의 몫을 넘지 않는지 DBA 와 확인합니다.
- 설정 키 `minimum-idle` 을 정하지 않으면 HikariCP 는 `maximum-pool-size` 와 같게 둡니다 (첫 접속 뒤 항상 최대치만큼 연결 유지). 소스 DB 에 연결을 붙여 두고 싶지 않으면 1~2 로 낮춥니다.

## 3. 시간 값

| 키 | HikariCP 기본 | 언제 바꾸나 |
|---|---|---|
| `connection-timeout` | 30000 (30초) | 풀에서 연결을 기다리는 최대 시간. 운영처럼 DB 가 멀거나 바쁘면 60000. 이보다 길게 두면 고갈을 늦게 알아챕니다 |
| `max-lifetime` | 1800000 (30분) | 연결을 버리고 새로 여는 주기. **DB · 방화벽 · 터널이 유휴 연결을 끊는 시간보다 짧게** (예: 끊는 시간 − 1분). 끊긴 연결을 쓰면 `Connection reset` · `ORA-03113` · `Communications link failure` |
| `idle-timeout` | 600000 (10분) | `minimum-idle` 보다 많은 유휴 연결을 닫는 시간 |
| `keepalive-time` | (버전마다 다름) | 유휴 연결에 주기적으로 확인을 보냄. 방화벽이 유휴 연결을 짧게 끊는 망에서 `max-lifetime` 과 같이 씁니다 (예: 300000) |
| `initialization-fail-timeout` | 1 | `-1` 이면 기동 때 접속하지 않습니다 (DB 가 늦게 떠도 앱 · health 가 먼저 뜸). 소스 · 타겟 DB 가 앱보다 늦게 뜰 수 있는 환경이면 `-1` |
| `validation-timeout` | 5000 | 연결 확인 시간. 보통 그대로 |

## 4. 보는 곳

- **시스템 화면 · 대시보드**: DB · 커넥션 풀 상태 (active · idle · 대기). 실제 수치는 `hikari.register-mbeans: true` 일 때만 나옵니다 (없으면 기본값 1 / 10). 안내서 "헬스체크".
- **서버 진단 › Metrics**: HikariCP 지표 (`hikaricp.connections.active` · `.pending` · `.timeout` · `.acquire` · `.usage`). `pending` 이 자주 0 보다 크면 모자란 것, `active` 최대치가 늘 크기의 절반 아래면 남는 것입니다.
- **`/actuator/health`** 의 `<이름>` 항목: 풀 크기 · 접속 여부.
- **종료 로그**: 앱을 끌 때 풀마다 `Pool Status [종료 전]: active=…, idle=…, total=…, waiting=…` 을 남깁니다.

## 5. 증상과 조치

| 증상 | 원인 | 조치 |
|---|---|---|
| `Connection is not available, request timed out after 30000ms` | 풀 고갈. 연결을 다 빌려 간 채 돌려주지 않음 | 타겟이면 풀 ≥ W + 2 인지. 점검을 겹쳐 돌렸는지. 오래 걸리는 쿼리 · 잠금 대기(타겟 DB 잠금)를 먼저 봅니다 |
| Writer 진척 없음 · 적재 대기 초과 (`write-timeout`) | 타겟 잠금 · 느린 디스크 · 풀 고갈 | 안내서 "차단·실패 사유별 조치" 의 write-timeout. W 와 풀을 같이 줄여 보기도 합니다 |
| 한동안 쉬었다 다시 돌리면 첫 쿼리가 끊김 | 방화벽 · 터널 · DB 가 유휴 연결을 끊음 | `max-lifetime` 을 끊는 시간보다 짧게, `keepalive-time` 추가 |
| DB 에서 `too many connections` · `ORA-00018` · `ORA-00020` | 인스턴스 수 × 풀이 DB 상한을 넘음 | 풀을 줄이거나 DB 상한을 올립니다 (DBA) |
| DB 가 늦게 뜨거나 터널이 없으면 앱 기동이 늦어지거나 실패 | `initialization-fail-timeout` 이 양수라 기동 때 접속 시도 | `-1` 로 지연 접속 |

## 6. 소스 잠금 (`FOR UPDATE WAIT`)과 풀

`georu.db-policy.allow-for-update-wait: true` 로 소스에 `SELECT … FOR UPDATE WAIT n` 을 쓰면, 행 잠금은 **그 소스 연결의 트랜잭션이 끝날 때** 풀립니다. 소스는 `auto-commit: false` 이므로 연결을 오래 쥐고 있으면 잠금도 오래 갑니다. 같은 행을 쓰는 ASIS 작업이 대기하거나 실패할 수 있으니, 잠금을 쓰는 테이블은 청크 크기를 작게 두고 실행 시간대를 ASIS 와 맞춥니다. 소스 풀 크기를 키워도 잠금 시간은 줄지 않습니다.

## 7. 순서 요약

1. W 를 정한다 (타겟 쓰기 처리량).
2. 타겟 풀 = W + 2 + 여유, 소스 풀 = 4 (+ 점검 병렬).
3. 인스턴스 수 × 풀이 DB 상한 안인지 DBA 와 확인.
4. `max-lifetime` 을 망 · DB 유휴 끊김 시간보다 짧게.
5. 한 번 돌리고 지표의 `pending` · `active` 최대치를 보고 조정.
