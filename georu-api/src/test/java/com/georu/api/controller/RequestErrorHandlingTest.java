package com.georu.api.controller;

import com.georu.api.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 요청 형식 오류(필수 파라미터·파트·헤더 누락, 본문 없음·JSON 오류, Content-Type)는 500 이 아니라 4xx.
 * 시험 컨트롤러를 api.controller 패키지(처리기 basePackages 범위)에 두고 실제 처리기로 확인한다. DB·Spring 컨텍스트 불필요.
 */
@DisplayName("요청 오류 응답 형식")
class RequestErrorHandlingTest {

    @RestController
    static class Probe {
        @GetMapping("/probe/param")
        String param(@RequestParam String pluginName) {
            return pluginName;
        }

        @PostMapping("/probe/part")
        String part(@RequestPart("file") MultipartFile file, @RequestParam String tableName) {
            return tableName;
        }

        @GetMapping("/probe/header")
        String header(@RequestHeader("X-Need") String v) {
            return v;
        }

        @PostMapping("/probe/body")   // 실제 컨트롤러처럼 consumes 미지정 (인자 해석 단계에서 Content-Type 오류)
        String body(@RequestBody Map<String, Object> body) {
            return "ok";
        }

        @GetMapping("/probe/boom")
        String boom() {
            throw new NullPointerException("서버 결함");
        }

        @GetMapping("/probe/illegal-arg")
        String illegalArg() {
            throw new IllegalArgumentException("테이블명이 잘못되었습니다");
        }

        @GetMapping("/probe/illegal-state")
        String illegalState() {
            throw new IllegalStateException("실행 중인 파일은 삭제할 수 없습니다");
        }

        @GetMapping("/probe/number")
        String number(@RequestParam int limit) {
            return String.valueOf(limit);
        }

        @GetMapping("/probe/checked")
        String checked() throws Exception {
            throw new Exception("검사 예외");
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("필수 파라미터 누락 → 400 MISSING_PARAMETER, message 는 사람이 읽는 설명")
    void missingParameter() throws Exception {
        mvc.perform(get("/probe/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
                .andExpect(jsonPath("$.message").value("필수 파라미터 'pluginName'(String) 가 없습니다."));
    }

    @Test
    @DisplayName("멀티파트 파트 누락 → 400 MISSING_PART")
    void missingPart() throws Exception {
        mvc.perform(multipart("/probe/part").param("tableName", "t"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PART"));
        mvc.perform(multipart("/probe/part").file(new MockMultipartFile("file", "a.csv", "text/csv", "A\n1\n".getBytes())).param("tableName", "t"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("필수 헤더 누락 → 400 MISSING_HEADER")
    void missingHeader() throws Exception {
        mvc.perform(get("/probe/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    @DisplayName("본문 없음·JSON 깨짐 → 400 INVALID_BODY (파서 원문은 응답에 넣지 않음)")
    void unreadableBody() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BODY"))
                .andExpect(jsonPath("$.message").value("요청 본문(JSON)이 없습니다."));
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BODY"))
                .andExpect(jsonPath("$.message").value("요청 본문을 JSON 으로 읽을 수 없습니다. 형식과 필드 타입을 확인하세요."));
    }

    @Test
    @DisplayName("지원하지 않는 Content-Type → 415")
    void unsupportedMediaType() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("서버 결함은 그대로 500 (요청 오류 처리기가 가로채지 않음), code 는 RUNTIME_ERROR")
    void serverErrorStays500() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("RUNTIME_ERROR"));
        mvc.perform(get("/probe/checked"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
    }

    // ---------------------------------------------------------------- message 와 code 가 뒤바뀌지 않는다

    @Test
    @DisplayName("IllegalArgumentException → code INVALID_ARGUMENT, message 는 예외 메시지")
    void illegalArgumentFieldsNotSwapped() throws Exception {
        mvc.perform(get("/probe/illegal-arg"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("테이블명이 잘못되었습니다"));
    }

    @Test
    @DisplayName("IllegalStateException → code INVALID_STATE, message 는 예외 메시지")
    void illegalStateFieldsNotSwapped() throws Exception {
        mvc.perform(get("/probe/illegal-state"))
                .andExpect(jsonPath("$.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.message").value("실행 중인 파일은 삭제할 수 없습니다"));
    }

    @Test
    @DisplayName("타입 불일치 → 400 code TYPE_MISMATCH, message 는 설명")
    void typeMismatchFieldsNotSwapped() throws Exception {
        mvc.perform(get("/probe/number").param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.message").value("'limit' 파라미터의 값 'abc'을(를) int 타입으로 변환할 수 없습니다."));
    }
}
