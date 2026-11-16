package com.georu.core.writer;

import com.georu.core.config.TestModeContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Writer 기반 클래스의 테스트 모드 분기 - Writer 미실행 / WRITER_TEST 감사 컬럼 치환 (DB 불필요)
 */
@DisplayName("AbstractMigrationWriter - 테스트 모드 분기")
class AbstractMigrationWriterTestModeTest {

    /** 생성기 산출 Target DTO 와 같은 감사 컬럼 setter 를 가진 테스트 DTO */
    public static class Dto {
        private String id;
        private String inuserid = TestModeContext.AUDIT_USER_MIGRATION;
        private String luserid = TestModeContext.AUDIT_USER_MIGRATION;

        Dto(String id) {
            this.id = id;
        }

        public String getId() { return id; }
        public String getInuserid() { return inuserid; }
        public void setInuserid(String v) { this.inuserid = v; }
        public String getLuserid() { return luserid; }
        public void setLuserid(String v) { this.luserid = v; }
    }

    /** 감사 컬럼이 없는 DTO */
    public static class PlainDto {
        private final String id;

        PlainDto(String id) {
            this.id = id;
        }

        public String getId() { return id; }
    }

    static class RecordingWriter<T> extends AbstractMigrationWriter<T> {
        final List<T> written = new ArrayList<>();

        RecordingWriter() {
            super("MIGRATION", "TARGET");
        }

        @Override
        protected void writeItems(List<? extends T> items) {
            written.addAll(items);
        }
    }

    private static StepExecution step(String testMode) {
        return new StepExecution("step", new JobExecution(1L,
                new JobParametersBuilder().addString(TestModeContext.PARAM_TEST_MODE, testMode).toJobParameters()));
    }

    private static Chunk<Dto> chunk(String... ids) {
        List<Dto> list = new ArrayList<>();
        for (String id : ids) {
            list.add(new Dto(id));
        }
        return new Chunk<>(list);
    }

    @Test
    @DisplayName("NORMAL - 실제 writeItems 호출, 등록자 MIG-DATA 유지")
    void normalWrites() throws Exception {
        RecordingWriter<Dto> w = new RecordingWriter<>();
        w.beforeStep(step("NORMAL"));
        w.write(chunk("a", "b"));
        assertEquals(2, w.written.size());
        assertEquals(TestModeContext.AUDIT_USER_MIGRATION, w.written.get(0).getInuserid());
        assertEquals(2, w.getWriteAttempted());
        assertEquals(0, w.getWriteSkippedByMode());
    }

    @Test
    @DisplayName("READER_TEST / PROCESSOR_TEST - writeItems 미호출, 건너뛴 건수만 집계")
    void readerAndProcessorTestSkipWrite() throws Exception {
        for (String mode : new String[]{"READER_TEST", "PROCESSOR_TEST"}) {
            RecordingWriter<Dto> w = new RecordingWriter<>();
            StepExecution se = step(mode);
            w.beforeStep(se);
            w.write(chunk("a", "b", "c"));
            assertTrue(w.written.isEmpty(), mode + " 는 쓰기 없음");
            assertEquals(3, w.getWriteSkippedByMode());
            w.afterStep(se);
            assertEquals(3L, se.getExecutionContext().getLong(TestModeContext.CTX_WRITE_SKIPPED));
            assertEquals(0L, se.getExecutionContext().getLong(TestModeContext.CTX_WRITE_ATTEMPTED));
        }
    }

    @Test
    @DisplayName("WRITER_TEST - 실제 쓰기 + 등록자 MIG-TEST 치환 (INUSERID, LUSERID)")
    void writerTestWritesWithTestAudit() throws Exception {
        RecordingWriter<Dto> w = new RecordingWriter<>();
        StepExecution se = step("WRITER_TEST");
        w.beforeStep(se);
        w.write(chunk("a", "b"));
        assertEquals(2, w.written.size());
        for (Dto d : w.written) {
            assertEquals(TestModeContext.AUDIT_USER_TEST, d.getInuserid());
            assertEquals(TestModeContext.AUDIT_USER_TEST, d.getLuserid());
        }
        w.afterStep(se);
        assertEquals(2L, se.getExecutionContext().getLong(TestModeContext.CTX_WRITE_ATTEMPTED));
    }

    @Test
    @DisplayName("WRITER_TEST - 감사 컬럼 setter 가 없는 DTO 도 그대로 적재 (예외 없음)")
    void writerTestWithoutAuditSetter() throws Exception {
        RecordingWriter<PlainDto> w = new RecordingWriter<>();
        w.beforeStep(step("WRITER_TEST"));
        w.write(new Chunk<>(List.of(new PlainDto("x"))));
        assertEquals(1, w.written.size());
    }

    @Test
    @DisplayName("빈 청크는 어느 모드에서도 카운터 변화 없음")
    void emptyChunk() throws Exception {
        RecordingWriter<Dto> w = new RecordingWriter<>();
        w.beforeStep(step("WRITER_TEST"));
        w.write(new Chunk<>(List.of()));
        assertEquals(0, w.getWriteAttempted());
    }
}
