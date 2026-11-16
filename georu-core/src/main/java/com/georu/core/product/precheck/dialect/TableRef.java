package com.georu.core.product.precheck.dialect;

/** 메타 조회 기준. catalog/schema 는 DB 마다 의미가 다르다 (MySQL=DB명이 catalog, Oracle=소유자가 schema, MSSQL=dbo 등 schema) */
public record TableRef(String catalog, String schema, String table, String note) {

    public TableRef(String catalog, String schema, String table) {
        this(catalog, schema, table, null);
    }
}
