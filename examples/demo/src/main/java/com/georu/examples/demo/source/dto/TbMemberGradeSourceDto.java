package com.georu.examples.demo.source.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Source] TOBE tb_member_grade : 회원등급 ← ASIS(Oracle) MEM_GRADE : 회원 등급 (매핑 추정, 미확정)
 * TbMemberGradeSourceMapper.selectPageData 결과 (ASIS 값을 TOBE 컬럼명·타입으로 변환한 값)
 * Reader 출력 = Processor 입력. Processor 가 TbMemberGradeTargetDto 로 옮긴다.
 * 자동 생성: 코드 생성기
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbMemberGradeSourceDto {

    /** GRADE_CD VARCHAR(10) : 등급코드 */
    private String gradeCd;

    /** GRADE_NM VARCHAR(50) : 등급명 */
    private String gradeNm;

    /** MIN_POINT INT : 최소포인트 */
    private Integer minPoint;

    /** DISCOUNT_RATE DECIMAL(5,2) : 할인율 */
    private BigDecimal discountRate;

    /** USE_YN CHAR(1) : 사용여부 */
    private String useYn;

    /** INUSERID VARCHAR(40) : 등록자 */
    private String inuserid;

    /** INDATE VARCHAR(14) : 등록일시 */
    private String indate;

    /** LUSERID VARCHAR(40) : 수정자 */
    private String luserid;

    /** LDATE VARCHAR(14) : 수정일시 */
    private String ldate;
}
