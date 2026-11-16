# DB 접속 · JDBC 드라이버

이관 앱이 소스 · 타겟 DB 에 붙으려면 두 가지가 필요합니다: **드라이버 jar 가 클래스패스에 있고**, **`georu.datasource.<이름>` 에 접속 정보가 있어야** 합니다. 이 안내서는 처음 설정할 때 그 둘을 하는 법입니다.

## 1. 드라이버는 앱이 고른다

`georu-spring-boot-starter` 에는 **H2 만** 들어 있습니다 (운영 저장소 기본값 · 테스트). 소스 · 타겟 DB 드라이버는 이관 앱의 `build.gradle` · `pom.xml` 에 직접 적습니다. 오픈소스 라이브러리가 특정 DB 드라이버를 강제로 끌어오지 않게 하려는 것이고, 쓰지 않는 드라이버가 납품물 취약점 점검에 걸리는 일도 없앱니다.

| DB | 좌표 | 드라이버 클래스 | 검증 |
|---|---|---|---|
| Oracle | `com.oracle.database.jdbc:ojdbc11` + `com.oracle.database.nls:orai18n` (한글 문자셋) | `oracle.jdbc.OracleDriver` | 데모 · 시범 적용 (소스) |
| MySQL | `com.mysql:mysql-connector-j` | `com.mysql.cj.jdbc.Driver` | 데모 · 시범 적용 (타겟) |
| H2 | starter 에 들어 있음 | `org.h2.Driver` | 운영 저장소 · 테스트 |

예제 앱 `examples/demo/build.gradle` 이 소스 Oracle · 타겟 MySQL 의 예입니다:

```groovy
implementation 'com.georu:georu-spring-boot-starter:0.0.1'
runtimeOnly 'com.oracle.database.jdbc:ojdbc11'
runtimeOnly 'com.oracle.database.nls:orai18n'
runtimeOnly 'com.mysql:mysql-connector-j'
```

georu jar 를 **`libs/` 폴더에 파일로** 넣어 쓰는 프로젝트(georu-base 등)는 POM 이 없어 H2 를 포함한 모든 외부 라이브러리를 `build.gradle` 에 직접 적습니다.

0.5.x 에서 올릴 때: Oracle · MySQL 을 쓰던 앱은 위 세 줄을 더합니다. 더하지 않으면 기동은 되고 첫 접속에서 `Cannot load driver class` 로 실패합니다.

## 2. 다른 DB 드라이버 더하기

코드 생성기의 DB 종류(방언)는 oracle · mysql · mssql · postgresql 입니다. MariaDB 는 MySQL 방언으로 씁니다. 버전을 적지 않은 줄은 Spring Boot BOM 이 맞는 버전을 정합니다. 예제 앱 `examples/demo/build.gradle` 에 다섯 드라이버가 모두 있습니다.

| DB | 좌표 | 드라이버 클래스 | URL 예 | 검증 (예제 앱) |
|---|---|---|---|---|
| Oracle | `com.oracle.database.jdbc:ojdbc11` (+ `orai18n`) | `oracle.jdbc.OracleDriver` | `jdbc:oracle:thin:@//호스트:1521/서비스명` | Oracle 23 Free, 소스 |
| MySQL | `com.mysql:mysql-connector-j` | `com.mysql.cj.jdbc.Driver` | `jdbc:mysql://호스트:3306/DB?serverTimezone=Asia/Seoul&characterEncoding=UTF-8&rewriteBatchedStatements=true` | MySQL 8.0, 타겟 |
| MariaDB | `org.mariadb.jdbc:mariadb-java-client` | `org.mariadb.jdbc.Driver` | `jdbc:mariadb://호스트:3306/DB` | MariaDB 11, 타겟 · 채번 (demo.xdb) |
| SQL Server | `com.microsoft.sqlserver:mssql-jdbc` | `com.microsoft.sqlserver.jdbc.SQLServerDriver` | `jdbc:sqlserver://호스트:1433;databaseName=DB;encrypt=false` | Azure SQL Edge 15.0 (SQL Server 2019 계열), 소스 (demo.xdb) |
| PostgreSQL | `org.postgresql:postgresql` | `org.postgresql.Driver` | `jdbc:postgresql://호스트:5432/DB` | PostgreSQL 16, 소스 (demo.xdb) |

