package com.georu.codegen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("코드 생성")
class CodeGeneratorTest {

    static final LocalDateTime AT = LocalDateTime.of(2026, 10, 28, 9, 30);

    static GenerationResult generate(CodegenSettings settings) {
        SpecReadResult spec = new TableSpecReader().read("tobe.csv", Samples.CSV.getBytes(StandardCharsets.UTF_8));
        return new CodeGenerator(CodeGeneratorTest.class.getClassLoader(), () -> AT).generate(spec, settings);
    }

    static CodegenSettings acme() {
        return CodegenSettings.of("acme").work("회원", "member");
    }

    @Test
    @DisplayName("업체 기본 셋트 + 업무 Strategy + 테이블마다 5개(ENGINE, REUSE 는 Writer 더해 6) 또는 7개(FILES) + 안내 · 리포트. 이관대상 N 은 만들지 않는다")
    void fileSet() {
        GenerationResult engine = generate(acme());
        String jj = "src/main/java/com/georu/acme/";
        assertThat(engine.errors()).isEmpty();
        assertThat(engine.files().keySet()).noneMatch(f -> f.contains("/reader/"))
                .contains(jj + "board/writer/TbBoardWriter.java")             // REUSE 테이블은 Writer 파일 (엔진 공용 Writer 는 BATCH 뿐)
                .doesNotContain(jj + "member/writer/TbMemberWriter.java", jj + "member/writer/TbMemberLogWriter.java");
        assertThat(engine.files()).hasSize(2 + 7 + 2 + 5 + 5 + 6);
        assertThat(engine.tables()).filteredOn(GenerationResult.TableReport::migrate).extracting(GenerationResult.TableReport::files)
                .containsExactly(5, 5, 6);

        GenerationResult r = generate(acme().readerWriter(ReaderWriterMode.FILES));

        assertThat(r.errors()).isEmpty();
        String j = "src/main/java/com/georu/acme/";
        String x = "src/main/resources/georu/acme/";
        assertThat(r.files().keySet()).first().isEqualTo("APPLY.md");
        assertThat(r.files().keySet()).contains(
                "generation-report.csv",
                j + "AcmeApplication.java", j + "common/AcmeConvertUtils.java", j + "common/AcmeAuditRules.java",
                "src/main/resources/application.yml", "src/main/resources/application-dev.yml",
                "src/test/java/com/georu/acme/AcmeArchTest.java", "gradle/georu-mapping-status.gradle",
                j + "member/MemberStrategy.java", j + "board/BoardStrategy.java",
                j + "member/reader/TbMemberReader.java", j + "member/processor/TbMemberProcessor.java",
                j + "member/writer/TbMemberWriter.java", j + "member/dto/TbMemberSourceDto.java",
                j + "member/dto/TbMemberTargetDto.java", x + "member/TbMemberSourceMapper.xml",
                x + "member/TbMemberTargetMapper.xml", j + "member/reader/TbMemberLogReader.java",
                j + "board/reader/TbBoardReader.java");
        assertThat(r.files()).hasSize(2 + 7 + 2 + 3 * 7);
        assertThat(r.files().keySet()).noneMatch(f -> f.contains("TbTmp"));
        assertThat(r.zipName()).isEqualTo("acme-codegen-20261028.zip");
        assertThat(r.tables()).extracting(GenerationResult.TableReport::status)
                .containsExactly("생성", "생성 (골격)", "생성", "제외");
    }

