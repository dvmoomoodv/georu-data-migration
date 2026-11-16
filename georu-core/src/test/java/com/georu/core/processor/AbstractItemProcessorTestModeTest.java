package com.georu.core.processor;

import com.georu.core.config.TestModeContext;
import com.georu.core.utils.ValidationUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Processor 기반 클래스의 테스트 모드 분기 (DB 불필요)
 */
@DisplayName("AbstractItemProcessor - 테스트 모드 분기")
class AbstractItemProcessorTestModeTest {

    /** "bad" 로 시작하는 입력은 변환 예외 */
    static class UpperProcessor extends AbstractItemProcessor<String, String> {
        final AtomicInteger transformCalls = new AtomicInteger();

        UpperProcessor() {
            super("MIGRATION", "TARGET");
        }

        @Override
        protected ValidationUtils.ValidationResult validateItem(String item) {
            return ValidationUtils.ValidationResult.success();
        }

        @Override
        protected String transformItem(String item) {
            transformCalls.incrementAndGet();
            if (item.startsWith("bad")) {
                throw new IllegalStateException("변환 실패: " + item);
            }
            return item.toUpperCase();
        }

        @Override
        protected Object getItemId(String item) {
            return item;
        }

        @Override
        protected void setUpdateTimestamp(String item) {
        }
    }

    private static StepExecution step(String testMode) {
        return new StepExecution("step", new JobExecution(1L,
                new JobParametersBuilder().addString(TestModeContext.PARAM_TEST_MODE, testMode).toJobParameters()));
    }

    @Test
    @DisplayName("NORMAL - 변환 수행, 오류는 그대로 전파")
    void normalPropagatesError() throws Exception {
        UpperProcessor p = new UpperProcessor();
        p.beforeStep(step("NORMAL"));
        assertEquals("ABC", p.process("abc"));
        assertThrows(IllegalStateException.class, () -> p.process("bad-1"));
        assertEquals(1, p.getProcessedCount());
    }

    @Test
    @DisplayName("READER_TEST - 변환하지 않고 null (필터)")
    void readerTestSkipsTransform() throws Exception {
        UpperProcessor p = new UpperProcessor();
        p.beforeStep(step("READER_TEST"));
        assertNull(p.process("abc"));
        assertNull(p.process("bad-1"));
        assertEquals(0, p.transformCalls.get());
    }

    @Test
    @DisplayName("PROCESSOR_TEST - 오류는 집계 후 null, 정상 건은 변환 결과 반환, afterStep 에 카운터 기록")
    void processorTestCollectsErrors() throws Exception {
        UpperProcessor p = new UpperProcessor();
        StepExecution se = step("PROCESSOR_TEST");
        p.beforeStep(se);
        assertEquals("ABC", p.process("abc"));
        assertNull(p.process("bad-1"));
        assertNull(p.process("bad-2"));
        assertEquals("XYZ", p.process("xyz"));
        assertEquals(2, p.getProcessedCount());
        assertEquals(2, p.getProcessErrorCount());

        p.afterStep(se);
        assertEquals(2L, se.getExecutionContext().getLong(TestModeContext.CTX_PROCESSED));
        assertEquals(2L, se.getExecutionContext().getLong(TestModeContext.CTX_PROCESS_ERRORS));
    }

    @Test
    @DisplayName("WRITER_TEST - 변환은 NORMAL 과 동일 (오류 전파)")
    void writerTestTransformsNormally() throws Exception {
        UpperProcessor p = new UpperProcessor();
        p.beforeStep(step("WRITER_TEST"));
        assertEquals("ABC", p.process("abc"));
        assertThrows(IllegalStateException.class, () -> p.process("bad-1"));
    }

    @Test
    @DisplayName("Step 마다 카운터 초기화")
    void countersResetPerStep() throws Exception {
        UpperProcessor p = new UpperProcessor();
        p.beforeStep(step("PROCESSOR_TEST"));
        p.process("bad-1");
        assertEquals(1, p.getProcessErrorCount());
        p.beforeStep(step("PROCESSOR_TEST"));
        assertEquals(0, p.getProcessErrorCount());
    }
}
