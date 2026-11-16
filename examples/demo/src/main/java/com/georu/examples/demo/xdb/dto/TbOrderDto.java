package com.georu.examples.demo.xdb.dto;

import com.georu.core.plugin.MigrationId;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 주문 (source-2 PostgreSQL shop_order → target-2 MariaDB tb_order, 완료분은 target-1 MySQL tb_order). 소스·타겟 같은 DTO */
@Data
@NoArgsConstructor
public class TbOrderDto {

    @MigrationId
    private Long orderId;

    private Integer customerId;

    private Long orderAmt;

    private String orderDate;

    private String status;
}
