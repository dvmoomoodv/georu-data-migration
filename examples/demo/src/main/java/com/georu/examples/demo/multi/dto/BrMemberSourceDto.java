package com.georu.examples.demo.multi.dto;

import com.georu.core.plugin.MigrationId;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 지사 회원 (source-1 BR_MEMBER) */
@Data
@NoArgsConstructor
public class BrMemberSourceDto {

    @MigrationId
    private Integer memberId;

    private String loginId;

    private String memberNm;

    private String email;

    private String phone;

    private String branchCd;

    private String gradeCd;

    private String status;

    private String joinDate;
}
