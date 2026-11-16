# 헬스체크 (DB · 앱 상태 확인)

앱과 DB 가 살아 있는지 확인하는 곳은 네 군데입니다. 같은 DataSource 를 보지만 쓰임이 다릅니다.

| 어디 | 무엇을 보나 | 누가 쓰나 |
|---|---|---|
| `/actuator/health` (`/live` · `/ready`) | 앱 생존 · 운영 저장소 · DataSource 마다 UP/DOWN | 컨테이너 · LB · 모니터링 |
| `GET /api/status/database` | DataSource 마다 연결 · 응답 시간 · 풀 사용량 | **시스템** 화면 · 대시보드의 DB 상태 |
| 헬스 모니터 (1시간마다) | DB 연결 · 청크 멈춤 · GC | 텔레그램 · UI 알림 |
| 실행 전 확인 | 실행할 Job 의 소스 · 타겟 | 이관 실행 (사전 점검) |

모두 **풀에서 연결을 하나 빌려 `Connection.isValid(5초)`** 로 확인합니다. 쿼리를 보내지 않으므로 DB 종류와 관계없이 같은 방식입니다 (예전 `SELECT 1` 은 Oracle 19c 이하에서 `ORA-00923: FROM keyword not found` 로 실패했습니다).

## 1. actuator health

| 경로 | 포함 | 용도 |
|---|---|---|
| `/actuator/health/live` | ping | **생존**. 앱 프로세스가 응답하면 UP. 컨테이너 · LB 헬스체크는 이것을 씁니다 |
| `/actuator/health/ready` | 운영 저장소(`operation`) | **준비**. 실행 이력 · Batch 메타를 쓸 수 있으면 UP |
| `/actuator/health` | 전체: 운영 저장소 + DataSource 마다 `<이름>` 항목 (`source-m` · `target-m` …) | 사람이 볼 때. **꺼진 소스 DB 가 하나라도 있으면 전체가 DOWN** |

```yaml
management:
  endpoint:
    health:
      show-details: when_authorized      # 상세(풀 크기 · URL · 오류)는 로그인한 사용자에게만
      show-components: always            # 항목별 UP/DOWN 은 항상
      group:
        live:
          include: ping
        ready:
          include: operation
  health:
    db:
      enabled: false                     # Spring Boot 기본 DB 지표 대신 georu 의 DataSource 지표
```

- **컨테이너 · LB 는 `/live` 로 봅니다.** `/actuator/health` 로 보면 ASIS DB 점검 · 터널 끊김만으로 앱이 재시작됩니다. 소스 · 타겟은 Job 을 실행할 때 확인합니다.
- `<이름>` 항목의 상세: `poolName` · `maximumPoolSize` · `minimumIdle` · `connectionTimeout` · `idleTimeout` · `maxLifetime` · `jdbcUrl` · `connectionValid`. 실패하면 `reason` · `sqlError`.
- `georu.datasource.<이름>.enabled: false` 나 플러그인 끄기로 빠진 DataSource 는 항목도 없습니다.

예제 앱의 도커 설정:

```yaml
healthcheck:
  test: ["CMD", "curl", "-fs", "http://localhost:8081/actuator/health/live"]
  interval: 30s
  timeout: 10s
  start_period: 90s
  retries: 3
```

## 2. 시스템 화면 · `/api/status/database`

DataSource 마다 아래 값을 돌려줍니다 (키는 빈 이름에서 `DataSource` 를 뗀 값: `source-m` · `target-m` · `operation`).

| 값 | 뜻 |
|---|---|
| `status` · `healthy` | `CONNECTED` / `DISCONNECTED` |
| `responseTime` | 연결을 빌리고 확인하기까지 걸린 시간 |
| `host` · `database` · `driverInfo` | URL 의 호스트:포트, 카탈로그, 드라이버 이름 · 버전 |
| `activeConnections` · `idleConnections` · `totalConnections` · `maxConnections` · `connectionPoolUsage` | 풀 상태 (사용률 = total ÷ max) |
| `errorMessage` | 실패 사유 |

