package com.georu.core.internal.product.guard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * 타겟 테이블 건수 확인. 모든 DB 에서 같은 코드로 동작하도록 JDBC 표준 기능만 쓴다.
 * <ul>
 *   <li>데이터 유무: {@code SELECT 1 FROM t} + {@code setMaxRows(1)} (LIMIT/ROWNUM/TOP 문법 차이를 피한다)</li>
 *   <li>건수: {@code SELECT COUNT(*) FROM t} + {@code setQueryTimeout} (큰 테이블은 시간 초과 시 "1건 이상" 으로 표시)</li>
 * </ul>
 */
@Slf4j
@Component
public class TargetCountService {

    /** 테이블명 허용 문자 (SQL 에 그대로 넣으므로 식별자 문자만 허용) */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_$#.]+");

    @Value("${georu.product.count-timeout-seconds:10}")
    private int countTimeoutSeconds;

    /** 건수 조회 결과. count 가 null 이면 확인 불가, exact=false 면 시간 초과로 "1건 이상" 만 확인 */
    public record CountResult(boolean known, boolean hasData, Long count, boolean exact, String error) {
        public static CountResult unknown(String error) { return new CountResult(false, false, null, false, error); }
        public String display() {
            if (!known) return "확인 불가";
            if (!hasData) return "0";
            return exact ? String.format("%,d", count) : "1건 이상 (건수 조회 시간 초과)";
        }
    }

    /** 데이터가 있는지 먼저 1건만 확인하고, 있을 때만 시간 제한을 두고 건수를 센다 */
    public CountResult inspect(DataSource ds, String table) {
        if (ds == null || table == null || !SAFE_IDENTIFIER.matcher(table).matches()) {
            return CountResult.unknown("테이블명을 확인할 수 없음: " + table);
        }
        try (Connection conn = ds.getConnection()) {
            boolean hasData;
            try (Statement st = conn.createStatement()) {
                st.setMaxRows(1);
                st.setQueryTimeout(countTimeoutSeconds);
                try (ResultSet rs = st.executeQuery("SELECT 1 FROM " + table)) {
                    hasData = rs.next();
                }
            }
            if (!hasData) {
                return new CountResult(true, false, 0L, true, null);
            }
            return new CountResult(true, true, countWithTimeout(conn, table), true, null);
        } catch (CountTimeout t) {
            return new CountResult(true, true, null, false, null);
        } catch (Exception e) {
            log.debug("타겟 건수 확인 실패 {}: {}", table, e.getMessage());
            return CountResult.unknown(e.getMessage());
        }
    }

    /** 건수만 센다 (완료 판정의 적재 전·후 건수). 시간 초과·실패는 null */
    public Long countOrNull(DataSource ds, String table) {
        if (ds == null || table == null || !SAFE_IDENTIFIER.matcher(table).matches()) {
            return null;
        }
        try (Connection conn = ds.getConnection()) {
            return countWithTimeout(conn, table);
        } catch (Exception e) {
            log.debug("타겟 건수 조회 실패/시간 초과 {}: {}", table, e.getMessage());
            return null;
        }
    }

    private long countWithTimeout(Connection conn, String table) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(countTimeoutSeconds);
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLTimeoutException e) {
            throw new CountTimeout();
        } catch (java.sql.SQLException e) {
            // 드라이버에 따라 시간 초과가 SQLTimeoutException 이 아닌 일반 SQLException 으로 온다 (메시지로 판별)
            String m = String.valueOf(e.getMessage()).toLowerCase();
            if (m.contains("timeout") || m.contains("timed out") || m.contains("cancel")) {
                throw new CountTimeout();
            }
            throw e;
        }
    }

    private static final class CountTimeout extends Exception {
        CountTimeout() { super(null, null, false, false); }
    }
}
