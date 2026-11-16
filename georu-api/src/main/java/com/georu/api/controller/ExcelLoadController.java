package com.georu.api.controller;

import com.georu.api.dto.ApiResponse;
import com.georu.core.internal.excel.ExcelFileRecord;
import com.georu.core.internal.excel.ExcelFileService;
import com.georu.core.internal.excel.ExcelValidationReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 엑셀 이관 파일 API. 업로드·검증·미리보기·템플릿. 실행은 {@code POST /api/migrate/table} 에 {@code source=EXCEL, excelFileId} 를 넣어 요청한다.
 * 참고용 옛 모듈(/api/excel, com.georu.excel)과는 다른 기능이다.
 */
@Slf4j
@RestController
@RequestMapping("/api/excel-load")
@RequiredArgsConstructor
@Tag(name = "Excel Load", description = "엑셀 이관 - 업로드한 엑셀(TOBE 컬럼 헤더)을 테이블 이관 파이프라인으로 적재")
public class ExcelLoadController {

    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ExcelFileService fileService;

    @Operation(summary = "업로드 (저장 후 즉시 검증)")
    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ExcelFileRecord>> upload(@Parameter(description = "엑셀(xlsx) 또는 CSV 파일. 1행은 타겟 컬럼 헤더") @RequestParam("file") MultipartFile file,
                                                               @Parameter(description = "대상 플러그인 이름", example = "approval.common") @RequestParam("pluginName") String pluginName,
                                                               @Parameter(description = "대상 테이블명", example = "FB_PROPOSE_TEMPLATE") @RequestParam("tableName") String tableName,
                                                               @Parameter(description = "헤더 형식. 현재 TOBE(타겟 컬럼 헤더)만 지원, 비우면 TOBE", example = "TOBE") @RequestParam(value = "format", required = false) String format,
                                                               @Parameter(description = "업로드한 사용자 (uploadedBy 에 기록, 최대 100자)") @RequestHeader(value = "X-Executed-By", required = false) String by) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("EMPTY_FILE", "파일이 비어 있습니다"));
        }
        try (var in = file.getInputStream()) {
            ExcelFileRecord r = fileService.upload(file.getOriginalFilename(), file.getSize(), in, pluginName, tableName, format, trim(by));
            return ResponseEntity.ok(ApiResponse.success(r.getMessage(), r));
        } catch (com.georu.core.internal.excel.ExcelSizeLimitException e) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            ResponseEntity<ApiResponse<ExcelFileRecord>> resp = (ResponseEntity) ResponseEntity.badRequest()
                    .body(ApiResponse.error("EXCEL_TOO_LARGE", e.getMessage(), e.getPlan()));
            return resp;
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_REQUEST", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("FILE_READ_FAILED", "파일을 읽을 수 없습니다: " + e.getMessage()));
        }
    }

    @Operation(summary = "파일 목록")
    @GetMapping("/files")
    public ResponseEntity<ApiResponse<List<ExcelFileRecord>>> list(@Parameter(description = "상태 필터 (UPLOADED, VALIDATED, INVALID, RUNNING, COMPLETED, FAILED). 비우면 전체") @RequestParam(required = false) String status,
                                                                   @Parameter(description = "플러그인 이름 필터. 비우면 전체") @RequestParam(required = false) String pluginName,
                                                                   @Parameter(description = "테이블명 필터. 비우면 전체") @RequestParam(required = false) String tableName,
                                                                   @Parameter(description = "최대 건수 (1~1000)") @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(ApiResponse.success(fileService.list(status, pluginName, tableName, limit)));
    }

    @Operation(summary = "크기 제한 (업로드 상한·xlsx 셀 상한·권장값·Excel 형식 한계)")
    @GetMapping("/limits")
    public ResponseEntity<ApiResponse<Map<String, Object>>> limits() {
        return ResponseEntity.ok(ApiResponse.success(fileService.limits()));
    }

    @Operation(summary = "분할 안내 계산 (데이터 행 수·열 수 → 파일당 행 수·파일별 범위)")
    @GetMapping("/split-plan")
    public ResponseEntity<ApiResponse<com.georu.core.internal.excel.ExcelSplitPlan>> splitPlan(@Parameter(description = "테이블명 (분할 파일 이름 제안에 사용, 비우면 data)") @RequestParam(required = false) String tableName,
                                                                                          @Parameter(description = "데이터 행 수 (헤더 제외, 0 이상)", example = "500000") @RequestParam int dataRows, @Parameter(description = "열 수 (1 이상)", example = "30") @RequestParam int columns) {
        try {
            return ResponseEntity.ok(ApiResponse.success(fileService.splitPlan(tableName, dataRows, columns)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_REQUEST", e.getMessage()));
        }
    }

    @Operation(summary = "상태별 건수")
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> summary() {
        return ResponseEntity.ok(ApiResponse.success(fileService.statusCounts()));
    }

    @Operation(summary = "파일 1건")
    @GetMapping("/files/{fileId}")
    public ResponseEntity<ApiResponse<ExcelFileRecord>> get(@Parameter(description = "엑셀 파일 id (업로드 응답의 fileId)") @PathVariable String fileId) {
        try {
            return ResponseEntity.ok(ApiResponse.success(fileService.get(fileId)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND", e.getMessage()));
        }
    }

    @Operation(summary = "검증 보고서·실행 오류 목록")
    @GetMapping("/files/{fileId}/report")
    public ResponseEntity<ApiResponse<ExcelFileService.IssuesDoc>> report(@Parameter(description = "엑셀 파일 id (업로드 응답의 fileId)") @PathVariable String fileId) {
        try {
            return ResponseEntity.ok(ApiResponse.success(fileService.issues(fileId)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND", e.getMessage()));
        }
    }

    @Operation(summary = "미리보기 (헤더·앞 N행·컬럼 대응표)")
    @GetMapping("/files/{fileId}/preview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> preview(@Parameter(description = "엑셀 파일 id (업로드 응답의 fileId)") @PathVariable String fileId, @Parameter(description = "미리볼 데이터 행 수 (1~200, 비우면 georu.excel.preview-rows, 기본 20)") @RequestParam(required = false) Integer rows) {
        try {
            return ResponseEntity.ok(ApiResponse.success(fileService.preview(fileId, rows)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND", e.getMessage()));
        } catch (IOException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("PREVIEW_FAILED", e.getMessage()));
        }
    }

    @Operation(summary = "재검증")
    @PostMapping("/files/{fileId}/validate")
    public ResponseEntity<ApiResponse<ExcelValidationReport>> validate(@Parameter(description = "엑셀 파일 id (업로드 응답의 fileId)") @PathVariable String fileId) {
        try {
            ExcelValidationReport report = fileService.validate(fileId);
            return ResponseEntity.ok(ApiResponse.success(report.isBlocked() ? "검증 차단 " + report.getBlockCount() + "건" : "검증 통과", report));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND", e.getMessage()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(409).body(ApiResponse.error("NOT_ALLOWED", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("FILE_READ_FAILED", e.getMessage()));
        }
    }

    @Operation(summary = "삭제 (실행 중 제외)")
    @DeleteMapping("/files/{fileId}")
    public ResponseEntity<ApiResponse<String>> delete(@Parameter(description = "엑셀 파일 id (업로드 응답의 fileId)") @PathVariable String fileId) {
        try {
            fileService.delete(fileId);
            return ResponseEntity.ok(ApiResponse.success("삭제했습니다", fileId));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(ApiResponse.error("NOT_ALLOWED", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(ApiResponse.error("DELETE_FAILED", e.getMessage()));
        }
    }

    @Operation(summary = "원본 다운로드")
    @GetMapping("/files/{fileId}/download")
    public ResponseEntity<Resource> download(@Parameter(description = "엑셀 파일 id (업로드 응답의 fileId)") @PathVariable String fileId) {
        try {
            ExcelFileRecord r = fileService.get(fileId);
            Resource res = new FileSystemResource(fileService.path(fileId));
            if (!res.exists()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok().headers(attachment(r.getOriginalName())).contentType(MediaType.APPLICATION_OCTET_STREAM).body(res);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "검증·실행 오류 목록 xlsx")
    @GetMapping("/files/{fileId}/issues.xlsx")
    public ResponseEntity<byte[]> issues(@Parameter(description = "엑셀 파일 id (업로드 응답의 fileId)") @PathVariable String fileId) {
        try {
            byte[] body = fileService.issuesXlsx(fileId);
            return ResponseEntity.ok().headers(attachment(fileId + "_issues.xlsx")).contentType(XLSX).body(body);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(summary = "컬럼 대응표 (타겟 컬럼 ↔ DTO 프로퍼티, 필수·감사·채번)")
    @GetMapping("/binding")
    public ResponseEntity<ApiResponse<Map<String, Object>>> binding(@Parameter(description = "대상 플러그인 이름", example = "approval.common") @RequestParam String pluginName, @Parameter(description = "대상 테이블명", example = "FB_PROPOSE_TEMPLATE") @RequestParam String tableName) {
        try {
            return ResponseEntity.ok(ApiResponse.success(fileService.bindingView(fileService.binding(pluginName, tableName))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_REQUEST", e.getMessage()));
        }
    }

    @Operation(summary = "템플릿 xlsx (헤더 + 컬럼 설명 시트)")
    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@Parameter(description = "대상 플러그인 이름", example = "approval.common") @RequestParam String pluginName, @Parameter(description = "대상 테이블명", example = "FB_PROPOSE_TEMPLATE") @RequestParam String tableName) {
        try {
            byte[] body = fileService.template(pluginName, tableName);
            return ResponseEntity.ok().headers(attachment(tableName.trim().toLowerCase() + "_template.xlsx")).contentType(XLSX).body(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private static HttpHeaders attachment(String filename) {
        HttpHeaders h = new HttpHeaders();
        h.setContentDisposition(ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
        return h;
    }

    private static String trim(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > 100 ? t.substring(0, 100) : t;
    }
}
