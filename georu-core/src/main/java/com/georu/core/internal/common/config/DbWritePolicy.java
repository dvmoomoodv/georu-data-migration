package com.georu.core.internal.common.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DB 쓰기 정책. 역할마다 보낼 수 있는 SQL 을 정한다.
 *
 * <ul>
 *   <li><b>소스</b>: 읽기만 — 문장 첫 단어가 SELECT · WITH · SHOW · DESC(RIBE) · EXPLAIN 이고, 쓰기 단어
 *       (INSERT · UPDATE · DELETE · MERGE · TRUNCATE · REPLACE INTO · DDL · CALL 등)가 없다. {@code SELECT … FOR UPDATE} 도 막는다</li>
 *   <li><b>타겟 · 채번</b>: DELETE · TRUNCATE 를 뺀 DML — INSERT · UPDATE · MERGE(삭제 절 없이) · SELECT 등은 되고,
 *       DELETE · TRUNCATE · REPLACE(MySQL 에서는 지우고 넣는다) · DDL(CREATE · ALTER · DROP · RENAME) · GRANT · REVOKE · CALL · EXEC 는 막는다</li>
 *   <li>운영 저장소는 대상이 아니다 (georu 자체 DB)</li>
 * </ul>
 *
 * <p>소스 예외</p>
 * <ul>
 *   <li>Oracle {@code SELECT … FOR UPDATE [OF 컬럼] WAIT <정수>}: {@value #ALLOW_FOR_UPDATE_WAIT_KEY}=true 일 때만. 문장 끝의 이 꼬리를 떼고
 *       나머지를 같은 규칙으로 본다. 맨 {@code FOR UPDATE} · {@code NOWAIT} · {@code SKIP LOCKED} · 숫자가 아닌 대기 값은 계속 거절.
 *       대기 시간 상한은 두지 않는다 (쿼리가 정한다)</li>
 *   <li>SQL Server 읽기 힌트 {@code WITH (NOLOCK)} · {@code (NOLOCK)} · {@code READUNCOMMITTED}: 잠그지 않는 읽기라 설정 없이 통과한다
 *       (쓰기 · 잠금 단어 목록에 없다). 같은 문장의 다른 부분은 그대로 판정</li>
 * </ul>
 *
 * <p>판정은 글자로 한다: 주석 · 따옴표 안(문자열 · 따옴표 식별자)을 지우고 {@code ;} 로 나눈 문장마다 첫 단어와 단어 목록을 본다.
 * 기동 때 매퍼 검사({@link MapperPolicyValidator})와 실행 중 연결 검사({@link PolicyDataSource})가 같은 규칙을 쓴다.
 * 비상시 끄기: {@code georu.db-policy.enabled=false} (기동 로그 경고).</p>
 */
public final class DbWritePolicy {

    /** 비상용 끄기 설정 (기본 true) */
    public static final String ENABLED_KEY = "georu.db-policy.enabled";

    /** 소스 {@code SELECT … FOR UPDATE [OF …] WAIT n} 허용 (기본 false) */
    public static final String ALLOW_FOR_UPDATE_WAIT_KEY = "georu.db-policy.allow-for-update-wait";

    private static final Set<String> READ_FIRST = Set.of("SELECT", "WITH", "SHOW", "DESC", "DESCRIBE", "EXPLAIN");
    private static final Set<String> SOURCE_FORBIDDEN_WORDS = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "TRUNCATE", "UPSERT",
            "CREATE", "ALTER", "DROP", "RENAME", "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE", "LOCK");
    private static final Set<String> TARGET_FORBIDDEN_FIRST = Set.of("DELETE", "TRUNCATE", "REPLACE", "CREATE", "ALTER", "DROP", "RENAME",
            "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE");
    private static final Set<String> TARGET_FORBIDDEN_WORDS = Set.of("DELETE", "TRUNCATE");

    /** 식별자 한 단어 (단어 목록 · FOR UPDATE OF 컬럼이 같이 쓴다) */
    private static final String IDENT = "[A-Za-z_][A-Za-z0-9_$#]*";
    private static final Pattern WORD = Pattern.compile(IDENT);
    private static final Pattern REPLACE_INTO = Pattern.compile("\\bREPLACE\\s+INTO\\b");
    /** 문장 끝의 FOR UPDATE [OF 컬럼, …] WAIT 정수 (대문자 · 주석과 따옴표를 지운 글자 기준. 따옴표 식별자는 x 로 남아 있다) */
    private static final String COLUMN = IDENT + "(?:\\s*\\.\\s*" + IDENT + ")*";
    private static final Pattern FOR_UPDATE_WAIT_TAIL = Pattern.compile(
            "\\bFOR\\s+UPDATE(?:\\s+OF\\s+" + COLUMN + "(?:\\s*,\\s*" + COLUMN + ")*)?\\s+WAIT\\s+\\d+\\s*$");

    private DbWritePolicy() {
    }

    /** 정책을 거는 역할인지 (소스 · 타겟 · 채번. 운영 저장소 · 모르는 이름은 아님) */
    public static boolean applies(String role) {
        return DataSourceNames.ROLE_SOURCE.equals(role) || DataSourceNames.ROLE_TARGET.equals(role) || DataSourceNames.ROLE_SEQUENCE.equals(role);
    }

    /**
     * 이 역할의 DataSource 로 보내면 안 되는 SQL 이면 사유, 괜찮으면 empty.
     * {@code ${…}} · {@code #{…}} 자리표시자가 남아 있어도 된다 (기동 때 검사)
     *
     * @param allowForUpdateWait {@value #ALLOW_FOR_UPDATE_WAIT_KEY} 값. true 면 소스 문장 끝의 {@code FOR UPDATE [OF …] WAIT n} 을 떼고 본다
     */
    public static Optional<String> violation(String role, String sql, boolean allowForUpdateWait) {
        if (!applies(role) || sql == null || sql.isBlank()) {
            return Optional.empty();
        }
        boolean source = DataSourceNames.ROLE_SOURCE.equals(role);
        for (String stmt : statements(strip(sql))) {
            String upper = stmt.toUpperCase(Locale.ROOT);
            if (source && allowForUpdateWait) {
                upper = FOR_UPDATE_WAIT_TAIL.matcher(upper).replaceFirst("");
            }
            List<String> words = words(upper);
            if (words.isEmpty()) {
                continue;
            }
            String first = words.get(0);
            if (source) {
                if (!READ_FIRST.contains(first)) {
                    return Optional.of("소스 DB 는 읽기(SELECT)만 허용합니다 - '" + first + "' 문장");
                }
                for (String w : words) {
                    if (SOURCE_FORBIDDEN_WORDS.contains(w)) {
                        return Optional.of("소스 DB 는 읽기(SELECT)만 허용합니다 - 쓰기 · 잠금 단어 '" + w + "'"
                                + ("UPDATE".equals(w) ? updateHint(allowForUpdateWait, words.contains("WAIT")) : ""));
                    }
                }
                if (REPLACE_INTO.matcher(upper).find()) {
                    return Optional.of("소스 DB 는 읽기(SELECT)만 허용합니다 - 'REPLACE INTO'");
                }
            } else {
                if (TARGET_FORBIDDEN_FIRST.contains(first)) {
                    return Optional.of("타겟 DB 에는 DELETE · TRUNCATE · REPLACE · DDL · 프로시저 호출을 보낼 수 없습니다 - '" + first + "' 문장");
                }
                for (String w : words) {
                    if (TARGET_FORBIDDEN_WORDS.contains(w)) {
                        return Optional.of("타겟 DB 에는 DELETE · TRUNCATE 를 보낼 수 없습니다 - 단어 '" + w + "'"
                                + ("MERGE".equals(first) ? " (MERGE 의 삭제 절)" : ""));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** UPDATE 단어로 거절할 때 덧붙이는 안내 */
    private static String updateHint(boolean allowForUpdateWait, boolean hasWait) {
        if (allowForUpdateWait) {
            return " (SELECT … FOR UPDATE 포함. 허용은 문장 끝의 FOR UPDATE [OF …] WAIT 숫자 뿐)";
        }
        // WAIT 가 있는 문장에만 설정 키를 안내한다 (NOWAIT · SKIP LOCKED · 다른 DB 는 켜도 통과하지 않는다)
        return hasWait
                ? " (SELECT … FOR UPDATE 포함. Oracle 문장 끝의 FOR UPDATE [OF …] WAIT n 은 " + ALLOW_FOR_UPDATE_WAIT_KEY + "=true 로 허용)"
                : " (SELECT … FOR UPDATE 포함)";
    }

    /** 오류 메시지용 문장 앞부분 (공백 정리, 120자) */
    public static String head(String sql) {
        String s = sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
        return s.length() > 120 ? s.substring(0, 117) + "..." : s;
    }

    /** 주석(블록 · 줄)과 따옴표 안(문자열 '…', 식별자 "…" · `…`)을 지운다. 지운 자리는 자리표시 글자 */
    static String strip(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            if (c == '/' && next == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else if (c == '-' && next == '-') {
                int end = sql.indexOf('\n', i + 2);
                i = end < 0 ? n : end + 1;
                out.append(' ');
            } else if (c == '\'' || c == '"' || c == '`') {
                int j = i + 1;
                while (j < n) {
                    if (sql.charAt(j) == c) {
                        if (j + 1 < n && sql.charAt(j + 1) == c) {   // '' · "" 이스케이프
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                i = j + 1;
                out.append(c == '\'' ? " '' " : " x ");   // 문자열은 값, 식별자는 이름 자리로 남긴다
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** {@code ;} 로 문장을 나눈다 (따옴표 · 주석은 이미 지운 글자) */
    static List<String> statements(String stripped) {
        List<String> list = new ArrayList<>();
        for (String s : stripped.split(";")) {
            if (!s.isBlank()) {
                list.add(s.trim());
            }
        }
        return list;
    }

    private static List<String> words(String upper) {
        List<String> list = new ArrayList<>();
        Matcher m = WORD.matcher(upper);
        while (m.find()) {
            list.add(m.group());
        }
        return list;
    }
}