    @Test
    @DisplayName("Strategy 는 @GeoruPlugin(업체.업무) + ENGINE 은 Processor 만(REUSE 는 Writer 도), FILES 는 Reader · Processor · Writer 연결")
    void strategy() {
        GenerationResult engine = generate(acme());
        String em = engine.files().get("src/main/java/com/georu/acme/member/MemberStrategy.java");
        assertThat(em).doesNotContain(".reader(Tb").doesNotContain(".writer(Tb").doesNotContain(".pageSize(")
                .doesNotContain("import com.georu.acme.member.reader.*;").doesNotContain("import com.georu.acme.member.writer.*;")
                .contains("엔진 공용 Reader · Writer 가 SourceMapper · TargetMapper 를 이름 규칙으로 부른다")
                .contains("TableDef.of(\"tb_member\", TbMemberSourceDto.class, TbMemberTargetDto.class)    // 회원\n"
                        + "                        .processor(TbMemberProcessor.class),\n")
                .contains(".processor(TbMemberLogProcessor.class)\n        );");
        assertThat(engine.files().get("src/main/java/com/georu/acme/board/BoardStrategy.java"))
                .contains("import com.georu.acme.board.writer.*;").doesNotContain("import com.georu.acme.board.reader.*;")
                .contains("                        .processor(TbBoardProcessor.class)\n                        .writer(TbBoardWriter.class)\n        );");
        assertThat(CodegenSettings.DEFAULT_PAGE_SIZE).as("엔진 공용 Reader 기본 페이지 크기와 같아야 .pageSize 생략이 맞다")
                .isEqualTo(com.georu.core.plugin.TableDef.of("x", Object.class, Object.class).pageSize());
        assertThat(generate(acme().pageSize(500)).files().get("src/main/java/com/georu/acme/member/MemberStrategy.java"))
                .contains("                        .processor(TbMemberProcessor.class)\n                        .pageSize(500),\n");

        String s = generate(acme().readerWriter(ReaderWriterMode.FILES)).files().get("src/main/java/com/georu/acme/member/MemberStrategy.java");
        assertThat(s).contains("import com.georu.acme.member.reader.*;").contains("import com.georu.acme.member.writer.*;");

        assertThat(s).contains("package com.georu.acme.member;")
                .contains("@GeoruPlugin(name = \"acme.member\", description = \"회원\", source = \"source-m\", target = \"target-m\")")
                .contains("public class MemberStrategy implements TableCatalog")
                .contains("TableDef.of(\"tb_member\", TbMemberSourceDto.class, TbMemberTargetDto.class)    // 회원\n"
                        + "                        .reader(TbMemberReader.class)\n"
                        + "                        .processor(TbMemberProcessor.class)\n"
                        + "                        .writer(TbMemberWriter.class),\n")
                .contains(".writer(TbMemberLogWriter.class)\n        );");
    }

    @Test
    @DisplayName("원천이 있으면 같은 이름 SELECT, 없으면 골격(WHERE 1 = 0 · TODO ← ASIS ?). 주석의 -- 는 XML 에서 깨지지 않게 바꾼다")
    void sourceSql() {
        GenerationResult r = generate(acme());
        String mapped = r.files().get("src/main/resources/georu/acme/member/TbMemberSourceMapper.xml");
        String skeleton = r.files().get("src/main/resources/georu/acme/member/TbMemberLogSourceMapper.xml");

        assertThat(mapped).contains("<mapper namespace=\"georu.acme.member.TbMemberSourceMapper\">")
                .contains("        WHERE 1 = 1\n")
                .contains("              A.\"MEMBER_ID\" AS \"MEMBER_ID\"    <!-- 회원ID BIGINT ← MEMBER_ID (같은 이름으로 가정) -->\n"
                        + "            , A.\"MEMBER_NM\" AS \"MEMBER_NM\"")
                .contains("FROM \"MEMBER_SRC\" A")
                .contains("ORDER BY A.\"MEMBER_ID\"\n")
                .contains("OFFSET #{_skiprows} ROWS FETCH NEXT #{_pagesize} ROWS ONLY")
                .contains("등록일시 - - 감사")
                .doesNotContain("TODO");
        assertThat(skeleton).containsPattern("(?m)^\\s*WHERE 1 = 0 ")
                .contains("NULL AS \"MSG\"    <!-- VARCHAR(200) TODO ← ASIS ? -->");
        assertThat(r.todos()).filteredOn(t -> t.table().equals("tb_member_log"))
                .extracting(GenerationResult.Todo::type)
                .containsExactlyInAnyOrder("SOURCE_UNDEFINED", "COLUMN_TODO", "COLUMN_TODO", "TODO", "TODO", "PK_TODO");
        // 원천 없는 PK 는 검증하지 않는다 (검증이 채번보다 먼저라 막힌다)
        assertThat(r.files().get("src/main/java/com/georu/acme/member/processor/TbMemberLogProcessor.java"))
                .doesNotContain("LOG_ID 누락").contains("// TODO PK: LOG_ID 원천 없음 - 채번이면 Strategy 에");
        assertThat(r.files().get("src/main/java/com/georu/acme/member/processor/TbMemberProcessor.java"))
                .contains("failure(\"MEMBER_ID 누락\")").doesNotContain("TODO PK");
        assertThat(r.todos()).noneMatch(t -> t.table().equals("tb_member"));
    }

