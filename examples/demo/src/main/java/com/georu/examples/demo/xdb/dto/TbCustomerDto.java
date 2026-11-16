package com.georu.examples.demo.xdb.dto;

import com.georu.core.plugin.MigrationId;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 고객 (target-2 MariaDB tb_customer). CUSTOMER_SEQ 는 demo_mart.tb_sequence 에서 채번 (yml georu.datasource.target-2.sequence) */
@Data
@NoArgsConstructor
public class TbCustomerDto {

    private Long customerSeq;

    @MigrationId
    private Integer asisId;

    private String loginId;

    private String customerNm;

    private String gradeCd;

    private String joinDate;
}
