package com.georu.examples.demo.source.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Source] TOBE tb_member_login_log : 로그인이력 ← ASIS(Oracle) MEM_LOGIN_HIS : 로그인 이력 (매핑 추정, 미확정)
 * TbMemberLoginLogSourceMapper.selectPageData 결과 (ASIS 값을 TOBE 컬럼명·타입으로 변환한 값)
 * Reader 출력 = Processor 입력. Processor 가 TbMemberLoginLogTargetDto 로 옮긴다.
 * 자동 생성: 코드 생성기
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbMemberLoginLogSourceDto {

    /** LOGIN_LOG_ID BIGINT : 이력ID */
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
