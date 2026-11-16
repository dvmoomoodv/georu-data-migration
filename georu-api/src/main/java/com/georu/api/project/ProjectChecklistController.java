package com.georu.api.project;

import com.georu.api.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 이관 진행 체크리스트 API (화면 /ui/project). 체크한 사람은 X-Executed-By (로그인이 켜져 있으면 로그인 이름으로 바뀐다).
 */
@RestController
@RequestMapping("/api/project/checklist")
@Tag(name = "Project", description = "이관 진행 - 권장 단계 · 체크리스트 · 엑셀 양식")
public class ProjectChecklistController {

    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ChecklistService service;

    public ProjectChecklistController(ChecklistService service) {
        this.service = service;
    }

    /** 화면이 보내는 항목 변경. 보낸 것만 바꾼다 */
    @Schema(description = "체크리스트 항목 변경. 보낸 것만 바꾼다")
    public record ItemChange(@Schema(description = "체크 여부. null 이면 그대로") Boolean checked,
                             @Schema(description = "메모. null 이면 그대로, 빈 문자열이면 지움") String memo) {
    }

    @Operation(summary = "체크리스트", description = "단계 · 항목 · 단계 상태. 처음이면 권장 단계로 채운다")
    @GetMapping
    public ApiResponse<ChecklistService.View> view(@Parameter(description = "실행자 (처음 열어 권장 단계로 채울 때 기록)") @RequestHeader(value = "X-Executed-By", required = false) String actor) {
        return ApiResponse.success(service.view(actor));
    }

    @Operation(summary = "항목 체크 · 메모", description = "보낸 것만 바꾸고 갱신된 체크리스트 전체를 돌려준다")
    @PutMapping("/items/{id}")
    public ApiResponse<ChecklistService.View> change(@Parameter(description = "체크리스트 항목 id") @PathVariable long id, @RequestBody ItemChange change,
                                                     @Parameter(description = "체크한 사람 (checkedBy · 기록에 남음)") @RequestHeader(value = "X-Executed-By", required = false) String actor) {
        return ApiResponse.success(service.change(id, change.checked(), change.memo(), actor));
    }

    @Operation(summary = "기록", description = "itemId 가 있으면 그 항목, 없으면 체크리스트 전체(채우기 · 엑셀 적용 · 되돌리기)")
    @GetMapping("/events")
    public ApiResponse<List<ChecklistEvent>> events(@Parameter(description = "항목 id. 비우면 체크리스트 전체 기록 (최근 50건)") @RequestParam(required = false) Long itemId) {
        return ApiResponse.success(service.events(itemId));
    }

    @Operation(summary = "엑셀 내려받기", description = "지금 체크리스트 그대로. 이 파일이 양식이다 (정의 열을 고쳐 다시 올린다)")
    @GetMapping("/download")
    public ResponseEntity<byte[]> download() {
        String name = "georu-checklist-" + LocalDate.now() + ".xlsx";
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .body(service.download());
    }

    @Operation(summary = "엑셀 올리기", description = "apply=false: 미리보기(추가 · 삭제 · 바뀜) / apply=true: 적용. 같은 단계 이름 + 항목은 체크 상태를 유지한다")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ChecklistService.Preview>> upload(@Parameter(description = "체크리스트 엑셀 파일 (내려받은 양식)") @RequestPart("file") MultipartFile file,
                                                                        @Parameter(description = "false: 미리보기만, true: 적용") @RequestParam(defaultValue = "false") boolean apply,
                                                                        @Parameter(description = "실행자 (적용 시 기록)") @RequestHeader(value = "X-Executed-By", required = false) String actor) throws IOException {
        ChecklistService.Preview p = apply
                ? service.apply(file.getOriginalFilename(), file.getBytes(), actor)
                : service.preview(file.getOriginalFilename(), file.getBytes());
        return p.ok() ? ResponseEntity.ok(ApiResponse.success(p))
                : ResponseEntity.badRequest().body(ApiResponse.error("CHECKLIST_INVALID", String.join(" / ", p.errors()), p));
    }

    @Operation(summary = "권장 단계로 되돌리기", description = "같은 단계 이름 + 항목은 체크 상태를 유지하고, 권장 단계에 없는 항목은 지운다")
    @PostMapping("/reset")
    public ApiResponse<ChecklistService.Preview> reset(@Parameter(description = "실행자 (기록에 남음)") @RequestHeader(value = "X-Executed-By", required = false) String actor) {
        return ApiResponse.success(service.reset(actor));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("NOT_FOUND", e.getMessage()));
    }
}
