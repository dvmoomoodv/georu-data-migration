package com.georu.examples.demo.target.dto;

import com.georu.core.plugin.MigrationId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Target] TOBE(MySQL) tb_code : 코드 (업무 01 코드관리)
 * 자동 생성: 코드 생성기 (tobe-table-column.csv)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbCodeTargetDto {

    /** GROUP_CD VARCHAR(20) : 그룹코드 */
    @MigrationId
    private String groupCd;

    /** CODE VARCHAR(20) : 코드 */
    private String code;

    /** CODE_NM VARCHAR(100) : 코드명 */
    private String codeNm;

    /** CODE_DESC VARCHAR(500) : 설명 */
    private String codeDesc;

    /** USE_YN CHAR(1) : 사용여부 */
    private String useYn;

    /** SORT_NO INT : 정렬순서 */
    private Integer sortNo;

    /** INUSERID VARCHAR(40) : 등록자 */
    private String inuserid;

    /** INDATE VARCHAR(14) : 등록일시 */
    private String indate;

    /** LUSERID VARCHAR(40) : 수정자 */
    private String luserid;

    /** LDATE VARCHAR(14) : 수정일시 */
    private String ldate;
}
