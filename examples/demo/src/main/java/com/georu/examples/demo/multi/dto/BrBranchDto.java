package com.georu.examples.demo.multi.dto;

import com.georu.core.plugin.MigrationId;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 지점 코드표 (source-1 BR_BRANCH → target-m tb_branch). 소스·타겟 같은 DTO */
@Data
@NoArgsConstructor
public class BrBranchDto {

    @MigrationId
    private String branchCd;

    private String branchNm;

    private String region;

    private String useYn;
}
