package com.georu.api.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("이관 진행 체크리스트 API - 권장 단계 · 체크 · 엑셀 왕복 · 미리보기 · 적용 · 되돌리기")
class ProjectChecklistControllerTest {

    private EmbeddedDatabase ops;
    private MockMvc mvc;
    /** 앱(Spring Boot)과 같게 날짜를 ISO 문자열로 (spring.jackson.serialization.write-dates-as-timestamps=false 기본) */
    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    @BeforeEach
    void setUp() {
        ops = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
        new ResourceDatabasePopulator(new ClassPathResource("operation/schema/old/V12__checklist-h2.sql")).execute(ops);
        mvc = MockMvcBuilders.standaloneSetup(new ProjectChecklistController(new ChecklistService(new ChecklistStore(ops))))
                .setMessageConverters(new org.springframework.http.converter.ByteArrayHttpMessageConverter(), new MappingJackson2HttpMessageConverter(json))
                .defaultResponseCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8).build();
    }

    @AfterEach
    void tearDown() {
        ops.shutdown();
    }

    private JsonNode view() throws Exception {
        return json.readTree(mvc.perform(get("/api/project/checklist").header("X-Executed-By", "관리자"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    private long idOf(JsonNode v, String title) {
        for (JsonNode p : v.get("phases")) for (JsonNode i : p.get("items")) if (i.get("title").asText().equals(title)) return i.get("id").asLong();
        throw new AssertionError("항목 없음: " + title);
    }

    @Test
    @DisplayName("처음 열면 권장 단계로 채우고(한 번만) 기록을 남긴다. 단계 상태는 항목에서 계산")
    void seedAndCheck() throws Exception {
        JsonNode v = view();
        int total = ChecklistFile.defaults().size();
        assertThat(v.get("total").asInt()).isEqualTo(total);
        assertThat(v.get("phases").get(0).get("name").asText()).isEqualTo("투입 준비");
        assertThat(v.get("phases").get(0).get("status").asText()).isEqualTo("WAIT");
        assertThat(v.get("currentPhase").asInt()).isZero();
        assertThat(view().get("total").asInt()).isEqualTo(total);   // 두 번째는 채우지 않는다
        assertThat(new JdbcTemplate(ops).queryForObject("SELECT COUNT(*) FROM migration_checklist_event WHERE action = 'SEED'", Integer.class)).isEqualTo(1);

        // 0 단계 필수 항목을 하나씩 체크 → 진행 중 → 완료, 현재 단계가 1 로
        JsonNode phase0 = v.get("phases").get(0);
        long first = phase0.get("items").get(0).get("id").asLong();
        mvc.perform(put("/api/project/checklist/items/" + first).header("X-Executed-By", "관리자")
                        .contentType("application/json").content("{\"checked\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phases[0].items[0].checked").value(true)).andExpect(jsonPath("$.data.phases[0].items[0].checkedBy").value("관리자"))
                .andExpect(jsonPath("$.data.phases[0].status").value("IN_PROGRESS"));   // PUT 이 갱신된 전체를 돌려준다
        for (JsonNode i : phase0.get("items")) {
            mvc.perform(put("/api/project/checklist/items/" + i.get("id").asLong()).header("X-Executed-By", "관리자")
                    .contentType("application/json").content("{\"checked\":true}")).andExpect(status().isOk());
        }
        JsonNode after = view();
        assertThat(after.get("phases").get(0).get("status").asText()).isEqualTo("DONE");
        assertThat(after.get("phases").get(0).get("completedAt").isNull()).isFalse();
        assertThat(after.get("currentPhase").asInt()).isEqualTo(1);

        // 해제하면 체크한 사람 · 시각이 지워지고 기록이 남는다. 메모
        mvc.perform(put("/api/project/checklist/items/" + first).contentType("application/json").content("{\"checked\":false,\"memo\":\"계정 대기\"}"))
                .andExpect(jsonPath("$.data.phases[0].items[0].checked").value(false)).andExpect(jsonPath("$.data.phases[0].items[0].checkedBy").doesNotExist())
                .andExpect(jsonPath("$.data.phases[0].items[0].memo").value("계정 대기"));
        mvc.perform(get("/api/project/checklist/events").param("itemId", String.valueOf(first)))
                .andExpect(jsonPath("$.data[0].action").value("MEMO")).andExpect(jsonPath("$.data[1].action").value("UNCHECK"))
                .andExpect(jsonPath("$.data[2].action").value("CHECK"))
                // 시각은 항목의 checkedAt 과 같은 형식(로컬 시각, 시간대 표시 없음). Timestamp 그대로면 UTC 로 나가 화면이 어긋난다
                .andExpect(jsonPath("$.data[0].createdAt").value(org.hamcrest.Matchers.matchesPattern("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+")));
        mvc.perform(put("/api/project/checklist/items/99999").contentType("application/json").content("{\"checked\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("엑셀 내려받기 → 고쳐 올리기: 미리보기는 바꾸지 않고, 적용하면 같은 항목의 체크 · 메모를 유지한다")
    void excelRoundTrip() throws Exception {
        JsonNode v = view();
        long keepId = idOf(v, "시스템 화면에서 DB 3개 UP 확인");
        long dropId = idOf(v, "회고");
        mvc.perform(put("/api/project/checklist/items/" + keepId).contentType("application/json").content("{\"checked\":true,\"memo\":\"10-30 확인\"}"));
        mvc.perform(put("/api/project/checklist/items/" + dropId).contentType("application/json").content("{\"checked\":true}"));

        byte[] xlsx = mvc.perform(get("/api/project/checklist/download"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("georu-checklist-")))
                .andReturn().getResponse().getContentAsByteArray();
        byte[] edited;
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.getSheet(ChecklistFile.SHEET);
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("단계 순서");
            for (int r = 1; r <= s.getLastRowNum(); r++) {
                Row row = s.getRow(r);
                String title = row.getCell(2).getStringCellValue();
                if (title.equals("시스템 화면에서 DB 3개 UP 확인")) row.getCell(4).setCellValue("설명 바꿈");     // 바뀜
                if (title.equals("회고")) s.removeRow(row);                                                    // 삭제 (체크된 채)
            }
            Row add = s.createRow(s.getLastRowNum() + 1);                                                        // 추가
            add.createCell(0).setCellValue(6);
            add.createCell(1).setCellValue("사후 지원");
            add.createCell(2).setCellValue("1주 모니터링");
            add.createCell(3).setCellValue("N");
            wb.write(out);
            edited = out.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile("file", "mine.xlsx", "application/octet-stream", edited);
        mvc.perform(multipart("/api/project/checklist/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.added").value(1)).andExpect(jsonPath("$.data.removed").value(1))
                .andExpect(jsonPath("$.data.changed").value(1)).andExpect(jsonPath("$.data.removedChecked[0]").value("종료 · 회고"));
        assertThat(view().get("total").asInt()).isEqualTo(ChecklistFile.defaults().size());   // 미리보기는 바꾸지 않는다

        mvc.perform(multipart("/api/project/checklist/upload").file(file).param("apply", "true").header("X-Executed-By", "관리자"))
                .andExpect(status().isOk());
        JsonNode applied = view();
        assertThat(applied.get("total").asInt()).isEqualTo(ChecklistFile.defaults().size());   // +1 -1
        JsonNode kept = find(applied, keepId);
        assertThat(kept.get("checked").asBoolean()).isTrue();
        assertThat(kept.get("memo").asText()).isEqualTo("10-30 확인");
        assertThat(kept.get("info").asText()).isEqualTo("설명 바꿈");
        assertThat(applied.get("phases").get(applied.get("phases").size() - 1).get("name").asText()).isEqualTo("사후 지원");
        mvc.perform(get("/api/project/checklist/events")).andExpect(jsonPath("$.data[0].action").value("IMPORT"))
                .andExpect(jsonPath("$.data[0].detail").value(org.hamcrest.Matchers.containsString("체크된 채 삭제: 종료 · 회고")));

        // 권장 단계로 되돌리기: 추가한 단계는 지워지고 체크는 유지
        mvc.perform(post("/api/project/checklist/reset")).andExpect(jsonPath("$.data.removed").value(1)).andExpect(jsonPath("$.data.added").value(1));
        JsonNode reset = view();
        assertThat(find(reset, keepId).get("checked").asBoolean()).isTrue();
        assertThat(reset.get("phases")).extracting(p -> p.get("name").asText()).doesNotContain("사후 지원");
    }

    @Test
    @DisplayName("잘못된 양식은 400 과 이유, 체크리스트는 그대로")
    void invalid() throws Exception {
        view();
        mvc.perform(multipart("/api/project/checklist/upload").file(new MockMultipartFile("file", "x.csv", "text/csv", "a,b".getBytes())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("CHECKLIST_INVALID"));
        byte[] bad;
        try (Workbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("x");
            Row h = s.createRow(0);
            List.of("단계 순서", "단계 이름", "항목").forEach(n -> h.createCell(h.getLastCellNum() < 0 ? 0 : h.getLastCellNum()).setCellValue(n));
            Row r1 = s.createRow(1);
            r1.createCell(0).setCellValue("첫째");
            r1.createCell(1).setCellValue("준비");
            r1.createCell(2).setCellValue("가");
            Row r2 = s.createRow(2);
            r2.createCell(0).setCellValue(1);
            r2.createCell(1).setCellValue("준비");
            r2.createCell(2).setCellValue("나");
            Row r3 = s.createRow(3);
            r3.createCell(0).setCellValue(1);
            r3.createCell(1).setCellValue("준비");
            r3.createCell(2).setCellValue("나");
            wb.write(out);
            bad = out.toByteArray();
        }
        mvc.perform(multipart("/api/project/checklist/upload").file(new MockMultipartFile("file", "bad.xlsx", "application/octet-stream", bad)).param("apply", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("2행: 단계 순서 '첫째' 는 0~999 정수여야 합니다")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("4행: 같은 단계에 같은 항목 '나'")));
        assertThat(view().get("total").asInt()).isEqualTo(ChecklistFile.defaults().size());
    }

    @Test
    @DisplayName("양식 검사: 단계 순서는 정수, 저장 길이 초과는 오류, 필수 열의 아니요 · No 는 선택")
    void parseRules() {
        String[] h = {"단계 순서", "단계 이름", "항목", "필수"};
        ChecklistFile.Parsed p = ChecklistFile.parse(java.util.List.of(
                new String[]{"1.5", "추가", "가", ""},
                new String[]{"1", "준비", "x".repeat(301), ""},
                new String[]{"2", "개발", "나", "아니요"},
                new String[]{"2", "개발", "다", "No"},
                new String[]{"2", "개발", "라", "글쎄"}), h);
        assertThat(p.errors()).anyMatch(e -> e.startsWith("2행:") && e.contains("정수"))
                .anyMatch(e -> e.startsWith("3행:") && e.contains("항목 301자 (최대 300자)"));
        assertThat(p.items()).extracting(ChecklistItem::title, ChecklistItem::required)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("나", false), org.assertj.core.groups.Tuple.tuple("다", false),
                        org.assertj.core.groups.Tuple.tuple("라", true));
        assertThat(p.warnings()).anyMatch(w -> w.contains("'글쎄'"));
    }

    @Test
    @DisplayName("같은 단계 이름 + 항목은 DB 에서도 한 행 (동시 적용 중복 방지), 지워진 항목 체크는 404")
    void uniqueAndDeleted() throws Exception {
        view();
        JdbcTemplate jdbc = new JdbcTemplate(ops);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO migration_checklist_item (phase_no, phase_name, title, sort_no, updated_at) VALUES (0, '투입 준비', '네트워크 · 방화벽 확인', 9, CURRENT_TIMESTAMP)"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        long id = jdbc.queryForObject("SELECT MIN(item_id) FROM migration_checklist_item", Long.class);
        ChecklistStore store = new ChecklistStore(ops);
        assertThat(store.setChecked(id, true, "x")).isTrue();
        jdbc.update("DELETE FROM migration_checklist_item WHERE item_id = ?", id);
        assertThat(store.setChecked(id, false, "x")).isFalse();
    }

    private static JsonNode find(JsonNode v, long id) {
        for (JsonNode p : v.get("phases")) for (JsonNode i : p.get("items")) if (i.get("id").asLong() == id) return i;
        throw new AssertionError("항목 없음: " + id);
    }
}
