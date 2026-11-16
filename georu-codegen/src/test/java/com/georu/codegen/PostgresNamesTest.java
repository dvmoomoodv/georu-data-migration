package com.georu.codegen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL 소스: 따옴표 없이 만든 이름은 소문자로 저장된다 (TODO 09, 예제 앱 PostgreSQL 16 에서 확인) */
class PostgresNamesTest {

    static final String TOBE = """
            테이블명,테이블 설명,컬럼명,컬럼 설명,타입,길이,PK,Not Null,업무구분,이관대상,원천 테이블
            tb_customer,고객,CUSTOMER_ID,고객ID,INT,,Y,Y,shop,Y,SHOP_CUSTOMER
            tb_customer,,JOINED_AT,가입,VARCHAR,14,,,,,
            tb_customer,,MixedName,혼합,VARCHAR,10,,,,,
            """;
    static final String ASIS = """
            테이블명,테이블 설명,컬럼명,컬럼 설명,타입,길이,PK,Not Null
            SHOP_CUSTOMER,,CUSTOMER_ID,,INTEGER,,Y,Y
            SHOP_CUSTOMER,,JOINED_AT,,TIMESTAMP,,,Y
            SHOP_CUSTOMER,,MixedName,,VARCHAR,10,,
            """;

    static String sql(boolean exact) {
        TableSpecReader reader = new TableSpecReader();
        SpecReadResult asis = reader.readDefinition("asis.csv", ASIS.getBytes(StandardCharsets.UTF_8));
        GenerationResult r = new CodeGenerator().generate(reader.read("tobe.csv", TOBE.getBytes(StandardCharsets.UTF_8)),
                CodegenSettings.of("acme").sourceDialect("postgresql").sourceSchema("public")
                        .sourceCatalog(SourceCatalog.of(asis)).sourceNamesExact(exact));
        return r.files().get("src/main/resources/georu/acme/shop/TbCustomerSourceMapper.xml");
    }

    @Test
    @DisplayName("정의 파일의 대문자 이름은 소문자로 (테이블 · 컬럼 · 정렬키 · 형변환), 대소문자가 섞인 이름은 그대로")
    void fileNamesFolded() {
        assertThat(sql(false))
                .contains("FROM \"public\".\"shop_customer\" A")
                .contains("A.\"customer_id\" AS \"CUSTOMER_ID\"")
                .contains("TO_CHAR(A.\"joined_at\", 'YYYYMMDDHH24MISS') AS \"JOINED_AT\"")
                .contains("A.\"MixedName\" AS \"MixedName\"")
                .contains("ORDER BY A.\"customer_id\"");
    }

    @Test
    @DisplayName("DB 메타에서 온 이름은 그대로 (대문자로 만든 테이블도 맞게)")
    void exactNamesKept() {
        assertThat(sql(true)).contains("FROM \"public\".\"SHOP_CUSTOMER\" A").contains("A.\"CUSTOMER_ID\" AS \"CUSTOMER_ID\"");
    }
}
