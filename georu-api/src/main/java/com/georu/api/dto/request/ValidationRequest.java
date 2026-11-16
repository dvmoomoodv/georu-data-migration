package com.georu.api.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import jakarta.validation.constraints.NotBlank;

/**
 * 🔍 데이터 검증 요청 DTO
 *
 * 📋 기능 설명:
 * - 마이그레이션 전 데이터 품질 검증을 위한 요청 정보
 * - 정합성, 제약조건, 중복 데이터 검증 옵션 제공
 * - 특정 테이블 또는 전체 도메인 검증 지원
 *
 * 🔄 검증 프로세스:
 * 1. 플러그인 존재 여부 확인
 * 2. 테넌트 계층 구조 검증
 * 3. 선택된 검증 옵션에 따른 데이터 품질 검사
 * 4. 검증 결과 반환 (성공/실패 및 상세 오류 정보)
 *
 * ⚠️ 유의사항:
 * - 검증은 실제 마이그레이션을 수행하지 않음 (읽기 전용)
 * - 대용량 데이터의 경우 검증 시간이 오래 걸릴 수 있음
 * - tables 파라미터가 null이면 도메인 전체 테이블 검증
 */
@Data
public class ValidationRequest {

    /**
     * 검증할 도메인 (플러그인명)
     * 예: approval.common, board.standard, common.code
     */
    @NotBlank(message = "도메인은 필수입니다")
    @Schema(description = "도메인", example = "approval.common")
    @JsonProperty(required = true)
    private String domain;

    // ========================================
    // 테넌트 계층 구조 (5단계) - 필수 항목
    // ========================================

    /**
     * 그룹 레벨 (1단계) - 최상위 조직
     * 예: GROUP (그룹사)
     */
    @Schema(description = "그룹 레벨", example = "GROUP")
    @NotBlank(message = "그룹 레벨은 필수입니다")
    @JsonProperty(required = true)
    private String tenantLevel1;

    /**
     * 계열사 레벨 (2단계) - 그룹 내 계열사
     * 예: AA (계열사 A), AB (계열사 B)
     */
    @Schema(description = "계열사 레벨", example = "AA")
    @NotBlank(message = "계열사 레벨은 필수입니다")
    @JsonProperty(required = true)
    private String tenantLevel2;

    /**
     * 사업부 레벨 (3단계) - 계열사 내 사업부
     * 예: HEADQUATERS (본사), FACTORY (공장)
     */
    @Schema(description = "사업부 레벨", example = "HEADQUATERS")
    @NotBlank(message = "사업부 레벨은 필수입니다")
    @JsonProperty(required = true)
    private String tenantLevel3;

    /**
     * 부서 레벨 (4단계) - 사업부 내 부서
     * 예: DEPARTMENTS (부서), DIVISIONS (사업부)
     */
    @Schema(description = "부서 레벨", example = "DEPARTMENTS")
    @NotBlank(message = "부서 레벨은 필수입니다")
    @JsonProperty(required = true)
    private String tenantLevel4;

    /**
     * 팀 레벨 (5단계) - 부서 내 팀
     * 예: TEAMS (팀), UNITS (유닛)
     */
    @Schema(description = "팀 레벨", example = "TEAMS")
    @NotBlank(message = "팀 레벨은 필수입니다")
    @JsonProperty(required = true)
    private String tenantLevel5;

    // ========================================
    // 검증 옵션 설정
    // ========================================

    /**
     * 정합성 검증 여부
     *
     * 🔍 검증 내용:
     * - 데이터 타입, 길이, 형식 등 기본 정합성 검사
     * - 필수 필드 누락 여부 확인
     * - 소스와 타겟 간 레코드 수 일치성 확인
     * - 합계값, 평균값, 최대/최소값 비교
     *
     * ⚠️ 주의사항:
     * - 대용량 테이블의 경우 검증 시간이 오래 걸릴 수 있음 (수십 분)
     * - 실시간 변경되는 데이터의 경우 정확한 비교 어려움
     *
     * 💡 권장 사용 시나리오:
     * - 마이그레이션 전 데이터 품질 사전 확인
     * - 마이그레이션 후 결과 검증
     * - 정기적인 데이터 무결성 점검
     */
    @Schema(description = "정합성 검증 여부 - 데이터 타입, 길이, 형식 등 기본 정합성 검사",
            example = "true",
            defaultValue = "true")
    private Boolean checkIntegrity = true;

