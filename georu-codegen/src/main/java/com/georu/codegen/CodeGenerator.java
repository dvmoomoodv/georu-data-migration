package com.georu.codegen;

import com.georu.codegen.CodegenModel.Column;
import com.georu.codegen.CodegenModel.Company;
import com.georu.codegen.CodegenModel.Gen;
import com.georu.codegen.CodegenModel.Table;
import com.georu.codegen.CodegenModel.Work;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * TOBE 컬럼 정의 → 기본 소스 묶음. 업체 적용 전에 필요한 파일을 모두 만든다.
 *
 * <ul>
 *   <li>업체 기본 셋트: {Acme}Application · application.yml · application-dev.yml · common/{Acme}ConvertUtils · common/{Acme}AuditRules ·
 *       {Acme}ArchTest · gradle/georu-mapping-status.gradle (이미 있으면 풀 때 건너뛴다)</li>
 *   <li>업무구분마다: {업무}/{Work}Strategy (@GeoruPlugin + TableCatalog)</li>
 *   <li>이관대상 테이블마다: Reader · Processor · Writer · Source DTO · Target DTO · Source XML · Target XML</li>
 *   <li>매번: APPLY.md · generation-report.csv</li>
 * </ul>
 * 이관대상 N 인 테이블은 만들지 않고 리포트에 "제외"로 남긴다.
 *
 * <pre>
 * SpecReadResult spec = new TableSpecReader().read("tobe.xlsx", bytes);
 * GenerationResult r = new CodeGenerator().generate(spec, CodegenSettings.of("acme").work("회원", "member"));
 * if (r.ok()) Files.write(Path.of(r.zipName()), r.toZip());
 * </pre>
 */
public final class CodeGenerator {

    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern WHERE_NONE = Pattern.compile("^\\s*WHERE\\s+1\\s*=\\s*0\\b.*");
    private static final Pattern TODO_SUMMARY = Pattern.compile(".*TODO\\s+\\d+\\b.*");
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TemplateRenderer templates;
    private final Supplier<LocalDateTime> clock;

    public CodeGenerator() {
        this(CodeGenerator.class.getClassLoader());
    }

    /** 템플릿을 찾을 클래스로더 (업체 앱의 classpath:georu/codegen/ 덮어쓰기용) */
    public CodeGenerator(ClassLoader classLoader) {
        this(classLoader, LocalDateTime::now);
    }

    CodeGenerator(ClassLoader classLoader, Supplier<LocalDateTime> clock) {
        this.templates = new TemplateRenderer(classLoader);
        this.clock = clock;
    }

    public static String version() {
        try (InputStream in = CodeGenerator.class.getResourceAsStream("/georu/codegen/codegen.properties")) {
            Properties p = new Properties();
            if (in != null) p.load(in);
            String v = p.getProperty("version", "");
            return v.isEmpty() || v.startsWith("$") ? "dev" : v;
        } catch (IOException e) {
            return "dev";
        }
    }

