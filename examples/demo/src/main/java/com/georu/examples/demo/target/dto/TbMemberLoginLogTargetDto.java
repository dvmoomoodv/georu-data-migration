package com.georu.examples.demo.target.dto;

import com.georu.core.plugin.MigrationId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Target] TOBE(MySQL) tb_member_login_log : 로그인이력 (업무 02 회원관리)
 * 자동 생성: 코드 생성기 (tobe-table-column.csv)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbMemberLoginLogTargetDto {

    /** LOGIN_LOG_ID BIGINT : 이력ID */
    @MigrationId
    private Long loginLogId;

    /** ASIS_ID BIGINT : ASIS이력ID */
    private Long asisId;

    /** MEMBER_ID INT : 회원ID */
    private Integer memberId;

    /** LOGIN_DATE VARCHAR(14) : 로그인일시 */
    private String loginDate;

    /** LOGIN_IP VARCHAR(45) : IP */
    private String loginIp;

    /** DEVICE_CD VARCHAR(10) : 기기 */
    private String deviceCd;

    /** RESULT_CD VARCHAR(10) : 결과 */
    private String resultCd;

    /** FAIL_REASON VARCHAR(200) : 실패사유 */
    private String failReason;

    /** INDATE VARCHAR(14) : 등록일시 */
    private String indate;
}
