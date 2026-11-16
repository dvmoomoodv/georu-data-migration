package com.georu.core.reader;

import com.georu.core.config.TestMode;
import com.georu.core.config.TestModeContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reader 기반 클래스의 테스트 모드 읽기 제한 (DB 불필요)
 */
@DisplayName("AbstractPagingItemReader - 테스트 모드 읽기 제한")
class AbstractPagingItemReaderTestModeTest {

    /** 25건 데이터, pageSize 10 인 메모리 Reader */
    static class InMemoryReader extends AbstractPagingItemReader<String, Object> {
        final List<String> data = IntStream.rangeClosed(1, 25).mapToObj(i -> "row-" + i).toList();
        final List<Integer> requestedLimits = new ArrayList<>();

        InMemoryReader() {
            super(new Object(), "MIGRATION", "SOURCE", 10);
        }

        @Override
        protected int getTotalCount() {
            return data.size();
        }

        @Override
        protected List<String> getPageData(int offset, int limit) {
            requestedLimits.add(limit);
            int to = Math.min(offset + limit, data.size());
            return offset >= data.size() ? List.of() : new ArrayList<>(data.subList(offset, to));
        }

        @Override
        protected void logReadItem(String item) {
        }
    }

    private static StepExecution step(String testMode, Long testMaxItems) {
        JobParametersBuilder b = new JobParametersBuilder().addString("tableName", "t");
        if (testMode != null) {
            b.addString(TestModeContext.PARAM_TEST_MODE, testMode);
        }
        if (testMaxItems != null) {
            b.addLong(TestModeContext.PARAM_TEST_MAX_ITEMS, testMaxItems);
        }
        JobParameters params = b.toJobParameters();
        return new StepExecution("step", new JobExecution(1L, params));
    }

    private static int drain(InMemoryReader reader) throws Exception {
        int n = 0;
        while (reader.read() != null) {
            n++;
        }
        return n;
    }

    @Test
    @DisplayName("NORMAL - 전량 읽기, 페이지 limit 은 pageSize")
    void normalReadsAll() throws Exception {
        InMemoryReader r = new InMemoryReader();
        r.beforeStep(step("NORMAL", null));
        assertEquals(25, drain(r));
        assertEquals(TestMode.NORMAL, r.getTestModeContext().mode());
        assertTrue(r.requestedLimits.stream().allMatch(l -> l == 10));
    }

    @Test
    @DisplayName("READER_TEST testMaxItems=3 - 3건만 읽고 종료, 첫 페이지 조회 limit 도 3")
    void readerTestLimits() throws Exception {
        InMemoryReader r = new InMemoryReader();
        StepExecution se = step("READER_TEST", 3L);
        r.beforeStep(se);
        assertEquals(3, drain(r));
        assertEquals(3, r.getReadCount());
        assertEquals(3, r.requestedLimits.get(0));
        assertEquals(1L, se.getExecutionContext().getLong(TestModeContext.CTX_READ_LIMIT_HIT));
        assertEquals("READER_TEST", r.getTestMode());
        assertEquals(3, r.getTestMaxItems());
    }

    @Test
    @DisplayName("READER_TEST testMaxItems 미지정 - 기본 5건")
    void readerTestDefault() throws Exception {
        InMemoryReader r = new InMemoryReader();
        r.beforeStep(step("READER_TEST", null));
        assertEquals(5, drain(r));
    }

    @Test
    @DisplayName("WRITER_TEST testMaxItems=500 - 상한 100 적용 (데이터 25건이면 25건)")
    void writerTestCapped() throws Exception {
        InMemoryReader r = new InMemoryReader();
        r.beforeStep(step("WRITER_TEST", 500L));
        assertEquals(100, r.getTestModeContext().maxItems());
        assertEquals(25, drain(r));
    }

    @Test
    @DisplayName("Step 이 바뀌면 상태가 초기화된다 (싱글톤 재사용)")
    void stateResetsPerStep() throws Exception {
        InMemoryReader r = new InMemoryReader();
        r.beforeStep(step("READER_TEST", 4L));
        assertEquals(4, drain(r));
        r.beforeStep(step("NORMAL", null));
        assertEquals(25, drain(r));
        r.beforeStep(step("PROCESSOR_TEST", null));   // 기본 100 → 전량 25
        assertEquals(25, drain(r));
    }

    @Test
    @DisplayName("알 수 없는 testMode 는 NORMAL 로 동작")
    void unknownModeIsNormal() throws Exception {
        InMemoryReader r = new InMemoryReader();
        r.beforeStep(step("FULL_TEST", 2L));
        assertEquals(TestMode.NORMAL, r.getTestModeContext().mode());
        assertEquals(25, drain(r));
    }

    @Test
    @DisplayName("시작 때 센 전체 건수를 StepExecutionContext 에 남긴다 - 테스트 상한과 무관한 원천 건수 (todo-task 39)")
    void storesReaderTotalInStepContext() throws Exception {
        InMemoryReader r = new InMemoryReader();
        StepExecution se = step("READER_TEST", 5L);
        r.beforeStep(se);
        assertFalse(se.getExecutionContext().containsKey(AbstractPagingItemReader.CTX_READER_TOTAL));   // 첫 read 전에는 없음
        r.read();
        assertEquals(25L, se.getExecutionContext().getLong(AbstractPagingItemReader.CTX_READER_TOTAL));
    }
}