    @Test
    @DisplayName("DTO 타입 · @MigrationId, Writer 는 GeoruDataSources 의 타겟, Target SQL 은 타겟 DB 따옴표")
    void javaAndTarget() {
        GenerationResult r = generate(acme().targetSchema("acme_tobe").readerWriter(ReaderWriterMode.FILES));
        String target = r.files().get("src/main/java/com/georu/acme/member/dto/TbMemberTargetDto.java");
        String writer = r.files().get("src/main/java/com/georu/acme/member/writer/TbMemberWriter.java");
        String insert = r.files().get("src/main/resources/georu/acme/member/TbMemberTargetMapper.xml");

        assertThat(target).contains("import java.math.BigDecimal;\nimport java.time.LocalDate;\nimport java.time.LocalDateTime;\n")
                .contains("    /** MEMBER_ID BIGINT : 회원ID */\n    @MigrationId\n    private Long memberId;")
                .contains("private BigDecimal point;").contains("private LocalDate birthDt;").contains("private LocalDateTime regDtm;");
        assertThat(writer).contains("dataSources.sqlSessionFactory(\"target-m\")")
                .contains("return item != null ? item.getMemberId() : null;");
        assertThat(insert).contains("INSERT INTO `acme_tobe`.`tb_member` (")
                .contains("              `MEMBER_ID`    <!-- BIGINT 회원ID -->\n")
                .contains("            , #{regDtm}\n        )");
    }

    @Test
    @DisplayName("Writer: 기본 BATCH(AbstractBatchMigrationWriter), 설정 · 파일 Writer 열로 REUSE(AbstractMigrationWriter + writeToDatabase)")
    void writerMode() {
        GenerationResult batch = generate(acme().readerWriter(ReaderWriterMode.FILES));
        String member = batch.files().get("src/main/java/com/georu/acme/member/writer/TbMemberWriter.java");
        String board = batch.files().get("src/main/java/com/georu/acme/board/writer/TbBoardWriter.java");
        assertThat(member).contains("extends AbstractBatchMigrationWriter<TbMemberTargetDto>").doesNotContain("writeToDatabase");
        assertThat(board).contains("extends AbstractMigrationWriter<TbBoardTargetDto>")
                .contains("    protected void writeItems(List<? extends TbBoardTargetDto> items) throws Exception {\n        writeToDatabase(items);\n    }")
                .contains("session.insert(NS + \".insertData\", item);");

        GenerationResult reuse = generate(acme().writerMode(WriterMode.REUSE));
        assertThat(reuse.files().get("src/main/java/com/georu/acme/member/writer/TbMemberWriter.java")).contains("extends AbstractMigrationWriter<");
        assertThat(reuse.tables()).filteredOn(GenerationResult.TableReport::migrate).extracting(GenerationResult.TableReport::writer)
                .containsOnly("REUSE");
        assertThat(WriterMode.of("batch")).isEqualTo(WriterMode.BATCH);
        assertThat(WriterMode.of("x")).isNull();
        assertThat(ReaderWriterMode.of("files")).isEqualTo(ReaderWriterMode.FILES);
        assertThat(ReaderWriterMode.of("engine")).isEqualTo(ReaderWriterMode.ENGINE);
        assertThat(ReaderWriterMode.of("x")).isNull();
    }