전체에는 `overallHealthy`(모두 정상) · `operationHealthy`(운영 저장소 정상 = 화면 · 실행 이력은 쓸 수 있음) · `totalConnections` · `lastChecked` 가 붙습니다.

**풀 수치를 보려면 `register-mbeans: true`.** 풀 상태는 HikariCP 의 JMX MBean 에서 읽습니다. 설정하지 않으면 MBean 이 없어 풀 수치가 실제 값이 아닌 기본값(활성 1 · 최대 10 · 10%)으로 나오고 로그에 `HikariCP MBean이 등록되지 않음` 경고가 남습니다.

```yaml
georu:
  datasource:
    source-m:
      hikari:
        register-mbeans: true
    target-m:
      hikari:
        register-mbeans: true
```

## 3. 헬스 모니터 (알림)

1시간마다(기동 5분 뒤 처음) 돕니다.

| 점검 | 알림 |
|---|---|
| DataSource 마다 연결 (`isValid`) | 연속 `georu.monitor.db-failure-alert-threshold`(기본 3)회 실패하면 1회, 복구되면 1회. 실패는 매번 WARN 로그 |
| 청크 진행 (실행 중 Job 이 있을 때만) | 30분 동안 청크가 끝나지 않으면 "청크 처리 멈춤" |
| GC 일시정지 | 5초를 넘으면 경고 |

- `jdbc-url` 이 비어 있는 DataSource 는 접속하지 않고 실패로 셉니다 (`접속정보 없음 (jdbc-url 미설정) - 점검 생략`).
- 알림은 텔레그램(설정했을 때)과 UI 알림으로 갑니다. 안내서 "텔레그램 알림 메시지 유형".

## 4. 걸리는 시간과 풀

- 확인 한 번에 **풀 연결 하나**를 잠깐 씁니다. 풀이 꽉 차 있으면 확인도 `connection-timeout` 만큼 기다리다 실패합니다. 이관 중 DB 상태가 DOWN 이면 DB 장애인지 풀 고갈인지 `errorMessage` 로 구분합니다 (`Connection is not available, request timed out` = 풀 고갈).
- **DB 가 꺼져 있으면 연결을 만드는 데 `connection-timeout`(기본 30초)까지 걸립니다.** 시스템 화면은 DataSource 를 차례로 보므로, 꺼진 DB 가 여럿이면 그만큼 늦게 뜹니다. `isValid` 의 5초는 연결을 얻은 뒤의 응답 확인입니다.
- `initialization-fail-timeout: -1` 이면 기동 때 접속하지 않아, DB 가 꺼져 있어도 앱과 `/live` · `/ready` 는 바로 뜹니다. 첫 헬스체크나 첫 실행 때 연결합니다.
- 소스는 `isValid` 도 정책 검사 대상이 아닙니다 (SQL 을 보내지 않음).

## 5. 증상과 조치

| 증상 | 보는 곳 · 조치 |
|---|---|
| `/actuator/health` 가 DOWN 인데 화면은 잘 됨 | 소스 · 타겟 중 하나가 꺼짐. 항목별 상태를 봅니다. 운영 영향은 `/ready` 로 판단 |
| `/ready` DOWN | 운영 저장소 접속 실패. 실행 · 이력 기록이 안 됩니다. `OPERATION_DB_URL` · 계정 · 네트워크 |
| 시스템 화면 DB 상태가 늦게 뜸 | 꺼진 DB 의 `connection-timeout` 대기. 쓰지 않는 DataSource 는 `enabled: false` |
| 풀 수치가 늘 1 / 10 / 10% | `register-mbeans: true` 가 없음 (2절) |
| 오래 쉬었다 보면 첫 확인만 실패 | 방화벽 · 터널 · DB 가 유휴 연결을 끊음. 안내서 "커넥션 풀 크기 (Hikari 튜닝)" 의 `max-lifetime` · `keepalive-time` |
| 접속 오류 (`ORA-` · `Communications link failure` · 인증 실패) | 안내서 "DB 접속 · JDBC 드라이버" 의 확인 절 |