검증은 예제 앱에서 손으로 쓴 SQL 로 READER → NORMAL 까지 돈 것이고, 코드 생성기가 만든 PostgreSQL · SQL Server Source SQL 도 같은 DB 에서 그대로 실행해 건수 · 값을 확인했습니다 (0.0.1).

### Gradle — Maven Central 에 있는 드라이버

```groovy
dependencies {
    runtimeOnly 'org.postgresql:postgresql'                 // 버전은 Spring Boot BOM
    runtimeOnly 'com.microsoft.sqlserver:mssql-jdbc'
    runtimeOnly 'org.mariadb.jdbc:mariadb-java-client'
}
```

### Gradle — jar 파일만 있는 드라이버 (사내 · 상용 DB, 망 분리 환경)

jar 를 프로젝트의 `libs/` 에 넣고:

```groovy
repositories {
    flatDir { dirs "$rootDir/libs" }
    mavenCentral()
}
dependencies {
    runtimeOnly name: 'tibero7-jdbc'            // libs/tibero7-jdbc.jar (확장자 없이 파일 이름)
    // 또는 파일 경로로: runtimeOnly files('libs/tibero7-jdbc.jar')
}
```

### Maven — Maven Central 에 있는 드라이버

```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>      <!-- 버전은 spring-boot-starter-parent / spring-boot-dependencies 가 정한다 -->
</dependency>
```

### Maven — jar 파일만 있는 드라이버

로컬 저장소에 한 번 설치하고 좌표로 씁니다 (`system` scope 는 실행 jar 에 들어가지 않아 권하지 않습니다).

```
mvn install:install-file -Dfile=libs/tibero7-jdbc.jar -DgroupId=com.tmax.tibero -DartifactId=tibero7-jdbc -Dversion=7 -Dpackaging=jar
```

```xml
<dependency>
    <groupId>com.tmax.tibero</groupId>
    <artifactId>tibero7-jdbc</artifactId>
    <version>7</version>
    <scope>runtime</scope>
</dependency>
```

여러 사람이 쓰면 사내 저장소(Nexus 등)에 올리거나, 프로젝트 안 폴더를 저장소로 두고(`<repository><url>file://${project.basedir}/repo</url></repository>`) `install:install-file -DlocalRepositoryPath=repo` 로 그 폴더에 설치합니다.

## 2-1. DB 버전별 설정과 한계

"검증" 은 예제 앱에서 실제로 돌려 본 것, "문서" 는 각 DB · 드라이버 공식 문서 기준입니다.

