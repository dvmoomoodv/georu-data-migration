package com.georu.examples.demo.xdb.dto;

import com.georu.core.plugin.MigrationId;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 재고 (source-3 SQL Server WMS_STOCK → target-2 MariaDB tb_stock) */
@Data
@NoArgsConstructor
public class TbStockDto {

    @MigrationId
    private Integer stockId;

    private String productCd;

    private String warehouseCd;

    private Integer qty;

    private String updDate;
}
