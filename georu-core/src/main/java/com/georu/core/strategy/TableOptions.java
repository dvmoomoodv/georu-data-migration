package com.georu.core.strategy;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 테이블별 실행 옵션. 플러그인 Strategy 가 {@link AbstractTableMigrationStrategy#getTableOptions(String)} 를
 * 덮어써서 필요한 테이블만 지정한다 (yml 이 아니라 코드에 둔다).
 *
 * <pre>
 * &#64;Override
 * protected TableOptions getTableOptions(String tableName) {
 *     return switch (tableName) {
 *         case "tb_user_hist" -> TableOptions.builder().countCheck(CountCheck.SKIP).build();   // 1:N 분할
 *         case "fb_propose_template" -> TableOptions.builder()
 *                 .sequenceColumns(List.of("TEMPLATE_SEQ"))                                  // 시퀀스 채번 컬럼
 *                 .sequenceSync(true)                                                          // Step 종료 후 MAX 동기화
 *                 .build();
 *         default -> TableOptions.DEFAULT;
 *     };
 * }
 * </pre>
 *
 * API 요청에 같은 값이 오면 API 값이 우선한다.
 */
@Value
@Builder
public class TableOptions {

    public static final TableOptions DEFAULT = TableOptions.builder().build();

    /** 완료 판정에서 소스·처리 건수를 비교할지. 1:N 분할, N:1 병합 테이블은 SKIP */
    @Builder.Default
    CountCheck countCheck = CountCheck.CHECK;

    /** 기존 데이터 무시 플래그의 테이블 기본값. null 이면 false (API 값이 오면 API 값 우선) */
    Boolean ignoreExistingData;

    /** 타겟 테이블명이 Job 키(tableName)와 다를 때만 지정. null 이면 tableName 을 그대로 쓴다 */
    String targetTable;

    // ========================================
    // 시퀀스 채번 (core.sequence 참조: src/main/java/com/georu/core/sequence/README.md)
    // ========================================

    /** 시퀀스를 채번해 넣을 Target DTO 컬럼 (예: TEMPLATE_SEQ). 비어 있으면 채번하지 않는다 */
    @Builder.Default
    List<String> sequenceColumns = List.of();

    /** Step 종료 후 관리 테이블을 채번 컬럼의 MAX(column) 로 동기화할지. null 이면 false */
    Boolean sequenceSync;

    /** 채번 없이 ASIS 값을 옮기지만 Step 종료 후 관리 테이블을 MAX 로 동기화할 컬럼 (TOBE 앱이 이어서 채번하게) */
    @Builder.Default
    List<String> sequenceSyncColumns = List.of();

    /** 이 테이블만 다른 정책 버전을 쓸 때 ("v1"). null 이면 georu.sequence.policy */
    String sequencePolicy;

    /**
     * 이 테이블의 채번을 할 DataSource (예: "target-m", "sequence-m"). null 이면 타겟의
     * {@code georu.datasource.<타겟>.sequence.datasource}, 그것도 없으면 타겟 자신
     */
    String sequenceDataSource;

    /**
     * N:1 병합 묶음 이름. 같은 타겟 DataSource·타겟 테이블에 적재하는 실행 단위들에 같은 이름을 준다 (예: 본사·지사 → tb_member).
     * 지정하면 (1) 묶음의 다른 단위가 적재한 행만 있을 때는 기존 데이터로 막지 않고, (2) 묶음이 모두 끝나면
     * 소스 합계(소스 - 필터) = 타겟 건수를 확인한다. 같은 타겟 테이블을 쓰는 단위끼리만 묶을 수 있다
     */
    String countGroup;

    // ========================================
    // 이관 상태 (코드로만 바꾼다. API 요청 값으로 바꿀 수 없다)
    // ========================================

    /**
     * 이관 상태. {@link TableState#ACTIVE} 수행(기본), {@link TableState#HOLD} 대기(목록·매핑 현황에 보이지만 실행 불가),
     * {@link TableState#EXCLUDED} 제외(매핑 대상·미완 집계에서 빠지고 실행 불가). 대기·제외는 모든 모드에서 실행을 막고,
     * 시나리오 단계에 있으면 정의 오류다
     */
    @Builder.Default
    TableState state = TableState.ACTIVE;

    /** 대기·제외 사유 (화면 툴팁·차단 메시지에 표시) */
    String stateReason;

    public enum CountCheck { CHECK, SKIP }

    /** 테이블 이관 상태 */
    public enum TableState {
        /** 수행 */
        ACTIVE,
        /** 대기: 목록·매핑 현황에 보이지만 실행 불가 */
        HOLD,
        /** 제외: 매핑 대상에서 빠지고 실행 불가 */
        EXCLUDED;

        /** 화면·메시지용 이름 */
        public String label() {
            return switch (this) {
                case ACTIVE -> "수행";
                case HOLD -> "대기";
                case EXCLUDED -> "이관 제외";
            };
        }
    }

    /** 상태 (null 이면 ACTIVE) */
    public TableState resolvedState() {
        return state == null ? TableState.ACTIVE : state;
    }

    /** 실행할 수 있는 상태인지 (ACTIVE 만) */
    public boolean isRunnable() {
        return resolvedState() == TableState.ACTIVE;
    }

    public boolean hasSequenceColumns() {
        return sequenceColumns != null && !sequenceColumns.isEmpty();
    }

    public boolean hasSequenceSyncColumns() {
        return sequenceSyncColumns != null && !sequenceSyncColumns.isEmpty();
    }

    /** 채번 또는 동기화 중 하나라도 지정됐는지 (시퀀스 정책이 필요한지) */
    public boolean needsSequencePolicy() {
        return hasSequenceColumns() || hasSequenceSyncColumns();
    }
}
