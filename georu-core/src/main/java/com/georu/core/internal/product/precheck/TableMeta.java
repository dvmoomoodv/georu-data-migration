package com.georu.core.internal.product.precheck;

import com.georu.core.product.precheck.ColumnMeta;

import com.georu.core.product.precheck.dialect.DialectRule;
import com.georu.core.product.precheck.dialect.TableRef;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/** 타겟 테이블 메타 정보. found=false 면 "조회 불가(권한 또는 테이블 없음)" */
@Value
@Builder
public class TableMeta {
    TableRef ref;
    boolean found;
    String databaseProduct;
    DialectRule dialect;
    /** 컬럼명(대문자) → 컬럼 */
    Map<String, ColumnMeta> columns;
    /** PK 컬럼명 (KEY_SEQ 순서, 대문자) */
    List<String> primaryKey;
    /** 문자 컬럼 길이 한도 (컬럼명 대문자 → 한도). 메타를 읽을 때 한 번 계산 */
    Map<String, com.georu.core.product.precheck.dialect.LengthLimit> lengthLimits;
    /** 조회 실패 사유 (found=false 일 때) */
    String error;

    public ColumnMeta column(String name) {
        return name == null ? null : columns.get(name.toUpperCase());
    }

    public com.georu.core.product.precheck.dialect.LengthLimit lengthLimit(String column) {
        return lengthLimits == null ? null : lengthLimits.get(column.toUpperCase());
    }
}
