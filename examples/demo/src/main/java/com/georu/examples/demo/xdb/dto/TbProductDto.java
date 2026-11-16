package com.georu.examples.demo.xdb.dto;

import com.georu.core.plugin.MigrationId;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 상품 (source-3 SQL Server WMS_PRODUCT → target-2 MariaDB · target-1 MySQL tb_product). 삭제 제외 */
@Data
@NoArgsConstructor
public class TbProductDto {

    @MigrationId
    private String productCd;

    private String productNm;

    private Long price;

    private String regDate;
}
