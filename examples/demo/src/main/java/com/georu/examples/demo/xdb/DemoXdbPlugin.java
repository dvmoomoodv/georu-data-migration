package com.georu.examples.demo.xdb;

import com.georu.core.plugin.GeoruPlugin;
import com.georu.core.plugin.TableCatalog;
import com.georu.core.plugin.TableDef;
import com.georu.core.strategy.TableOptions;
import com.georu.examples.demo.code.processor.TbCodeProcessor;
import com.georu.examples.demo.source.dto.TbCodeSourceDto;
import com.georu.examples.demo.target.dto.TbCodeTargetDto;
import com.georu.examples.demo.xdb.dto.ShopCustomerSourceDto;
import com.georu.examples.demo.xdb.dto.TbCustomerDto;
import com.georu.examples.demo.xdb.dto.TbOrderDto;
import com.georu.examples.demo.xdb.dto.TbProductDto;
import com.georu.examples.demo.xdb.dto.TbStockDto;

import java.util.List;

/**
 * 여러 DB 예제 5대 (손으로 쓴 참고용). 소스 3대 · 타겟 2대의 여섯 조합을 한 번씩 지난다.
 *
 * <pre>
 * 소스  source-m Oracle DEMO (기존)      source-2 PostgreSQL demo_pg      source-3 SQL Server demo_ms
 * 타겟  target-m · target-1 MySQL (기존)  target-2 MariaDB demo_mart
 *
 * Oracle     → MariaDB   tb_code_mart     (데모 코드 소스 SQL 재사용, 스키마 없는 타겟 SQL 재사용)
 * PostgreSQL → MariaDB   tb_customer      (탈퇴 제외 · 등급 코드 변환 · 채번 demo_mart.tb_sequence) · tb_order
 * PostgreSQL → MySQL     tb_order_dw      (같은 소스 SQL + params status=DONE, target-1 tb_order)
 * SQL Server → MariaDB   tb_product       (삭제 제외) · tb_stock
 * SQL Server → MySQL     tb_product_dw    (같은 소스 SQL, target-1 tb_product)
 * Oracle     → MySQL     기존 데모 (demo.code · member · board · stats)
 * </pre>
 *
 * 소스 SQL 은 DB 마다 문법이 다르다: 페이징 (PostgreSQL LIMIT/OFFSET, SQL Server OFFSET … FETCH), 날짜 → 문자 14 (to_char · CONVERT 120).
 * {@code georu.plugins.demo.xdb.enabled} 와 DataSource source-2 · source-3 · target-2 는 DEMO_XDB_ENABLED 로 함께 켠다 (./run-scripts/run-docker.sh xdb).
 * 설명은 안내서 guides/demo.md 8절.
 */
@GeoruPlugin(name = "demo.xdb", description = "여러 DB 예제 5대 - 소스 Oracle · PostgreSQL · SQL Server → 타겟 MySQL · MariaDB")
public class DemoXdbPlugin implements TableCatalog {

    static final String ORDER_SOURCE = "georu.demo.xdb.TbOrderSourceMapper";
    static final String PRODUCT_SOURCE = "georu.demo.xdb.TbProductSourceMapper";

    @Override
    public List<TableDef> tables() {
        return List.of(
                // Oracle → MariaDB: 데모 코드 테이블의 소스 SQL · Processor 를 그대로, 타겟 SQL 은 스키마 이름이 없는 복사본 (demo.multi)
                TableDef.of("tb_code_mart", TbCodeSourceDto.class, TbCodeTargetDto.class).processor(TbCodeProcessor.class)
                        .sourceNamespace("georu.demo.code.TbCodeSourceMapper").target("target-2")
                        .targetNamespace("georu.demo.multi.TbCodeToDwTargetMapper")
                        .options(TableOptions.builder().targetTable("tb_code").build()),
                // PostgreSQL → MariaDB: 새 번호(CUSTOMER_SEQ)는 target-2 자기 DB 의 tb_sequence 에서
                TableDef.of("tb_customer", ShopCustomerSourceDto.class, TbCustomerDto.class).source("source-2").target("target-2")
                        .options(TableOptions.builder().sequenceColumns(List.of("CUSTOMER_SEQ")).sequenceSync(true).build()),
                TableDef.of("tb_order", TbOrderDto.class, TbOrderDto.class).source("source-2").target("target-2"),
                // PostgreSQL → MySQL: 같은 소스 SQL 에 조건만 (params)
                TableDef.of("tb_order_dw", TbOrderDto.class, TbOrderDto.class).sourceNamespace(ORDER_SOURCE).source("source-2").target("target-1")
                        .params("status", "DONE")
                        .options(TableOptions.builder().targetTable("tb_order").build()),
                // SQL Server → MariaDB
                TableDef.of("tb_product", TbProductDto.class, TbProductDto.class).source("source-3").target("target-2"),
                TableDef.of("tb_stock", TbStockDto.class, TbStockDto.class).source("source-3").target("target-2"),
                // SQL Server → MySQL
                TableDef.of("tb_product_dw", TbProductDto.class, TbProductDto.class).sourceNamespace(PRODUCT_SOURCE).source("source-3").target("target-1")
                        .options(TableOptions.builder().targetTable("tb_product").build())
        );
    }
}
