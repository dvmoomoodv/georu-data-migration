package com.georu.api.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 이관 설계 도구 실행 기록 (운영 저장소 migration_gen_run). 올린 파일과 결과 파일은 저장하지 않는다.
 * 화면이 보낸 설정 JSON 은 그대로 저장해(V10 settings_json) 같은 조건으로 다시 만들 수 있게 한다.
 * 기록 실패는 도구 실행을 막지 않는다.
 */
@Component
public class GenRunStore {

    /** 설정 JSON 저장 상한. 넘으면 저장하지 않고 요약에 표시한다 (잘린 JSON 을 저장하지 않는다) */
    static final int SETTINGS_MAX = 64 * 1024;
    static final String SETTINGS_SKIPPED = " (설정 미저장: 크기 초과)";

    /** 초가 0 이어도 자리를 지킨다 (LocalDateTime.toString 은 ":00" 초를 빼서 "HH:mm" 이 된다) */
    private static final java.time.format.DateTimeFormatter TIME = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String COLUMNS = "run_id, tool, executed_by, executed_at, file_name, result, summary, settings_json";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public GenRunStore(@Qualifier("operationDataSource") DataSource operationDataSource) {
        this.jdbc = new JdbcTemplate(operationDataSource);
    }

    /** 설정 없이 기록 (이전 호출 호환) */
    public void record(String tool, String executedBy, String fileName, boolean ok, String summary) {
        record(tool, executedBy, fileName, ok, summary, null);
    }

    /**
     * @param settingsJson 화면이 보낸 settings 파라미터 그대로 (파싱 · 재직렬화하지 않는다). null · 빈 값이면 저장하지 않는다
     */
    public void record(String tool, String executedBy, String fileName, boolean ok, String summary, String settingsJson) {
        String settings = settingsJson == null || settingsJson.isBlank() ? null : settingsJson;
        if (settings != null && settings.length() > SETTINGS_MAX) {
            settings = null;
            summary = (summary == null ? "" : summary) + SETTINGS_SKIPPED;
        }
        jdbc.update("INSERT INTO migration_gen_run (tool, executed_by, executed_at, file_name, result, summary, settings_json) VALUES (?, ?, ?, ?, ?, ?, ?)",
                tool, cut(executedBy, 100), Timestamp.valueOf(LocalDateTime.now()), cut(fileName, 300), ok ? "OK" : "ERROR", cut(summary, 2000), settings);
    }

    /**
     * 최근 기록 (열 이름은 소문자). {@code settings} 는 저장한 JSON 을 객체로 바꾼 것이고, 없으면 키가 없다.
     * 저장된 JSON 을 읽지 못하면 {@code settings} 대신 {@code settings_error} 를 넣는다
     */
    public List<Map<String, Object>> recent(String tool, int limit) {
        return query(tool, limit).stream().map(this::view).toList();
    }

    private List<Map<String, Object>> query(String tool, int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        if (tool == null || tool.isBlank()) {
            return jdbc.queryForList("SELECT " + COLUMNS + " FROM migration_gen_run ORDER BY run_id DESC LIMIT " + n);
        }
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM migration_gen_run WHERE tool = ? ORDER BY run_id DESC LIMIT " + n, tool);
    }

    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        // 시각은 서버 시간대의 "yyyy-MM-dd HH:mm:ss" 문자열 (JSON 의 Timestamp 는 UTC 로 나가 화면 시각이 어긋난다)
        row.forEach((k, v) -> {
            String key = k.toLowerCase(Locale.ROOT);
            if (key.equals("settings_json")) {
                return;
            }
            m.put(key, v instanceof Timestamp t ? t.toLocalDateTime().format(TIME) : v);
        });
        Object raw = row.get("settings_json");
        if (raw == null) raw = row.get("SETTINGS_JSON");
        if (raw != null) {
            String s = raw instanceof java.sql.Clob c ? clob(c) : String.valueOf(raw);
            if (s != null && !s.isBlank()) {
                try {
                    m.put("settings", json.readValue(s, Map.class));
                } catch (Exception e) {
                    m.put("settings_error", "저장된 설정을 읽지 못했습니다: " + e.getMessage());
                }
            }
        }
        return m;
    }

    private static String clob(java.sql.Clob c) {
        try {
            return c.getSubString(1, (int) Math.min(c.length(), Integer.MAX_VALUE));
        } catch (Exception e) {
            return null;
        }
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
