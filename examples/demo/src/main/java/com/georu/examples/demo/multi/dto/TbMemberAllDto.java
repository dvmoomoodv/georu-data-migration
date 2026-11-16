package com.georu.examples.demo.multi.dto;

import com.georu.core.plugin.MigrationId;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 전체 회원 (target-1 tb_member_all): target-m stg_member_all 을 다시 읽어 복제 */
@Data
@NoArgsConstructor
public class TbMemberAllDto {

    @MigrationId
    private String srcCd;

    @MigrationId
    private Integer memberId;

    private String loginId;

    private String memberNm;

    private String email;

    private String gradeCd;

    private String joinDate;
}
