package com.georu.core.internal.sequence.support;

import com.georu.core.internal.sequence.SequenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IdentifierValidator - ${} 바인딩 식별자 검증")
class IdentifierValidatorTest {

    @Test
    void 허용_식별자() {
        assertEquals("mig_sequence", IdentifierValidator.requireTable("t", " mig_sequence "));
        assertEquals("demo.mig_sequence", IdentifierValidator.requireTable("t", "demo.mig_sequence"));
        assertEquals("SEQ", IdentifierValidator.requireColumn("c", "SEQ"));
        assertEquals("_x1", IdentifierValidator.requireColumn("c", "_x1"));
    }

    @Test
    void 거부_식별자() {
        assertThrows(SequenceException.class, () -> IdentifierValidator.requireTable("t", "mig_sequence; DROP TABLE x"));
        assertThrows(SequenceException.class, () -> IdentifierValidator.requireTable("t", "a.b.c"));
        assertThrows(SequenceException.class, () -> IdentifierValidator.requireColumn("c", "SEQ VAL"));
        assertThrows(SequenceException.class, () -> IdentifierValidator.requireColumn("c", "1SEQ"));
        assertThrows(SequenceException.class, () -> IdentifierValidator.requireColumn("c", null));
        assertThrows(SequenceException.class, () -> IdentifierValidator.requireColumn("c", "a.b"));
        assertThrows(SequenceException.class, () -> IdentifierValidator.requireColumn("c", "`SEQ`"));
    }
}