    public GenerationResult generate(SpecReadResult spec, CodegenSettings settings) {
        GenerationResult result = new GenerationResult();
        result.warnings().addAll(spec.warnings());
        if (!spec.ok()) {
            result.errors().addAll(spec.errors());
            return result;
        }
        if (!Names.packageWord(settings.company())) {
            result.errors().add("업체 단어는 소문자 영문으로 시작하는 영문·숫자 한 단어여야 합니다 (예: acme): '" + settings.company() + "'");
            return result;
        }
        LocalDateTime now = clock.get();
        Gen gen = new Gen(version(), now.format(AT), settings.fileName().isEmpty() ? spec.fileName() : settings.fileName());
        Company company = new Company(settings);
        result.zipName(settings.company() + "-codegen-" + now.format(DAY) + ".zip");

        Map<String, Work> works = new LinkedHashMap<>();
        Map<String, String> classes = new HashMap<>();
        Set<String> missingWorks = new java.util.LinkedHashSet<>();
        Map<String, String> packages = resolvePackages(spec, settings, result.warnings());
        for (SpecTable st : spec.tables()) {
            if (!st.migrate()) {
                result.tables().add(new GenerationResult.TableReport(st.work(), "", st.name(), st.comment(), false,
                        st.source(), "", "", "제외", 0, 0, "이관대상 아님"));
                continue;
            }
            String key = packages.get(st.work());
            if (key == null) {
                missingWorks.add(st.work());
                continue;
            }
            Table table = table(st, settings, result);
            if (table == null) continue;
            String other = classes.putIfAbsent(table.getPascal().toLowerCase(java.util.Locale.ROOT), st.name());
            if (other != null) {
                result.errors().add(st.row() + "행: " + st.name() + " 의 클래스 이름 " + table.getPascal() + " 이 " + other + " 와 겹칩니다");
                continue;
            }
            works.computeIfAbsent(key, k -> {
                Work w = new Work(st.work(), k, company);
                company.getWorks().add(w);
                return w;
            }).getTables().add(table);
        }
        for (String w : missingWorks) {
            result.errors().add("업무구분 '" + w + "' 의 패키지 이름을 정하세요 (소문자 영문으로 시작하는 영문·숫자, 예: member)");
        }
        if (!result.ok()) return result;
        if (works.isEmpty()) {
            result.errors().add("이관대상 테이블이 없습니다");
            return result;
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("s", company);
        vars.put("gen", gen);
        String javaRoot = "src/main/java/" + company.getBasePackage().replace('.', '/') + "/";
        String resRoot = "src/main/resources/georu/" + company.getCompany() + "/";
        Map<String, String> files = result.files();

        // 업체 기본 셋트
        files.put(javaRoot + company.getPascal() + "Application.java", templates.render("app-application.java.txt", vars));
        files.put(javaRoot + "common/" + company.getPascal() + "ConvertUtils.java", templates.render("app-convert-utils.java.txt", vars));
        files.put(javaRoot + "common/" + company.getPascal() + "AuditRules.java", templates.render("app-audit-rules.java.txt", vars));
        files.put("src/main/resources/application.yml", templates.render("app-application.yml.txt", vars));
        files.put("src/main/resources/application-dev.yml", templates.render("app-application-dev.yml.txt", vars));
        files.put("src/test/java/" + company.getBasePackage().replace('.', '/') + "/" + company.getPascal() + "ArchTest.java",
                templates.render("app-arch-test.java.txt", vars));
        files.put("gradle/georu-mapping-status.gradle", templates.raw("app-mapping-status.gradle"));

        Map<String, int[]> perTable = new HashMap<>();
        Map<String, String> mappings = new HashMap<>();
        Map<String, String> notes = new HashMap<>();
        Map<String, String> writers = new HashMap<>();
        for (Work w : company.getWorks()) {
            vars.put("w", w);
            String wJava = javaRoot + w.getKey() + "/";
            files.put(wJava + w.getPascal() + "Strategy.java", templates.render("work-strategy.java.txt", vars));
            for (Table t : w.getTables()) {
                vars.put("t", t);
                Map<String, String> tableFiles = new LinkedHashMap<>();
                if (t.isReaderFile()) {
                    tableFiles.put(wJava + "reader/" + t.getPascal() + "Reader.java", "table-reader.java.txt");
                }
                tableFiles.put(wJava + "processor/" + t.getPascal() + "Processor.java", "table-processor.java.txt");
                if (t.isWriterFile()) {
                    tableFiles.put(wJava + "writer/" + t.getPascal() + "Writer.java",
                            t.getWriterMode().equals("REUSE") ? "table-writer-reuse.java.txt" : "table-writer.java.txt");
                }
                tableFiles.put(wJava + "dto/" + t.getPascal() + "SourceDto.java", "table-source-dto.java.txt");
                tableFiles.put(wJava + "dto/" + t.getPascal() + "TargetDto.java", "table-target-dto.java.txt");
                tableFiles.put(resRoot + w.getKey() + "/" + t.getPascal() + "SourceMapper.xml", "table-source-mapper.xml.txt");
                tableFiles.put(resRoot + w.getKey() + "/" + t.getPascal() + "TargetMapper.xml", "table-target-mapper.xml.txt");
                int todo = 0;
                for (Map.Entry<String, String> f : tableFiles.entrySet()) {
                    String content = templates.render(f.getValue(), vars);
                    files.put(f.getKey(), content);
                    todo += scan(w, t, f.getKey(), content, result.todos());
                }
                perTable.put(t.getTable(), new int[]{todo, tableFiles.size()});
                mappings.put(t.getTable(), t.getMappingSummary());
                notes.put(t.getTable(), t.getNote());
                writers.put(t.getTable(), t.getWriterMode());
            }
            vars.remove("t");
        }
        vars.remove("w");

        // 리포트 (파일 순서대로, 제외 포함)
        List<GenerationResult.TableReport> reports = new ArrayList<>();
        for (SpecTable st : spec.tables()) {
            if (!st.migrate()) {
                reports.add(result.tables().stream().filter(r -> r.table().equals(st.name())).findFirst().orElseThrow());
                continue;
            }
            Work w = works.get(packages.get(st.work()));
            int[] n = perTable.get(st.name().toLowerCase(java.util.Locale.ROOT));
            boolean mapped = !st.source().isEmpty();
            reports.add(new GenerationResult.TableReport(st.work(), w.getKey(), st.name(), st.comment(), true, st.source(),
                    mappings.get(st.name().toLowerCase(java.util.Locale.ROOT)), writers.get(st.name().toLowerCase(java.util.Locale.ROOT)),
                    mapped ? "생성" : "생성 (골격)", n[0], n[1],
                    mapped ? notes.getOrDefault(st.name().toLowerCase(java.util.Locale.ROOT), "") : "원천 테이블이 없어 SELECT 골격만 (WHERE 1 = 0)"));
        }
        result.tables().clear();
        result.tables().addAll(reports);

        vars.put("sum", summary(result, company, spec, settings));
        vars.put("zipName", result.zipName());
        files.put("generation-report.csv", report(result));
        LinkedHashMap<String, String> ordered = new LinkedHashMap<>();
        ordered.put("APPLY.md", templates.render("apply-guide.md.txt", vars));
        ordered.putAll(files);
        files.clear();
        files.putAll(ordered);
        return result;
    }

    // ------------------------------------------------------------------ 업무 패키지

    /** 업무구분마다 패키지 이름: 화면 설정 → 파일의 업무 패키지 열 → 영문 그대로 · 사전 · 테이블 접두어 제안(경고). 없으면 빠진다 */
    static Map<String, String> resolvePackages(SpecReadResult spec, CodegenSettings settings, List<String> warnings) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, String> fromFile = spec.workPackages();
        Map<String, List<String>> tables = new LinkedHashMap<>();
        for (SpecTable st : spec.tables()) {
            if (st.migrate() && !st.work().isEmpty()) tables.computeIfAbsent(st.work(), k -> new ArrayList<>()).add(st.name());
        }
        for (String work : tables.keySet()) {
            String chosen = settings.works().get(work);
            if (chosen != null && Names.packageWord(chosen)) {
                out.put(work, chosen);
                continue;
            }
            String file = fromFile.get(work);
            if (file != null) {
                String f = file.trim().toLowerCase(java.util.Locale.ROOT);
                if (Names.packageWord(f)) {
                    out.put(work, f);
                    continue;
                }
                warnings.add("업무구분 '" + work + "' 의 업무 패키지 '" + file + "' 은 패키지 이름으로 쓸 수 없어 제안으로 대신합니다 (소문자 영문으로 시작하는 영문·숫자)");
            }
            WorkNames.Suggestion sg = WorkNames.suggest(work, tables.get(work), settings.workNames());
            if (sg != null) {
                out.put(work, sg.pkg());
                if (!sg.how().equals("업무구분 영문")) {
                    warnings.add("업무구분 '" + work + "' 의 패키지 이름을 '" + sg.pkg() + "' 로 제안했습니다 (" + sg.how() + "). 바꾸려면 화면에서 입력하거나 파일의 업무 패키지 열에 적으세요");
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 테이블 모델

    private Table table(SpecTable st, CodegenSettings settings, GenerationResult result) {
        if (!TABLE_NAME.matcher(st.name()).matches()) {
            result.errors().add(st.row() + "행: 테이블명 '" + st.name() + "' 은 영문으로 시작하는 영문·숫자·밑줄이어야 합니다");
            return null;
        }
        if (st.columns().isEmpty()) {
            result.errors().add(st.row() + "행: " + st.name() + " 에 컬럼이 없습니다");
            return null;
        }
        List<Column> columns = new ArrayList<>();
        Set<String> fields = new HashSet<>();
        boolean ok = true;
        for (SpecColumn sc : st.columns()) {
            String field = Names.field(sc.name());
            if (!Names.identifier(field) || !TABLE_NAME.matcher(sc.name()).matches()) {
                result.errors().add(sc.row() + "행: " + st.name() + "." + sc.name() + " 은 자바 필드 이름으로 바꿀 수 없습니다 (영문으로 시작하는 영문·숫자·밑줄, 예약어 아님)");
                ok = false;
                continue;
            }
            if (!fields.add(field)) {
                result.errors().add(sc.row() + "행: " + st.name() + "." + sc.name() + " 의 필드 이름 " + field + " 이 다른 컬럼과 겹칩니다");
                ok = false;
                continue;
            }
            JavaTypes.Mapping type = JavaTypes.of(sc.type(), sc.length());
            if (!type.known()) {
                result.warnings().add(sc.row() + "행: " + st.name() + "." + sc.name() + " 의 타입 " + sc.type() + " 을 몰라 String 으로 둡니다");
            }
            columns.add(new Column(sc, type, settings));
        }
        if (!ok) return null;
        SourceCatalog.SourceTable meta = null;
        if (!st.source().isEmpty() && settings.sourceCatalog() != null) {
            meta = settings.sourceCatalog().table(st.source());
            if (meta == null) {
                result.warnings().add(st.row() + "행: " + st.name() + " 의 원천 테이블 " + st.source() + " 을 소스 메타에서 찾지 못했습니다 (같은 이름 가정)");
            }
        }
        return new Table(st, columns, settings, meta);
    }

    // ------------------------------------------------------------------ 할 일 (scanMappingStatus 와 같은 규칙)

    private static int scan(Work w, Table t, String file, String content, List<GenerationResult.Todo> todos) {
        int count = 0;
        boolean xml = file.endsWith("Mapper.xml");
        // 템플릿이 CRLF 로 체크아웃된 환경(Windows)에서는 줄 끝에 \r 이 남아 정규식 .* 가 끝까지 가지 못한다 → 줄 끝 공백 · \r 을 뗀다
        String[] lines = content.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].stripTrailing();
            String type = null;
            if (line.contains("@mig-ok")) continue;
            if (xml && WHERE_NONE.matcher(line).matches()) {
                type = "SOURCE_UNDEFINED";
            } else if (line.contains("TODO")) {
                if (TODO_SUMMARY.matcher(line).matches() || line.contains("(TODO)")) continue;
                if (line.contains("TODO PK")) type = "PK_TODO";
                else if (line.contains("TODO 정렬키")) type = "SORT_KEY_TODO";
                else if (line.contains("TODO ←") || line.contains("TODO <-")) type = "COLUMN_TODO";
                else type = "TODO";
            }
            if (type == null) continue;
            count++;
            todos.add(new GenerationResult.Todo(w.getKey(), t.getTable(), type, file, i + 1, line.trim()));
        }
        return count;
    }

    // ------------------------------------------------------------------ 안내·리포트

    private static Map<String, Object> summary(GenerationResult r, Company company, SpecReadResult spec, CodegenSettings settings) {
        Map<String, Object> m = new HashMap<>();
        long migrate = r.tables().stream().filter(GenerationResult.TableReport::migrate).count();
        long skeleton = r.tables().stream().filter(t -> t.migrate() && t.source().isEmpty()).count();
        m.put("tables", r.tables().size());
        m.put("migrate", migrate);
        m.put("mapped", migrate - skeleton);
        m.put("skeleton", skeleton);
        m.put("excluded", r.tables().size() - migrate);
        m.put("works", company.getWorks().size());
        m.put("workKeys", String.join(", ", company.getWorks().stream().map(Work::getKey).toList()));
        m.put("javaFiles", r.files().keySet().stream().filter(f -> f.endsWith(".java")).count());
        m.put("xmlFiles", r.files().keySet().stream().filter(f -> f.endsWith(".xml")).count());
        m.put("configFiles", r.files().keySet().stream().filter(f -> f.endsWith(".yml") || f.endsWith(".gradle")).count());
        m.put("todo", r.todos().size());
        m.put("columnTodo", r.todos().stream().filter(t -> t.type().equals("COLUMN_TODO")).count());
        m.put("sourceUndefined", r.todos().stream().filter(t -> t.type().equals("SOURCE_UNDEFINED")).count());
        m.put("skeletonTables", r.todos().stream().filter(t -> t.type().equals("SOURCE_UNDEFINED")).toList());
        List<GenerationResult.Todo> other = r.todos().stream()
                .filter(t -> !t.type().equals("SOURCE_UNDEFINED") && !skeletonTable(r, t)).toList();
        m.put("todoTop", other.stream().limit(20).toList());
        m.put("todoMore", Math.max(0, other.size() - 20));
        m.put("batch", r.tables().stream().filter(t -> "BATCH".equals(t.writer())).count());
        m.put("reuse", r.tables().stream().filter(t -> "REUSE".equals(t.writer())).count());
        m.put("readerWriter", settings.readerWriter().name());
        m.put("warnings", r.warnings());
        m.put("fileName", spec.fileName());
        return m;
    }

    /** 골격 테이블의 할 일은 표의 한 줄로 묶어 보여 준다 */
    private static boolean skeletonTable(GenerationResult r, GenerationResult.Todo todo) {
        return r.tables().stream().anyMatch(t -> t.migrate() && t.source().isEmpty() && t.table().equalsIgnoreCase(todo.table()));
    }

    private static String report(GenerationResult r) {
        StringBuilder sb = new StringBuilder("업무구분,패키지,테이블,설명,이관대상,원천 테이블,매핑,Writer,상태,TODO,파일,비고\n");
        for (GenerationResult.TableReport t : r.tables()) {
            sb.append(csv(t.work())).append(',').append(csv(t.pkg())).append(',').append(csv(t.table())).append(',')
                    .append(csv(t.comment())).append(',').append(t.migrate() ? "Y" : "N").append(',').append(csv(t.source()))
                    .append(',').append(csv(t.mapping())).append(',').append(csv(t.writer())).append(',').append(csv(t.status())).append(',').append(t.todo()).append(',').append(t.files())
                    .append(',').append(csv(t.note())).append('\n');
        }
        if (!r.todos().isEmpty()) {
            sb.append('\n').append("업무,테이블,종류,파일,줄,내용\n");
            for (GenerationResult.Todo t : r.todos()) {
                sb.append(csv(t.work())).append(',').append(csv(t.table())).append(',').append(t.type()).append(',')
                        .append(csv(t.file())).append(',').append(t.line()).append(',').append(csv(t.text())).append('\n');
            }
        }
        return sb.toString();
    }

    private static String csv(String v) {
        if (v == null) return "";
        return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }
}
