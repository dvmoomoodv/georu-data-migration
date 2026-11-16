package com.georu.core.internal.product.guard;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;
import java.util.Map;

/** 실행 전 확인 결과 */
@Value
@Builder
public class GuardResult {

    public static final String EXISTING_DATA = "EXISTING_DATA";
    public static final String PRECHECK = "PRECHECK";
    public static final String INCOMPLETE_CODE = "INCOMPLETE_CODE";
    public static final String DEPRECATED_OPTION = "DEPRECATED_OPTION";
    /** 채번 컬럼이 지정됐는데 시퀀스 정책(버전)이 등록되지 않았거나 기능이 꺼져 있음 */
    public static final String SEQUENCE_POLICY = "SEQUENCE_POLICY";
    /** 같은 테이블을 실행 전 점검 등 다른 작업이 쓰고 있음 (TableUsageRegistry) */
    public static final String TABLE_IN_USE = "TABLE_IN_USE";
    /** 엑셀 이관 파일이 없거나 검증을 통과하지 못함 (core/excel) */
    public static final String EXCEL_FILE = "EXCEL_FILE";
    /** 이관 제외 테이블 (TableOptions.state = EXCLUDED). 모든 모드 */
    public static final String TABLE_EXCLUDED = "TABLE_EXCLUDED";
    /** 대기 테이블 (TableOptions.state = HOLD). 모든 모드 */
    public static final String TABLE_ON_HOLD = "TABLE_ON_HOLD";

    boolean allowed;
    /** 차단 사유 코드 (allowed=false 일 때) */
    String blockedReason;
    /** 화면·API 응답에 보여줄 한국어 설명 */
    String message;
    /** 무시하고 진행한 항목 (기존 데이터, 점검 차단) - 완료 판정에서 경고로 남긴다 */
    @Singular
    List<String> ignoredReasons;
    /** 안내 (더 이상 쓰지 않는 옵션 등) */
    @Singular
    List<String> notices;
    /** API 응답 details */
    Map<String, Object> details;
}
