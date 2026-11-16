package com.georu.examples.demo.xdb.dto;

import com.georu.core.plugin.MigrationId;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 고객 (source-2 PostgreSQL shop_customer). 탈퇴 제외, 등급 코드 변환은 SELECT 에서 */
@Data
@NoArgsConstructor
public class ShopCustomerSourceDto {

    @MigrationId
    private Integer asisId;

    private String loginId;

    private String customerNm;

    private String gradeCd;

    private String joinDate;
}
