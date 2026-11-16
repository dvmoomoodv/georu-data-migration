package com.georu.api.generator;

import com.georu.codegen.CodeGenerator;
import com.georu.codegen.CodegenSettings;
import com.georu.codegen.GenerationResult;
import com.georu.codegen.RuleFile;
import com.georu.codegen.SourceCatalog;
import com.georu.codegen.SpecReadResult;
import com.georu.codegen.SpecTable;
import com.georu.codegen.TableSpecReader;
import com.georu.codegen.ReaderWriterMode;
import com.georu.codegen.WriterMode;
import com.georu.core.plugin.GeoruDataSources;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 코드 생성 화면의 검사 · 생성. 올린 파일은 메모리에서만 읽고 저장하지 않는다.
 * 소스 DB 종류 auto 는 그 DataSource 의 메타데이터로, 원천 메타 db 는 소스 DataSource 의 테이블 정보로 정한다.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "georu.codegen", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CodeGenService {

    public static final String TOOL = "CODE";

    private final GeoruDataSources dataSources;
    private final GenRunStore runs;
    private final TableSpecReader reader = new TableSpecReader();
    private final CodeGenerator generator = new CodeGenerator(ClassUtils.getDefaultClassLoader());

    public CodeGenService(GeoruDataSources dataSources, GenRunStore runs) {
        this.dataSources = dataSources;
        this.runs = runs;
    }

    /** 올린 파일 하나 */
    public record Upload(String name, byte[] content) {
    }

    /** 검사 · 생성 결과. {@code request} 는 규칙 파일 · 기본값을 채운 설정, {@code rules} 는 함께 올린 규칙 파일(없으면 빈 것) */
    public record Outcome(SpecReadResult spec, GenerationResult result, List<String> errors, List<String> warnings,
                          String sourceDialect, String targetDialect, CodeGenRequest request, RuleFile rules) {
        public boolean ok() {
            return errors.isEmpty() && result != null && result.ok();
        }
    }

    public Outcome run(Upload tobe, Upload asis, CodeGenRequest req) {
        return run(tobe, asis, null, req);
    }

    /** {@code rules} 는 업체 규칙 파일(선택). 설정의 빈 항목을 채우고, 사전은 업무 패키지 제안에 쓴다 */
    public Outcome run(Upload tobe, Upload asis, Upload rules, CodeGenRequest raw) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        RuleFile rf = RuleFile.empty();
        if (rules != null) {
            rf = RuleFile.read(rules.name(), rules.content());
            if (rf.error() != null) errors.add(rf.error());
            rf.warnings().forEach(w -> warnings.add("규칙 파일: " + w));
        }
        CodeGenRequest req = raw.resolved(rf);
        if (WriterMode.of(req.getWriterMode()) == null) {
            warnings.add("Writer 기본 값 '" + req.getWriterMode() + "' 을 알 수 없어 BATCH 로 만듭니다 (BATCH · REUSE)");
        }
        if (ReaderWriterMode.of(req.getReaderWriter()) == null) {
            warnings.add("Reader · Writer 파일 값 '" + req.getReaderWriter() + "' 을 알 수 없어 ENGINE(만들지 않음)으로 만듭니다 (ENGINE · FILES)");
        }
        SpecReadResult spec = reader.read(tobe.name(), tobe.content());
        String srcDialect = dialect(req.getSourceDialect(), req.getSourceDataSource(), "oracle", warnings);
        String tgtDialect = dialect(req.getTargetDialect(), req.getTargetDataSource(), "mysql", warnings);
        if (!spec.ok()) {
            errors.addAll(spec.errors());
            return new Outcome(spec, null, errors, warnings, srcDialect, tgtDialect, req, rf);
        }
        if (req.getCompany() == null || req.getCompany().isBlank()) {
            errors.add("업체 단어를 넣으세요 (예: acme → com.georu.acme). 규칙 파일의 기본값 시트 company 로도 줄 수 있습니다");
            return new Outcome(spec, null, errors, warnings, srcDialect, tgtDialect, req, rf);
        }
        if (!errors.isEmpty()) {
            return new Outcome(spec, null, errors, warnings, srcDialect, tgtDialect, req, rf);
        }
        CodegenSettings settings = CodegenSettings.of(req.getCompany().trim())
                .works(req.getWorks())
                .workNames(req.getWorkNames())
                .sourceDataSource(req.getSourceDataSource()).targetDataSource(req.getTargetDataSource())
                .sourceDialect(srcDialect).targetDialect(tgtDialect)
                .sourceSchema(req.getSourceSchema()).targetSchema(req.getTargetSchema())
                .writerMode(WriterMode.of(req.getWriterMode()))
                .readerWriter(ReaderWriterMode.of(req.getReaderWriter()))
                .commonColumns(req.getCommonColumns())
                .audit(req.getAudit())
                .fileName(tobe.name());
        SourceCatalog catalog = catalog(req, asis, errors, warnings);
        if (!errors.isEmpty()) {
            return new Outcome(spec, null, errors, warnings, srcDialect, tgtDialect, req, rf);
        }
        settings.sourceCatalog(catalog).sourceNamesExact("db".equalsIgnoreCase(req.getSourceMeta()));
        try {
            GenerationResult result = generator.generate(spec, settings);
            if (!rf.isEmpty()) result.binaries().put("rules.xlsx", rf.write());   // 어떤 규칙으로 만들었는지 생성물과 함께
            return new Outcome(spec, result, new ArrayList<>(result.errors()), warnings, srcDialect, tgtDialect, req, rf);
        } catch (IllegalStateException e) {
            errors.add(e.getMessage());   // 소스 DB 메타 조회 실패 등
            return new Outcome(spec, null, errors, warnings, srcDialect, tgtDialect, req, rf);
        }
    }

    /** 생성 기록 (실패해도 생성은 막지 않는다). {@code settingsJson} 은 화면이 보낸 settings 그대로 (다시 채우기용) */
    public void record(Outcome o, String company, String executedBy, String settingsJson) {
        try {
            runs.record(TOOL, executedBy, o.spec().fileName(), o.ok(), summary(o, company), settingsJson);
        } catch (Exception e) {
            log.warn("코드 생성 기록 실패: {}", e.getMessage());
        }
    }

    static String summary(Outcome o, String company) {
        if (!o.ok()) {
            return "오류 " + o.errors().size() + ": " + String.join(" / ", o.errors().stream().limit(3).toList());
        }
        GenerationResult r = o.result();
        long migrate = r.migrateCount();
        return "업체 " + company + " · 테이블 " + r.tables().size() + " (이관대상 " + migrate + " · 제외 " + (r.tables().size() - migrate)
                + ") · 파일 " + r.fileCount() + " · TODO " + r.todos().size() + " · " + r.zipName()
                + (o.rules() == null || o.rules().isEmpty() ? "" : " · 규칙 파일 (" + o.rules().summary() + ")");
    }

    /**
     * 업무구분 값마다 테이블 수 · 이관대상 수 · 정한 패키지 이름과 출처
     * (input 화면 입력 · file 파일의 업무 패키지 열 · value 업무구분 영문 · suggested 사전 · 테이블 접두어 제안 · 없음)
     */
    public static List<Map<String, Object>> works(SpecReadResult spec, Map<String, String> chosen) {
        return works(spec, chosen, Map.of());
    }

    /** {@code extra} 는 업체 규칙 파일의 사전 (일반 사전 위에 덮는다) */
    public static List<Map<String, Object>> works(SpecReadResult spec, Map<String, String> chosen, Map<String, String> extra) {
        Map<String, List<String>> tables = new LinkedHashMap<>();
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (SpecTable t : spec.tables()) {
            int[] c = counts.computeIfAbsent(t.work(), k -> new int[2]);
            c[0]++;
            if (t.migrate()) {
                c[1]++;
                tables.computeIfAbsent(t.work(), k -> new ArrayList<>()).add(t.name());
            }
        }
        Map<String, String> fromFile = spec.workPackages();
        List<Map<String, Object>> out = new ArrayList<>();
        counts.forEach((work, c) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", work);
            m.put("tables", c[0]);
            m.put("migrate", c[1]);
            String pkg = null, source = null, how = "";
            String in = chosen == null ? null : chosen.get(work);
            if (in != null && !in.isBlank()) {
                pkg = in;
                source = "input";
            } else if (fromFile.containsKey(work) && com.georu.codegen.WorkNames.suggest(fromFile.get(work), List.of(), extra) != null) {
                pkg = fromFile.get(work).toLowerCase(Locale.ROOT);
                source = "file";
            } else {
                com.georu.codegen.WorkNames.Suggestion sg = com.georu.codegen.WorkNames.suggest(work, tables.getOrDefault(work, List.of()), extra);
                if (sg != null) {
                    pkg = sg.pkg();
                    source = sg.how().equals("업무구분 영문") ? "value" : "suggested";
                    how = sg.how();
                }
            }
            m.put("pkg", pkg);
            m.put("source", source);
            m.put("how", how);
            m.put("needsName", c[1] > 0 && pkg == null);
            out.add(m);
        });
        return out;
    }

    private SourceCatalog catalog(CodeGenRequest req, Upload asis, List<String> errors, List<String> warnings) {
        String mode = req.getSourceMeta() == null ? "none" : req.getSourceMeta().trim().toLowerCase(Locale.ROOT);
        switch (mode) {
            case "file" -> {
                if (asis == null) {
                    errors.add("원천 메타를 ASIS 정의 파일로 고르면 ASIS 정의 파일을 올리세요");
                    return null;
                }
                SpecReadResult def = reader.readDefinition(asis.name(), asis.content());
                if (!def.ok()) {
                    def.errors().forEach(e -> errors.add("ASIS 정의: " + e));
                    return null;
                }
                warnings.addAll(def.warnings().stream().map(w -> "ASIS 정의: " + w).toList());
                return SourceCatalog.of(def);
            }
            case "db" -> {
                try {
                    return SourceCatalog.jdbc(dataSources.dataSource(req.getSourceDataSource()), req.getSourceSchema());
                } catch (RuntimeException e) {
                    errors.add("소스 DB 메타를 쓸 수 없습니다: " + e.getMessage());
                    return null;
                }
            }
            default -> {
                return null;
            }
        }
    }

    /** auto 면 DataSource 메타데이터로 DB 종류를 정한다. 알 수 없으면 기본값과 경고 */
    private String dialect(String requested, String dataSource, String fallback, List<String> warnings) {
        if (requested != null && !requested.isBlank() && !requested.equalsIgnoreCase("auto")) {
            return requested.trim().toLowerCase(Locale.ROOT);
        }
        try {
            DataSource ds = dataSources.dataSource(dataSource);
            try (Connection c = ds.getConnection()) {
                DatabaseMetaData md = c.getMetaData();
                String d = dialectOf(md.getDatabaseProductName(), md.getURL());
                if (d != null) return d;
                warnings.add(dataSource + " 의 DB 종류(" + md.getDatabaseProductName() + ")를 몰라 " + fallback + " 로 생성합니다");
            }
        } catch (Exception e) {
            warnings.add(dataSource + " 에 접속하지 못해 DB 종류를 " + fallback + " 로 둡니다 (" + e.getMessage() + ")");
        }
        return fallback;
    }

    static String dialectOf(String product, String url) {
        String p = product == null ? "" : product.toLowerCase(Locale.ROOT);
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (p.contains("oracle")) return "oracle";
        if (p.contains("mysql") || p.contains("mariadb")) return "mysql";
        if (p.contains("microsoft") || p.contains("sql server")) return "mssql";
        if (p.contains("postgres")) return "postgresql";
        if (p.contains("h2")) {
            if (u.contains("mode=oracle")) return "oracle";
            if (u.contains("mode=mssqlserver")) return "mssql";
            if (u.contains("mode=postgresql")) return "postgresql";
            return "mysql";
        }
        return null;
    }
}
