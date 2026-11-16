package com.georu.core.internal.common.config;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.scripting.xmltags.DynamicSqlSource;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 기동 때 매퍼 검사. DataSource 마다 SqlSessionFactory 를 만든 직후, 실린 모든 문장을 {@link DbWritePolicy} 로 본다.
 * 어긋나는 문장이 하나라도 있으면 기동을 멈춘다 (배포 전에 잡는다).
 *
 * <ul>
 *   <li>소스: 문장 종류가 SELECT 여야 하고 글자도 읽기만</li>
 *   <li>타겟 · 채번: DELETE · TRUNCATE · REPLACE · DDL · 프로시저 호출이 없어야 한다 ({@code <insert>} 안에 DELETE 를 써도 잡는다)</li>
 *   <li>동적 SQL({@code <if>} · {@code <foreach>} 등)은 모든 분기의 글자를 모아 본다. 글자를 읽을 수 없는 문장(Provider 등)은 경고만</li>
 * </ul>
 */
public final class MapperPolicyValidator {

    private static final Logger log = LoggerFactory.getLogger(MapperPolicyValidator.class);

    private MapperPolicyValidator() {
    }

    /**
     * 위반 목록 (없으면 빈 목록). 각 줄: 문장 id · 사유 · 매퍼 파일
     *
     * @param allowForUpdateWait 소스 {@code SELECT … FOR UPDATE [OF …] WAIT n} 허용 ({@link DbWritePolicy#ALLOW_FOR_UPDATE_WAIT_KEY})
     */
    public static List<String> violations(Configuration config, String role, boolean allowForUpdateWait) {
        List<String> out = new ArrayList<>();
        if (!DbWritePolicy.applies(role)) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Object o : config.getMappedStatements()) {
            if (!(o instanceof MappedStatement ms) || !seen.add(ms.getId()) || !ms.getId().contains(".")) {
                continue;   // MyBatis 는 같은 문장을 짧은 id 로도 넣는다
            }
            if (DataSourceNames.ROLE_SOURCE.equals(role) && ms.getSqlCommandType() != SqlCommandType.SELECT
                    && ms.getSqlCommandType() != SqlCommandType.UNKNOWN) {
                out.add(ms.getId() + " : 소스 DB 는 읽기(SELECT)만 허용합니다 - <" + ms.getSqlCommandType().name().toLowerCase() + "> 문장 ("
                        + resource(ms) + ")");
                continue;
            }
            Optional<String> text = text(ms.getSqlSource());
            if (text.isEmpty()) {
                log.warn("[DB 정책] {} 의 SQL 글자를 읽지 못해 기동 검사를 건너뜀 (실행할 때 연결 검사는 한다) - {}", ms.getId(), resource(ms));
                continue;
            }
            DbWritePolicy.violation(role, text.get(), allowForUpdateWait).ifPresent(v -> out.add(ms.getId() + " : " + v + " (" + resource(ms) + ")"));
        }
        return out;
    }

    /** 위반이 있으면 기동 실패. 실행 중 연결 검사({@link PolicyDataSource})와 같은 allowForUpdateWait 를 넘긴다 */
    public static void check(SqlSessionFactory factory, String name, String role, boolean allowForUpdateWait) {
        List<String> v = violations(factory.getConfiguration(), role, allowForUpdateWait);
        if (!v.isEmpty()) {
            throw new IllegalStateException("[georu DB 정책] " + name + "(" + role + ") 매퍼에 허용하지 않는 문장 " + v.size() + "개:\n  - "
                    + String.join("\n  - ", v) + "\n  소스는 SELECT 만, 타겟은 DELETE · TRUNCATE · REPLACE · DDL 없이. "
                    + "비상시에만 " + DbWritePolicy.ENABLED_KEY + "=false (안내서 troubleshooting#db-policy)");
        }
        log.info("[DB 정책] {}({}) 매퍼 {}개 문장 검사 통과", name, role, factory.getConfiguration().getMappedStatementNames().size());
    }

    private static String resource(MappedStatement ms) {
        return ms.getResource() == null ? "-" : ms.getResource();
    }

    /** 문장의 SQL 글자. 동적 SQL 은 모든 노드의 글자를 이어 붙인다 (분기 조건은 보지 않음) */
    static Optional<String> text(SqlSource src) {
        try {
            if (src instanceof RawSqlSource || src instanceof StaticSqlSource) {
                return Optional.ofNullable(src.getBoundSql(null).getSql());
            }
            if (src instanceof DynamicSqlSource) {
                StringBuilder b = new StringBuilder();
                collect(field(src, "rootSqlNode"), b, new IdentityHashMap<>());
                return Optional.of(b.toString());
            }
        } catch (Exception e) {
            log.debug("SQL 글자 읽기 실패: {}", e.toString());
        }
        return Optional.empty();
    }

    /** SqlNode 트리를 따라가며 글자를 모은다 (StaticTextSqlNode · TextSqlNode 의 text, 그 밖에는 하위 노드) */
    private static void collect(Object node, StringBuilder b, Map<Object, Boolean> visited) throws IllegalAccessException {
        if (node == null || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        if (node instanceof Collection<?> list) {
            for (Object n : list) {
                collect(n, b, visited);
            }
            return;
        }
        Class<?> c = node.getClass();
        if (!c.getName().startsWith("org.apache.ibatis.scripting.xmltags.")) {
            return;
        }
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(node);
                if (v instanceof String s && "text".equals(f.getName())) {
                    b.append(' ').append(s).append(' ');
                } else if (v instanceof Collection<?> || (v != null && v.getClass().getName().startsWith("org.apache.ibatis.scripting.xmltags."))) {
                    collect(v, b, visited);
                }
            }
        }
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }
}