| DB · 버전 | 드라이버 | 설정 · 주의 | 생성기 SQL | 근거 |
|---|---|---|---|---|
| **Oracle 12c 이상** (12.2 · 19c · 21c · 23ai) | `ojdbc11` | 서비스명 URL `@//호스트:1521/서비스명`, SID 면 `@호스트:1521:SID`. 한글 문자셋(KO16MSWIN949 등)이면 `orai18n` | 그대로 (`OFFSET … FETCH`) | 검증 23 Free |
| **Oracle 11g** | `ojdbc8` (19.x) — `ojdbc11` 대신 | 위와 같음 | **페이징을 손으로 고친다** (아래) | 문서 |
| **MySQL 8.0** | `mysql-connector-j` | 인증 `caching_sha2_password` → 개발 DB 는 `allowPublicKeyRetrieval=true&useSSL=false`. `serverTimezone=Asia/Seoul` · `characterEncoding=UTF-8`, 대량 쓰기는 `rewriteBatchedStatements=true` | 그대로 | 검증 8.0 |
| **MySQL 5.7** | `mysql-connector-j` (8.x 가 5.7 에 붙는다) | 인증은 `mysql_native_password` 라 public key 옵션 불필요. utf8mb4 확인 | 그대로 | 문서 |
| **MariaDB 10.x · 11.x** | `mariadb-java-client` (`jdbc:mariadb:`). MySQL 드라이버로도 붙지만 전용 드라이버 권장 | 채번 방언은 접속 정보로 MySQL 계열을 자동 선택 | 생성기 DB 종류 **mysql** 로 | 검증 11 |
| **SQL Server 2012 이상** (2016 · 2019 · 2022) | `mssql-jdbc` | 드라이버 10.x 부터 암호화가 기본 → `encrypt=false` 또는 `trustServerCertificate=true` | 그대로 (`OFFSET … FETCH`, 날짜 `CONVERT`) | 검증 Azure SQL Edge 15.0 (2019 계열) |
| **SQL Server 2008 · 2008 R2** | `mssql-jdbc` | Java 17 은 TLS 1.0 · 1.1 을 막아 패치 안 된 서버는 접속부터 실패할 수 있다 (`java.security` 의 `jdk.tls.disabledAlgorithms` 조정은 보안 검토 후) | **페이징을 손으로 고친다** | 문서 |
| **PostgreSQL 10 이상** | `postgresql` | 따옴표 없이 만든 테이블 · 컬럼은 소문자로 저장된다 | 그대로. 소스 메타가 **DB** 면 실제 이름, **정의 파일**이면 대문자 이름을 소문자로 바꿔 쓴다 (0.0.1) | 검증 16 |
| **H2 2.x** | starter 에 있음 | 운영 저장소 · 테스트. 1.4 로 만든 파일은 2.x 에서 열리지 않는다 | — | 검증 2.2 |

**Oracle 11g · SQL Server 2008 의 페이징.** 생성기 Source SQL 의 `OFFSET #{_skiprows} ROWS FETCH NEXT #{_pagesize} ROWS ONLY` 는 Oracle 12c · SQL Server 2012 부터 됩니다. 그 전 판이면 테이블마다 `selectPageData` 를 이렇게 바꿉니다 (정렬키는 생성된 `ORDER BY` 그대로).

```sql
-- Oracle 11g
SELECT * FROM (
    SELECT X.*, ROWNUM AS RN_ FROM (
        SELECT … FROM "ASIS"."TB_X" A WHERE 1 = 1 ORDER BY A."ID"     -- 생성된 SELECT 그대로
    ) X WHERE ROWNUM <= #{_skiprows} + #{_pagesize}
) WHERE RN_ > #{_skiprows}

-- SQL Server 2008
SELECT * FROM (
    SELECT …, ROW_NUMBER() OVER (ORDER BY A.[ID]) AS RN_ FROM [dbo].[TB_X] A WHERE 1 = 1
) X WHERE RN_ > #{_skiprows} AND RN_ <= #{_skiprows} + #{_pagesize}
```

`RN_` 은 DTO 에 없는 열이라 무시됩니다. 이런 고객이 생기면 생성기 DB 종류에 oracle11 · mssql2008 을 더하는 것을 검토합니다.

**날짜 ↔ 문자 14자리 (생성기가 쓰는 식)**

| DB | 날짜 → 문자 | 문자 → 날짜 |
|---|---|---|
| Oracle | `TO_CHAR(x, 'YYYYMMDDHH24MISS')` | `TO_DATE(x, 'YYYYMMDDHH24MISS')` |
| MySQL · MariaDB | `DATE_FORMAT(x, '%Y%m%d%H%i%s')` | `STR_TO_DATE(x, '%Y%m%d%H%i%s')` |
| PostgreSQL | `TO_CHAR(x, 'YYYYMMDDHH24MISS')` | `TO_TIMESTAMP(x, 'YYYYMMDDHH24MISS')` |
| SQL Server | `REPLACE(REPLACE(REPLACE(CONVERT(VARCHAR(19), x, 120), '-', ''), ' ', ''), ':', '')` (8자리는 `CONVERT(VARCHAR(8), x, 112)`) | `CONVERT(DATETIME, STUFF(STUFF(STUFF(x, 9, 0, ' '), 12, 0, ':'), 15, 0, ':'), 120)` (0.0.1) |

