package com.georu.core.internal.common.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientException;
import java.sql.Statement;
import java.util.Optional;
import java.util.Set;

/**
 * 소스 · 타겟 · 채번 DataSource. HikariDataSource 그대로이되, 내준 연결이 SQL 을 준비 · 실행하기 전에
 * {@link DbWritePolicy} 로 검사해 어긋나면 DB 로 보내지 않고 {@link Violation} 을 던진다.
 * MyBatis 매퍼뿐 아니라 직접 만든 Reader · Writer · Processor 가 이 DataSource 로 JDBC 를 써도 같은 규칙을 지킨다.
 *
 * <ul>
 *   <li>검사 자리: {@code prepareStatement}, {@code createStatement} 로 만든 Statement 의 {@code execute*} · {@code executeQuery} ·
 *       {@code addBatch}. {@code prepareCall}(프로시저 호출)은 소스 · 타겟 모두 거절</li>
 *   <li>소스는 연결을 읽기 전용으로 두고 {@code setReadOnly(false)} 는 무시한다</li>
 *   <li>풀 내부(연결 확인 · init SQL)는 검사하지 않는다 (앱 코드가 아님)</li>
 * </ul>
 */
public class PolicyDataSource extends HikariDataSource {

    private static final Logger log = LoggerFactory.getLogger(PolicyDataSource.class);
    private static final Set<String> STATEMENT_SQL_METHODS = Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "addBatch");

    private final String name;
    private final String role;
    private final boolean enabled;
    private final boolean allowForUpdateWait;

    /** @param allowForUpdateWait 소스 {@code SELECT … FOR UPDATE [OF …] WAIT n} 허용 ({@link DbWritePolicy#ALLOW_FOR_UPDATE_WAIT_KEY}) */
    public PolicyDataSource(String name, String role, boolean enabled, boolean allowForUpdateWait) {
        this.name = name;
        this.role = role;
        this.enabled = enabled && DbWritePolicy.applies(role);
        this.allowForUpdateWait = allowForUpdateWait;
    }

    public String policyName() {
        return name;
    }

    public String policyRole() {
        return role;
    }

    /** 정책을 거는지 (역할이 소스 · 타겟 · 채번이고 georu.db-policy.enabled 가 true) */
    public boolean policyEnabled() {
        return enabled;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection c) {
        if (!enabled) {
            return c;
        }
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, new ConnectionHandler(c));
    }

    /** 정책 위반: DB 로 보내지 않았다 (Spring 은 비일시적 오류로 바꾼다) */
    public static class Violation extends SQLNonTransientException {
        public Violation(String message) {
            super(message);
        }
    }

    void check(String sql) throws Violation {
        Optional<String> v = DbWritePolicy.violation(role, sql, allowForUpdateWait);
        if (v.isPresent()) {
            String msg = "[georu DB 정책] " + name + "(" + role + "): " + v.get() + " · 보내지 않음: " + DbWritePolicy.head(sql);
            log.error(msg);
            throw new Violation(msg);
        }
    }

    private final class ConnectionHandler implements InvocationHandler {
        private final Connection target;

        ConnectionHandler(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            String n = m.getName();
            if ("prepareCall".equals(n)) {
                String msg = "[georu DB 정책] " + name + "(" + role + "): 프로시저 호출(prepareCall)은 허용하지 않습니다 · 보내지 않음: "
                        + DbWritePolicy.head(args != null && args.length > 0 ? String.valueOf(args[0]) : "");
                log.error(msg);
                throw new Violation(msg);
            }
            if ("prepareStatement".equals(n) && args != null && args.length > 0 && args[0] instanceof String sql) {
                check(sql);
            }
            if ("setReadOnly".equals(n) && DataSourceNames.ROLE_SOURCE.equals(role) && args != null && Boolean.FALSE.equals(args[0])) {
                return null;   // 소스는 읽기 전용 유지 (트랜잭션 끝의 되돌리기 호출 등)
            }
            if ("unwrap".equals(n) || "isWrapperFor".equals(n)) {
                if (args != null && args[0] == Connection.class) {
                    return "unwrap".equals(n) ? proxy : Boolean.TRUE;
                }
            }
            Object r = call(target, m, args);
            if (r instanceof Statement st && !(r instanceof java.sql.PreparedStatement) && !(r instanceof CallableStatement)) {
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class}, new StatementHandler(st, proxy));
            }
            return r;
        }
    }

    private final class StatementHandler implements InvocationHandler {
        private final Statement target;
        private final Object connectionProxy;

        StatementHandler(Statement target, Object connectionProxy) {
            this.target = target;
            this.connectionProxy = connectionProxy;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            if (STATEMENT_SQL_METHODS.contains(m.getName()) && args != null && args.length > 0 && args[0] instanceof String sql) {
                check(sql);
            }
            if ("getConnection".equals(m.getName())) {
                return connectionProxy;
            }
            return call(target, m, args);
        }
    }

    private static Object call(Object target, Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
