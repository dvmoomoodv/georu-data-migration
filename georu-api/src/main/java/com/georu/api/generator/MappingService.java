package com.georu.api.generator;

import com.georu.codegen.CodeSpec;
import com.georu.codegen.MappingDraft;
import com.georu.codegen.RuleFile;
import com.georu.codegen.SpecReadResult;
import com.georu.codegen.TableSpecReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 매핑 정의서 초안: ASIS · TOBE 정의(+ 공통코드 표) → 초안 xlsx. 올린 파일은 저장하지 않는다.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "georu.codegen", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MappingService {

    public static final String TOOL = "MAPPING";

    private final GenRunStore runs;
    private final TableSpecReader reader = new TableSpecReader();

    public MappingService(GenRunStore runs) {
        this.runs = runs;
    }

    /** 읽기 오류(errors)가 있으면 result 가 null. {@code rules} 는 함께 올린 규칙 파일(없으면 빈 것) */
    public record Outcome(List<String> errors, List<String> warnings, String tobeName, MappingDraft.Result result, MappingRequest request, RuleFile rules) {
        public boolean ok() {
            return errors.isEmpty() && result != null;
        }
    }

    public Outcome run(CodeGenService.Upload tobe, CodeGenService.Upload asis, CodeGenService.Upload asisCode, CodeGenService.Upload tobeCode,
                       MappingRequest req) {
        return run(tobe, asis, asisCode, tobeCode, null, req);
    }

    public Outcome run(CodeGenService.Upload tobe, CodeGenService.Upload asis, CodeGenService.Upload asisCode, CodeGenService.Upload tobeCode,
                       CodeGenService.Upload rules, MappingRequest raw) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        RuleFile rf = RuleFile.empty();
        if (rules != null) {
            rf = RuleFile.read(rules.name(), rules.content());
            if (rf.error() != null) errors.add(rf.error());
            rf.warnings().forEach(w -> warnings.add("규칙 파일: " + w));
        }
        MappingRequest req = raw.resolved(rf);
        if (asis == null) errors.add("ASIS 정의 파일을 올리세요");
        if ((asisCode == null) != (tobeCode == null)) errors.add("공통코드 매핑은 ASIS · TOBE 코드표를 함께 올려야 합니다");
        SpecReadResult t = reader.readDefinition(tobe.name(), tobe.content());
        prefixed("TOBE 정의", t.errors(), errors);
        prefixed("TOBE 정의", t.warnings(), warnings);
        SpecReadResult a = null;
        if (asis != null) {
            a = reader.readDefinition(asis.name(), asis.content());
            prefixed("ASIS 정의", a.errors(), errors);
            prefixed("ASIS 정의", a.warnings(), warnings);
        }
        CodeSpec ac = asisCode == null ? null : CodeSpec.read(asisCode.name(), asisCode.content());
        CodeSpec tc = tobeCode == null ? null : CodeSpec.read(tobeCode.name(), tobeCode.content());
        if (ac != null) prefixed("ASIS 코드", ac.errors(), errors);
        if (tc != null) prefixed("TOBE 코드", tc.errors(), errors);
        if (!errors.isEmpty()) return new Outcome(errors, warnings, tobe.name(), null, req, rf);
        MappingDraft.Result r = MappingDraft.build(a, t, ac, tc, MappingDraft.Settings.defaults()
                .sourceDialect(req.getSourceDialect()).commonColumns(req.isCommonColumns()).audit(req.getAudit()));
        warnings.addAll(r.warnings());
        return new Outcome(errors, warnings, tobe.name(), r, req, rf);
    }

    private static void prefixed(String what, List<String> from, List<String> to) {
        from.forEach(m -> to.add(what + ": " + m));
    }

    public static String fileName(String tobeName) {
        String base = tobeName == null ? "mapping" : tobeName.replaceAll("\\.[^.]+$", "").replaceAll("[^\\p{L}\\p{N}_-]+", "_");
        return base + "-mapping-draft-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
    }

    public static String summary(Outcome o) {
        if (!o.ok()) return "오류 " + o.errors().size() + ": " + String.join(" / ", o.errors().stream().limit(3).toList());
        MappingDraft.Result r = o.result();
        return "TOBE 테이블 " + r.tobeTables() + " (ASIS 대응 " + r.mapped() + ") · ASIS 미매핑 " + r.asisUnmapped() + " · 정의 행 " + r.rows()
                + " (자동 제안 " + r.autoRows() + ")" + (r.codeGroups().isEmpty() ? "" : " · 코드그룹 대응 " + r.codeMatchedGroups() + "/" + r.codeGroups().size())
                + (o.rules() == null || o.rules().isEmpty() ? "" : " · 규칙 파일");
    }

    /** 초안 기록. {@code settingsJson} 은 화면이 보낸 settings 그대로 (다시 채우기용) */
    public void record(Outcome o, String executedBy, String settingsJson) {
        try {
            runs.record(TOOL, executedBy, o.tobeName(), o.ok(), summary(o), settingsJson);
        } catch (Exception e) {
            log.warn("매핑 정의서 초안 기록 실패: {}", e.getMessage());
        }
    }

    public static Map<String, Object> view(Outcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", o.ok());
        m.put("errors", o.errors());
        m.put("warnings", o.warnings());
        if (!o.ok()) return m;
        MappingDraft.Result r = o.result();
        m.put("tobeTables", r.tobeTables());
        m.put("mapped", r.mapped());
        m.put("estimated", r.estimated());
        m.put("tobeNew", r.tobeTables() - r.mapped());
        m.put("asisUnmapped", r.asisUnmapped());
        m.put("excluded", r.excludedAsis().size());
        m.put("rows", r.rows());
        m.put("autoRows", r.autoRows());
        m.put("codeGroups", r.codeGroups().size());
        m.put("codeMatched", r.codeMatchedGroups());
        List<Map<String, Object>> groups = new ArrayList<>();
        for (MappingDraft.Group g : r.groups()) {
            if (groups.size() >= 400) break;
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("no", g.no());
            x.put("tobe", g.tobe() == null ? "" : g.tobe().name());
            x.put("tobeComment", g.tobe() == null ? "" : g.tobe().comment());
            x.put("work", g.tobe() == null ? "" : g.tobe().work());
            x.put("asis", String.join(", ", g.asisTables()));
            x.put("type", g.type());
            x.put("status", g.status());
            x.put("basis", g.basis());
            x.put("rows", g.rows().size());
            x.put("auto", g.rows().stream().filter(y -> !y.gubun().isEmpty()).count());
            groups.add(x);
        }
        m.put("groups", groups);
        m.put("groupCount", r.groups().size());
        m.put("xlsxName", fileName(o.tobeName()));
        return m;
    }
}
