package com.georu.core.internal.product.precheck;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 실행 전 점검 요청 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "실행 전 점검 요청. 소스 샘플을 변환해 타겟 컬럼 정의(길이·NOT NULL·타입)와 대조합니다. 타겟 · 소스 어디에도 쓰지 않습니다 (INSERT 문은 만들기만 하고 실행하지 않음). targetCheck · sourceCheck 로 나눠 돌릴 수 있습니다.")
public class PrecheckRequest {

    @Schema(description = "플러그인명", example = "hr.member", requiredMode = Schema.RequiredMode.REQUIRED)
    private String pluginName;

    @Schema(description = "테이블 목록. 비우면 플러그인의 전체 테이블", example = "[\"tb_member\"]")
    private List<String> tableNames;

    @Schema(description = "샘플 구간 수 (기본 10)", example = "10")
    private Integer segments;

    @Schema(description = "구간당 건수 (기본 100)", example = "100")
    private Integer segmentSize;

    @Schema(description = "동시에 점검할 테이블 수 (기본 4)", example = "4")
    private Integer parallel;

    @Schema(description = "소스 조회 조건 - 테넌트", example = "DEFAULT")
    private String tenantLevel1;
    @Schema(description = "소스 조회 조건 - 테넌트 레벨 2")
    private String tenantLevel2;
    @Schema(description = "소스 조회 조건 - 테넌트 레벨 3")
    private String tenantLevel3;
    @Schema(description = "소스 조회 조건 - 테넌트 레벨 4")
    private String tenantLevel4;
    @Schema(description = "소스 조회 조건 - 테넌트 레벨 5")
    private String tenantLevel5;

    @Schema(description = "소스 조회 조건 - 기간 시작 (YYYY-MM-DD)")
    private String startDate;
    @Schema(description = "소스 조회 조건 - 기간 종료 (YYYY-MM-DD)")
    private String endDate;

    @Schema(description = "소스 조회 조건 - 커스텀 속성 1 (플러그인 Reader 가 해석)")
    private String attribute1;
    @Schema(description = "소스 조회 조건 - 커스텀 속성 2 (플러그인 Reader 가 해석)")
    private String attribute2;
    @Schema(description = "소스 조회 조건 - 커스텀 속성 3 (플러그인 Reader 가 해석)")
    private String attribute3;
    @Schema(description = "소스 조회 조건 - 커스텀 속성 4 (플러그인 Reader 가 해석)")
    private String attribute4;
    @Schema(description = "소스 조회 조건 - 커스텀 속성 5 (플러그인 Reader 가 해석)")
    private String attribute5;

    @Schema(description = "시퀀스 채번 컬럼 (요청한 모든 테이블에 적용). 비우면 테이블 설정(TableOptions.sequenceColumns). "
            + "이관 요청에 sequenceColumns 를 줄 때와 같은 결과를 보려면 같은 값을 넣는다", example = "[\"TEMPLATE_SEQ\"]")
    private List<String> sequenceColumns;

    @Schema(description = "시퀀스 정책 버전. 비우면 테이블 설정 → georu.sequence.policy", example = "v1")
    private String sequencePolicy;

    @Schema(description = "타겟 점검: 타겟 테이블 · 컬럼 정의 · 기존 건수 · INSERT 구조 (기본 true). 소스만 점검해도 값 비교를 위해 컬럼 정의는 읽는다", example = "true")
    private Boolean targetCheck;

    @Schema(description = "소스(ASIS) 점검: 소스 건수 · 샘플 · 변환 · 값 점검 (기본 true). false 면 소스 DB 에 쿼리를 한 건도 보내지 않는다", example = "true")
    private Boolean sourceCheck;

    /** 타겟 점검을 하는지 (null 이면 true) */
    public boolean targetCheckOn() {
        return !Boolean.FALSE.equals(targetCheck);
    }

    /** 소스(ASIS) 점검을 하는지 (null 이면 true) */
    public boolean sourceCheckOn() {
        return !Boolean.FALSE.equals(sourceCheck);
    }

    /** 실행 시 자동 점검이면 작업 ID (API 로 직접 보내지 않음) */
    @Schema(hidden = true)
    private String migrationId;
}
