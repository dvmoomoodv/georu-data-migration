package com.georu.examples.demo.multi.dto;

import com.georu.core.plugin.MigrationId;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 지사 회원 (target-1 tb_br_member). BR_MEMBER_SEQ 는 demo_dw.seq_master 에서 TB_BR_MEMBER / BR_MEMBER_SEQ 로 채번 (이름·열이 다른 관리 테이블, yml georu.datasource.target-1.sequence) */
@Data
@NoArgsConstructor
public class TbBrMemberDto {

    private Long brMemberSeq;

    @MigrationId
    private Integer memberId;

    private String loginId;

    private String memberNm;

    private String branchCd;

    private String gradeCd;

    private String joinDate;
}
