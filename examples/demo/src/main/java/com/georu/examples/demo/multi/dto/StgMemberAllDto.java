package com.georu.examples.demo.multi.dto;

import com.georu.core.plugin.MigrationId;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 회원 합치기 스테이징 (target-m stg_member_all). SRC_CD 는 단위마다 타겟 XML 이 넣는다 (HQ/BR). tb_member_all 의 소스로도 쓴다 */
@Data
@NoArgsConstructor
public class StgMemberAllDto {

    @MigrationId
    private String srcCd;

    @MigrationId
    private Integer memberId;

    private String loginId;

    private String memberNm;

    private String email;

    private String phone;

    private String gradeCd;

    private String status;

    private String joinDate;
}
