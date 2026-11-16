package com.georu.codegen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("원천 메타로 Source SELECT 채우기")
class SourceMappingTest {

    /** TOBE: 이름 일치(같은 타입 · 형변환) · 감사 컬럼 · 미매핑 · 형변환 불가 · 예약어 컬럼 */
    static final String TOBE = """
            테이블명,컬럼명,타입,길이,PK,업무구분,원천 테이블
            tb_member,MEMBER_ID,BIGINT,,Y,member,MEMBER
            tb_member,BIRTH_YMD,VARCHAR,8,,,
            tb_member,JOIN_DTM,VARCHAR,14,,,
            tb_member,POINT,VARCHAR,20,,,
            tb_member,AGE,INT,,,,
            tb_member,LAST_LOGIN,DATETIME,,,,
            tb_member,PHOTO,VARCHAR,100,,,
            tb_member,INDATE,DATETIME,,,,
            tb_member,CLASS,VARCHAR,10,,,
            tb_member,NEW_COL,VARCHAR,10,,,
            tb_lost,ID,INT,,Y,member,NO_SUCH_TABLE
            """;

    static final String ASIS = """
            테이블명,컬럼명,컬럼타입,PK
            MEMBER,MEMBER_ID,NUMBER(18),Y
            MEMBER,BIRTH_YMD,DATE,
            MEMBER,JOIN_DTM,DATE,
            MEMBER,POINT,"NUMBER(10,2)",
            MEMBER,AGE,VARCHAR2(3),
            MEMBER,LAST_LOGIN,VARCHAR2(14),
            MEMBER,PHOTO,BLOB,
            MEMBER,REG_DATE,DATE,
            MEMBER,CLASS,VARCHAR2(10),
            """;

    static GenerationResult generate(SourceCatalog catalog) {
        TableSpecReader reader = new TableSpecReader();
        return new CodeGenerator().generate(reader.read("tobe.csv", TOBE.getBytes(StandardCharsets.UTF_8)),
                CodegenSettings.of("acme").sourceCatalog(catalog).sourceSchema("ASIS").audit("INDATE", "REG_DATE"));
    }

    static SourceCatalog asisFile() {
        SpecReadResult asis = new TableSpecReader().readDefinition("asis.csv", ASIS.getBytes(StandardCharsets.UTF_8));
        assertThat(asis.errors()).isEmpty();
        return SourceCatalog.of(asis);
    }

    @Test
    @DisplayName("이름 일치 → 감사 컬럼 → TODO, 타입이 다르면 형변환 (Oracle)")
    void rules() {
        GenerationResult r = generate(asisFile());
        String xml = r.files().get("src/main/resources/georu/acme/member/TbMemberSourceMapper.xml");

        assertThat(xml)
                .contains("  A.\"MEMBER_ID\" AS \"MEMBER_ID\"    <!-- BIGINT ← MEMBER_ID (이름 일치) -->")
                .contains(", TO_CHAR(A.\"BIRTH_YMD\", 'YYYYMMDD') AS \"BIRTH_YMD\"")
                .contains(", TO_CHAR(A.\"JOIN_DTM\", 'YYYYMMDDHH24MISS') AS \"JOIN_DTM\"")
                .contains(", TO_CHAR(A.\"POINT\") AS \"POINT\"")
                .contains(", TO_NUMBER(A.\"AGE\") AS \"AGE\"    <!-- INT ← AGE (이름 일치) (형변환, 숫자 아닌 값 확인) -->")
                .contains(", TO_DATE(A.\"LAST_LOGIN\", 'YYYYMMDDHH24MISS') AS \"LAST_LOGIN\"")
                .contains(", NULL AS \"PHOTO\"    <!-- VARCHAR(100) ← PHOTO TODO 형변환 BLOB -> VARCHAR(100) -->")
                .contains(", A.\"REG_DATE\" AS \"INDATE\"    <!-- DATETIME ← REG_DATE (감사 컬럼) -->")
                .contains(", A.\"CLASS\" AS \"CLASS_VALUE\"")
                .contains(", NULL AS \"NEW_COL\"    <!-- VARCHAR(10) TODO ← ASIS ? -->")
                .contains("FROM \"ASIS\".\"MEMBER\" A\n")
                .contains("ORDER BY A.\"MEMBER_ID\"\n")
                .contains("이름 일치 7 · 공통 컬럼 0 · 감사 컬럼 1 · 미매핑 2 (TOBE 컬럼 10)");
        assertThat(r.files().get("src/main/java/com/georu/acme/member/dto/TbMemberTargetDto.java")).contains("private String classValue;");
        assertThat(r.files().get("src/main/resources/georu/acme/member/TbMemberTargetMapper.xml")).contains("`CLASS`").contains("#{classValue}");
        assertThat(r.todos()).filteredOn(t -> t.table().equals("tb_member")).extracting(GenerationResult.Todo::type)
                .containsExactlyInAnyOrder("TODO", "COLUMN_TODO");
    }

