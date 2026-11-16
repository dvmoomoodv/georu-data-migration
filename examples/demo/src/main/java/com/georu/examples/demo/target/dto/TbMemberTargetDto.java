package com.georu.examples.demo.target.dto;

import com.georu.core.plugin.MigrationId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Target] TOBE(MySQL) tb_member : 회원 (업무 02 회원관리)
 * 자동 생성: 코드 생성기 (tobe-table-column.csv)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbMemberTargetDto {

    /** MEMBER_ID INT : 회원ID */
    @MigrationId
    private Integer memberId;

    /** LOGIN_ID VARCHAR(50) : 로그인ID */
    private String loginId;

    /** MEMBER_NM VARCHAR(50) : 회원명 */
    private String memberNm;

    /** PASSWORD_HASH VARCHAR(128) : 비밀번호해시 */
    private String passwordHash;

    /** GENDER CHAR(1) : 성별 */
    private String gender;

    /** BIRTH_YMD VARCHAR(8) : 생년월일 */
    private String birthYmd;

    /** EMAIL VARCHAR(100) : 이메일 */
    private String email;

    /** PHONE VARCHAR(20) : 전화 */
    private String phone;

    /** GRADE_CD VARCHAR(10) : 등급코드 */
    private String gradeCd;

    /** STATUS VARCHAR(10) : 상태 */
    private String status;

    /** JOIN_DATE VARCHAR(14) : 가입일시 */
    private String joinDate;

    /** LAST_LOGIN_DATE VARCHAR(14) : 최근로그인 */
    private String lastLoginDate;

    /** POINT INT : 포인트 */
    private Integer point;

    /** INUSERID VARCHAR(40) : 등록자 */
    private String inuserid;

    /** INDATE VARCHAR(14) : 등록일시 */
    private String indate;

    /** LUSERID VARCHAR(40) : 수정자 */
    private String luserid;

    /** LDATE VARCHAR(14) : 수정일시 */
    private String ldate;
}
