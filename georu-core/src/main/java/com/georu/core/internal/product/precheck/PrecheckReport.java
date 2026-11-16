package com.georu.core.internal.product.precheck;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 실행 전 점검 결과. 테이블 → 컬럼 → 유형 단위로 묶고 샘플 값은 최대 5개만 남긴다 */
@Data
@Schema(description = "실행 전 점검 결과. 테이블 → 컬럼 → 유형 단위로 묶고 샘플 값은 최대 5개만 남깁니다.")
public class PrecheckReport {

    public static final int MAX_SAMPLES = 5;

    @Schema(description = "점검 실행 ID", example = "PC-20261031114200-a1b2c3")
    private String runId;
    @Schema(description = "점검 상태 (RUNNING / DONE / FAILED)", example = "DONE")
    private String status = "RUNNING";           // RUNNING / DONE / FAILED
    @Schema(description = "플러그인명", example = "hr.member")
    private String pluginName;
    @Schema(description = "실행 시 자동 점검이면 작업 ID")
    private String migrationId;                  // 실행 시 자동 점검이면 작업 ID
    @Schema(description = "점검 시작 시각")
    private LocalDateTime startedAt = LocalDateTime.now();
    @Schema(description = "점검 종료 시각")
    private LocalDateTime finishedAt;
    @Schema(description = "점검 대상 테이블 수", example = "3")
    private int totalTables;
    @Schema(description = "점검을 마친 테이블 수", example = "3")
    private int doneTables;
    @Schema(description = "테이블별 점검 결과")
    private final List<TableReport> tables = new ArrayList<>();
    @Schema(description = "점검 실패 시 오류 메시지 (status=FAILED)")
    private String error;

    public synchronized void add(TableReport t) {
        tables.add(t);
        doneTables++;
    }

    public long countBySeverity(CheckType.Severity s) {
        return tables.stream().flatMap(t -> t.getIssues().stream()).filter(i -> i.getSeverity() == s).count();
    }

    public boolean hasBlock() {
        return countBySeverity(CheckType.Severity.BLOCK) > 0;
    }

    @Data
    @Schema(description = "테이블 단위 실행 전 점검 결과")
    public static class TableReport {
        @Schema(description = "플러그인명", example = "hr.member")
        private String pluginName;
        @Schema(description = "테이블명", example = "tb_member")
        private String tableName;
        @Schema(description = "Writer 가 적재하는 타겟 테이블명")
        private String targetTable;
        @Schema(description = "타겟 DB 종류 (메타데이터 조회 기준)")
        private String dialect;
        @Schema(description = "점검 결과 (OK / WARN / BLOCK). 가장 심각한 문제 기준", example = "OK")
        private String result = "OK";            // OK / WARN / BLOCK
        @Schema(description = "소스 조회 조건 기준 전체 건수. 조회 전이면 -1", example = "125000")
        private long sourceTotal = -1;
        @Schema(description = "샘플로 읽은 소스 건수", example = "1000")
        private int sampled;
        @Schema(description = "Processor 변환을 통과한 건수", example = "998")
        private int processed;
        @Schema(description = "Processor 검증에서 제외된(null 반환) 건수", example = "2")
        private int filtered;
        @Schema(description = "타겟 테이블 기존 데이터 건수 표시값 (\"0\", 천 단위 구분 숫자, \"1건 이상 (건수 조회 시간 초과)\", \"확인 불가\")", example = "0")
        private String existingCount;
        @Schema(description = "비고")
        private String note;
        @JsonIgnore
        private final Map<String, Issue> issueMap = new LinkedHashMap<>();
        /** 접속 기록: DB(TOBE/ASIS) → 쿼리 종류 → 횟수. 테이블 점검이 끝나면 {@link #flushAccess()} 로 DB_ACCESS 한 줄씩 남긴다 */
        @JsonIgnore
        private final Map<String, Map<String, Integer>> access = new LinkedHashMap<>();

        /** 쿼리를 보낸 자리에서 부른다 (읽기 쿼리만 있다. 쓰기 경로가 생기면 kind "쓰기" 로 센다) */
        public synchronized void access(String db, String kind, int n) {
            access.computeIfAbsent(db, k -> new LinkedHashMap<>()).merge(kind, n, Integer::sum);
        }

        /** 접속 기록을 DB 마다 DB_ACCESS 한 줄로. 꺼진 쪽은 "꺼짐 (쿼리 0)" */
        public synchronized void flushAccess(boolean targetOn, boolean sourceOn) {
            for (String db : List.of("TOBE", "ASIS")) {
                Map<String, Integer> m = access.getOrDefault(db, Map.of());
                boolean on = "TOBE".equals(db) ? true : sourceOn;   // TOBE 는 소스만 점검에서도 컬럼 정의를 읽는다
                String body = m.isEmpty() ? (on ? "쿼리 0" : "꺼짐 (쿼리 0)")
                        : m.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).collect(java.util.stream.Collectors.joining(" · "));
                int writes = m.getOrDefault("쓰기", 0);
                issue(CheckType.DB_ACCESS, db, body + (m.containsKey("쓰기") ? "" : " · 쓰기 " + writes), null);
            }
            access.clear();
        }

        /** 같은 테이블·컬럼·유형은 1건으로 묶고 발생 건수와 샘플 값(최대 5개)만 누적 */
        public synchronized void issue(CheckType type, String column, String message, Object sample) {
            String key = type.name() + "|" + (column == null ? "" : column.toUpperCase());
            Issue i = issueMap.computeIfAbsent(key, k -> new Issue(type, column, message));
            i.occurrences++;
            if (sample != null && i.samples.size() < MAX_SAMPLES) {
                String s = String.valueOf(sample);
                i.samples.add(s.length() > 120 ? s.substring(0, 117) + "..." : s);
            }
            if (type.severity() == CheckType.Severity.BLOCK) {
                result = "BLOCK";
            } else if (type.severity() == CheckType.Severity.WARN && !"BLOCK".equals(result)) {
                result = "WARN";
            }
        }

        @Schema(description = "점검 문제 목록 (같은 컬럼·유형은 1건으로 묶음)")
        public Collection<Issue> getIssues() {
            return issueMap.values();
        }

        public long blockCount() {
            return issueMap.values().stream().filter(i -> i.getSeverity() == CheckType.Severity.BLOCK).count();
        }
    }

    @Data
    public static class Issue {
        private final CheckType type;
        private final String column;
        private final String message;
        private int occurrences;
        private final List<String> samples = new ArrayList<>();

        public CheckType.Severity getSeverity() {
            return type.severity();
        }

        public String getLabel() {
            return type.label();
        }
    }
}
