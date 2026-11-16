package com.georu.examples.demo.multi.dto;

import com.georu.core.plugin.MigrationId;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 회원 포인트 (target-m · target-1 tb_member_point): 본사 회원 + 지사 DB 의 지점·포인트 */
@Data
@NoArgsConstructor
public class TbMemberPointDto {

    @MigrationId
    private Integer memberId;

    private String memberNm;

    private String branchCd;

    private String branchNm;

    private Long pointBal;
}
