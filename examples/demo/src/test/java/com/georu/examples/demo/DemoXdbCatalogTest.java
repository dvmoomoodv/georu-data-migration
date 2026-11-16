package com.georu.examples.demo;

import com.georu.core.plugin.TableDef;
import com.georu.examples.demo.xdb.DemoXdbPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("데모 여러 DB 5대 카탈로그 - 소스 3 · 타겟 2 의 조합과 DataSource 배정")
class DemoXdbCatalogTest {

    private final Map<String, TableDef> units = new DemoXdbPlugin().tables().stream()
            .collect(Collectors.toMap(TableDef::table, Function.identity()));

    @Test
    void sevenUnits() {
        assertThat(units).containsOnlyKeys("tb_code_mart", "tb_customer", "tb_order", "tb_order_dw", "tb_product", "tb_product_dw", "tb_stock");
    }

    @Test
    @DisplayName("소스 · 타겟 배정: Oracle(기본) · PostgreSQL(source-2) · SQL Server(source-3) → MySQL(target-1) · MariaDB(target-2)")
    void dataSources() {
        assertThat(units.get("tb_code_mart").source()).isNull();          // 플러그인 기본 source-m (Oracle)
        assertThat(units.get("tb_code_mart").target()).isEqualTo("target-2");
        for (String t : new String[]{"tb_customer", "tb_order", "tb_order_dw"}) {
            assertThat(units.get(t).source()).as(t).isEqualTo("source-2");
        }
        for (String t : new String[]{"tb_product", "tb_product_dw", "tb_stock"}) {
            assertThat(units.get(t).source()).as(t).isEqualTo("source-3");
        }
        assertThat(units.get("tb_order_dw").target()).isEqualTo("target-1");
        assertThat(units.get("tb_product_dw").target()).isEqualTo("target-1");
        assertThat(units.get("tb_order_dw").params()).containsEntry("status", "DONE");
        assertThat(units.get("tb_customer").options().getSequenceColumns()).containsExactly("CUSTOMER_SEQ");
        assertThat(units.get("tb_order_dw").options().getTargetTable()).isEqualTo("tb_order");
        assertThat(units.get("tb_product_dw").options().getTargetTable()).isEqualTo("tb_product");
    }
}
