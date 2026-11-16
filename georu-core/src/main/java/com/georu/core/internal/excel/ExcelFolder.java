package com.georu.core.internal.excel;

import java.util.Locale;

/** 파일 생애주기 폴더. {@code georu.excel.dir} 아래 소문자 폴더명 */
public enum ExcelFolder {
    PENDING, PROCESSING, COMPLETED, FAILED;

    public String dirName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ExcelFolder of(String name) {
        if (name == null || name.isBlank()) {
            return PENDING;
        }
        return valueOf(name.trim().toUpperCase(Locale.ROOT));
    }
}
