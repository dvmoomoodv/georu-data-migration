package com.georu.core.internal.common.writer.queue;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientConnectionException;
import java.util.Locale;

/**
 * 적재(flush) 실패를 다시 시도할 수 있는지 가른다 (적재 자동 재시도).
 *
 * <ul>
 *   <li>{@link Kind#ROLLED_BACK}: 데드락·잠금 대기 초과·쿼리 시간 초과. DB 가 그 트랜잭션을 되돌렸으므로 같은 묶음을 그대로 다시 넣어도 된다</li>
 *   <li>{@link Kind#CONNECTION}: 연결 끊김. commit 이 됐는지 알 수 없으므로 이미 들어간 키를 걸러서 다시 넣어야 한다</li>
 *   <li>{@link Kind#NONE}: 제약 위반·중복 키·길이 초과·변환 오류 등. 다시 해도 같으므로 바로 최종 실패</li>
 * </ul>
 * 인터럽트(종료 처리·대기 초과)는 재시도하지 않는다.
 */
public final class WriteRetryClassifier {

    public enum Kind { NONE, ROLLED_BACK, CONNECTION }

    private WriteRetryClassifier() {
    }

    public static Kind classify(Throwable error) {
        Kind found = Kind.NONE;
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof InterruptedException) {
                return Kind.NONE;
            }
            Kind k = classifyOne(t);
            if (k == Kind.CONNECTION) {
                return Kind.CONNECTION;
            }
            if (k == Kind.ROLLED_BACK) {
                found = Kind.ROLLED_BACK;
            }
        }
        return found;
    }

    private static Kind classifyOne(Throwable t) {
        if (t instanceof SQLRecoverableException || t instanceof SQLNonTransientConnectionException
                || t instanceof SQLTransientConnectionException || t instanceof CannotGetJdbcConnectionException) {
            return Kind.CONNECTION;
        }
        if (t instanceof SQLTransactionRollbackException || t instanceof SQLTimeoutException
                || t instanceof CannotAcquireLockException || t instanceof PessimisticLockingFailureException
                || t instanceof QueryTimeoutException) {
            return Kind.ROLLED_BACK;
        }
        if (t instanceof SQLException sql) {
            String state = sql.getSQLState();
            int code = sql.getErrorCode();
            if (state != null && state.startsWith("08")) {
                return Kind.CONNECTION;
            }
            // 40001 직렬화 실패·데드락, MySQL 1213 데드락·1205 잠금 대기 초과
            if ("40001".equals(state) || code == 1213 || code == 1205) {
                return Kind.ROLLED_BACK;
            }
        }
        String name = t.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (name.contains("communicationsexception")) {
            return Kind.CONNECTION;
        }
        return Kind.NONE;
    }
}
