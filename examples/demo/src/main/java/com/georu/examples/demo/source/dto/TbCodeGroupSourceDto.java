package com.georu.examples.demo.source.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Source] TOBE tb_code_group : 코드그룹 ← ASIS(Oracle) CD_GROUP : 코드 그룹 (매핑 추정, 미확정)
 * TbCodeGroupSourceMapper.selectPageData 결과 (ASIS 값을 TOBE 컬럼명·타입으로 변환한 값)
 * Reader 출력 = Processor 입력. Processor 가 TbCodeGroupTargetDto 로 옮긴다.
 * 자동 생성: 코드 생성기
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbCodeGroupSourceDto {

    /** GROUP_CD VARCHAR(20) : 그룹코드 */
    private String groupCd;

    /** GROUP_NM VARCHAR(100) : 그룹명 */
    private String groupNm;

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
