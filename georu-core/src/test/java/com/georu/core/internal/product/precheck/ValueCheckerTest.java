package com.georu.core.internal.product.precheck;

import com.georu.core.product.precheck.ColumnMeta;

import com.georu.core.product.precheck.dialect.DialectRule;
import com.georu.core.internal.product.precheck.dialect.GenericDialectRule;
import com.georu.core.product.precheck.dialect.LengthLimit;
import com.georu.core.internal.product.precheck.dialect.MySqlDialectRule;
import com.georu.core.internal.product.precheck.dialect.OracleDialectRule;
import com.georu.core.product.precheck.dialect.TableRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ValueChecker - 변환 값과 타겟 컬럼 정의 대조")
class ValueCheckerTest {

    private static ColumnMeta col(String name, int type, String typeName, int size, int digits, boolean nullable) {
        return ColumnMeta.builder().name(name).dataType(type).typeName(typeName).size(size).decimalDigits(digits).nullable(nullable).build();
    }

    private static TableMeta meta(DialectRule dialect, ColumnMeta... cols) throws Exception {
        Map<String, ColumnMeta> map = new LinkedHashMap<>();
        Map<String, LengthLimit> limits = new LinkedHashMap<>();
        TableRef ref = new TableRef(null, null, "t");
        for (ColumnMeta c : cols) {
            map.put(c.getName().toUpperCase(), c);
            if (c.isCharacter()) {
                // 이 테스트의 규칙들은 길이 판단에 Connection 을 쓰지 않는다 (Oracle 은 CHAR_USED 조회 실패 → 판단 불가)
                limits.put(c.getName().toUpperCase(), dialect instanceof OracleDialectRule ? LengthLimit.bytes(c.getSize(), null)
                        : dialect.lengthLimit(ref, c, null));
            }
        }
        return TableMeta.builder().ref(ref).found(true).dialect(dialect).columns(map).primaryKey(List.of()).lengthLimits(limits).build();
    }

