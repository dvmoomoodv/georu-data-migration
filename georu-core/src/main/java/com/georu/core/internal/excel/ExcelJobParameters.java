package com.georu.core.internal.excel;

import com.georu.core.internal.request.TableMigrationRequest;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

import java.util.Locale;

/**
 * 엑셀 이관 Job 파라미터. 테이블 이관 요청의 {@code source=EXCEL} 이 여기로 들어와
 * Strategy 가 Reader·Processor 를 엑셀용으로 바꾸고, Job 리스너가 파일 상태를 갱신한다.
 */
public final class ExcelJobParameters {

    public static final String PARAM_SOURCE = "source";
    public static final String SOURCE_EXCEL = "EXCEL";
    public static final String PARAM_FILE_ID = "excelFileId";
    public static final String PARAM_ON_ROW_ERROR = "excelOnRowError";

    public static final String ON_ROW_ERROR_STOP = "STOP";
    public static final String ON_ROW_ERROR_SKIP = "SKIP";

    private ExcelJobParameters() {
    }

    public static void add(JobParametersBuilder builder, TableMigrationRequest request) {
        if (request == null || !request.isExcelSource()) {
            return;
        }
        builder.addString(PARAM_SOURCE, SOURCE_EXCEL);
        builder.addString(PARAM_FILE_ID, request.getExcelFileId() == null ? "" : request.getExcelFileId().trim());
        builder.addString(PARAM_ON_ROW_ERROR, normalizeOnRowError(request.getOnRowError()));
    }

    public static boolean isExcel(JobParameters params) {
        return params != null && SOURCE_EXCEL.equalsIgnoreCase(params.getString(PARAM_SOURCE));
    }

    public static String fileId(JobParameters params) {
        return params == null ? null : params.getString(PARAM_FILE_ID);
    }

    public static String onRowError(JobParameters params) {
        return normalizeOnRowError(params == null ? null : params.getString(PARAM_ON_ROW_ERROR));
    }

    public static boolean skipRows(JobParameters params) {
        return ON_ROW_ERROR_SKIP.equals(onRowError(params));
    }

    /** STOP(기본) / SKIP. 그 외 값은 IllegalArgumentException */
    public static String normalizeOnRowError(String value) {
        if (value == null || value.isBlank()) {
            return ON_ROW_ERROR_STOP;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (ON_ROW_ERROR_STOP.equals(v) || ON_ROW_ERROR_SKIP.equals(v)) {
            return v;
        }
        throw new IllegalArgumentException("onRowError 는 STOP 또는 SKIP 이어야 합니다: " + value);
    }
}
