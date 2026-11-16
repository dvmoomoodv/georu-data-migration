package com.georu.core.internal.excel;

import java.util.Map;

/**
 * 엑셀/CSV 데이터 1행. {@code rowNum} 은 사람이 보는 행 번호(헤더가 1행, 첫 데이터가 2행).
 * {@code values} 는 헤더(대문자, 공백 제거) → 셀 문자열. 빈 셀은 들어 있지 않다.
 */
public record ExcelRow(int rowNum, Map<String, String> values) {

    public String get(String column) {
        return column == null ? null : values.get(column.trim().toUpperCase(java.util.Locale.ROOT));
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}