    private static Set<CheckType> run(TableMeta m, String column, Object value) {
        PrecheckReport.TableReport tr = new PrecheckReport.TableReport();
        ValueChecker.check(tr, m, m.column(column), value);
        return tr.getIssues().stream().map(PrecheckReport.Issue::getType).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("MySQL: NOT NULL, 문자 수 기준 길이, UNSIGNED, 정수 변환, DECIMAL 정밀도")
    void mysqlRules() throws Exception {
        TableMeta m = meta(new MySqlDialectRule(),
                col("NAME", Types.VARCHAR, "VARCHAR", 5, 0, false),
                col("CNT", Types.INTEGER, "INT UNSIGNED", 10, 0, true),
                col("AMT", Types.DECIMAL, "DECIMAL", 5, 2, true),
                col("SMALL", Types.TINYINT, "TINYINT", 3, 0, true));

        assertEquals(Set.of(CheckType.NOT_NULL), run(m, "NAME", null));
        assertTrue(run(m, "NAME", "가나다라마").isEmpty(), "MySQL VARCHAR(5) 는 문자 5자까지 허용 (바이트 아님)");
        assertEquals(Set.of(CheckType.LENGTH), run(m, "NAME", "가나다라마바"));
        assertTrue(run(m, "CNT", -1).contains(CheckType.NUMERIC_RANGE), "UNSIGNED 컬럼의 음수");
        assertEquals(Set.of(CheckType.TYPE_MISMATCH), run(m, "CNT", "abc"));
        assertEquals(Set.of(CheckType.NUMERIC_RANGE), run(m, "AMT", new BigDecimal("1234.5")), "정수부 4자리 > 3자리");
        assertEquals(Set.of(CheckType.DECIMAL_SCALE), run(m, "AMT", "12.345"), "소수 3자리 → 반올림 경고");
        assertTrue(run(m, "AMT", "999.99").isEmpty());
        assertEquals(Set.of(CheckType.NUMERIC_RANGE), run(m, "SMALL", 200), "TINYINT 범위 -128~127");
    }

    @Test
    @DisplayName("MySQL 날짜: 0000-00-00, TIMESTAMP 범위(2038년), 형식 오류")
    void mysqlDates() throws Exception {
        TableMeta m = meta(new MySqlDialectRule(),
                col("DT", Types.TIMESTAMP, "DATETIME", 19, 0, true),
                col("TS", Types.TIMESTAMP, "TIMESTAMP", 19, 0, true));

        assertEquals(Set.of(CheckType.DATE_RANGE), run(m, "DT", "0000-00-00 00:00:00"));
        assertEquals(Set.of(CheckType.TYPE_MISMATCH), run(m, "DT", "2024/13/45"));
        assertTrue(run(m, "DT", "20240131").isEmpty());
        assertTrue(run(m, "DT", LocalDateTime.of(2050, 1, 1, 0, 0)).isEmpty(), "DATETIME 은 9999년까지");
        assertEquals(Set.of(CheckType.DATE_RANGE), run(m, "TS", LocalDateTime.of(2050, 1, 1, 0, 0)), "TIMESTAMP 는 2038-01-19 까지");
    }

    @Test
    @DisplayName("Oracle: 빈 문자열은 NULL, BYTE 단위면 한글 3바이트로 센다")
    void oracleRules() throws Exception {
        TableMeta m = meta(new OracleDialectRule(), col("NAME", Types.VARCHAR, "VARCHAR2", 6, 0, false));

        assertEquals(Set.of(CheckType.NOT_NULL), run(m, "NAME", ""), "Oracle 은 '' 를 NULL 로 저장");
        assertTrue(run(m, "NAME", "가나").isEmpty(), "6바이트");
        assertEquals(Set.of(CheckType.LENGTH), run(m, "NAME", "가나다"), "9바이트 > 6바이트");
    }

    @Test
    @DisplayName("규칙 없는 DB: 길이·날짜 범위는 판단 불가(경고)")
    void genericRules() throws Exception {
        TableMeta m = meta(new GenericDialectRule(),
                col("NAME", Types.VARCHAR, "VARCHAR", 5, 0, true),
                col("DT", Types.DATE, "DATE", 10, 0, true));

        assertEquals(Set.of(CheckType.LENGTH_UNKNOWN), run(m, "NAME", "abcdefgh"));
        assertEquals(Set.of(CheckType.DATE_UNKNOWN), run(m, "DT", "2024-01-01"));
        assertEquals(Set.of(CheckType.TYPE_MISMATCH), run(m, "DT", "not-a-date"), "형식 점검은 규칙 없이도 한다");
    }

    @Test
    @DisplayName("AUTO_INCREMENT 컬럼은 NULL 이어도 오류가 아니다")
    void autoIncrementNull() throws Exception {
        ColumnMeta id = ColumnMeta.builder().name("ID").dataType(Types.BIGINT).typeName("BIGINT").size(19).nullable(false).autoIncrement(true).build();
        TableMeta m = meta(new MySqlDialectRule(), id);
        assertTrue(run(m, "ID", null).isEmpty());
    }

    @Test
    @DisplayName("같은 테이블·컬럼·유형은 1건으로 묶고 샘플 값은 최대 5개")
    void aggregation() {
        PrecheckReport.TableReport tr = new PrecheckReport.TableReport();
        for (int i = 0; i < 8; i++) {
            tr.issue(CheckType.LENGTH, "NAME", "길이 초과", "v" + i);
        }
        tr.issue(CheckType.PK_DUPLICATE, "ID", "중복", "1");
        assertEquals(2, tr.getIssues().size());
        PrecheckReport.Issue first = tr.getIssues().iterator().next();
        assertEquals(8, first.getOccurrences());
        assertEquals(5, first.getSamples().size());
        assertEquals("BLOCK", tr.getResult(), "차단 항목이 하나라도 있으면 BLOCK");
    }
}