    @Test
    @DisplayName("원천 테이블이 메타에 없으면 경고 + FROM 줄 TODO, 컬럼은 같은 이름 가정")
    void missingTable() {
        GenerationResult r = generate(asisFile());

        assertThat(r.warnings()).anyMatch(w -> w.contains("NO_SUCH_TABLE"));
        assertThat(r.files().get("src/main/resources/georu/acme/member/TbLostSourceMapper.xml"))
                .contains("FROM \"ASIS\".\"NO_SUCH_TABLE\" A    <!-- TODO 원천 테이블 NO_SUCH_TABLE 을 소스 메타에서 찾지 못함 -->")
                .contains("(같은 이름으로 가정)");
    }

    @Test
    @DisplayName("소스 DB 메타데이터 (JDBC): 타입 · 길이 · PK")
    void jdbc() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:srcmeta" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new JdbcTemplate(ds).execute("""
                CREATE SCHEMA ASIS;
                CREATE TABLE ASIS.MEMBER (MEMBER_ID NUMERIC(18) PRIMARY KEY, BIRTH_YMD DATE, JOIN_DTM TIMESTAMP,
                    POINT DECIMAL(10,2), AGE VARCHAR(3), LAST_LOGIN VARCHAR(14), PHOTO BLOB, REG_DATE TIMESTAMP, CLASS VARCHAR(10));
                """);
        SourceCatalog catalog = SourceCatalog.jdbc(ds, "ASIS");

        SourceCatalog.SourceTable t = catalog.table("member");
        assertThat(t.pk()).containsExactly("MEMBER_ID");
        assertThat(t.column("point").dbType()).isEqualTo("DECIMAL(10,2)");
        assertThat(t.column("age").dbType()).isEqualTo("CHARACTER VARYING(3)");
        assertThat(catalog.table("NO_SUCH_TABLE")).isNull();

        String xml = generate(catalog).files().get("src/main/resources/georu/acme/member/TbMemberSourceMapper.xml");
        assertThat(xml).contains("TO_CHAR(A.\"POINT\") AS \"POINT\"").contains("TO_NUMBER(A.\"AGE\") AS \"AGE\"")
                .contains("A.\"REG_DATE\" AS \"INDATE\"").contains("이름 일치 7 · 공통 컬럼 0 · 감사 컬럼 1 · 미매핑 2");
    }

    @Test
    @DisplayName("공통 컬럼: REG_DATE · REG_ID · UPT_DATE · UPT_ID 는 흔한 이름으로, DEL_YN · USE_YN 은 삭제일 · 반대 값으로도. Processor 기본값")
    void commonColumns() {
        String tobe = """
                테이블명,컬럼명,타입,길이,PK,업무구분,원천 테이블
                tb_post,POST_ID,BIGINT,,Y,board,POST
                tb_post,REG_DATE,VARCHAR,14,,,
                tb_post,REG_ID,VARCHAR,20,,,
                tb_post,UPT_DATE,DATETIME,,,,
                tb_post,UPT_ID,VARCHAR,20,,,
                tb_post,DEL_YN,CHAR,1,,,
                tb_post,USE_YN,CHAR,1,,,
                tb_note,NOTE_ID,BIGINT,,Y,board,NOTE
                tb_note,USE_YN,CHAR,1,,,
                tb_note,DEL_YN,CHAR,1,,,
                """;
        String asis = """
                테이블명,컬럼명,컬럼타입,PK
                POST,POST_ID,NUMBER(18),Y
                POST,REG_DT,DATE,
                POST,CREATED_BY,VARCHAR2(20),
                POST,MOD_DATE,DATE,
                POST,MOD_ID,VARCHAR2(20),
                POST,DEL_DATE,DATE,
                NOTE,NOTE_ID,NUMBER(18),Y
                NOTE,DEL_FLAG,CHAR(1),
                """;
        TableSpecReader reader = new TableSpecReader();
        SourceCatalog catalog = SourceCatalog.of(reader.readDefinition("asis.csv", asis.getBytes(StandardCharsets.UTF_8)));
        GenerationResult r = new CodeGenerator().generate(reader.read("tobe.csv", tobe.getBytes(StandardCharsets.UTF_8)),
                CodegenSettings.of("acme").sourceCatalog(catalog));
        String post = r.files().get("src/main/resources/georu/acme/board/TbPostSourceMapper.xml");
        String note = r.files().get("src/main/resources/georu/acme/board/TbNoteSourceMapper.xml");
        String processor = r.files().get("src/main/java/com/georu/acme/board/processor/TbPostProcessor.java");

        assertThat(post)
                .contains(", TO_CHAR(A.\"REG_DT\", 'YYYYMMDDHH24MISS') AS \"REG_DATE\"    <!-- VARCHAR(14) ← REG_DT (공통 컬럼) -->")
                .contains(", A.\"CREATED_BY\" AS \"REG_ID\"")
                .contains(", A.\"MOD_DATE\" AS \"UPT_DATE\"")
                .contains(", A.\"MOD_ID\" AS \"UPT_ID\"")
                .contains(", CASE WHEN A.\"DEL_DATE\" IS NULL THEN 'N' ELSE 'Y' END AS \"DEL_YN\"    <!-- CHAR(1) ← DEL_DATE 유무 (공통 컬럼) -->")
                .contains(", CASE WHEN A.\"DEL_DATE\" IS NULL THEN 'Y' ELSE 'N' END AS \"USE_YN\"")
                .contains("이름 일치 1 · 공통 컬럼 6 · 감사 컬럼 0 · 미매핑 0");
        assertThat(note).contains(", A.\"DEL_FLAG\" AS \"DEL_YN\"")
                .contains(", CASE WHEN A.\"DEL_FLAG\" = 'Y' THEN 'N' ELSE 'Y' END AS \"USE_YN\"    <!-- CHAR(1) ← DEL_FLAG 반대 값 (공통 컬럼) -->");
        assertThat(processor).contains("import com.georu.acme.common.AcmeConvertUtils;")
                .contains("        if (item.getRegDate() == null) {\n            item.setRegDate(AcmeConvertUtils.nowYmdHms());\n        }")
                .contains("item.setRegId(AcmeConvertUtils.MIG_USER);")
                .contains("item.setUptDate(java.time.LocalDateTime.now());")
                .contains("item.setDelYn(\"N\");").contains("item.setUseYn(\"Y\");");

        GenerationResult off = new CodeGenerator().generate(reader.read("tobe.csv", tobe.getBytes(StandardCharsets.UTF_8)),
                CodegenSettings.of("acme").sourceCatalog(catalog).commonColumns(false));
        assertThat(off.files().get("src/main/resources/georu/acme/board/TbPostSourceMapper.xml"))
                .contains("NULL AS \"REG_ID\"    <!-- VARCHAR(20) TODO ← ASIS ? -->");
        assertThat(off.files().get("src/main/java/com/georu/acme/board/processor/TbPostProcessor.java")).doesNotContain("MIG_USER");
    }

    @Test
    @DisplayName("소스 DB 종류마다 형변환 함수")
    void dialects() {
        assertThat(SqlDialect.MYSQL.dateToChar("x", false)).isEqualTo("DATE_FORMAT(x, '%Y%m%d%H%i%s')");
        assertThat(SqlDialect.MSSQL.dateToChar("x", true)).isEqualTo("CONVERT(VARCHAR(8), x, 112)");
        assertThat(SqlDialect.MSSQL.dateToChar("x", false)).isEqualTo("REPLACE(REPLACE(REPLACE(CONVERT(VARCHAR(19), x, 120), '-', ''), ' ', ''), ':', '')");
        assertThat(SqlDialect.POSTGRESQL.charToDate("x", false)).isEqualTo("TO_TIMESTAMP(x, 'YYYYMMDDHH24MISS')");
        assertThat(SqlDialect.MSSQL.charToDate("x", false)).isEqualTo("CONVERT(DATETIME, STUFF(STUFF(STUFF(x, 9, 0, ' '), 12, 0, ':'), 15, 0, ':'), 120)");
        assertThat(SqlDialect.MYSQL.fallbackOrder()).isNull();
    }
}
