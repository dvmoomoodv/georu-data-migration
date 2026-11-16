package com.georu.core.internal.product.notice;

import com.georu.core.operation.mapper.ProductRecordMapper;
import com.georu.core.product.model.JobExt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 묶음 실행(실행 목록·시나리오) 요약. 같은 batch_id 의 migration_job_ext 를 모아 1회 알린다.
 * 묶음 중에는 경고 없는 테이블 완료 알림을 보내지 않으므로, 끝날 때 이 요약이 전체 결과를 대신한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchSummaryService {

    private final ProductRecordMapper recordMapper;
    private final ProductNotifier notifier;

    /** 요약을 만들어 알리고 화면용 집계를 돌려준다 */
    public Map<String, Object> finish(String batchId, String title) {
        return finish(batchId, title, List.of(), 0);
    }

    /**
     * Job 기록에 없는 항목(시나리오의 실행 전 차단·미실행 단계)을 덧붙여 요약한다
     *
     * @param extraProblems 문제 줄 (앞에 붙음)
     * @param extraBlocked  Job 기록 밖에서 차단된 수 (차단 건수·전체에 더함)
     */
    public Map<String, Object> finish(String batchId, String title, List<String> extraProblems, int extraBlocked) {
        List<JobExt> rows = recordMapper.selectByBatch(batchId);
        int completed = 0, warnings = 0, failed = 0, blocked = extraBlocked;
        long source = 0, processed = 0;
        LocalDateTime first = null, last = null;
        List<String> problems = new ArrayList<>(extraProblems);
        for (JobExt e : rows) {
            if (e.getCreatedAt() != null && (first == null || e.getCreatedAt().isBefore(first))) first = e.getCreatedAt();
            LocalDateTime end = e.getUpdatedAt() != null ? e.getUpdatedAt() : e.getCreatedAt();
            if (end != null && (last == null || end.isAfter(last))) last = end;
            source += e.getSourceCount() == null ? 0 : e.getSourceCount();
            processed += e.getProcessedCount() == null ? 0 : e.getProcessedCount();

            if (JobExt.GUARD_BLOCKED.equals(e.getGuardStatus())) {
                blocked++;
                problems.add("⛔ " + e.getTableName() + " : 차단 (" + reasonLabel(e.getBlockedReason()) + ")");
            } else if (e.getJobStatus() != null && !"COMPLETED".equals(e.getJobStatus())) {
                failed++;
                problems.add("❌ " + e.getTableName() + " : " + e.getJobStatus());
            } else if (Boolean.TRUE.equals(e.getWarning())) {
                warnings++;
                completed++;
                String first1 = e.getWarningReasons() == null ? "" : e.getWarningReasons().lines().findFirst().orElse("");
                problems.add("⚠️ " + e.getTableName() + " : " + (first1.length() > 80 ? first1.substring(0, 77) + "..." : first1));
            } else if ("COMPLETED".equals(e.getJobStatus())) {
                completed++;
            }
        }
        long durationMs = first != null && last != null ? Duration.between(first, last).toMillis() : 0;
        notifier.batchSummary(ProductNotifier.BatchSummary.builder()
                .batchId(batchId).title(Objects.requireNonNullElse(title, batchId))
                .total(rows.size() + extraBlocked).completed(completed).warnings(warnings).failed(failed).blocked(blocked)
                .sourceSum(source).processedSum(processed).durationMs(durationMs).problemLines(problems).build());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchId", batchId);
        m.put("total", rows.size() + extraBlocked);
        m.put("completed", completed);
        m.put("warnings", warnings);
        m.put("failed", failed);
        m.put("blocked", blocked);
        m.put("sourceSum", source);
        m.put("processedSum", processed);
        m.put("problems", problems);
        return m;
    }

    static String reasonLabel(String code) {
        if (code == null) return "-";
        return switch (code) {
            case "EXISTING_DATA" -> "기존 데이터";
            case "PRECHECK" -> "실행 전 점검";
            case "INCOMPLETE_CODE" -> "미완 코드";
            case "DEPRECATED_OPTION" -> "지원하지 않는 옵션";
            case "TABLE_ON_HOLD" -> "대기 테이블";
            case "TABLE_EXCLUDED" -> "이관 제외 테이블";
            default -> code;
        };
    }
}
