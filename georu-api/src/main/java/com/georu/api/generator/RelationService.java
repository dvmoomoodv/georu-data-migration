package com.georu.api.generator;

import com.georu.codegen.RuleFile;
import com.georu.codegen.SpecReadResult;
import com.georu.codegen.TableSpecReader;
import com.georu.codegen.relation.Relation;
import com.georu.codegen.relation.RelationAnalyzer;
import com.georu.codegen.relation.RelationSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 관계 분석: 테이블 구조 정의 → 이름으로 FK 추정 → 분석용 xlsx. 올린 파일은 저장하지 않는다.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "georu.codegen", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RelationService {

    public static final String TOOL = "RELATIONS";

    private final GenRunStore runs;
    private final TableSpecReader reader = new TableSpecReader();

    public RelationService(GenRunStore runs) {
        this.runs = runs;
    }

    /** 읽기 · 분석 결과. 읽기 오류면 result 가 null. {@code rules} 는 함께 올린 규칙 파일(없으면 빈 것), {@code request} 는 그것을 채운 설정 */
    public record Outcome(SpecReadResult spec, RelationAnalyzer.Result result, RelationRequest request, RuleFile rules) {
        public boolean ok() {
            return spec.ok() && result != null;
        }
    }

    public Outcome run(CodeGenService.Upload file, RelationRequest req) {
        return run(file, null, req);
    }

    public Outcome run(CodeGenService.Upload file, CodeGenService.Upload rules, RelationRequest raw) {
        RuleFile rf = RuleFile.empty();
        SpecReadResult spec = reader.readDefinition(file.name(), file.content());
        if (rules != null) {
            rf = RuleFile.read(rules.name(), rules.content());
            if (rf.error() != null) spec.errors().add(rf.error());
            rf.warnings().forEach(w -> spec.warnings().add("규칙 파일: " + w));
        }
        RelationRequest req = raw.resolved(rf);
        if (!spec.ok()) return new Outcome(spec, null, req, rf);
        RelationSettings s = RelationSettings.defaults().declaredPk(req.isDeclaredPk());
        for (RelationRequest.SuffixRule r : req.getSuffixRules()) {
            if (r.getSuffix() != null && !r.getSuffix().isBlank() && r.getParentTable() != null && r.getParentKey() != null) {
                s.suffixRule(r.getSuffix().trim(), r.getParentTable().trim(), r.getParentKey().trim(), r.isNumericOnly());
            }
        }
        s.auditColumns(req.getAuditColumns().toArray(String[]::new));
        return new Outcome(spec, new RelationAnalyzer(s).analyze(spec), req, rf);
    }

    public static String fileName(String uploaded) {
        String base = uploaded == null ? "tables" : uploaded.replaceAll("\\.[^.]+$", "").replaceAll("[^\\p{L}\\p{N}_-]+", "_");
        return base + "-relations-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
    }

    public static String summary(Outcome o) {
        if (!o.ok()) return "오류 " + o.spec().errors().size() + ": " + String.join(" / ", o.spec().errors().stream().limit(3).toList());
        RelationAnalyzer.Result r = o.result();
        return "테이블 " + r.tables().size() + " · 관계 " + r.relations().size() + " (FK추정 " + r.estimated() + " · 검토필요 " + r.review()
                + ") · 적재 단계 " + r.loadOrder().size() + " · 순환 " + r.cycles().size()
                + (o.rules() == null || o.rules().isEmpty() ? "" : " · 규칙 파일");
    }

    /** 분석 기록. {@code settingsJson} 은 화면이 보낸 settings 그대로 (다시 채우기용) */
    public void record(Outcome o, String executedBy, String settingsJson) {
        try {
            runs.record(TOOL, executedBy, o.spec().fileName(), o.ok(), summary(o), settingsJson);
        } catch (Exception e) {
            log.warn("관계 분석 기록 실패: {}", e.getMessage());
        }
    }

    /** 화면용 요약 */
    public static Map<String, Object> view(Outcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", o.ok());
        m.put("fileName", o.spec().fileName());
        m.put("errors", o.spec().errors());
        m.put("warnings", o.spec().warnings());
        if (!o.ok()) return m;
        RelationAnalyzer.Result r = o.result();
        long variants = r.tables().stream().filter(RelationAnalyzer.TableInfo::variant).count();
        m.put("tables", r.tables().size());
        m.put("variants", variants);
        m.put("pkDeclared", r.tables().stream().filter(t -> !t.variant() && t.pkBasis().equals("정의서 PK")).count());
        m.put("pkEstimated", r.tables().stream().filter(t -> !t.variant() && t.pkBasis().contains("추정")).count());
        m.put("pkNone", r.tables().stream().filter(t -> !t.variant() && t.pk().isEmpty()).count());
        m.put("relations", r.relations().size());
        m.put("estimated", r.estimated());
        m.put("review", r.review());
        m.put("duplicated", r.duplicated());
        m.put("high", r.count("HIGH"));
        m.put("medium", r.count("MEDIUM"));
        m.put("low", r.count("LOW"));
        m.put("levels", r.loadOrder().stream().map(List::size).toList());
        m.put("cycles", r.cycles());
        m.put("sample", r.relations().stream().limit(300).map(RelationService::row).toList());
        m.put("xlsxName", fileName(o.spec().fileName()));
        return m;
    }

    private static Map<String, Object> row(Relation x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", x.id());
        m.put("child", x.child());
        m.put("childCols", String.join("+", x.childCols()));
        m.put("parent", x.parent());
        m.put("parentCols", String.join("+", x.parentCols()));
        m.put("card", x.card());
        m.put("conf", x.conf());
        m.put("status", x.status());
        m.put("dup", !x.dup().isEmpty());
        m.put("basis", x.basis());
        m.put("notes", String.join(" / ", x.notes()));
        return m;
    }
}
