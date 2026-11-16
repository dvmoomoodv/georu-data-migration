package com.georu.core.sequence.model;

import com.georu.core.internal.sequence.SequenceException;
import com.georu.core.sequence.SequenceRunMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("시퀀스 모델 - SequenceKey / SequenceBlock / SequenceStepStats")
class SequenceModelTest {

    @Test
    void key_대소문자_규칙과_원본_유지() {
        SequenceKey k = SequenceKey.of(" fb_propose_template ", "template_seq", KeyCase.UPPER);
        assertEquals("FB_PROPOSE_TEMPLATE", k.getTable());
        assertEquals("TEMPLATE_SEQ", k.getColumn());
        assertEquals("fb_propose_template", k.getTargetTable());
        assertEquals("template_seq", k.getTargetColumn());
        assertEquals("FB_PROPOSE_TEMPLATE.TEMPLATE_SEQ", k.display());
        assertEquals(k, SequenceKey.of("fb_propose_template", "template_seq", KeyCase.UPPER));
        assertEquals("fb_x", SequenceKey.of("FB_X", "C", KeyCase.LOWER).getTable());
        assertEquals("Fb_X", SequenceKey.of("Fb_X", "C", KeyCase.AS_IS).getTable());
        assertThrows(IllegalArgumentException.class, () -> SequenceKey.of("", "C"));
        assertThrows(IllegalArgumentException.class, () -> SequenceKey.of("T", " "));
    }

    @Test
    void block_소비_순서와_소진() {
        SequenceBlock b = new SequenceBlock(11, 13);
        assertEquals(3, b.size());
        assertEquals(10, b.last());
        assertEquals(11, b.next());
        assertEquals(12, b.next());
        assertEquals(1, b.remaining());
        assertEquals(13, b.next());
        assertTrue(b.exhausted());
        assertEquals(3, b.used());
        assertThrows(SequenceException.class, b::next);
    }

    @Test
    void block_빈_구간() {
        SequenceBlock empty = new SequenceBlock(5, 4);
        assertTrue(empty.exhausted());
        assertEquals(0, empty.size());
        assertThrows(IllegalArgumentException.class, () -> new SequenceBlock(5, 3));
    }

    @Test
    void stats_미사용_계산() {
        SequenceKey k = SequenceKey.of("T", "C");
        SequenceStepStats s = new SequenceStepStats(k, "v1", SequenceRunMode.NORMAL, 100, 1050, 1000, 950);
        assertEquals(50, s.unused());
        assertTrue(s.summary().contains("T.C"));
        assertTrue(s.summary().contains("v1"));
    }
}
