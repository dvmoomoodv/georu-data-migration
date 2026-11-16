package com.georu.core.internal.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.Page;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 로그 화면(/api/status/logs) - logback-spring.xml 파이프 형식 파싱, 파일 끝에서부터 읽기, 파일 선택 */
@DisplayName("LogManagementService - 로그 파일 읽기·파싱")
class LogManagementServiceTest {

    @TempDir
    Path dir;

    private LogManagementService service;

    @BeforeEach
    void setUp() {
        service = new LogManagementService();
        ReflectionTestUtils.setField(service, "logFilePath", dir.toString());
        ReflectionTestUtils.setField(service, "logFileName", "application.log");
    }

    private static String line(int sec, String level, String msg) {
        return String.format("2026-10-16 10:00:%02d.123 | %-5s | [main] | c.b.m.SomeService | %s", sec, level, msg);
    }

    @Test
    void 파이프_형식을_파싱하고_스택트레이스는_앞_로그에_붙인다() {
        List<LogManagementService.LogEntry> entries = service.parseLogLines(List.of(
                line(1, "INFO", "시작"),
                line(2, "ERROR", "실패했습니다"),
                "java.lang.IllegalStateException: boom",
                "\tat com.georu.X.run(X.java:10)",
                line(3, "WARN", "경고")));

        assertThat(entries).hasSize(3);
        LogManagementService.LogEntry err = entries.get(1);
        assertThat(err.getLevel()).isEqualTo("ERROR");
        assertThat(err.getThread()).isEqualTo("main");
        assertThat(err.getLogger()).isEqualTo("c.b.m.SomeService");
        assertThat(err.getMessage()).startsWith("실패했습니다\njava.lang.IllegalStateException: boom\n\tat ");
        assertThat(err.getTimestamp()).isNotNull();
    }

    @Test
    void 형식을_모르는_파일은_줄_그대로_보여준다() {
        List<LogManagementService.LogEntry> entries = service.parseLogLines(List.of("[0.010s][info][gc] Using ZGC", "[0.020s][info][gc] x"));
        assertThat(entries).hasSize(2).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo("RAW"));
    }

    @Test
    void 파일_끝에서부터_최근_N줄을_읽는다_한글_포함() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            lines.add("줄 " + i + " 한글 메시지");
        }
        Path f = dir.resolve("big.log");
        Files.write(f, lines, StandardCharsets.UTF_8);   // 마지막 줄 뒤에도 줄바꿈

        List<String> tail = LogManagementService.readTail(f, 3);
        assertThat(tail).containsExactly("줄 4997 한글 메시지", "줄 4998 한글 메시지", "줄 4999 한글 메시지");

        assertThat(LogManagementService.readTail(f, 100_000)).hasSize(5000);
    }

    @Test
    void 최신_로그가_먼저_오고_레벨_검색_필터가_동작한다() throws Exception {
        Files.write(dir.resolve("application.log"), List.of(
                line(1, "INFO", "첫째 migrationId=A"),
                line(2, "ERROR", "둘째 migrationId=B"),
                line(3, "INFO", "셋째 migrationId=B")), StandardCharsets.UTF_8);

        Page<Map<String, Object>> all = service.getLogs(0, 10, null, null, null);
        assertThat(all.getTotalElements()).isEqualTo(3);
        assertThat(all.getContent().get(0).get("message")).isEqualTo("셋째 migrationId=B");

        assertThat(service.getLogs(0, 10, "ERROR", null, null).getTotalElements()).isEqualTo(1);
        assertThat(service.getLogs(0, 10, null, "migrationid=b", null).getTotalElements()).isEqualTo(2);
    }

    @Test
    void 다른_로그_파일을_고를_수_있고_디렉토리_밖은_거부한다() throws Exception {
        Files.write(dir.resolve("sql.log"), List.of(line(1, "DEBUG", "SELECT 1")), StandardCharsets.UTF_8);
        assertThat(service.getLogs(0, 10, null, null, "sql.log").getContent()).hasSize(1);

        assertThatThrownBy(() -> service.getLogs(0, 10, null, null, "../secret.log")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getLogs(0, 10, null, null, "application.yml")).isInstanceOf(IllegalArgumentException.class);
    }
}
