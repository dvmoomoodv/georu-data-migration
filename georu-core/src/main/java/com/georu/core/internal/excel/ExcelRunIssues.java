package com.georu.core.internal.excel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 실행 중 건너뛴 행(onRowError=SKIP) 기록. 파일 ID 별로 모았다가 Job 종료 리스너가 꺼내 대장에 남긴다.
 * 프로세스 안에서만 유효하다 (단일 실행 규칙으로 동시에 한 파일만 돈다).
 */
public class ExcelRunIssues {

    private final Map<String, Entry> byFile = new ConcurrentHashMap<>();
    private final int maxIssues;

    public ExcelRunIssues(int maxIssues) {
        this.maxIssues = Math.max(50, maxIssues);
    }

    public static final class Entry {
        private final List<ExcelValidationReport.Issue> issues = new ArrayList<>();
        private int count;
        private boolean truncated;

        public synchronized void add(ExcelValidationReport.Issue issue, int max) {
            count++;
            if (issues.size() >= max) {
                truncated = true;
                return;
            }
            issues.add(issue);
        }

        public synchronized List<ExcelValidationReport.Issue> issues() {
            return List.copyOf(issues);
        }

        public synchronized int count() {
            return count;
        }

        public synchronized boolean truncated() {
            return truncated;
        }
    }

    public void start(String fileId) {
        byFile.put(fileId, new Entry());
    }

    public void add(String fileId, int row, String column, String value, String message) {
        if (fileId == null) {
            return;
        }
        byFile.computeIfAbsent(fileId, k -> new Entry()).add(ExcelValidationReport.Issue.builder()
                .row(row).column(column).severity("SKIP").message(message)
                .value(value != null && value.length() > 200 ? value.substring(0, 200) + "…" : value).build(), maxIssues);
    }

    public Entry finish(String fileId) {
        Entry e = fileId == null ? null : byFile.remove(fileId);
        return e == null ? new Entry() : e;
    }
}
