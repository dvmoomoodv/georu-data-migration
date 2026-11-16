package com.georu.core.internal.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 로그 관리 서비스
 * 
 * <p>시스템 로그 파일을 읽어서 로그 정보를 제공하는 서비스입니다.</p>
 * 
 * <h3>주요 기능:</h3>
 * <ul>
 *   <li>실제 로그 파일 읽기 및 파싱</li>
 *   <li>로그 레벨별 필터링 (ERROR, WARN, INFO, DEBUG)</li>
 *   <li>페이징 처리된 로그 조회</li>
 *   <li>로그 검색 및 정렬</li>
 *   <li>로그 파일 목록 관리</li>
 * </ul>
 * 
 * <h3>지원하는 로그 형식:</h3>
 * <ul>
 *   <li><strong>Logback</strong>: 2025-01-20 10:30:45.123 [main] INFO  c.b.m.Application - Message</li>
 *   <li><strong>Spring Boot</strong>: 2025-01-20 10:30:45.123  INFO 12345 --- [main] c.b.m.Application : Message</li>
 *   <li><strong>일반 형식</strong>: [2025-01-20 10:30:45] INFO: Message</li>
 * </ul> * @version 1.0
 * @since 2025-01-20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogManagementService {

    /**
     * 로그 파일 경로 (application.yml에서 설정)
     */
    @Value("${logging.file.path:logs}")
    private String logFilePath;

    /**
     * 로그 파일명 패턴 (application.yml에서 설정)
     */
    @Value("${logging.file.name:application.log}")
    private String logFileName;

    /**
     * 최대 로그 라인 수 (메모리 보호)
     */
    private static final int MAX_LOG_LINES = 10000;

    /** 이어지는 줄(스택트레이스)을 한 항목에 붙이는 최대 길이 */
    private static final int MAX_CONTINUATION_CHARS = 8000;

    /**
     * 로그 라인 파싱을 위한 정규식 패턴들
     */
    /** logback-spring.xml 의 PATTERN: 시각 | 레벨 | [스레드] | 로거 | 메시지 */
    static final Pattern PIPE_PATTERN = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) \\| (\\w+)\\s*\\| \\[([^\\]]*)\\] \\| (\\S+)\\s*\\| ?(.*)$"
    );

    private static final Pattern LOGBACK_PATTERN = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\[([^\\]]+)\\]\\s+(\\w+)\\s+([^\\s]+)\\s+-\\s+(.*)$"
    );
    
    private static final Pattern SPRING_BOOT_PATTERN = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\w+)\\s+(\\d+)\\s+---\\s+\\[([^\\]]+)\\]\\s+([^\\s]+)\\s+:\\s+(.*)$"
    );
    
    private static final Pattern SIMPLE_PATTERN = Pattern.compile(
        "^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\]\\s+(\\w+):\\s+(.*)$"
    );

    /**
     * 날짜 포맷터들
     */
    private static final DateTimeFormatter FULL_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter SIMPLE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 로그 목록 조회 (페이징)
     * 
     * <p>지정된 조건에 따라 로그를 조회하고 페이징 처리합니다.</p>
     * 
     * @param page 페이지 번호 (0부터 시작)
     * @param size 페이지 크기
     * @param level 로그 레벨 필터 (선택사항)
     * @param search 검색어 (선택사항)
     * @return 페이징된 로그 목록
     */
    public Page<Map<String, Object>> getLogs(int page, int size, String level, String search) {
        return getLogs(page, size, level, search, null);
    }

    /**
     * 로그 목록 조회 (파일 지정). 파일 끝에서부터 최근 {@value #MAX_LOG_LINES}줄을 읽어 최신 로그가 먼저 오게 돌려준다.
     *
     * @param fileName 로그 디렉토리 안의 .log 파일 이름 (비우면 기본 로그 파일)
     * @throws IllegalArgumentException 로그 디렉토리 밖이거나 없는 파일
     */
    public Page<Map<String, Object>> getLogs(int page, int size, String level, String search, String fileName) {
        log.debug("로그 조회 요청 - page: {}, size: {}, level: {}, search: {}, file: {}", page, size, level, search, fileName);
        Path logFile = resolveLogFile(fileName);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, size));
        try {
            List<String> logLines = readTail(logFile, MAX_LOG_LINES);
            List<LogEntry> filteredLogs = filterLogs(parseLogLines(logLines), level, search);
            // 파일은 시간 순서로 쌓이므로 뒤집으면 최신이 먼저 (시각 없는 줄도 제자리를 지킨다)
            Collections.reverse(filteredLogs);

            int start = (int) Math.min(pageable.getOffset(), filteredLogs.size());
            int end = Math.min(start + pageable.getPageSize(), filteredLogs.size());
            List<Map<String, Object>> logMaps = filteredLogs.subList(start, end).stream()
                    .map(this::convertToMap)
                    .collect(Collectors.toList());
            return new PageImpl<>(logMaps, pageable, filteredLogs.size());
        } catch (IOException e) {
            log.error("로그 조회 중 오류 발생: {}", logFile, e);
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }
    }

    /**
     * 로그 파일 경로 확인. 이름만 받고(경로 구분자·".." 불가) 로그 디렉토리의 .log 파일만 허용한다.
     */
    Path resolveLogFile(String fileName) {
        String name = (fileName == null || fileName.isBlank()) ? logFileName : fileName.trim();
        if (!name.matches("[A-Za-z0-9._-]+") || name.contains("..") || !name.endsWith(".log")) {
            throw new IllegalArgumentException("로그 파일 이름이 올바르지 않습니다: " + name);
        }
        Path dir = Paths.get(logFilePath).toAbsolutePath().normalize();
        Path file = dir.resolve(name).normalize();
        if (!file.getParent().equals(dir)) {
            throw new IllegalArgumentException("로그 디렉토리 밖의 파일은 읽을 수 없습니다: " + name);
        }
        return file;
    }

    /**
     * 로그 파일 목록 조회
     * 
     * <p>로그 디렉토리에서 사용 가능한 로그 파일들의 목록을 반환합니다.</p>
     * 
     * @return 로그 파일 정보 목록
     */
    public List<Map<String, Object>> getLogFiles() {
        log.debug("로그 파일 목록 조회 시작");
        
        try {
            Path logDir = Paths.get(logFilePath);
            
            if (!Files.exists(logDir)) {
                log.warn("로그 디렉토리가 존재하지 않음: {}", logFilePath);
                return Collections.emptyList();
            }
            
            return Files.list(logDir)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".log"))
                    .map(this::createLogFileInfo)
                    .sorted((a, b) -> ((String) b.get("name")).compareTo((String) a.get("name")))
                    .collect(Collectors.toList());
                    
        } catch (Exception e) {
            log.error("로그 파일 목록 조회 중 오류 발생", e);
            return Collections.emptyList();
        }
    }

    /**
     * 기본 로그 파일의 최근 {@value #MAX_LOG_LINES}줄 (getLogDetails 등 기존 호출용)
     */
    private List<String> readLogFile() throws IOException {
        return readTail(resolveLogFile(null), MAX_LOG_LINES);
    }

    /**
     * 파일 끝에서부터 최대 maxLines 줄을 읽는다 (오래된 줄부터 돌려줌). 파일 전체를 메모리에 올리지 않는다.
     * 줄바꿈 경계에서 잘라 한 번에 UTF-8 로 풀기 때문에 한글이 깨지지 않는다.
     */
    static List<String> readTail(Path file, int maxLines) throws IOException {
        if (!Files.exists(file)) {
            log.warn("로그 파일이 존재하지 않음: {}", file);
            return Collections.emptyList();
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            long pos = length;
            int newlines = 0;
            byte[] buf = new byte[64 * 1024];
            long start = 0;
            // 마지막 줄 끝의 줄바꿈은 세지 않는다
            if (length > 0) {
                raf.seek(length - 1);
                if (raf.read() == '\n') {
                    pos = length - 1;
                }
            }
            long scanEnd = pos;
            outer:
            while (pos > 0) {
                int n = (int) Math.min(buf.length, pos);
                pos -= n;
                raf.seek(pos);
                raf.readFully(buf, 0, n);
                for (int i = n - 1; i >= 0; i--) {
                    if (buf[i] == '\n' && ++newlines >= maxLines) {
                        start = pos + i + 1;
                        break outer;
                    }
                }
            }
            int size = (int) (scanEnd - start);
            byte[] tail = new byte[size];
            raf.seek(start);
            raf.readFully(tail);
            String text = new String(tail, StandardCharsets.UTF_8);
            if (text.endsWith("\r")) {   // CRLF 파일의 마지막 줄바꿈
                text = text.substring(0, text.length() - 1);
            }
            if (text.isEmpty()) {
                return Collections.emptyList();
            }
            return new ArrayList<>(Arrays.asList(text.split("\r?\n", -1)));
        }
    }

    /**
     * 로그 라인들을 파싱하여 LogEntry 객체로 변환
     * 
     * @param logLines 로그 라인 목록
     * @return 파싱된 LogEntry 목록
     */
    List<LogEntry> parseLogLines(List<String> logLines) {
        List<LogEntry> logEntries = new ArrayList<>();
        LogEntry last = null;

        for (int i = 0; i < logLines.size(); i++) {
            String line = logLines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            LogEntry entry;
            try {
                entry = parseLogLine(line);
            } catch (Exception e) {
                entry = null;
            }
            if (entry != null) {
                entry.setLineNumber(i + 1);
                logEntries.add(entry);
                last = entry;
            } else if (last != null) {
                // 형식에 맞지 않는 줄은 앞 로그에 이어지는 줄 (스택트레이스, 여러 줄 메시지)
                if (last.getMessage().length() < MAX_CONTINUATION_CHARS) {
                    last.setMessage(last.getMessage() + "\n" + line);
                }
            } else {
                // 형식을 모르는 파일(gc.log 등) 또는 파일 앞부분이 잘린 이어지는 줄: 줄 그대로
                logEntries.add(LogEntry.builder().lineNumber(i + 1).level("RAW").logger("").thread("").message(line).build());
            }
        }

        log.debug("로그 파싱 완료: {}항목 / {}줄", logEntries.size(), logLines.size());
        return logEntries;
    }

    /**
     * 단일 로그 라인을 파싱하여 LogEntry 객체로 변환
     *
     * @param line 로그 라인
     * @return 파싱된 LogEntry (파싱 실패 시 null)
     */
    private LogEntry parseLogLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }

        // 현재 logback-spring.xml 패턴: "%d | %-5level | [%thread] | %logger{36} | %msg"
        Matcher pipeMatcher = PIPE_PATTERN.matcher(line);
        if (pipeMatcher.matches()) {
            return LogEntry.builder()
                    .timestamp(parseTimestamp(pipeMatcher.group(1), FULL_FORMATTER))
                    .level(pipeMatcher.group(2))
                    .thread(pipeMatcher.group(3))
                    .logger(pipeMatcher.group(4))
                    .message(pipeMatcher.group(5))
                    .build();
        }

        // Logback 패턴 시도
        Matcher logbackMatcher = LOGBACK_PATTERN.matcher(line);
        if (logbackMatcher.matches()) {
            return LogEntry.builder()
                    .timestamp(parseTimestamp(logbackMatcher.group(1), FULL_FORMATTER))
                    .thread(logbackMatcher.group(2))
                    .level(logbackMatcher.group(3))
                    .logger(logbackMatcher.group(4))
                    .message(logbackMatcher.group(5))
                    .build();
        }

        // Spring Boot 패턴 시도
        Matcher springBootMatcher = SPRING_BOOT_PATTERN.matcher(line);
        if (springBootMatcher.matches()) {
            return LogEntry.builder()
                    .timestamp(parseTimestamp(springBootMatcher.group(1), FULL_FORMATTER))
                    .level(springBootMatcher.group(2))
                    .processId(springBootMatcher.group(3))
                    .thread(springBootMatcher.group(4))
                    .logger(springBootMatcher.group(5))
                    .message(springBootMatcher.group(6))
                    .build();
        }

        // 간단한 패턴 시도
        Matcher simpleMatcher = SIMPLE_PATTERN.matcher(line);
        if (simpleMatcher.matches()) {
            return LogEntry.builder()
                    .timestamp(parseTimestamp(simpleMatcher.group(1), SIMPLE_FORMATTER))
                    .level(simpleMatcher.group(2))
                    .logger("unknown")
                    .thread("unknown")
                    .message(simpleMatcher.group(3))
                    .build();
        }

        // 모든 패턴 실패 시 null 반환
        return null;
    }

    /**
     * 타임스탬프 문자열을 LocalDateTime으로 파싱
     *
     * @param timestampStr 타임스탬프 문자열
     * @param formatter 사용할 포맷터
     * @return 파싱된 LocalDateTime (실패 시 현재 시간)
     */
    private LocalDateTime parseTimestamp(String timestampStr, DateTimeFormatter formatter) {
        try {
            return LocalDateTime.parse(timestampStr, formatter);
        } catch (DateTimeParseException e) {
            log.debug("타임스탬프 파싱 실패: {}", timestampStr);
            return LocalDateTime.now();
        }
    }

    /**
     * 로그 필터링
     *
     * @param logEntries 원본 로그 목록
     * @param level 레벨 필터
     * @param search 검색어 필터
     * @return 필터링된 로그 목록
     */
    private List<LogEntry> filterLogs(List<LogEntry> logEntries, String level, String search) {
        return logEntries.stream()
                .filter(entry -> filterByLevel(entry, level))
                .filter(entry -> filterBySearch(entry, search))
                .collect(Collectors.toList());
    }

    /**
     * 레벨별 필터링
     *
     * @param entry 로그 엔트리
     * @param level 필터할 레벨
     * @return 필터 통과 여부
     */
    private boolean filterByLevel(LogEntry entry, String level) {
        if (level == null || level.trim().isEmpty()) {
            return true;
        }
        return level.trim().equalsIgnoreCase(entry.getLevel());
    }

    /**
     * 검색어별 필터링
     *
     * @param entry 로그 엔트리
     * @param search 검색어
     * @return 필터 통과 여부
     */
    private boolean filterBySearch(LogEntry entry, String search) {
        if (search == null || search.trim().isEmpty()) {
            return true;
        }

        String searchLower = search.trim().toLowerCase();
        return containsLower(entry.getMessage(), searchLower) ||
               containsLower(entry.getLogger(), searchLower) ||
               containsLower(entry.getThread(), searchLower);
    }

    private static boolean containsLower(String value, String needleLower) {
        return value != null && value.toLowerCase().contains(needleLower);
    }

    /**
     * LogEntry를 Map으로 변환
     *
     * @param entry LogEntry 객체
     * @return Map 형태의 로그 정보
     */
    private Map<String, Object> convertToMap(LogEntry entry) {
        Map<String, Object> map = new HashMap<>();
        map.put("lineNumber", entry.getLineNumber());
        map.put("timestamp", entry.getTimestamp());
        map.put("level", entry.getLevel());
        map.put("logger", entry.getLogger());
        map.put("thread", entry.getThread());
        map.put("message", entry.getMessage());

        if (entry.getProcessId() != null) {
            map.put("processId", entry.getProcessId());
        }

        return map;
    }

    /**
     * 로그 파일 정보 생성
     *
     * @param path 로그 파일 경로
     * @return 로그 파일 정보 Map
     */
    private Map<String, Object> createLogFileInfo(Path path) {
        Map<String, Object> info = new HashMap<>();

        try {
            File file = path.toFile();
            info.put("name", file.getName());
            info.put("size", formatFileSize(file.length()));
            info.put("sizeBytes", file.length());
            info.put("lastModified", LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(file.lastModified()),
                java.time.ZoneId.systemDefault()));
            info.put("path", path.toString());

        } catch (Exception e) {
            log.warn("로그 파일 정보 생성 실패: {}", path, e);
            info.put("name", path.getFileName().toString());
            info.put("size", "Unknown");
            info.put("error", e.getMessage());
        }

        return info;
    }

    /**
     * 파일 크기를 사람이 읽기 쉬운 형태로 포맷
     *
     * @param bytes 바이트 크기
     * @return 포맷된 문자열
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * 로그 엔트리 DTO
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class LogEntry {
        private String id;
        private int lineNumber;
        private LocalDateTime timestamp;
        private String level;
        private String logger;
        private String thread;
        private String processId;
        private String message;
    }
}
