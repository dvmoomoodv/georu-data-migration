package com.georu.core.internal.product.guard;

import com.georu.core.internal.product.precheck.PrecheckRequest;
import lombok.Builder;
import lombok.Value;

/** 실행 전 확인 입력 (테이블 1개) */
@Value
@Builder
public class GuardContext {
    String migrationId;
    String batchId;
    String pluginName;
    String tableName;
    String testMode;              // NORMAL / WRITER_TEST / ...

    /** API 요청의 ignoreExistingData (null 이면 미지정 → 테이블 기본값) */
    Boolean ignoreExistingData;
    boolean skipPrecheck;
    boolean ignorePrecheckErrors;

    /** 더 이상 쓰지 않는 옵션 (문제 7) */
    Boolean truncateTarget;
    String upsertMode;

    /** 자동 점검의 소스 조회 조건 (테넌트·기간·속성) */
    PrecheckRequest precheckTemplate;

    /** API 요청의 시퀀스 채번 컬럼 (없으면 테이블 설정). 판정은 SequenceJobParameters.resolve 와 같다 */
    java.util.List<String> sequenceColumns;
    /** API 요청의 시퀀스 정책 버전 */
    String sequencePolicy;
    /** 엑셀 이관 (source=EXCEL): 소스 샘플 점검·미완 코드 확인 대신 파일 존재·검증 상태를 확인한다 (core/excel) */
    boolean excelSource;
    String excelFileId;
    String excelOnRowError;
    /** 빠진 행 채우기:"기존 데이터" 로 막지 않는다 (원 실행 확인은 FillMissingService 가 먼저 함) */
    boolean fillMissing;

    public boolean isNormal() {
        return testMode == null || "NORMAL".equalsIgnoreCase(testMode);
    }

    /** 실제로 타겟에 쓰는 모드 (NORMAL, WRITER_TEST) */
    public boolean writesTarget() {
        return isNormal() || "WRITER_TEST".equalsIgnoreCase(testMode);
    }
}
