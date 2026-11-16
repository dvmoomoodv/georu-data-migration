package com.georu.core.product.precheck.dialect;

import java.time.LocalDateTime;

/** 날짜 컬럼 허용 범위. known=false 이면 날짜 범위 점검은 "판단 불가(경고)" */
public record DateRange(boolean known, LocalDateTime min, LocalDateTime max, String label) {

    public static final DateRange UNKNOWN = new DateRange(false, null, null, "알 수 없음");

    public static DateRange of(LocalDateTime min, LocalDateTime max, String label) {
        return new DateRange(true, min, max, label);
    }

    public boolean contains(LocalDateTime v) {
        return !known || v == null || (!v.isBefore(min) && !v.isAfter(max));
    }
}
