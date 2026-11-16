package com.georu.core.internal.sequence;

import com.georu.core.strategy.TableOptions;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 시퀀스 옵션을 JobParameters 로 싣고 내리는 규칙 (Strategy / JobParametersFactory → Processor.beforeStep).
 *
 * <p>Processor 는 싱글턴이라 setter 주입 대신 JobParameters 로 받는다 (testMode 와 같은 방식).
 * 우선순위: API 요청 값 → Strategy 의 {@link TableOptions} → 없음.</p>
 */
public final class SequenceJobParameters {

    /** 채번 컬럼 (쉼표 구분, 예: "TEMPLATE_SEQ,ITEM_SEQ") */
    public static final String PARAM_COLUMNS = "sequenceColumns";
    /** 정책 버전 ("v1"). 없으면 georu.sequence.policy */
    public static final String PARAM_POLICY = "sequencePolicy";
    /** Step 종료 후 채번 컬럼 MAX 동기화 ("true"/"false") */
    public static final String PARAM_SYNC = "sequenceSync";
    /** 채번 없이 Step 종료 후 MAX 동기화만 할 컬럼 (쉼표 구분) */
    public static final String PARAM_SYNC_COLUMNS = "sequenceSyncColumns";
    /** 타겟 테이블명 (TableOptions.targetTable). 없으면 tableName */
    public static final String PARAM_TARGET_TABLE = "targetTable";

    /** ExecutionContext: 사용한 정책 버전 */
    public static final String CTX_POLICY = "seq.policy";
    /** ExecutionContext: 채번 컬럼 목록 (쉼표) */
    public static final String CTX_COLUMNS = "seq.columns";

    private SequenceJobParameters() {
    }

    /** "A, b ,C" → [A, b, C] (공백 제거, 빈 항목 제외, 순서 유지, 중복 제거) */
    public static List<String> parseColumns(String csv) {
        if (csv == null || csv.isBlank()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty() && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    public static String joinColumns(Collection<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return "";
        }
        return String.join(",", columns);
    }

    /** Strategy 의 TableOptions 만으로 파라미터 추가 (API 덮어쓰기 없음) */
    public static void addFromOptions(JobParametersBuilder builder, TableOptions options) {
        add(builder, options, null, null, null);
    }

    /**
     * 실제로 적용할 시퀀스 옵션. Job 파라미터 생성, 실행 전 차단, 실행 전 점검이 모두 이 규칙을 쓴다.
     *
     * <p>우선순위: API 요청 값 → Strategy 의 {@link TableOptions} → 없음.
     * 동기화 전용 컬럼(sequenceSyncColumns)은 API 로 받지 않으므로 항상 옵션 값이다.</p>
     *
     * @param apiColumns API 요청 채번 컬럼 (null/빈 목록이면 옵션 값)
     * @param apiPolicy  API 요청 정책 버전 (null 이면 옵션 값 → 설정 기본값)
     * @param apiSync    API 요청 동기화 여부 (null 이면 옵션 값 → false)
     */
    public static Resolved resolve(TableOptions options, List<String> apiColumns, String apiPolicy, Boolean apiSync) {
        TableOptions opts = options == null ? TableOptions.DEFAULT : options;
        List<String> columns = apiColumns != null && !apiColumns.isEmpty() ? apiColumns : opts.getSequenceColumns();
        List<String> syncColumns = opts.getSequenceSyncColumns();
        columns = columns == null ? List.of() : List.copyOf(columns);
        syncColumns = syncColumns == null ? List.of() : List.copyOf(syncColumns);
        boolean sync = !columns.isEmpty() && (apiSync != null ? apiSync : Boolean.TRUE.equals(opts.getSequenceSync()));
        String policy = SequenceVersions.normalize(apiPolicy != null ? apiPolicy : opts.getSequencePolicy());
        String targetTable = opts.getTargetTable() != null && !opts.getTargetTable().isBlank() ? opts.getTargetTable().trim() : null;
        return new Resolved(columns, syncColumns, sync, policy, targetTable);
    }

    /**
     * 판정 결과.
     *
     * @param columns     채번 컬럼
     * @param syncColumns 동기화 전용 컬럼
     * @param sync        채번 컬럼 MAX 동기화 여부
     * @param policy      요청 정책 버전 (null 이면 설정 기본값)
     * @param targetTable 타겟 테이블명 (null 이면 tableName)
     */
    public record Resolved(List<String> columns, List<String> syncColumns, boolean sync, String policy, String targetTable) {
        /** 채번 또는 동기화 컬럼이 있어 시퀀스 정책이 필요한지 */
        public boolean uses() {
            return !columns.isEmpty() || !syncColumns.isEmpty();
        }

        /** 사람이 읽는 대상 컬럼 목록 (채번 + 동기화 전용) */
        public List<String> allColumns() {
            List<String> out = new ArrayList<>(columns);
            syncColumns.stream().filter(c -> !out.contains(c)).forEach(out::add);
            return out;
        }
    }

    /**
     * 시퀀스 파라미터 추가. 채번·동기화 컬럼이 (API 또는 옵션에) 없으면 targetTable 외에는 아무것도 넣지 않는다.
     * 판정은 {@link #resolve} 와 같다.
     */
    public static void add(JobParametersBuilder builder, TableOptions options,
                           List<String> apiColumns, String apiPolicy, Boolean apiSync) {
        Resolved r = resolve(options, apiColumns, apiPolicy, apiSync);
        if (r.targetTable() != null) {
            builder.addString(PARAM_TARGET_TABLE, r.targetTable(), false);
        }
        if (!r.uses()) {
            return;
        }
        if (!r.columns().isEmpty()) {
            builder.addString(PARAM_COLUMNS, joinColumns(r.columns()), false);
            builder.addString(PARAM_SYNC, Boolean.toString(r.sync()), false);
        }
        if (!r.syncColumns().isEmpty()) {
            builder.addString(PARAM_SYNC_COLUMNS, joinColumns(r.syncColumns()), false);
        }
        if (r.policy() != null) {
            builder.addString(PARAM_POLICY, r.policy(), false);
        }
    }

    public static List<String> columns(JobParameters params) {
        return params == null ? Collections.emptyList() : parseColumns(params.getString(PARAM_COLUMNS));
    }

    public static List<String> syncColumns(JobParameters params) {
        return params == null ? Collections.emptyList() : parseColumns(params.getString(PARAM_SYNC_COLUMNS));
    }

    public static String policy(JobParameters params) {
        return params == null ? null : SequenceVersions.normalize(params.getString(PARAM_POLICY));
    }

    public static boolean sync(JobParameters params) {
        return params != null && Boolean.parseBoolean(params.getString(PARAM_SYNC));
    }

    /** targetTable 파라미터, 없으면 tableName */
    public static String targetTable(JobParameters params) {
        if (params == null) {
            return null;
        }
        String t = params.getString(PARAM_TARGET_TABLE);
        if (t == null || t.isBlank()) {
            t = params.getString("tableName");
        }
        return t == null || t.isBlank() ? null : t.trim();
    }

    /** ExecutionContext 키: seq.{column}.{suffix} */
    public static String ctxKey(String column, String suffix) {
        return "seq." + column + "." + suffix;
    }
}