    /**
     * 제약조건 검증 여부
     *
     * 🔍 검증 내용:
     * - Primary Key 중복 및 NULL 값 검사
     * - Foreign Key 참조 무결성 확인
     * - Unique 제약조건 위반 검사
     * - NOT NULL 제약조건 확인
     * - CHECK 제약조건 검증
     *
     * ⚠️ 주의사항:
     * - 제약조건 위반 데이터 발견 시 마이그레이션 실패 가능성 높음
     * - 복잡한 제약조건의 경우 검증 시간 증가
     * - 외래키 참조 테이블이 많을 경우 성능 저하
     *
     * 💡 권장 사용 시나리오:
     * - 마이그레이션 전 데이터 정합성 사전 확인 (필수)
     * - 데이터 품질 개선 작업 전 현황 파악
     * - 시스템 간 데이터 호환성 검증
     */
    @Schema(description = "제약조건 검증 여부 - PK, FK, Unique, NOT NULL 등 제약조건 검사",
            example = "true",
            defaultValue = "true")
    private Boolean checkConstraints = true;

    /**
     * 중복 검증 여부
     *
     * 🔍 검증 내용:
     * - Primary Key 기준 중복 레코드 검사
     * - 비즈니스 키 기준 중복성 확인
     * - 동일한 내용을 가진 레코드 식별
     * - 중복 패턴 분석 및 상세 리포트 제공
     *
     * ⚠️ 주의사항:
     * - 중복 기준 정의가 명확해야 함 (플러그인별 설정)
     * - 대용량 데이터에서 성능 이슈 발생 가능
     * - 메모리 사용량 증가 (해시 테이블 사용)
     *
     * 💡 권장 사용 시나리오:
     * - 데이터 정제 작업 전 중복 현황 파악
     * - 마이그레이션 전 중복 데이터 사전 제거
     * - 정기적인 데이터 품질 모니터링
     */
    @Schema(description = "중복 검증 여부 - 동일한 키 값을 가진 중복 레코드 검사",
            example = "true",
            defaultValue = "true")
    private Boolean checkDuplicates = true;

    /**
     * 검증할 테이블 목록 (선택사항)
     *
     * 🔍 동작 방식:
     * - null 또는 빈 배열: 도메인 전체 테이블 검증 (기본값)
     * - 배열 지정: 지정된 테이블만 선택적 검증
     * - 테이블명은 플러그인에 정의된 정확한 이름 사용
     *
     * 📋 테이블명 예시:
     * - approval.common: ["appr_master", "appr_search", "appr_file"]
     * - board.standard: ["board_post", "board_reply", "board_attach"]
     * - common.code: ["common_code", "code_group"]
     *
     * ⚠️ 주의사항:
     * - 존재하지 않는 테이블명 지정 시 검증 실패
     * - 테이블 간 의존성이 있는 경우 관련 테이블도 함께 검증 권장
     * - 대소문자 구분하여 정확한 테이블명 입력 필요
     *
     * 💡 사용 시나리오:
     * - 특정 문제 테이블만 집중 검증
     * - 개발/테스트 단계에서 일부 테이블만 빠른 검증
     * - 대용량 도메인에서 단계별 검증
     */
    @Schema(description = "검증할 테이블 목록 (선택사항) - null이면 전체 테이블 검증",
            example = "[\"appr_master\", \"appr_search\"]",
            nullable = true)
    private String[] tables;

    /**
     * 전체 테넌트 ID 생성 (점으로 구분)
     */
    public String getFullTenantId() {
        if (tenantLevel1 == null || tenantLevel2 == null || tenantLevel3 == null ||
                tenantLevel4 == null || tenantLevel5 == null) {
            return null;
        }
        return String.join(".", tenantLevel1, tenantLevel2, tenantLevel3, tenantLevel4, tenantLevel5);
    }

    /**
     * 전체 테넌트 ID를 개별 레벨로 분리 설정
     */
    public void setFullTenantId(String fullTenantId) {
        if (fullTenantId == null || fullTenantId.trim().isEmpty()) {
            return;
        }

        String[] levels = fullTenantId.split("\\.");
        if (levels.length >= 1) tenantLevel1 = levels[0];
        if (levels.length >= 2) tenantLevel2 = levels[1];
        if (levels.length >= 3) tenantLevel3 = levels[2];
        if (levels.length >= 4) tenantLevel4 = levels[3];
        if (levels.length >= 5) tenantLevel5 = levels[4];
    }
}
