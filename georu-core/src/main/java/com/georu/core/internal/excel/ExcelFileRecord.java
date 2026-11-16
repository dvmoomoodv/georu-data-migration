package com.georu.core.internal.excel;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 운영 저장소 {@code migration_excel_file} 1행 (엑셀 이관 파일 대장) */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "엑셀 이관 파일 대장 1건")
public class ExcelFileRecord {

    public static final String FORMAT_TOBE = "TOBE";
    public static final String FORMAT_ASIS = "ASIS";

    public static final String STATUS_UPLOADED = "UPLOADED";
    public static final String STATUS_VALIDATED = "VALIDATED";
    public static final String STATUS_INVALID = "INVALID";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    @Schema(description = "엑셀 이관 파일 ID", example = "XF-20261021153000-a1b2")
    private String fileId;
    @Schema(description = "업로드한 원본 파일명", example = "tb_member.xlsx")
    private String originalName;
    @Schema(description = "보관 파일명 (fileId_원본명, 경로 구분자·제어 문자는 _ 로 치환)")
    private String storedName;
    /** 현재 폴더 ({@link ExcelFolder#name()}) */
    @Schema(description = "현재 보관 폴더 (PENDING / PROCESSING / COMPLETED / FAILED)", example = "PENDING")
    private String folder;
    @Schema(description = "플러그인명", example = "hr.member")
    private String pluginName;
    @Schema(description = "대상 테이블명 (소문자)", example = "tb_member")
    private String tableName;
    @Schema(description = "파일 형식. 현재 TOBE(타겟 컬럼 헤더)만 지원", example = "TOBE")
    private String format;
    @Schema(description = "파일 상태 (UPLOADED / VALIDATED / INVALID / RUNNING / COMPLETED / FAILED)", example = "VALIDATED")
    private String status;
    @Schema(description = "파일 크기 (바이트)", example = "20480")
    private long fileSize;
    @Schema(description = "파일 SHA-256 해시")
    private String sha256;
    @Schema(description = "검증한 데이터 행 수 (헤더 제외)", example = "1000")
    private Integer rowCount;
    @Schema(description = "검증 차단 건수", example = "0")
    private Integer blockCount;
    @Schema(description = "검증 경고 건수", example = "0")
    private Integer warnCount;
    @Schema(description = "마지막 실행의 적재 건수", example = "1000")
    private Integer writtenCount;
    @Schema(description = "마지막 실행에서 건너뛴 행 수 (onRowError=SKIP)", example = "0")
    private Integer skippedCount;
    @Schema(description = "마지막 실행의 오류 행 처리 (STOP / SKIP)", example = "STOP")
    private String onRowError;
    @Schema(description = "마지막 실행 모드 (NORMAL 또는 테스트 모드명)", example = "NORMAL")
    private String lastTestMode;
    @Schema(description = "마지막 실행의 작업 ID")
    private String migrationId;
    @Schema(description = "최근 처리 결과 메시지 (검증·실행 요약, 최대 1000자)")
    private String message;
    @Schema(description = "검증 보고서와 마지막 실행에서 건너뛴 행을 담은 JSON (IssuesDoc)")
    private String issuesJson;
    @Schema(description = "업로드한 사용자")
    private String uploadedBy;
    @Schema(description = "업로드 시각")
    private LocalDateTime uploadedAt;
    @Schema(description = "마지막 검증 시각")
    private LocalDateTime validatedAt;
    @Schema(description = "마지막 실행 시작 시각")
    private LocalDateTime startedAt;
    @Schema(description = "마지막 실행 종료 시각")
    private LocalDateTime finishedAt;
    @Schema(description = "최종 수정 시각")
    private LocalDateTime updatedAt;

    @Schema(description = "실행 중 여부 (status=RUNNING)", example = "false")
    public boolean isRunning() {
        return STATUS_RUNNING.equals(status);
    }

    @Schema(description = "검증을 마쳤는지 여부 (status 가 VALIDATED / INVALID / COMPLETED / FAILED)", example = "true")
    public boolean isValidated() {
        return STATUS_VALIDATED.equals(status) || STATUS_INVALID.equals(status)
                || STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status);
    }

    public ExcelFolder folderEnum() {
        return ExcelFolder.of(folder);
    }
}
