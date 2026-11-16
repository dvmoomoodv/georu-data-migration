package com.georu.api.generator;

import com.georu.core.plugin.GeoruDataSources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("코드 생성 API - 검사 · zip · 기록")
class GeneratorControllerTest {

    static final String TOBE = """
            테이블명,테이블 설명,컬럼명,타입,길이,PK,업무구분,이관대상,원천 테이블
            tb_member,회원,MEMBER_ID,BIGINT,,Y,회원,Y,MEMBER
            tb_member,,MEMBER_NM,VARCHAR,50,,,,
            tb_member,,REG_DATE,DATETIME,,,,,
            tb_code,코드,CODE_ID,VARCHAR,10,Y,code,Y,
            tb_tmp,임시,X,INT,,,code,N,
            """;
    static final String ASIS = """
            테이블명,컬럼명,컬럼타입,PK
            MEMBER,MEMBER_ID,NUMBER(18),Y
            MEMBER,MEMBER_NM,VARCHAR2(50),
            MEMBER,REG_DT,DATE,
            """;

    private EmbeddedDatabase ops;
    private EmbeddedDatabase source;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ops = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
        try (var c = ops.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("operation/schema/old/V9__gen_run-h2.sql"));
            ScriptUtils.executeSqlScript(c, new ClassPathResource("operation/schema/old/V10__gen_run_settings-h2.sql"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        source = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
        new JdbcTemplate(source).execute("CREATE TABLE MEMBER (MEMBER_ID NUMERIC(18) PRIMARY KEY, MEMBER_NM VARCHAR(50), REG_DT TIMESTAMP)");
        GeoruDataSources ds = mock(GeoruDataSources.class);
        when(ds.names()).thenReturn(Set.of("source-m", "target-m"));
        when(ds.dataSource(anyString())).thenReturn(source);
        GenRunStore runs = new GenRunStore(ops);
        CodeGenService service = new CodeGenService(ds, runs);
        mvc = MockMvcBuilders.standaloneSetup(new GeneratorController(service, new RelationService(runs), new MappingService(runs), runs, ds,
                new MockEnvironment().withProperty("georu.customer.name", "Acme Corp"), DataSize.ofMegabytes(1), true)).build();
    }

    @AfterEach
    void tearDown() {
        ops.shutdown();
        source.shutdown();
    }

    static MockMultipartFile tobe() {
        return new MockMultipartFile("file", "tobe.csv", "text/csv", TOBE.getBytes(StandardCharsets.UTF_8));
    }

    static String settings(String works, String meta) {
        return settings(works, meta, "");
    }

    /** extra: 더 넣을 JSON 항목 (예: {@code ,"readerWriter":"FILES"}) */
    static String settings(String works, String meta, String extra) {
        return "{\"company\":\"acme\",\"works\":" + works + ",\"sourceDialect\":\"oracle\",\"targetDialect\":\"mysql\",\"sourceMeta\":\"" + meta + "\"" + extra + "}";
    }

    @Test
    @DisplayName("기본값: DataSource 이름 · 업체 이름(소문자 영문 · 숫자만) · 크기 상한")
    void options() throws Exception {
        mvc.perform(get("/api/generator/options")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.company").value("acmecorp"))
                .andExpect(jsonPath("$.data.maxFileBytes").value(1048576))
                .andExpect(jsonPath("$.data.dataSources.length()").value(2));
    }

    @Test
    @DisplayName("검사: 한글 업무구분은 사전으로 제안(출처 suggested), 입력하면 input. 못 찾으면 needsName")
    void check() throws Exception {
        mvc.perform(multipart("/api/generator/code/check").file(tobe()).param("settings", settings("{}", "none")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.tables").value(3))
                .andExpect(jsonPath("$.data.works[0].value").value("회원"))
                .andExpect(jsonPath("$.data.works[0].pkg").value("member"))
                .andExpect(jsonPath("$.data.works[0].source").value("suggested"))
                .andExpect(jsonPath("$.data.works[0].needsName").value(false))
                .andExpect(jsonPath("$.data.works[1].source").value("value"))
                .andExpect(jsonPath("$.data.warnings[0]").value(org.hamcrest.Matchers.containsString("'member' 로 제안")));
        MockMultipartFile odd = new MockMultipartFile("file", "odd.csv", "text/csv", "테이블명,컬럼명,타입,업무구분,이관대상\ntb_a,ID,INT,ㅋㅋ,Y\n".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/generator/code/check").file(odd).param("settings", settings("{}", "none")))
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.works[0].needsName").value(true))
                .andExpect(jsonPath("$.data.errors[0]").value(org.hamcrest.Matchers.containsString("업무구분 'ㅋㅋ'")));

        mvc.perform(multipart("/api/generator/code/check").file(tobe()).param("settings", settings("{\"회원\":\"member\"}", "db")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.works[0].source").value("input"))
                .andExpect(jsonPath("$.data.works[0].pkg").value("member"))
                .andExpect(jsonPath("$.data.zipName").value(org.hamcrest.Matchers.startsWith("acme-codegen-")))
                .andExpect(jsonPath("$.data.report.length()").value(3))
                .andExpect(jsonPath("$.data.report[0].mapping").value(org.hamcrest.Matchers.containsString("공통 컬럼 1")))
                .andExpect(jsonPath("$.data.todoCounts.SOURCE_UNDEFINED").value(1))
                .andExpect(jsonPath("$.data.applyMd").value(org.hamcrest.Matchers.containsString("# 기본 소스 적용 안내")));
    }

    @Test
    @DisplayName("원천 메타를 ASIS 정의 파일로: 파일이 없으면 오류, 있으면 그 파일로 매핑")
    void asisFile() throws Exception {
        mvc.perform(multipart("/api/generator/code/check").file(tobe()).param("settings", settings("{\"회원\":\"member\"}", "file")))
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.errors[0]").value(org.hamcrest.Matchers.containsString("ASIS 정의 파일을 올리세요")));
        MockMultipartFile asis = new MockMultipartFile("asisFile", "asis.csv", "text/csv", ASIS.getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/generator/code/check").file(tobe()).file(asis).param("settings", settings("{\"회원\":\"member\"}", "file")))
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.report[0].mapping").value("이름 일치 2 · 공통 컬럼 1 · 감사 컬럼 0 · 미매핑 0 (TOBE 컬럼 3)"));
    }

    @Test
    @DisplayName("zip: 내려받기 · 실행 기록, 오류면 400 과 검사 결과")
    void generate() throws Exception {
        MvcResult r = mvc.perform(multipart("/api/generator/code/generate").file(tobe()).param("settings", settings("{\"회원\":\"member\"}", "db"))
                        .header("X-Executed-By", "tester"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("acme-codegen-")))
                .andReturn();
        List<String> names = new ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) names.add(e.getName());
        }
        assertThat(names).contains("APPLY.md", "src/main/java/com/georu/acme/member/MemberStrategy.java",
                "src/main/resources/georu/acme/code/TbCodeSourceMapper.xml");
        assertThat(names).as("기본 ENGINE: Reader 파일 없음").noneMatch(n -> n.contains("/reader/"));

        // readerWriter=FILES 면 테이블마다 Reader 파일
        String files = settings("{\"회원\":\"member\"}", "db", ",\"readerWriter\":\"FILES\"");
        byte[] zip = mvc.perform(multipart("/api/generator/code/generate").file(tobe()).param("settings", files).header("X-Executed-By", "tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(zipEntries(zip).keySet()).anyMatch(n -> n.contains("/reader/")).hasSizeGreaterThan(names.size());

        MockMultipartFile odd = new MockMultipartFile("file", "odd.csv", "text/csv", "테이블명,컬럼명,타입,업무구분,이관대상\ntb_a,ID,INT,ㅋㅋ,Y\n".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/generator/code/generate").file(odd).param("settings", settings("{}", "db")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("CODEGEN_INVALID"))
                .andExpect(jsonPath("$.data.works[0].needsName").value(true));

        mvc.perform(get("/api/generator/history").param("tool", "CODE"))
                .andExpect(jsonPath("$.data.length()").value(3))   // 기본 · FILES · 오류
                .andExpect(jsonPath("$.data[0].result").value("ERROR"))
                .andExpect(jsonPath("$.data[1].result").value("OK"))
                .andExpect(jsonPath("$.data[1].executed_by").value("tester"))
                .andExpect(jsonPath("$.data[1].executed_at").value(org.hamcrest.Matchers.matchesPattern("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
                .andExpect(jsonPath("$.data[1].summary").value(org.hamcrest.Matchers.containsString("이관대상 2")))
                // 그때 화면이 보낸 설정이 객체로 돌아온다 (다시 채우기용)
                .andExpect(jsonPath("$.data[1].settings.company").value("acme"))
                .andExpect(jsonPath("$.data[1].settings.works.회원").value("member"))
                .andExpect(jsonPath("$.data[1].settings.sourceMeta").value("db"));
    }

    @Test
    @DisplayName("기록의 설정으로 다시 생성하면 같은 zip 이 나온다. 설정이 없거나 너무 크면 기록만 남는다")
    void regenerateFromRecordedSettings() throws Exception {
        String s = settings("{\"회원\":\"member\"}", "db");
        byte[] first = mvc.perform(multipart("/api/generator/code/generate").file(tobe()).param("settings", s))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

        MvcResult h = mvc.perform(get("/api/generator/history").param("tool", "CODE")).andExpect(status().isOk()).andReturn();
        com.fasterxml.jackson.databind.JsonNode recorded = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(h.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data").get(0).path("settings");   // 한글 키(업무구분)가 깨지지 않게
        assertThat(recorded.isObject()).isTrue();

        byte[] again = mvc.perform(multipart("/api/generator/code/generate").file(tobe()).param("settings", recorded.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(zipEntries(again)).isEqualTo(zipEntries(first));   // 파일 목록 · 내용이 같다 (시각이 들어가는 항목은 비교에서 뺀다)

        // 설정 없이 호출 → settings 키 없음
        mvc.perform(multipart("/api/generator/code/generate").file(tobe())).andReturn();
        // 64KB 를 넘는 설정 → 저장하지 않고 요약에 표시
        String huge = "{\"company\":\"acme\",\"works\":{\"회원\":\"member\"},\"sourceMeta\":\"db\",\"note\":\"" + "x".repeat(GenRunStore.SETTINGS_MAX) + "\"}";
        mvc.perform(multipart("/api/generator/code/generate").file(tobe()).param("settings", huge)).andExpect(status().isOk());
        mvc.perform(get("/api/generator/history").param("tool", "CODE"))
                .andExpect(jsonPath("$.data[0].settings").doesNotExist())
                .andExpect(jsonPath("$.data[0].summary").value(org.hamcrest.Matchers.endsWith(GenRunStore.SETTINGS_SKIPPED)))
                .andExpect(jsonPath("$.data[1].settings").doesNotExist())
                .andExpect(jsonPath("$.data[2].settings.company").value("acme"));
    }

    /** zip 안 텍스트 파일 이름 → 내용. 생성 시각이 들어가는 줄(APPLY.md 머리 "생성 yyyy-MM-dd HH:mm · …")은 뺀다 */
    static java.util.Map<String, String> zipEntries(byte[] zip) throws Exception {
        java.util.Map<String, String> m = new java.util.TreeMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String body = new String(z.readAllBytes(), StandardCharsets.UTF_8);
                m.put(e.getName(), body.lines().filter(l -> !l.matches("^생성 \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} ·.*")).collect(java.util.stream.Collectors.joining("\n")));
            }
        }
        return m;
    }

    @Test
    @DisplayName("파일 크기 상한 · 빈 파일")
    void limits() throws Exception {
        byte[] big = new byte[(int) DataSize.ofMegabytes(1).toBytes() + 1];
        mvc.perform(multipart("/api/generator/code/check").file(new MockMultipartFile("file", "big.csv", "text/csv", big)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("크기 상한")));
        mvc.perform(multipart("/api/generator/code/check").file(new MockMultipartFile("file", "e.csv", "text/csv", new byte[0])))
                .andExpect(status().isBadRequest());
    }

    static final String DEF = """
            테이블명,컬럼명,타입,Not Null
            MEMBER,MEMBER_SEQ,NUMBER(10),Y
            MEMBER,MEMBER_NM,VARCHAR2(50),
            BOARD_POST,POST_SEQ,NUMBER(10),Y
            BOARD_POST,WRITER_MEMBER_SEQ,NUMBER(10),
            BOARD_POST,REG_MEMBER,NUMBER(10),
            """;

    @Test
    @DisplayName("관계 분석: 검사 요약 · 업체 규칙 · xlsx · 기록")
    void relations() throws Exception {
        MockMultipartFile def = new MockMultipartFile("file", "asis.csv", "text/csv", DEF.getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/generator/relations/check").file(def))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.tables").value(2))
                .andExpect(jsonPath("$.data.relations").value(1))
                .andExpect(jsonPath("$.data.sample[0].child").value("BOARD_POST"))
                .andExpect(jsonPath("$.data.sample[0].parent").value("MEMBER"))
                .andExpect(jsonPath("$.data.levels[0]").value(1));

        String rule = "{\"suffixRules\":[{\"suffix\":\"_MEMBER\",\"parentTable\":\"MEMBER\",\"parentKey\":\"MEMBER_SEQ\"}],\"auditColumns\":[\"REG_MEMBER\"]}";
        mvc.perform(multipart("/api/generator/relations/check").file(def).param("settings", rule))
                .andExpect(jsonPath("$.data.relations").value(2))
                .andExpect(jsonPath("$.data.low").value(1));

        MvcResult r = mvc.perform(multipart("/api/generator/relations/analyze").file(def).param("settings", rule).header("X-Executed-By", "tester"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("asis-relations-")))
                .andReturn();
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()))) {
            assertThat(wb.getSheetName(0)).isEqualTo("관계 목록");
            assertThat(wb.getSheet("관계 목록").getLastRowNum()).isEqualTo(2);
        }
        mvc.perform(multipart("/api/generator/relations/analyze").file(new MockMultipartFile("file", "x.csv", "text/csv", "a,b\n1,2\n".getBytes())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("RELATIONS_INVALID"));
        mvc.perform(get("/api/generator/history").param("tool", "RELATIONS"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].summary").value("테이블 2 · 관계 2 (FK추정 1 · 검토필요 1) · 적재 단계 2 · 순환 0"));
    }

    @Test
    @DisplayName("매핑 정의서 초안: ASIS 없으면 오류 · 코드표는 둘 다 · 검사 요약 · xlsx · 기록")
    void mapping() throws Exception {
        String asisDef = "테이블명,컬럼명,컬럼타입\nMEMBER,MEMBER_ID,NUMBER(10)\nMEMBER,MEMBER_NAME,VARCHAR2(50)\nMEMBER,REG_DT,DATE\n";
        MockMultipartFile asis = new MockMultipartFile("asisFile", "asis.csv", "text/csv", asisDef.getBytes(StandardCharsets.UTF_8));
        MockMultipartFile ac = new MockMultipartFile("asisCodeFile", "ac.csv", "text/csv", "CODE_ID,CODE,CODE_NAME\nG,1,남\nG,2,여\n".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile tc = new MockMultipartFile("tobeCodeFile", "tc.csv", "text/csv", "GUBUN,CODE,CODENM\nS,M,남\nS,F,여\n".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/generator/mapping/check").file(tobe()))
                .andExpect(jsonPath("$.data.ok").value(false)).andExpect(jsonPath("$.data.errors[0]").value("ASIS 정의 파일을 올리세요"));
        mvc.perform(multipart("/api/generator/mapping/check").file(tobe()).file(asis).file(ac))
                .andExpect(jsonPath("$.data.errors[0]").value(org.hamcrest.Matchers.containsString("함께 올려야")));
        mvc.perform(multipart("/api/generator/mapping/check").file(tobe()).file(asis).file(ac).file(tc))
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.tobeTables").value(2))
                .andExpect(jsonPath("$.data.mapped").value(1))
                .andExpect(jsonPath("$.data.groups[0].status").value("원천 지정"))
                .andExpect(jsonPath("$.data.codeMatched").value(1));
        MvcResult r = mvc.perform(multipart("/api/generator/mapping/draft").file(tobe()).file(asis).header("X-Executed-By", "tester"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("tobe-mapping-draft-")))
                .andReturn();
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()))) {
            assertThat(wb.getSheetName(2)).isEqualTo("테이블 매핑 정의서");
        }
        mvc.perform(get("/api/generator/history").param("tool", "MAPPING")).andExpect(jsonPath("$.data[0].result").value("OK"));
        mvc.perform(get("/api/generator/mapping/code-template")).andExpect(status().isOk());
    }

    static MockMultipartFile rules(com.georu.codegen.RuleFile r) {
        return new MockMultipartFile("rulesFile", "acme-rules.xlsx", "application/octet-stream", r.write());
    }

    @Test
    @DisplayName("업체 규칙 파일: 양식 · 읽기 · 내려받기(JSON → xlsx)")
    void rulesTemplateReadExport() throws Exception {
        MvcResult t = mvc.perform(get("/api/generator/rules/template")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("rules-template.xlsx"))).andReturn();
        mvc.perform(multipart("/api/generator/rules/read").file(new MockMultipartFile("rulesFile", "t.xlsx", "application/octet-stream", t.getResponse().getContentAsByteArray())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaults.company").value("acme"))
                .andExpect(jsonPath("$.data.workNames.강좌").value("subj"))
                .andExpect(jsonPath("$.data.suffixRules[0].suffix").value("_MEMBER"))
                .andExpect(jsonPath("$.data.warnings.length()").value(0));
        mvc.perform(multipart("/api/generator/rules/read").file(new MockMultipartFile("rulesFile", "r.csv", "text/csv", "a,b".getBytes())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("RULES_INVALID"));

        String dto = "{\"defaults\":{\"company\":\"zeta\",\"writerMode\":\"REUSE\",\"readerWriter\":\"FILES\"},\"workNames\":{\"회원\":\"mbr\"},\"audit\":{\"INDATE\":\"REG_DATE\"},"
                + "\"suffixRules\":[{\"suffix\":\"_dept\",\"parentTable\":\"dept\",\"parentKey\":\"dept_cd\",\"numericOnly\":false}],\"auditColumns\":[\"REG_MEMBER\"]}";
        MvcResult x = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/generator/rules/export")
                        .contentType("application/json").content(dto.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("zeta-rules.xlsx"))).andReturn();
        com.georu.codegen.RuleFile back = com.georu.codegen.RuleFile.read("zeta-rules.xlsx", x.getResponse().getContentAsByteArray());
        assertThat(back.defaults()).containsEntry("company", "zeta").containsEntry("writerMode", "REUSE").containsEntry("readerWriter", "FILES");
        assertThat(back.workNames()).containsEntry("회원", "mbr");
        assertThat(back.suffixRules().get(0).suffix()).isEqualTo("_DEPT");
        assertThat(back.suffixRules().get(0).numericOnly()).isFalse();
        assertThat(back.auditColumns()).contains("INDATE", "REG_DATE", "REG_MEMBER");
    }

    @Test
    @DisplayName("코드 생성에 규칙 파일: 빈 설정을 파일이 채우고(업체 단어 · 감사 규칙), 사전은 업체 값 우선, zip 에 rules.xlsx. 화면 값은 파일보다 우선")
    void codeWithRulesFile() throws Exception {
        com.georu.codegen.RuleFile rf = com.georu.codegen.RuleFile.of(
                Map.of("company", "acme", "sourceDialect", "oracle", "targetDialect", "mysql"),
                Map.of("회원", "mbr"), Map.of("REG_DATE", "REG_DT"), List.of(), List.of());
        MockMultipartFile asis = new MockMultipartFile("asisFile", "asis.csv", "text/csv", ASIS.getBytes(StandardCharsets.UTF_8));
        // 설정에 업체 단어 · 감사 규칙이 없다 → 규칙 파일이 채운다
        mvc.perform(multipart("/api/generator/code/check").file(tobe()).file(asis).file(rules(rf)).param("settings", "{\"sourceMeta\":\"file\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.works[0].pkg").value("mbr"))
                .andExpect(jsonPath("$.data.works[0].how").value(org.hamcrest.Matchers.startsWith("업체 사전")))
                .andExpect(jsonPath("$.data.report[0].mapping").value(org.hamcrest.Matchers.containsString("감사 컬럼 1")))
                .andExpect(jsonPath("$.data.rules.summary").value(org.hamcrest.Matchers.containsString("사전 1")))
                .andExpect(jsonPath("$.data.zipName").value(org.hamcrest.Matchers.startsWith("acme-codegen-")));
        // 화면 값이 파일보다 우선
        mvc.perform(multipart("/api/generator/code/check").file(tobe()).file(asis).file(rules(rf)).param("settings", "{\"company\":\"beta\",\"works\":{\"회원\":\"member\"},\"sourceMeta\":\"file\"}"))
                .andExpect(jsonPath("$.data.zipName").value(org.hamcrest.Matchers.startsWith("beta-codegen-")))
                .andExpect(jsonPath("$.data.works[0].pkg").value("member"))
                .andExpect(jsonPath("$.data.works[0].source").value("input"));
        // 규칙 파일 없이 업체 단어도 없으면 오류 (전과 같다)
        mvc.perform(multipart("/api/generator/code/check").file(tobe()).param("settings", "{\"sourceMeta\":\"none\"}"))
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.errors[0]").value(org.hamcrest.Matchers.containsString("업체 단어")));

        MvcResult r = mvc.perform(multipart("/api/generator/code/generate").file(tobe()).file(asis).file(rules(rf)).param("settings", "{\"sourceMeta\":\"file\"}"))
                .andExpect(status().isOk()).andReturn();
        List<String> names = new ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) names.add(e.getName());
        }
        assertThat(names).contains("rules.xlsx", "src/main/java/com/georu/acme/mbr/MbrStrategy.java");
        mvc.perform(get("/api/generator/history").param("tool", "CODE"))
                .andExpect(jsonPath("$.data[0].summary").value(org.hamcrest.Matchers.containsString("규칙 파일")));
    }

    @Test
    @DisplayName("관계 분석에 규칙 파일: 명명 규칙 · 감사 컬럼을 파일에서, xlsx 에 적용 규칙 시트")
    void relationsWithRulesFile() throws Exception {
        com.georu.codegen.RuleFile rf = com.georu.codegen.RuleFile.of(Map.of(), Map.of(), Map.of(),
                List.of(new com.georu.codegen.relation.RelationSettings.SuffixRule("_MEMBER", "MEMBER", "MEMBER_SEQ", true)), List.of("REG_MEMBER"));
        MockMultipartFile def = new MockMultipartFile("file", "asis.csv", "text/csv", DEF.getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/generator/relations/check").file(def).file(rules(rf)))
                .andExpect(jsonPath("$.data.relations").value(2))
                .andExpect(jsonPath("$.data.low").value(1))
                .andExpect(jsonPath("$.data.rules.suffixRules[0].suffix").value("_MEMBER"));
        MvcResult r = mvc.perform(multipart("/api/generator/relations/analyze").file(def).file(rules(rf))).andExpect(status().isOk()).andReturn();
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()))) {
            assertThat(wb.getSheet(com.georu.codegen.RuleFile.SHEET_APPLIED)).isNotNull();
        }
    }

    @Test
    @DisplayName("georu.codegen.history.settings=false 면 기록에 설정을 남기지 않는다")
    void historySettingsOff() throws Exception {
        GeoruDataSources ds = mock(GeoruDataSources.class);
        when(ds.names()).thenReturn(Set.of("source-m", "target-m"));
        when(ds.dataSource(anyString())).thenReturn(source);
        GenRunStore runs = new GenRunStore(ops);
        MockMvc off = MockMvcBuilders.standaloneSetup(new GeneratorController(new CodeGenService(ds, runs), new RelationService(runs), new MappingService(runs), runs, ds,
                new MockEnvironment(), DataSize.ofMegabytes(1), false)).build();
        off.perform(multipart("/api/generator/code/generate").file(tobe()).param("settings", settings("{\"회원\":\"member\"}", "db"))).andExpect(status().isOk());
        off.perform(get("/api/generator/history").param("tool", "CODE"))
                .andExpect(jsonPath("$.data[0].result").value("OK"))
                .andExpect(jsonPath("$.data[0].settings").doesNotExist());
    }

    @Test
    @DisplayName("예제 양식 내려받기: 코드 생성 · 테이블 정의")
    void templates() throws Exception {
        String type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        mvc.perform(get("/api/generator/code/template")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", type))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("tobe-definition-template.xlsx")));
        mvc.perform(get("/api/generator/relations/template")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("table-definition-template.xlsx")));
    }

    @Test
    @DisplayName("DB 종류 자동 판단: 제품 이름, H2 는 MODE")
    void dialectOf() {
        assertThat(CodeGenService.dialectOf("Oracle", "jdbc:oracle:thin:@x")).isEqualTo("oracle");
        assertThat(CodeGenService.dialectOf("MariaDB", "")).isEqualTo("mysql");
        assertThat(CodeGenService.dialectOf("Microsoft SQL Server", "")).isEqualTo("mssql");
        assertThat(CodeGenService.dialectOf("H2", "jdbc:h2:mem:x;MODE=Oracle")).isEqualTo("oracle");
        assertThat(CodeGenService.dialectOf("H2", "jdbc:h2:mem:x")).isEqualTo("mysql");
        assertThat(CodeGenService.dialectOf("Informix", "")).isNull();
    }
}