SQL Server 의 `FORMAT()` 은 CLR 이 필요하고(Azure SQL Edge 에서는 실패) 느려서 쓰지 않습니다.

## 3. DataSource 설정

DataSource 이름은 소스 `source-m` · 타겟 `target-m` · 운영 저장소 `operation` 이 기본입니다 (더 있으면 `source-1` · `target-1` …). 공통 값은 `application.yml`, 접속 정보는 프로파일 yml(`application-dev.yml` 등)이나 환경 변수에 둡니다.

```yaml
georu:
  datasource:
    source-m:
      hikari:
        driver-class-name: org.postgresql.Driver       # 위 표의 드라이버 클래스 (생략하면 URL 로 고른다)
        jdbc-url: ${SOURCE_DB_URL}                     # 예: jdbc:postgresql://db:5432/asis
        username: ${SOURCE_DB_USERNAME}
        password: ${SOURCE_DB_PASSWORD}
        read-only: true
        maximum-pool-size: 4
    target-m:
      hikari:
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: ${TARGET_DB_URL}
        username: ${TARGET_DB_USERNAME}
        password: ${TARGET_DB_PASSWORD}
        auto-commit: false
        maximum-pool-size: 8
```

- 코드 생성기의 DB 종류(소스 · 타겟)를 접속하는 DB 와 맞춥니다. 생성된 SQL 의 따옴표 · 페이징 · 형변환이 방언을 따릅니다.
- 운영 비밀번호는 yml 에 적지 않고 환경 변수로 넘깁니다.
- `maximum-pool-size` · 시간 값은 안내서 "커넥션 풀 크기 (Hikari 튜닝)".
- 채번 관리 테이블은 안내서 "시퀀스(채번) 사용법" 의 "채번 위치" 절.

## 4. 확인

1. 기동 로그에서 **활성 프로파일** (`The following 1 profile is active: "dev"`). 다른 프로파일로 뜨면 그 yml 이 없어 접속 정보가 빕니다.
2. **시스템** 화면의 DB 상태 (또는 `GET /api/status/database`) — 각 DataSource 가 연결되는지.
3. **테이블 이관 실행** 에서 READER_TEST 5건.

| 증상 | 원인 · 해결 |
|---|---|
| `Cannot load driver class: …` · `No suitable driver` | 드라이버 jar 가 클래스패스에 없다. 2절대로 더한다. `libs/` 방식이면 `build.gradle` 에 줄이 있는지 |
| `jdbcUrl is required with driverClassName` | 접속 정보가 비었다. 활성 프로파일 · 환경 변수 이름 확인 |
| MySQL `Public Key Retrieval is not allowed` | URL 에 `allowPublicKeyRetrieval=true&useSSL=false` (개발 DB) |
| SQL Server `encrypt` · 인증서 오류 | URL 에 `encrypt=false` 또는 `trustServerCertificate=true` |
| Oracle 한글 깨짐 · `ORA-17056` | `orai18n` 이 빠졌다 |
| MySQL 드라이버가 둘 (`mysql:mysql-connector-java` 와 `com.mysql:mysql-connector-j`) | 하나만 둔다. 새 좌표 `com.mysql:mysql-connector-j` 를 쓴다 (옛 좌표 8.0.33 은 이후 판에서 고쳐진 취약점이 있다) |
| `Cannot load driver class: oracle.jdbc.OracleDriver` (또는 MySQL) | 앱에 드라이버가 없다 (starter 에 들어 있지 않음). 1절의 줄을 더한다 |
