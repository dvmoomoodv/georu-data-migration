package com.georu.examples.demo.target.dto;

import com.georu.core.plugin.MigrationId;
import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Target] TOBE(MySQL) tb_member_grade : 회원등급 (업무 02 회원관리)
 * 자동 생성: 코드 생성기 (tobe-table-column.csv)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbMemberGradeTargetDto {

    /** GRADE_CD VARCHAR(10) : 등급코드 */
    @MigrationId
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
