package com.georu.examples.demo.utils;

import com.georu.core.internal.utils.CodeConversionUtils;
import com.georu.examples.demo.common.DemoConvertUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("데모 업체 규칙 - 코드·경로·변환")
class DemoRulesTest {

    @Test
    void codeRules() {
        assertEquals("1", DemoCodeRules.yn("Y")); assertEquals("0", DemoCodeRules.yn("n")); assertEquals("X", DemoCodeRules.yn("X")); assertNull(DemoCodeRules.yn(null));
        assertEquals("1", DemoCodeRules.gender("M")); assertEquals("2", DemoCodeRules.gender("F")); assertEquals("X", DemoCodeRules.gender("X"), "범위 밖은 원본 유지 (오류 데이터가 드러남)");
        assertEquals("ACTIVE", DemoCodeRules.status("A")); assertEquals("INACTIVE", DemoCodeRules.status("I")); assertEquals("DELETED", DemoCodeRules.status("D"));
        // 범용 엔진으로도 같은 결과
        assertEquals("1", CodeConversionUtils.convertCode("TB_BOARD-USE_YN", "Y", DemoCodeRules.INSTANCE));
        assertEquals("2", CodeConversionUtils.convertCode("TB_MEMBER-GENDER", "F", DemoCodeRules.INSTANCE));
        assertEquals("ACTIVE", CodeConversionUtils.convertCode("TB_POST-STATUS", "A", DemoCodeRules.INSTANCE));
        assertEquals("demo", DemoCodeRules.INSTANCE.id());
    }

    @Test
    void pathRules() {
        assertEquals("file/board/2025/03/12.pdf", DemoPathRules.toTobePath("/Upload_Board/2025/03/12.pdf"));
        assertEquals("/Legacy/2019/legacy.pdf", DemoPathRules.toTobePath("/Legacy/2019/legacy.pdf"), "규칙 없는 경로는 원본 유지");
        assertNull(DemoPathRules.toTobePath(null));
    }

    @Test
    void convertUtils() {
        assertEquals("MIG-DATA", DemoConvertUtils.MIG_USER);
        assertEquals(14, DemoConvertUtils.nowYmdHms().length());
        assertEquals("01012345678", DemoConvertUtils.digitsOnly("010-1234-5678"));
        assertEquals(Long.valueOf(42), DemoConvertUtils.toLong("42"));
    }
}
