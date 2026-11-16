package com.georu.api.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.georu.api.dto.ApiResponse;
import com.georu.codegen.CodeGenerator;
import com.georu.codegen.GenerationResult;
import com.georu.codegen.RuleFile;
import com.georu.core.plugin.GeoruDataSources;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 이관 설계 도구 API. 코드 생성: TOBE 정의(+ ASIS 정의)와 설정 → 검사 결과 · 코드 zip.
 * 관계 분석: 테이블 구조 정의 → 이름으로 추정한 관계 → 분석용 xlsx.
 * 매핑 정의서 초안: ASIS · TOBE 정의(+ 코드표) → 테이블 대응 · 컬럼 매칭 · 전환기준 제안 xlsx.
 * 업체 규칙 파일(xlsx)은 각 요청에 {@code rulesFile} 로 함께 올리고, 양식 · 읽기 · 내려받기는 {@code /rules/*}.
 * 올린 파일은 메모리에서만 읽고 저장하지 않는다. 끄기: {@code georu.codegen.enabled=false}
 */
@RestController
@RequestMapping("/api/generator")
@ConditionalOnProperty(prefix = "georu.codegen", name = "enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "Generator", description = "이관 설계 도구 - 코드 생성 · 관계 분석 · 매핑 정의서 초안")
public class GeneratorController {

    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final CodeGenService service;
    private final RelationService relations;
    private final MappingService mapping;
    private final GenRunStore runs;
    private final GeoruDataSources dataSources;
    private final Environment environment;
    private final ObjectMapper json = new ObjectMapper();
    private final long maxFileBytes;
    /** 실행 기록에 화면 설정을 남길지 (georu.codegen.history.settings, 기본 true). 업체 규칙을 서버에 남기고 싶지 않으면 false */
    private final boolean historySettings;

    public GeneratorController(CodeGenService service, RelationService relations, MappingService mapping, GenRunStore runs,
                               GeoruDataSources dataSources, Environment environment,
                               @Value("${georu.codegen.max-file-size:20MB}") DataSize maxFileSize,
                               @Value("${georu.codegen.history.settings:true}") boolean historySettings) {
        // 생성자는 하나만 둔다. 둘이면 Spring 이 고르지 못해 기동이 실패한다 (데모 게이트에서 잡은 회귀)
        this.service = service;
        this.relations = relations;
        this.mapping = mapping;
        this.runs = runs;
        this.dataSources = dataSources;
        this.environment = environment;
        this.maxFileBytes = maxFileSize.toBytes();
        this.historySettings = historySettings;
    }

    /** 기록에 남길 설정 (끄면 null) */
    private String recordable(String settings) {
        return historySettings ? settings : null;
    }

    // ------------------------------------------------------------------ 업체 규칙 파일

    @Operation(summary = "업체 규칙 파일 양식", description = "xlsx: 기본값 · 사전 · 감사 컬럼 · 명명 규칙 · 작성 안내. 예제 값을 지우고 채워 세 화면에 정의 파일과 함께 올린다")
    @GetMapping("/rules/template")
    public ResponseEntity<byte[]> rulesTemplate() {
        return xlsx("rules-template.xlsx", RuleFile.template());
    }

    @Operation(summary = "업체 규칙 파일 읽기", description = "올린 규칙 파일을 JSON 으로 돌려준다 (화면 칸 채우기용). 저장하지 않는다")
    @PostMapping(value = "/rules/read", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<RulesDto>> rulesRead(@Parameter(description = "업체 규칙 파일 xlsx (필수, 업체 규칙 파일 양식)") @RequestPart("rulesFile") MultipartFile rulesFile) throws IOException {
        if (rulesFile == null || rulesFile.isEmpty()) return ResponseEntity.badRequest().body(ApiResponse.error("BAD_FILE", "규칙 파일이 비어 있습니다"));
        if (rulesFile.getSize() > maxFileBytes) return ResponseEntity.badRequest().body(ApiResponse.error("BAD_FILE", "규칙 파일이 크기 상한을 넘습니다"));
        RuleFile r = RuleFile.read(rulesFile.getOriginalFilename(), rulesFile.getBytes());
        if (r.error() != null) return ResponseEntity.badRequest().body(ApiResponse.error("RULES_INVALID", r.error()));
        return ResponseEntity.ok(ApiResponse.success(r.warnings().isEmpty() ? "읽었습니다" : "읽었습니다 (경고 " + r.warnings().size() + ")",
                RulesDto.from(r, rulesFile.getOriginalFilename())));
    }

    @Operation(summary = "현재 규칙 내려받기", description = "화면 값(JSON)을 업체 규칙 파일 xlsx 로 만든다. 다음부터 이 파일을 올리면 같은 설정이다")
    @PostMapping(value = "/rules/export", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> rulesExport(@RequestBody RulesDto dto) {
        String company = dto.getDefaults() == null ? null : dto.getDefaults().get("company");
        String name = (company == null || company.isBlank() ? "georu" : company.trim().toLowerCase(Locale.ROOT)) + "-rules.xlsx";
        return xlsx(name, dto.toRuleFile().write());
    }

    @Operation(summary = "화면 기본값", description = "DataSource 이름, 업체 이름(georu.customer.name), 파일 크기 상한, 생성기 버전")
    @GetMapping("/options")
    public ApiResponse<Map<String, Object>> options() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dataSources", dataSources.names());
        String customer = environment.getProperty("georu.customer.name", "");
        m.put("company", customer.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
        m.put("maxFileBytes", maxFileBytes);
        m.put("version", CodeGenerator.version());
        return ApiResponse.success(m);
    }

    @Operation(summary = "코드 생성 예제 양식", description = "TOBE 정의 xlsx (첫 시트 데이터 · 예제 행 · 열 설명 메모 · 드롭다운 · 작성 안내 시트)")
    @GetMapping("/code/template")
    public ResponseEntity<byte[]> codeTemplate() {
        return xlsx("tobe-definition-template.xlsx", com.georu.codegen.SpecTemplate.codegen());
    }

    @Operation(summary = "테이블 정의 예제 양식", description = "관계 분석 · ASIS 정의용 xlsx (테이블 구조만)")
    @GetMapping("/relations/template")
    public ResponseEntity<byte[]> definitionTemplate() {
        return xlsx("table-definition-template.xlsx", com.georu.codegen.SpecTemplate.definition());
    }

    @Operation(summary = "공통코드 표 예제 양식", description = "매핑 정의서 초안의 ASIS · TOBE 코드표 xlsx")
    @GetMapping("/mapping/code-template")
    public ResponseEntity<byte[]> codeTableTemplate() {
        return xlsx("code-table-template.xlsx", com.georu.codegen.SpecTemplate.codes());
    }

    @Operation(summary = "매핑 정의서 초안 검사", description = "TOBE · ASIS 정의(+ ASIS · TOBE 코드표) → 테이블 대응 · 자동 제안 요약. 파일은 저장하지 않는다")
    @PostMapping(value = "/mapping/check", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> mappingCheck(@Parameter(description = "TOBE 정의 xlsx (필수, 코드 생성 예제 양식)") @RequestPart("file") MultipartFile tobe,
                                                                         @Parameter(description = "ASIS 테이블 정의 xlsx (테이블 정의 예제 양식)") @RequestPart(value = "asisFile", required = false) MultipartFile asis,
                                                                         @Parameter(description = "ASIS 공통코드 표 xlsx (공통코드 표 예제 양식)") @RequestPart(value = "asisCodeFile", required = false) MultipartFile asisCode,
                                                                         @Parameter(description = "TOBE 공통코드 표 xlsx (공통코드 표 예제 양식)") @RequestPart(value = "tobeCodeFile", required = false) MultipartFile tobeCode,
                                                                         @Parameter(description = "업체 규칙 파일 xlsx (업체 규칙 파일 양식). 비우면 화면 설정 · 기본값") @RequestPart(value = "rulesFile", required = false) MultipartFile rulesFile,
                                                                         @Parameter(description = "화면 설정 JSON (MappingRequest 형식). 비우면 기본값") @RequestParam(value = "settings", required = false) String settings) throws IOException {
        String bad = checkFiles(tobe, asis, asisCode, tobeCode, rulesFile);
        if (bad != null) return ResponseEntity.badRequest().body(ApiResponse.error("BAD_FILE", bad));
        MappingService.Outcome o = mapping.run(upload(tobe), upload(asis), upload(asisCode), upload(tobeCode), upload(rulesFile), mappingRequest(settings));
        return ResponseEntity.ok(ApiResponse.success(o.ok() ? "초안을 만들 수 있습니다" : "고칠 것이 있습니다", withRules(MappingService.view(o), o.rules())));
    }

    @Operation(summary = "매핑 정의서 초안 xlsx 내려받기", description = "작성안내 · 테이블 매핑 목록 · 테이블 매핑 정의서 · 공통코드 매핑 정의서 · 참조 시트. 실행 기록을 남긴다")
    @PostMapping(value = "/mapping/draft", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> mappingDraft(@Parameter(description = "TOBE 정의 xlsx (필수, 코드 생성 예제 양식)") @RequestPart("file") MultipartFile tobe,
                                          @Parameter(description = "ASIS 테이블 정의 xlsx (테이블 정의 예제 양식)") @RequestPart(value = "asisFile", required = false) MultipartFile asis,
                                          @Parameter(description = "ASIS 공통코드 표 xlsx (공통코드 표 예제 양식)") @RequestPart(value = "asisCodeFile", required = false) MultipartFile asisCode,
                                          @Parameter(description = "TOBE 공통코드 표 xlsx (공통코드 표 예제 양식)") @RequestPart(value = "tobeCodeFile", required = false) MultipartFile tobeCode,
                                          @Parameter(description = "업체 규칙 파일 xlsx (업체 규칙 파일 양식). 비우면 화면 설정 · 기본값") @RequestPart(value = "rulesFile", required = false) MultipartFile rulesFile,
                                          @Parameter(description = "화면 설정 JSON (MappingRequest 형식). 비우면 기본값") @RequestParam(value = "settings", required = false) String settings,
                                          @Parameter(description = "실행자 (실행 기록 migration_gen_run 에 남음)") @RequestHeader(value = "X-Executed-By", required = false) String executedBy) throws IOException {
        String bad = checkFiles(tobe, asis, asisCode, tobeCode, rulesFile);
        if (bad != null) return ResponseEntity.badRequest().body(ApiResponse.error("BAD_FILE", bad));
        MappingService.Outcome o = mapping.run(upload(tobe), upload(asis), upload(asisCode), upload(tobeCode), upload(rulesFile), mappingRequest(settings));
        mapping.record(o, executedBy, recordable(settings));
        if (!o.ok()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("MAPPING_INVALID", "고칠 것이 있어 만들지 않았습니다", withRules(MappingService.view(o), o.rules())));
        }
        return xlsx(MappingService.fileName(o.tobeName()), o.rules().appendTo(com.georu.codegen.MappingWorkbook.write(o.result())));
    }

    private MappingRequest mappingRequest(String settings) throws IOException {
        return settings == null || settings.isBlank() ? new MappingRequest() : json.readValue(settings, MappingRequest.class);
    }

    private static ResponseEntity<byte[]> xlsx(String name, byte[] body) {
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .body(body);
    }

    @Operation(summary = "코드 생성 검사", description = "업무구분 목록 · 오류 · 경고 · 테이블별 결과 · 할 일 · APPLY.md 미리보기. 파일은 저장하지 않는다")
    @PostMapping(value = "/code/check", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> check(@Parameter(description = "TOBE 정의 xlsx (필수, 코드 생성 예제 양식)") @RequestPart("file") MultipartFile file,
                                                                  @Parameter(description = "ASIS 테이블 정의 xlsx. 설정 sourceMeta=file 일 때 필수") @RequestPart(value = "asisFile", required = false) MultipartFile asisFile,
                                                                  @Parameter(description = "업체 규칙 파일 xlsx (업체 규칙 파일 양식). 비우면 화면 설정 · 기본값") @RequestPart(value = "rulesFile", required = false) MultipartFile rulesFile,
                                                                  @Parameter(description = "화면 설정 JSON (CodeGenRequest 형식). 비우면 기본값") @RequestParam(value = "settings", required = false) String settings) throws IOException {
        String bad = checkFiles(file, asisFile, rulesFile);
        if (bad != null) return ResponseEntity.badRequest().body(ApiResponse.error("BAD_FILE", bad));
        CodeGenService.Outcome o = service.run(upload(file), upload(asisFile), upload(rulesFile), request(settings));
        return ResponseEntity.ok(ApiResponse.success(o.ok() ? "생성할 수 있습니다" : "고칠 것이 있습니다", view(o)));
    }

    @Operation(summary = "코드 zip 내려받기", description = "검사와 같은 입력. 오류가 있으면 400 과 오류 목록. 실행 기록(migration_gen_run)을 남긴다")
    @PostMapping(value = "/code/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> generate(@Parameter(description = "TOBE 정의 xlsx (필수, 코드 생성 예제 양식)") @RequestPart("file") MultipartFile file,
                                      @Parameter(description = "ASIS 테이블 정의 xlsx. 설정 sourceMeta=file 일 때 필수") @RequestPart(value = "asisFile", required = false) MultipartFile asisFile,
                                      @Parameter(description = "업체 규칙 파일 xlsx (업체 규칙 파일 양식). 비우면 화면 설정 · 기본값") @RequestPart(value = "rulesFile", required = false) MultipartFile rulesFile,
                                      @Parameter(description = "화면 설정 JSON (CodeGenRequest 형식). 비우면 기본값") @RequestParam(value = "settings", required = false) String settings,
                                      @Parameter(description = "실행자 (실행 기록 migration_gen_run 에 남음)") @RequestHeader(value = "X-Executed-By", required = false) String executedBy) throws IOException {
        String bad = checkFiles(file, asisFile, rulesFile);
        if (bad != null) return ResponseEntity.badRequest().body(ApiResponse.error("BAD_FILE", bad));
        CodeGenService.Outcome o = service.run(upload(file), upload(asisFile), upload(rulesFile), request(settings));
        service.record(o, o.request().getCompany(), executedBy, recordable(settings));
        if (!o.ok()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("CODEGEN_INVALID", "고칠 것이 있어 만들지 않았습니다", view(o)));
        }
        GenerationResult r = o.result();
        return ResponseEntity.ok()
                .contentType(ZIP)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(r.zipName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Georu-Summary", java.net.URLEncoder.encode(CodeGenService.summary(o, o.request().getCompany()), StandardCharsets.UTF_8))
                .body(r.toZip());
    }

    @Operation(summary = "관계 분석 검사", description = "테이블 구조 정의(FK 없음) → 이름으로 추정한 관계 요약 · 앞 300건. 파일은 저장하지 않는다")
    @PostMapping(value = "/relations/check", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> relationsCheck(@Parameter(description = "테이블 구조 정의 xlsx (필수, 테이블 정의 예제 양식)") @RequestPart("file") MultipartFile file,
                                                                           @Parameter(description = "업체 규칙 파일 xlsx (업체 규칙 파일 양식). 비우면 화면 설정 · 기본값") @RequestPart(value = "rulesFile", required = false) MultipartFile rulesFile,
                                                                           @Parameter(description = "화면 설정 JSON (RelationRequest 형식). 비우면 기본값") @RequestParam(value = "settings", required = false) String settings) throws IOException {
        String bad = checkFiles(file, rulesFile);
        if (bad != null) return ResponseEntity.badRequest().body(ApiResponse.error("BAD_FILE", bad));
        RelationService.Outcome o = relations.run(upload(file), upload(rulesFile), relationRequest(settings));
        return ResponseEntity.ok(ApiResponse.success(o.ok() ? "분석했습니다" : "읽지 못했습니다", withRules(RelationService.view(o), o.rules())));
    }

    @Operation(summary = "관계 분석 xlsx 내려받기", description = "관계 목록 · 테이블 요약 · 적재 순서 · 원본 정의 · 안내. 실행 기록(migration_gen_run)을 남긴다")
    @PostMapping(value = "/relations/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> relationsAnalyze(@Parameter(description = "테이블 구조 정의 xlsx (필수, 테이블 정의 예제 양식)") @RequestPart("file") MultipartFile file,
                                              @Parameter(description = "업체 규칙 파일 xlsx (업체 규칙 파일 양식). 비우면 화면 설정 · 기본값") @RequestPart(value = "rulesFile", required = false) MultipartFile rulesFile,
                                              @Parameter(description = "화면 설정 JSON (RelationRequest 형식). 비우면 기본값") @RequestParam(value = "settings", required = false) String settings,
                                              @Parameter(description = "실행자 (실행 기록 migration_gen_run 에 남음)") @RequestHeader(value = "X-Executed-By", required = false) String executedBy) throws IOException {
        String bad = checkFiles(file, rulesFile);
        if (bad != null) return ResponseEntity.badRequest().body(ApiResponse.error("BAD_FILE", bad));
        RelationService.Outcome o = relations.run(upload(file), upload(rulesFile), relationRequest(settings));
        relations.record(o, executedBy, recordable(settings));
        if (!o.ok()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("RELATIONS_INVALID", "파일을 읽지 못했습니다", withRules(RelationService.view(o), o.rules())));
        }
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(RelationService.fileName(o.spec().fileName()), StandardCharsets.UTF_8).build().toString())
                .body(o.rules().appendTo(com.georu.codegen.relation.RelationWorkbook.write(o.result())));
    }

    @Operation(summary = "최근 실행 기록", description = "tool: CODE · RELATIONS · MAPPING (비우면 전체). 항목의 settings 는 그때 화면이 보낸 설정 (같은 조건으로 다시 만들기)")
    @GetMapping("/history")
    public ApiResponse<List<Map<String, Object>>> history(@Parameter(description = "도구 (CODE, RELATIONS, MAPPING). 비우면 전체") @RequestParam(value = "tool", required = false) String tool,
                                                          @Parameter(description = "최대 건수 (1~200)") @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ApiResponse.success(runs.recent(tool, limit));
    }

    // ------------------------------------------------------------------

    /** 함께 올린 규칙 파일을 응답에 넣는다 (화면이 칸을 채우고 요약을 보여 준다) */
    private static Map<String, Object> withRules(Map<String, Object> view, RuleFile rules) {
        if (rules != null && !rules.isEmpty()) view.put("rules", RulesDto.from(rules, null));
        return view;
    }

    private Map<String, Object> view(CodeGenService.Outcome o) {
        CodeGenRequest req = o.request();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", o.ok());
        m.put("fileName", o.spec().fileName());
        m.put("tables", o.spec().tables().size());
        m.put("migrate", o.spec().migrateCount());
        m.put("works", CodeGenService.works(o.spec(), req.getWorks(), o.rules().workNames()));
        m.put("errors", o.errors());
        List<String> warnings = new java.util.ArrayList<>(o.warnings());
        if (o.result() != null) warnings.addAll(o.result().warnings());
        else warnings.addAll(o.spec().warnings());
        m.put("warnings", warnings);
        m.put("sourceDialect", o.sourceDialect());
        m.put("targetDialect", o.targetDialect());
        if (o.ok()) {
            GenerationResult r = o.result();
            m.put("zipName", r.zipName());
            m.put("fileCount", r.fileCount());
            m.put("report", r.tables());
            Map<String, Long> todo = new TreeMap<>();
            r.todos().forEach(t -> todo.merge(t.type(), 1L, Long::sum));
            m.put("todoCounts", todo);
            m.put("todos", r.todos().stream().limit(100).toList());
            m.put("applyMd", r.files().get("APPLY.md"));
            m.put("paths", r.files().keySet().stream().filter(p -> !p.contains("/dto/") && !p.contains("/reader/")
                    && !p.contains("/processor/") && !p.contains("/writer/") && !p.endsWith("Mapper.xml")).toList());
        }
        withRules(m, o.rules());
        return m;
    }

    private RelationRequest relationRequest(String settings) throws IOException {
        return settings == null || settings.isBlank() ? new RelationRequest() : json.readValue(settings, RelationRequest.class);
    }

    private CodeGenRequest request(String settings) throws IOException {
        return settings == null || settings.isBlank() ? new CodeGenRequest() : json.readValue(settings, CodeGenRequest.class);
    }

    private String checkFiles(MultipartFile file, MultipartFile... others) {
        if (file == null || file.isEmpty()) return "TOBE 정의 파일이 비어 있습니다";
        List<MultipartFile> all = new java.util.ArrayList<>();
        all.add(file);
        all.addAll(java.util.Arrays.asList(others));
        for (MultipartFile f : all) {
            if (f != null && f.getSize() > maxFileBytes) {
                return f.getOriginalFilename() + " 가 크기 상한(" + DataSize.ofBytes(maxFileBytes).toMegabytes() + "MB)을 넘습니다 (georu.codegen.max-file-size)";
            }
        }
        return null;
    }

    private static CodeGenService.Upload upload(MultipartFile f) throws IOException {
        return f == null || f.isEmpty() ? null : new CodeGenService.Upload(f.getOriginalFilename(), f.getBytes());
    }
}
