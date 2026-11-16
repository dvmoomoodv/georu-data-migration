package com.georu.core.internal.sequence.support;

import com.georu.core.internal.sequence.SequenceException;
import lombok.Data;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SequenceValueInjector - 컬럼명 → setter, 타입 변환")
class SequenceValueInjectorTest {

    @Data
    static class Dto {
        private Long templateSeq;
        private Integer seq;
        private BigDecimal orderSeq;
        private String code;
        private int plain;
    }

    @Test
    void propertyName_변환() {
        assertEquals("templateSeq", SequenceValueInjector.propertyName("TEMPLATE_SEQ"));
        assertEquals("seq", SequenceValueInjector.propertyName("SEQ"));
        assertEquals("templateSeq", SequenceValueInjector.propertyName("templateSeq"));
        assertEquals("templateSeq", SequenceValueInjector.propertyName("TemplateSeq"));
        assertEquals("orderSeq", SequenceValueInjector.propertyName("order_seq"));
    }

    @Test
    void set_타입별_변환() {
        Dto dto = new Dto();
        SequenceValueInjector.set(dto, "TEMPLATE_SEQ", 10L);
        SequenceValueInjector.set(dto, "SEQ", 20L);
        SequenceValueInjector.set(dto, "ORDER_SEQ", 30L);
        SequenceValueInjector.set(dto, "CODE", 40L);
        SequenceValueInjector.set(dto, "PLAIN", 50L);
        assertEquals(10L, dto.getTemplateSeq());
        assertEquals(20, dto.getSeq());
        assertEquals(BigDecimal.valueOf(30), dto.getOrderSeq());
        assertEquals("40", dto.getCode());
        assertEquals(50, dto.getPlain());
    }

    @Test
    void hasValue_와_missingSetters() {
        Dto dto = new Dto();
        assertFalse(SequenceValueInjector.hasValue(dto, "TEMPLATE_SEQ"));
        dto.setTemplateSeq(1L);
        assertTrue(SequenceValueInjector.hasValue(dto, "TEMPLATE_SEQ"));
        dto.setCode("  ");
        assertFalse(SequenceValueInjector.hasValue(dto, "CODE"), "공백 문자열은 값 없음");
        assertEquals(List.of("NOPE"), SequenceValueInjector.missingSetters(Dto.class, List.of("TEMPLATE_SEQ", "NOPE")));
    }

    @Test
    void setter_없으면_예외() {
        SequenceException ex = assertThrows(SequenceException.class, () -> SequenceValueInjector.set(new Dto(), "NOPE", 1L));
        assertTrue(ex.getMessage().contains("setNope"));
    }

    @Test
    void int_범위_초과() {
        Dto dto = new Dto();
        assertThrows(SequenceException.class, () -> SequenceValueInjector.set(dto, "SEQ", 1L + Integer.MAX_VALUE));
    }
}
