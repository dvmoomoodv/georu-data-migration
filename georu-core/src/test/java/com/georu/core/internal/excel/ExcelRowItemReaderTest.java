package com.georu.core.internal.excel;

import com.georu.core.config.TestModeContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ExcelRowItemReader - 행 읽기·테스트 모드 제한·경로 지연 해석")
class ExcelRowItemReaderTest {

    @TempDir
    Path dir;

    private StepExecution step(String testMode, Long max) {
        JobParametersBuilder b = new JobParametersBuilder().addString("testMode", testMode);
        if (max != null) {
            b.addLong("testMaxItems", max);
        }
        return new StepExecution("s", new JobExecution(1L, b.toJobParameters()));
    }

    @Test
    void readsAll() throws Exception {
        Path f = dir.resolve("a.csv");
        Files.writeString(f, "CODE\nA\nB\nC\n", StandardCharsets.UTF_8);
        ExcelRowItemReader r = new ExcelRowItemReader(() -> f, "Excel(t)");
        r.beforeStep(step("NORMAL", null));
        assertEquals("A", r.read().get("CODE"));
        assertEquals("B", r.read().get("CODE"));
        assertEquals("C", r.read().get("CODE"));
        assertNull(r.read());
        assertNull(r.read(), "끝난 뒤에도 null");
        assertEquals(3, r.getReadCount());
    }

    @Test
    void 파일_대장의_행_수를_진행률_전체_건수로_남긴다() throws Exception {
        Path f = dir.resolve("c.csv");
        Files.writeString(f, "CODE\nA\n", StandardCharsets.UTF_8);
        StepExecution se = step("NORMAL", null);
        new ExcelRowItemReader(() -> f, "Excel(t)", () -> 1234).beforeStep(se);
        assertEquals(1234L, se.getExecutionContext().getLong(com.georu.core.reader.AbstractPagingItemReader.CTX_READER_TOTAL));

        StepExecution unknown = step("NORMAL", null);
        new ExcelRowItemReader(() -> f, "Excel(t)", () -> { throw new IllegalStateException("대장 없음"); }).beforeStep(unknown);
        assertFalse(unknown.getExecutionContext().containsKey(com.georu.core.reader.AbstractPagingItemReader.CTX_READER_TOTAL));
    }

    @Test
    void readerTestLimit() throws Exception {
        Path f = dir.resolve("b.csv");
        Files.writeString(f, "CODE\nA\nB\nC\n", StandardCharsets.UTF_8);
        ExcelRowItemReader r = new ExcelRowItemReader(() -> f, "Excel(t)");
        StepExecution se = step("READER_TEST", 2L);
        r.beforeStep(se);
        assertNotNull(r.read());
        assertNotNull(r.read());
        assertNull(r.read(), "2건에서 멈춘다");
        assertEquals(1L, TestModeContext.getLong(se, TestModeContext.CTX_READ_LIMIT_HIT));
    }

    @Test
    void lazyPath() throws IOException {
        Path moved = dir.resolve("processing").resolve("c.csv");
        Files.createDirectories(moved.getParent());
        Files.writeString(moved, "CODE\nA\n", StandardCharsets.UTF_8);
        int[] calls = {0};
        ExcelRowItemReader r = new ExcelRowItemReader(() -> { calls[0]++; return moved; }, "Excel(t)");
        assertEquals(0, calls[0], "생성 시점에는 경로를 묻지 않는다");
        r.beforeStep(step("NORMAL", null));
        assertThrows(Exception.class, () -> new ExcelRowItemReader(() -> dir.resolve("none.csv"), "x").read());
    }
}
