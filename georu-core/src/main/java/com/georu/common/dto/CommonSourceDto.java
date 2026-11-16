package com.georu.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 공통 Source DTO
 *
 * <p>공통 Source Mapper에서 사용하는 파라미터 객체입니다.</p>
 *
 * <h4>주요 기능:</h4>
 * <ul>
 *   <li><strong>테넌트 레벨 필터링</strong>: 5단계 테넌트 레벨 조건</li>
 *   <li><strong>날짜 범위 필터링</strong>: startDate, endDate 조건</li>
 *   <li><strong>커스텀 속성 필터링</strong>: attribute1~5 확장 조건</li>
 *   <li><strong>페이징 지원</strong>: _skiprows, _pagesize 파라미터</li>
 * </ul>
 *
 * <h4>사용 예시:</h4>
 * <pre>
 * // Reader에서 파라미터 객체 생성
 * CommonSourceDto params = CommonSourceDto.builder()
 *     .tenantLevel1("group")
 *     .tenantLevel2("company")
 *     .startDate("2024-01-01")
 *     .endDate("2024-12-31")
 *     .attribute1("GROUP")      // 회사코드
 *     .attribute2("2025-01-01")     // 이관범위 시작일자
 *     .attribute3("2025-12-31")     // 이관범위 종료일자
 *     ._skiprows(0)
 *     ._pagesize(1000)
 *     .build();
 *
 * // Mapper 호출
 * int totalCount = mapper.selectTotalCount(params);
 * List&lt;ActualDto&gt; data = mapper.selectPageData(params);
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommonSourceDto {


    // ========================================
    // 테넌트 레벨 필터링
    // ========================================

    

    /**
     * 테넌트 레벨 1 (회사/그룹)
     */
    private String tenantLevel1;

    /**
     * 테넌트 레벨 2 (사업부/계열사)
     */
    private String tenantLevel2;

    /**
     * 테넌트 레벨 3 (부서/사업장)
     */
    private String tenantLevel3;

    /**
     * 테넌트 레벨 4 (팀/지점)
     */
    private String tenantLevel4;

    /**
     * 테넌트 레벨 5 (개인/세부조직)
     */
    private String tenantLevel5;

    // ========================================
    // 날짜 범위 필터링
    // ========================================

    /**
     * 조회 시작일 (필터링용)
     * 형식: yyyy-MM-dd 또는 yyyy-MM-dd HH:mm:ss
     */
    private String startDate;

    /**
     * 조회 종료일 (필터링용)
     * 형식: yyyy-MM-dd 또는 yyyy-MM-dd HH:mm:ss
     */
    private String endDate;

    // ========================================
    // 커스텀 속성 (attribute1~25)
    // ========================================

    /**
     * 커스텀 속성 1 (회사코드, 필터링 조건 등)
     */
    private String attribute1;

    /**
     * 커스텀 속성 2 (이관범위 시작일자 등)
     */
    private String attribute2;

    /**
     * 커스텀 속성 3 (이관범위 종료일자 등)
     */
    private String attribute3;

    /**
     * 커스텀 속성 4 (추가 필터링 조건)
     */
    private String attribute4;

    /**
     * 커스텀 속성 5 (추가 필터링 조건)
     */
    private String attribute5;

    /**
     * 커스텀 속성 6 (추가 필터링 조건)
     */
    private String attribute6;

    /**
     * 커스텀 속성 7 (추가 필터링 조건)
     */
    private String attribute7;

    /**
     * 커스텀 속성 8 (추가 필터링 조건)
     */
    private String attribute8;

    /**
     * 커스텀 속성 9 (추가 필터링 조건)
     */
    private String attribute9;

    /**
     * 커스텀 속성 10 (추가 필터링 조건)
     */
    private String attribute10;

    /**
     * 커스텀 속성 11 (추가 필터링 조건)
     */
    private String attribute11;

    /**
     * 커스텀 속성 12 (추가 필터링 조건)
     */
    private String attribute12;

    /**
     * 커스텀 속성 13 (추가 필터링 조건)
     */
    private String attribute13;

    /**
     * 커스텀 속성 14 (추가 필터링 조건)
     */
    private String attribute14;

    /**
     * 커스텀 속성 15 (추가 필터링 조건)
     */
    private String attribute15;

    /**
     * 커스텀 속성 16 (추가 필터링 조건)
     */
    private String attribute16;

    /**
     * 커스텀 속성 17 (추가 필터링 조건)
     */
    private String attribute17;

    /**
     * 커스텀 속성 18 (추가 필터링 조건)
     */
    private String attribute18;

    /**
     * 커스텀 속성 19 (추가 필터링 조건)
     */
    private String attribute19;

    /**
     * 커스텀 속성 20 (추가 필터링 조건)
     */
    private String attribute20;

    /**
     * 커스텀 속성 21 (추가 필터링 조건)
     */
    private String attribute21;

    /**
     * 커스텀 속성 22 (추가 필터링 조건)
     */
    private String attribute22;

    /**
     * 커스텀 속성 23 (추가 필터링 조건)
     */
    private String attribute23;

    /**
     * 커스텀 속성 24 (추가 필터링 조건)
     */
    private String attribute24;

    /**
     * 커스텀 속성 25 (추가 필터링 조건)
     */
    private String attribute25;

    /**
     * 테이블별 고정 조회 조건 ({@code TableDef.params(...)}). 소스 SQL 에서 {@code #{params.<키>}} 로 쓴다.
     * 같은 소스 테이블을 조건마다 다른 타겟으로 보낼 때(조건 분기) 쓴다
     */
    private java.util.Map<String, String> params;

    // ========================================
    // 페이징 파라미터
    // ========================================

    /**
     * 페이징 오프셋
     * LIMIT #{_skiprows}, #{_pagesize}
     */
    private Integer _skiprows;

    /**
     * 페이징 사이즈
     * LIMIT #{_skiprows}, #{_pagesize}
     */
    private Integer _pagesize;
}