    @Test
    @DisplayName("업무 패키지: 파일 열 → 영문 그대로 → 사전(번호 · 관리 뗌) → 테이블 접두어, 화면 입력이 가장 먼저")
    void workPackage() {
        String csv = "테이블명,컬럼명,타입,업무구분,업무 패키지\n"
                + "tb_a,ID,INT,02 회원관리,\n"            // 사전
                + "tb_b,ID,INT,Board,\n"                  // 영문 그대로
                + "tb_c,ID,INT,수강신청,enroll\n"          // 파일 열
                + "tb_zeta_x,ID,INT,알수없음,\n"           // 테이블 접두어 zeta
                + "tb_zeta_y,ID,INT,알수없음,\n";
        SpecReadResult spec = new TableSpecReader().read("x.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(spec.workPackages()).containsExactly(java.util.Map.entry("수강신청", "enroll"));
        List<String> warnings = new ArrayList<>();
        assertThat(CodeGenerator.resolvePackages(spec, CodegenSettings.of("acme").work("Board", "bbs"), warnings))
                .containsExactly(java.util.Map.entry("02 회원관리", "member"), java.util.Map.entry("Board", "bbs"),
                        java.util.Map.entry("수강신청", "enroll"), java.util.Map.entry("알수없음", "zeta"));
        assertThat(warnings).hasSize(2);
        assertThat(WorkNames.suggest("게시판(board)", List.of())).extracting(WorkNames.Suggestion::pkg).isEqualTo("board");
        assertThat(WorkNames.suggest("코드", List.of("tb_code", "tb_code_group")).how()).startsWith("사전");
        assertThat(WorkNames.suggest("ㅋㅋ", List.of("tb_data_a", "tb_data_b"))).isNull();   // data 는 일반어
    }

    @Test
    @DisplayName("테이블 단위 값이 행마다 다르면 경고하고 처음 값, 모든 행에 같게 쓰면 경고 없음")
    void tableLevelConflict() {
        String same = "테이블명,컬럼명,타입,업무구분,이관대상,Writer\ntb_a,ID,INT,x,Y,REUSE\ntb_a,NM,INT,x,Y,REUSE\n";
        SpecReadResult ok = new TableSpecReader().read("x.csv", same.getBytes(StandardCharsets.UTF_8));
        assertThat(ok.warnings()).isEmpty();
        assertThat(ok.tables().get(0).writer()).isEqualTo(WriterMode.REUSE);
        String diff = "테이블명,컬럼명,타입,업무구분,이관대상,Writer\ntb_a,ID,INT,x,Y,REUSE\ntb_a,NM,INT,y,N,BATCH\n";
        SpecReadResult warn = new TableSpecReader().read("x.csv", diff.getBytes(StandardCharsets.UTF_8));
        assertThat(warn.warnings()).singleElement().asString().contains("3행").contains("업무구분 · 이관대상 · Writer").contains("처음 값");
        assertThat(warn.tables().get(0).work()).isEqualTo("x");
        assertThat(warn.tables().get(0).migrate()).isTrue();
    }

    @Test
    @DisplayName("PK 가 없으면 TODO PK · TODO 정렬키")
    void noPk() {
        String csv = "테이블명,컬럼명,타입,업무구분,원천\ntb_x,A,INT,x,SRC_X\ntb_x,B,INT,,\n";
        GenerationResult r = new CodeGenerator().generate(new TableSpecReader().read("x.csv", csv.getBytes(StandardCharsets.UTF_8)),
                CodegenSettings.of("acme"));

        assertThat(r.errors()).isEmpty();
        assertThat(r.todos()).extracting(GenerationResult.Todo::type).containsExactlyInAnyOrder("PK_TODO", "SORT_KEY_TODO");
        assertThat(r.files().get("src/main/resources/georu/acme/x/TbXSourceMapper.xml")).contains("ORDER BY A.ROWID    <!-- TODO 정렬키");
    }

    @Test
    @DisplayName("설정 오류: 업체 단어, 한글 업무구분의 패키지 이름, 필드 이름으로 못 바꾸는 컬럼")
    void settingErrors() {
        assertThat(generate(CodegenSettings.of("Acme Corp")).errors()).singleElement().asString().contains("업체 단어");
        // 한글 업무구분은 사전으로 제안한다 (회원 → member). 제안했다는 경고가 남는다
        GenerationResult suggested = generate(CodegenSettings.of("acme"));
        assertThat(suggested.errors()).isEmpty();
        assertThat(suggested.files()).containsKey("src/main/java/com/georu/acme/member/MemberStrategy.java");
        assertThat(suggested.warnings()).anyMatch(w -> w.contains("'회원' 의 패키지 이름을 'member' 로 제안") && w.contains("사전"));
        // 사전에도 없고 테이블 접두어도 다르면 입력을 요구한다
        String noName = "테이블명,컬럼명,타입,업무구분\ntb_a,ID,INT,ㅋㅋ\ntb_b,ID,INT,ㅋㅋ\n";
        assertThat(new CodeGenerator().generate(new TableSpecReader().read("x.csv", noName.getBytes(StandardCharsets.UTF_8)), CodegenSettings.of("acme")).errors())
                .singleElement().asString().contains("업무구분 'ㅋㅋ'");

        String csv = "테이블명,컬럼명,타입,업무구분\ntb_x,ID,INT,x\ntb_x,USER#,INT,x\ntb_x,x-y,INT,x\n";
        GenerationResult r = new CodeGenerator().generate(new TableSpecReader().read("x.csv", csv.getBytes(StandardCharsets.UTF_8)),
                CodegenSettings.of("acme"));
        assertThat(r.errors()).hasSize(2).allMatch(e -> e.contains("자바 필드 이름"));
    }

    @Test
    @DisplayName("APPLY.md 는 업체와 무관한 일반 안내에 값만 채운다")
    void applyGuide() {
        GenerationResult r = generate(acme());
        String apply = r.files().get("APPLY.md");

        assertThat(apply).contains("생성 2026-10-28 09:30 · georu ").contains("올린 파일 tobe.csv")
                .contains("기본 패키지 `com.georu.acme`")
                .contains("| 업무 | 2 (member, board) |")
                .contains("| 테이블 | 4 중 이관대상 3 (원천 지정 2 · 골격 1), 제외 1 |")
                .contains("| Writer | BATCH 2 · REUSE 1 → 6절 |")
                .contains("| Reader · Writer 파일 | 엔진 공용, 파일 없음 (ENGINE). REUSE 테이블만 Writer 파일 → 6절 |")
                .contains("**Reader · Writer 파일 (ENGINE · FILES).**")
                .contains("## 6. Writer 고르기").contains("| 회원 · 고객 마스터 |").contains("/ui/guide?name=writer-choice")
                .contains("unzip -n acme-codegen-20261028.zip -d .")
                .contains("apply from: 'gradle/georu-mapping-status.gradle'")
                .contains("| member | tb_member_log | `src/main/resources/georu/acme/member/TbMemberLogSourceMapper.xml:10` |")
                .doesNotContain("[(").doesNotContain("[#");
        assertThat(r.files().get("generation-report.csv"))
                .startsWith("업무구분,패키지,테이블,설명,이관대상,원천 테이블,매핑,Writer,상태,TODO,파일,비고\n"
                        + "회원,member,tb_member,회원,Y,MEMBER_SRC,원천 메타 없음 · 같은 이름 가정 5,BATCH,생성,0,5,\n");
    }

    @Test
    @DisplayName("zip: 저장소 루트 기준 경로, CSV 는 BOM")
    void zip() throws Exception {
        GenerationResult r = generate(acme());
        List<String> names = new ArrayList<>();
        byte[] csvHead = null;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(r.toZip()), StandardCharsets.UTF_8)) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                names.add(e.getName());
                if (e.getName().endsWith(".csv")) csvHead = java.util.Arrays.copyOf(in.readAllBytes(), 3);
            }
        }
        assertThat(names).containsExactlyElementsOf(r.files().keySet());
        assertThat(csvHead).containsExactly(0xEF, 0xBB, 0xBF);
    }

    /** 예시 zip 을 내려 본다: ./gradlew :georu-codegen:test --tests '*CodeGeneratorTest.dump' -PcodegenDump=/tmp/acme.zip */
    @Test
    @EnabledIfSystemProperty(named = "georu.codegen.dump", matches = ".+")
    void dump() throws Exception {
        Files.write(Path.of(System.getProperty("georu.codegen.dump")), generate(acme()).toZip());
    }
}
