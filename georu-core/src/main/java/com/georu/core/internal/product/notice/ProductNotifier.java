package com.georu.core.internal.product.notice;

import com.georu.core.product.notice.UiNotificationRecorder;

import com.georu.core.notification.TelegramNotificationService;
import com.georu.core.product.model.UiNotification;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 상품화 P0 알림. 같은 내용을 UI 알림 목록에 기록하고 텔레그램으로 보낸다.
 *
 * <p>메시지 종류 (category)</p>
 * <ul>
 *   <li>EXISTING_DATA   - 기존 데이터 경고 (적재 중단 / 무시하고 적재)</li>
 *   <li>PRECHECK_BLOCK  - 실행 전 점검 차단</li>
 *   <li>INCOMPLETE_CODE - 미완 코드(원천 미지정) 차단</li>
 *   <li>TABLE_RESULT    - 테이블 완료 / 완료(경고) / 실패</li>
 *   <li>BATCH_SUMMARY   - 묶음 실행(목록·시나리오) 요약</li>
 *   <li>SCENARIO_STEP   - 시나리오 시작 1회 + 단계마다 1회 (georu.product.notice.scenario-step)</li>
 *   <li>SCHEDULE        - cron 스케줄 등록·일시정지·재개·취소·종료, 주기 건너뜀</li>
 * </ul>
 * 묶음 실행 중에는 경고 없는 테이블 완료 알림을 보내지 않고, 끝날 때 요약 1회만 보낸다 (경고·실패는 즉시).
 * 시나리오는 그 사이에 단계 진행 알림을 보낸다 (청크·구간 단위가 아니라 단계 단위).
 * 본문은 일반 텍스트로 만들고, 텔레그램(HTML parse_mode)으로 보낼 때만 이스케이프한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductNotifier {

    public static final String CAT_EXISTING_DATA = "EXISTING_DATA";
    public static final String CAT_PRECHECK_BLOCK = "PRECHECK_BLOCK";
    public static final String CAT_INCOMPLETE_CODE = "INCOMPLETE_CODE";
    public static final String CAT_TABLE_RESULT = "TABLE_RESULT";
    public static final String CAT_BATCH_SUMMARY = "BATCH_SUMMARY";
    public static final String CAT_SCENARIO_STEP = "SCENARIO_STEP";
    public static final String CAT_SCHEDULE = "SCHEDULE";
    /** 운영 기록 정리 (사용자가 실행) */
    public static final String CAT_MAINTENANCE = "MAINTENANCE";

    /** 묶음 요약에서 이름을 나열할 테이블 수 상한 (나머지는 "외 N개") */
    public static final int SUMMARY_TOP_N = 20;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TelegramNotificationService telegram;
    private final UiNotificationRecorder recorder;

    @Value("${georu.product.ui-base-url:}")
    private String uiBaseUrl;

    /** 시나리오 시작·단계 알림 (georu.product.notice.scenario-step). 끝의 묶음 요약과는 별개 */
    @Value("${georu.product.notice.scenario-step:true}")
    private boolean scenarioStepEnabled = true;

    // ------------------------------------------------------------------ 메시지 데이터

    /** 기존 데이터 경고의 테이블 1행 */
    @lombok.Value
    @Builder
    public static class ExistingDataRow {
        String tableName;
        String existingCount;      // 표시용 ("1,204" / "1건 이상 (건수 조회 시간 초과)")
        String lastSampleRun;      // "5건, 2026-10-12 14:03" 또는 null
    }

    /** 테이블 결과 알림 데이터 */
    @lombok.Value
    @Builder
    public static class TableResult {
        String migrationId;
        String batchId;
        String pluginName;
        String tableName;
        String status;             // COMPLETED / FAILED / STOPPED ...
        String testMode;
        boolean warning;
        List<String> warningReasons;
        Long sourceCount;
        Long filterCount;
        Long processedCount;
        Long diffCount;
        Long targetBefore;
        Long targetAfter;
        boolean countCheckSkipped;
        long durationMs;
        String errorMessage;       // 실패 시 원문 오류
    }

    // ------------------------------------------------------------------ 기존 데이터

    public void existingData(String migrationId, String batchId, String pluginName, List<ExistingDataRow> rows, boolean proceeding) {
        StringBuilder m = new StringBuilder();
        m.append(proceeding ? "⚠️ [기존 데이터] 무시하고 적재합니다 (ignoreExistingData=true)\n\n"
                            : "⚠️ [기존 데이터] 타겟에 기존 데이터가 있어 적재를 중단했습니다.\n\n");
        m.append("플러그인: ").append(nvl(pluginName)).append('\n');
        appendRows(m, rows.stream().map(r -> "• " + r.getTableName() + " : 기존 " + r.getExistingCount() + "건"
                + (r.getLastSampleRun() != null ? " (마지막 샘플 실행: " + r.getLastSampleRun() + ")" : "")).toList());
        if (!proceeding) {
            m.append("\n조치 방법\n");
            m.append(" 1) 수작업으로 데이터를 정리한 뒤 다시 실행 → 정리 안내서: ").append(link("/ui/guide?name=manual-cleanup")).append('\n');
            m.append(" 2) 쿼리가 UPSERT·IGNORE 방식이면 ignoreExistingData=true 로 다시 실행\n");
        }
        m.append("\n🕒 ").append(now());
        String table = rows.size() == 1 ? rows.get(0).getTableName() : null;
        send("WARNING", CAT_EXISTING_DATA, proceeding ? "기존 데이터 무시하고 적재" : "기존 데이터로 적재 중단",
                m.toString(), migrationId, batchId, pluginName, table);
    }

    // ------------------------------------------------------------------ 실행 전 점검 차단

    /**
     * @param byType       점검 종류별 차단 건수 ("NOT_NULL 12" 같은 표시 문자열)
     * @param tableLines   테이블별 요약 ("fb_x : 길이 초과 3, NOT NULL 1")
     */
    public void precheckBlocked(String migrationId, String batchId, String pluginName, String runId,
                                List<String> byType, List<String> tableLines) {
        StringBuilder m = new StringBuilder("⛔ [실행 전 점검] 차단 항목이 있어 적재하지 않았습니다.\n\n");
        m.append("플러그인: ").append(nvl(pluginName)).append('\n');
        m.append("점검 ID: ").append(nvl(runId)).append('\n');
        if (!byType.isEmpty()) {
            m.append("\n유형별 차단 건수\n");
            byType.forEach(t -> m.append("• ").append(t).append('\n'));
        }
        m.append("\n테이블\n");
        appendRows(m, tableLines.stream().map(t -> "• " + t).toList());
        m.append("\n상세: ").append(link("/ui/precheck?runId=" + nvl(runId)));
        m.append("\n차단을 무시하려면 ignorePrecheckErrors=true 로 다시 실행 (권장하지 않음)");
        m.append("\n\n🕒 ").append(now());
        String table = tableLines.size() == 1 ? tableLines.get(0).split(" ")[0] : null;
        send("ERROR", CAT_PRECHECK_BLOCK, "실행 전 점검 차단", m.toString(), migrationId, batchId, pluginName, table);
    }

    // ------------------------------------------------------------------ 미완 코드 차단

    public void incompleteCodeBlocked(String migrationId, String batchId, String pluginName, String tableName, List<String> locations) {
        StringBuilder m = new StringBuilder("⛔ [미완 코드] 원천(FROM)이 지정되지 않아 실행하지 않았습니다.\n\n");
        m.append("플러그인: ").append(nvl(pluginName)).append('\n');
        m.append("테이블: ").append(tableName).append('\n');
        if (!locations.isEmpty()) {
            m.append("\n위치\n");
            appendRows(m, locations.stream().map(l -> "• " + l).toList());
        }
        m.append("\nSourceMapper.xml 의 FROM / WHERE 1 = 0 을 채운 뒤 다시 빌드·실행하세요.");
        m.append("\n\n🕒 ").append(now());
        send("ERROR", CAT_INCOMPLETE_CODE, "미완 코드로 실행 차단: " + tableName, m.toString(), migrationId, batchId, pluginName, tableName);
    }

    // ------------------------------------------------------------------ 테이블 결과

    public void tableResult(TableResult r) {
        boolean failed = r.getStatus() != null && !"COMPLETED".equalsIgnoreCase(r.getStatus());
        // 묶음 실행 중 경고 없는 완료는 보내지 않는다 (끝날 때 요약 1회)
        if (r.getBatchId() != null && !failed && !r.isWarning()) {
            return;
        }
        String head = failed ? "❌ [실패] " : (r.isWarning() ? "⚠️ [완료(경고)] " : "✅ [완료] ");
        StringBuilder m = new StringBuilder(head).append(r.getTableName()).append("\n\n");
        m.append("플러그인: ").append(nvl(r.getPluginName())).append('\n');
        m.append("상태: ").append(nvl(r.getStatus()));
        if (r.getTestMode() != null && !"NORMAL".equalsIgnoreCase(r.getTestMode())) {
            m.append(" (").append(r.getTestMode()).append(')');
        }
        m.append('\n');
        m.append("소스 ").append(fmt(r.getSourceCount())).append(" / 필터 ").append(fmt(r.getFilterCount()))
         .append(" / 처리 ").append(fmt(r.getProcessedCount()));
        if (r.isCountCheckSkipped()) {
            m.append(" (건수 비교 제외 테이블)");
        } else if (r.getDiffCount() != null) {
            m.append(" / 차이 ").append(fmt(r.getDiffCount()));
        }
        m.append('\n');
        if (r.getTargetBefore() != null || r.getTargetAfter() != null) {
            m.append("타겟 적재 전 ").append(fmt(r.getTargetBefore())).append(" → 후 ").append(fmt(r.getTargetAfter())).append('\n');
        }
        m.append("소요: ").append(duration(r.getDurationMs())).append('\n');
        if (r.isWarning() && r.getWarningReasons() != null && !r.getWarningReasons().isEmpty()) {
            m.append("\n경고 사유\n");
            r.getWarningReasons().forEach(w -> m.append("• ").append(w).append('\n'));
        }
        if (failed && r.getErrorMessage() != null) {
            String err = r.getErrorMessage().length() > 600 ? r.getErrorMessage().substring(0, 600) + "…" : r.getErrorMessage();
            m.append("\n오류\n").append(err).append('\n');
        }
        m.append("\n상세: ").append(link("/ui/history?migrationId=" + nvl(r.getMigrationId())));
        m.append("\n🕒 ").append(now());
        send(failed ? "ERROR" : (r.isWarning() ? "WARNING" : "INFO"), CAT_TABLE_RESULT,
                head.substring(2).trim() + " " + r.getTableName(), m.toString(), r.getMigrationId(), r.getBatchId(), r.getPluginName(), r.getTableName());
    }

    // ------------------------------------------------------------------ 묶음 요약

    @lombok.Value
    @Builder
    public static class BatchSummary {
        String batchId;
        String title;              // "실행 목록" / "시나리오 hr.member"
        int total;
        int completed;
        int warnings;
        int failed;
        int blocked;
        long sourceSum;
        long processedSum;
        long durationMs;
        List<String> problemLines; // 경고·실패·차단 테이블 ("⚠️ fb_x : 차이 3")
    }

    public void batchSummary(BatchSummary s) {
        boolean problem = s.getWarnings() + s.getFailed() + s.getBlocked() > 0;
        StringBuilder m = new StringBuilder(problem ? "⚠️ [묶음 실행 요약] " : "✅ [묶음 실행 요약] ").append(nvl(s.getTitle())).append("\n\n");
        m.append("전체 ").append(s.getTotal()).append(" / 완료 ").append(s.getCompleted())
         .append(" / 경고 ").append(s.getWarnings()).append(" / 실패 ").append(s.getFailed())
         .append(" / 차단 ").append(s.getBlocked()).append('\n');
        m.append("소스 합계 ").append(fmt(s.getSourceSum())).append(" / 처리 합계 ").append(fmt(s.getProcessedSum())).append('\n');
        if (s.getDurationMs() > 0) {
            m.append("소요: ").append(duration(s.getDurationMs())).append('\n');
        }
        if (s.getProblemLines() != null && !s.getProblemLines().isEmpty()) {
            m.append("\n경고·실패·차단 테이블\n");
            appendRows(m, s.getProblemLines());
        }
        m.append("\n상세: ").append(link("/ui/history"));
        m.append("\n🕒 ").append(now());
        send(problem ? "WARNING" : "INFO", CAT_BATCH_SUMMARY, "묶음 실행 요약 " + nvl(s.getTitle()), m.toString(),
                null, s.getBatchId(), null, null);
    }

    // ------------------------------------------------------------------ 시나리오 진행

    /** 시나리오 시작 */
    @lombok.Value
    @Builder
    public static class ScenarioStart {
        String groupId;
        String scenarioName;
        String testMode;
        int stepCount;
        int unitCount;
        String executedBy;
    }

    /** 시나리오 단계 1개 결과 (날짜 분할 단계도 1건) */
    @lombok.Value
    @Builder
    public static class ScenarioStep {
        String groupId;
        String scenarioName;
        int seq;
        int stepCount;
        String label;
        String pluginName;
        String tableName;
        String status;             // COMPLETED / PARTIAL / FAILED / BLOCKED / STOPPED (ExecGroupStatus)
        String statusLabel;        // "전체 성공" 등
        int unitTotal;
        int unitSuccess;
        int unitFailed;
        int unitNotRun;
        long processedSum;
        long durationMs;
        String detail;             // 차단 사유 등
        String next;               // "다음: 6단계 게시판 (tb_board)" / "마지막 단계" / "여기서 멈춤 - ..."
    }

    public void scenarioStart(ScenarioStart s) {
        if (!scenarioStepEnabled) {
            return;
        }
        send("INFO", CAT_SCENARIO_STEP, "시나리오 시작 " + nvl(s.getScenarioName()), scenarioStartMessage(s),
                null, s.getGroupId(), null, null);
    }

    public void scenarioStep(ScenarioStep s) {
        if (!scenarioStepEnabled) {
            return;
        }
        String level = "COMPLETED".equals(s.getStatus()) ? "INFO" : ("PARTIAL".equals(s.getStatus()) ? "WARNING" : "ERROR");
        send(level, CAT_SCENARIO_STEP, "시나리오 " + s.getSeq() + "/" + s.getStepCount() + "단계 " + nvl(s.getTableName()) + " - " + nvl(s.getStatusLabel()),
                scenarioStepMessage(s), null, s.getGroupId(), s.getPluginName(), s.getTableName());
    }

    String scenarioStartMessage(ScenarioStart s) {
        StringBuilder m = new StringBuilder("🎬 [시나리오 시작] ").append(nvl(s.getScenarioName())).append("\n\n");
        m.append("모드: ").append(nvl(s.getTestMode())).append('\n');
        m.append("단계 ").append(s.getStepCount()).append(" / 단위 ").append(s.getUnitCount()).append('\n');
        m.append("실행자: ").append(nvl(s.getExecutedBy())).append('\n');
        m.append("그룹: ").append(nvl(s.getGroupId())).append('\n');
        m.append("\n상세: ").append(link("/ui/scenario"));
        m.append("\n🕒 ").append(now());
        return m.toString();
    }

    String scenarioStepMessage(ScenarioStep s) {
        String icon = switch (nvl(s.getStatus())) {
            case "COMPLETED" -> "✅";
            case "PARTIAL" -> "⚠️";
            case "BLOCKED" -> "⛔";
            case "STOPPED" -> "⏹️";
            default -> "❌";
        };
        StringBuilder m = new StringBuilder(icon).append(" [시나리오 ").append(s.getSeq()).append('/').append(s.getStepCount())
                .append("단계] ").append(nvl(s.getScenarioName()));
        if (s.getLabel() != null && !s.getLabel().isBlank()) {
            m.append(" · ").append(s.getLabel());
        }
        m.append("\n\n");
        m.append(nvl(s.getPluginName())).append('.').append(nvl(s.getTableName())).append('\n');
        m.append("결과: ").append(nvl(s.getStatusLabel()));
        if (!"BLOCKED".equals(s.getStatus())) {
            m.append(" · 단위 ").append(s.getUnitSuccess()).append('/').append(s.getUnitTotal());
            if (s.getUnitFailed() + s.getUnitNotRun() > 0) {
                m.append(" (실패 ").append(s.getUnitFailed()).append(" · 미실행 ").append(s.getUnitNotRun()).append(')');
            }
            m.append(" · 처리 ").append(fmt(s.getProcessedSum()));
        }
        m.append(" · 소요 ").append(duration(s.getDurationMs())).append('\n');
        if (s.getDetail() != null && !s.getDetail().isBlank()) {
            m.append("사유: ").append(s.getDetail()).append('\n');
        }
        if (s.getNext() != null) {
            m.append(s.getNext()).append('\n');
        }
        m.append("\n그룹: ").append(nvl(s.getGroupId()));
        m.append("\n🕒 ").append(now());
        return m.toString();
    }

    // ------------------------------------------------------------------ cron 스케줄

    /**
     * 스케줄 등록·일시정지·재개·취소·종료와 주기 건너뜀. 주기 실행의 결과는 테이블 결과 알림(TABLE_RESULT)이,
     * 실행 전 차단은 차단 알림이 따로 보낸다
     */
    public void schedule(String level, String headline, String scheduleId, String pluginName, String tableName, String detail) {
        String icon = "WARNING".equals(level) ? "⚠️" : ("ERROR".equals(level) ? "❌" : "🗓️");
        StringBuilder m = new StringBuilder(icon).append(" [스케줄] ").append(headline).append("\n\n");
        m.append("스케줄: ").append(nvl(scheduleId)).append('\n');
        m.append("대상: ").append(nvl(pluginName)).append('.').append(nvl(tableName)).append('\n');
        if (detail != null && !detail.isBlank()) {
            m.append(detail).append('\n');
        }
        m.append("\n상세: ").append(link("/ui/running"));
        m.append("\n🕒 ").append(now());
        send(level, CAT_SCHEDULE, "스케줄 " + headline + " " + nvl(tableName), m.toString(), null, null, pluginName, tableName);
    }

    /** 운영 기록 정리 결과 (누가 언제 무엇을 몇 건 지웠는지) */
    public void operationCleanup(String target, int days, LocalDateTime cutoff, Map<String, Object> deleted, long total, String requestedBy) {
        StringBuilder m = new StringBuilder("🧹 [운영 기록 정리] ").append(target).append(" ").append(String.format("%,d", total)).append("건 삭제\n\n");
        m.append("기준: ").append(days).append("일 전 (").append(cutoff.withNano(0)).append(") 이전\n");
        m.append("요청자: ").append(nvl(requestedBy)).append('\n');
        deleted.forEach((k, v) -> m.append("· ").append(k).append(' ').append(String.format("%,d", ((Number) v).longValue())).append('\n'));
        m.append("\n상세: ").append(link("/ui/system"));
        m.append("\n🕒 ").append(now());
        send("INFO", CAT_MAINTENANCE, "운영 기록 정리 " + target + " " + total + "건", m.toString(), null, null, null, null);
    }

    /** 스케줄 여러 개에 걸친 알림 (재기동 복원 등) */
    public void scheduleSummary(String level, String headline, String detail) {
        String icon = "WARNING".equals(level) ? "⚠️" : ("ERROR".equals(level) ? "❌" : "🗓️");
        StringBuilder m = new StringBuilder(icon).append(" [스케줄] ").append(headline).append("\n\n");
        if (detail != null && !detail.isBlank()) {
            m.append(detail).append('\n');
        }
        m.append("\n상세: ").append(link("/ui/running"));
        m.append("\n🕒 ").append(now());
        send(level, CAT_SCHEDULE, "스케줄 " + headline, m.toString(), null, null, null, null);
    }

    // ------------------------------------------------------------------ 공통

    private void send(String level, String category, String title, String message,
                      String migrationId, String batchId, String pluginName, String tableName) {
        recorder.record(UiNotification.builder().level(level).category(category).title(title).message(message)
                .migrationId(migrationId).batchId(batchId).pluginName(pluginName).tableName(tableName).build());
        try {
            telegram.sendPrepared(escapeHtml(message));
        } catch (Exception e) {
            log.warn("텔레그램 전송 실패 ({}): {}", category, e.getMessage());
        }
    }

    /** 목록이 길면 상위 N개만 쓰고 "외 N개는 UI 에서 확인" 을 붙인다 (텔레그램 4,096자 제한) */
    private static void appendRows(StringBuilder m, List<String> rows) {
        rows.stream().limit(SUMMARY_TOP_N).forEach(r -> m.append(r).append('\n'));
        if (rows.size() > SUMMARY_TOP_N) {
            m.append("… 외 ").append(rows.size() - SUMMARY_TOP_N).append("개는 UI 에서 확인\n");
        }
    }

    private String link(String path) {
        return uiBaseUrl == null || uiBaseUrl.isBlank() ? "UI " + path : uiBaseUrl.replaceAll("/+$", "") + path;
    }

    static String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String fmt(Long n) {
        return n == null ? "-" : String.format("%,d", n);
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String now() {
        return LocalDateTime.now().format(TIME);
    }

    static String duration(long ms) {
        long s = ms / 1000;
        if (s >= 3600) return String.format("%d시간 %d분 %d초", s / 3600, (s % 3600) / 60, s % 60);
        if (s >= 60) return String.format("%d분 %d초", s / 60, s % 60);
        if (s > 0) return s + "초";
        return ms + "ms";
    }
}
