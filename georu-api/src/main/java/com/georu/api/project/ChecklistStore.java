package com.georu.api.project;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 이관 진행 체크리스트 저장 (운영 저장소 V12: migration_checklist_item · migration_checklist_event).
 * 앱 하나 = 이관 프로젝트 하나라 체크리스트도 하나다 (todo-task 24 결정).
 */
@Component
public class ChecklistStore {

    private static final String COLUMNS = "item_id, phase_no, phase_name, title, required_yn, info, link, owner, sort_no, checked_yn, checked_by, checked_at, memo";

    private static final RowMapper<ChecklistItem> ITEM = (rs, i) -> new ChecklistItem(
            rs.getLong("item_id"), rs.getInt("phase_no"), rs.getString("phase_name"), rs.getString("title"),
            "Y".equalsIgnoreCase(rs.getString("required_yn")), rs.getString("info"), rs.getString("link"), rs.getString("owner"),
            rs.getInt("sort_no"), "Y".equalsIgnoreCase(rs.getString("checked_yn")), rs.getString("checked_by"),
            ts(rs.getTimestamp("checked_at")), rs.getString("memo"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ChecklistStore(@Qualifier("operationDataSource") DataSource operationDataSource) {
        this.jdbc = new JdbcTemplate(operationDataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(operationDataSource));
    }

    /** 단계 순서 · 단계 안 순서 · id 순 */
    public List<ChecklistItem> list() {
        return jdbc.query("SELECT " + COLUMNS + " FROM migration_checklist_item ORDER BY phase_no, sort_no, item_id", ITEM);
    }

    public Optional<ChecklistItem> find(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM migration_checklist_item WHERE item_id = ?", ITEM, id).stream().findFirst();
    }

    /** 체크 · 해제. 해제하면 체크한 사람 · 시각을 지운다 */
    /** @return 항목이 있었는지 */
    public boolean setChecked(long id, boolean checked, String actor) {
        LocalDateTime now = LocalDateTime.now();
        return 1 == jdbc.update("UPDATE migration_checklist_item SET checked_yn = ?, checked_by = ?, checked_at = ?, updated_at = ? WHERE item_id = ?",
                checked ? "Y" : "N", checked ? cut(actor, 100) : null, checked ? Timestamp.valueOf(now) : null, Timestamp.valueOf(now), id);
    }

    /** @return 항목이 있었는지 */
    public boolean setMemo(long id, String memo) {
        return 1 == jdbc.update("UPDATE migration_checklist_item SET memo = ?, updated_at = ? WHERE item_id = ?",
                blankToNull(cut(memo, 2000)), Timestamp.valueOf(LocalDateTime.now()), id);
    }

    /**
     * 정의를 통째로 바꾼다 (한 트랜잭션). {@code keep} 의 항목은 id 로 정의만 고치고(체크 · 메모 유지), {@code add} 는 새로 넣고, {@code remove} 는 지운다
     */
    public void replace(List<ChecklistItem> keep, List<ChecklistItem> add, List<Long> remove, Consumer<JdbcTemplate> alsoInTx) {
        tx.executeWithoutResult(s -> {
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            for (Long id : remove) {
                jdbc.update("DELETE FROM migration_checklist_item WHERE item_id = ?", id);
            }
            for (ChecklistItem i : keep) {
                jdbc.update("UPDATE migration_checklist_item SET phase_no = ?, phase_name = ?, title = ?, required_yn = ?, info = ?, link = ?, owner = ?, sort_no = ?, updated_at = ? WHERE item_id = ?",
                        i.phaseNo(), cut(i.phaseName(), 100), cut(i.title(), 300), i.required() ? "Y" : "N", blankToNull(cut(i.info(), 2000)),
                        blankToNull(cut(i.link(), 500)), blankToNull(cut(i.owner(), 100)), i.sortNo(), now, i.id());
            }
            for (ChecklistItem i : add) {
                jdbc.update("INSERT INTO migration_checklist_item (phase_no, phase_name, title, required_yn, info, link, owner, sort_no, checked_yn, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'N', ?)",
                        i.phaseNo(), cut(i.phaseName(), 100), cut(i.title(), 300), i.required() ? "Y" : "N", blankToNull(cut(i.info(), 2000)),
                        blankToNull(cut(i.link(), 500)), blankToNull(cut(i.owner(), 100)), i.sortNo(), now);
            }
            if (alsoInTx != null) alsoInTx.accept(jdbc);
        });
    }

    // ------------------------------------------------------------------ 기록

    public void event(Long itemId, String action, String detail, String actor) {
        event(jdbc, itemId, action, detail, actor);
    }

    static void event(JdbcTemplate jdbc, Long itemId, String action, String detail, String actor) {
        jdbc.update("INSERT INTO migration_checklist_event (item_id, action, detail, actor, created_at) VALUES (?, ?, ?, ?, ?)",
                itemId, action, cut(detail, 2000), cut(actor, 100), Timestamp.valueOf(LocalDateTime.now()));
    }

    private static final RowMapper<ChecklistEvent> EVENT = (rs, i) -> new ChecklistEvent(rs.getLong("event_id"),
            rs.getObject("item_id") == null ? null : rs.getLong("item_id"), rs.getString("action"), rs.getString("detail"),
            rs.getString("actor"), ts(rs.getTimestamp("created_at")));

    /** 항목의 기록 (최근 순). itemId 가 null 이면 체크리스트 전체 기록(엑셀 적용 · 되돌리기 · 채우기) */
    public List<ChecklistEvent> events(Long itemId, int limit) {
        String sql = "SELECT event_id, item_id, action, detail, actor, created_at FROM migration_checklist_event WHERE "
                + (itemId == null ? "item_id IS NULL" : "item_id = ?") + " ORDER BY event_id DESC LIMIT " + Math.max(1, Math.min(limit, 200));
        return itemId == null ? jdbc.query(sql, EVENT) : jdbc.query(sql, EVENT, itemId);
    }

    static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static LocalDateTime ts(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
