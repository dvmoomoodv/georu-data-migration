package com.georu.core.internal.scenarioflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 시나리오 정의 (src/main/resources/scenarios/*.json).
 *
 * <p>시나리오 → 단계(테이블, 플러그인 자유) → 날짜 구간(선택) → Job. 단계는 배열 순서대로 하나씩 실행한다.</p>
 *
 * <pre>
 * {
 *   "id": "hr-member", "name": "인사 회원", "profiles": ["dev"],
 *   "onUnitFailure": "STOP", "onStepStop": "STOP_ALL",
 *   "common": { "tenantLevel1": "HR", ..., "chunkSize": 500 },
 *   "steps": [
 *     { "plugin": "hr.member", "table": "tb_member_grade" },
 *     { "plugin": "hr.member", "table": "tb_member_login_log",
 *       "divide": { "unit": "1m", "startDate": "20250101", "endDate": "20251231" },
 *       "onUnitFailure": "CONTINUE", "onStepStop": "NEXT_STEP", "options": { "chunkSize": 1000 } }
 *   ]
 * }
 * </pre>
 *
 * <p>common·options 는 테이블 이관 요청(POST /api/migrate/table)과 같은 필드 이름을 쓴다.
 * 실행 모드·최대 건수·실행자는 정의가 아니라 실행 요청에서 받는다.</p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScenarioDefinition {

    private String id;
    private String name;
    private String description;
    /** 실행할 수 있는 프로파일 (하나라도 활성이면 실행 가능). 비면 제한 없음 */
    private List<String> profiles = new ArrayList<>();
    /** 단위(구간 Job) 실패 시 기본값. 비면 STOP */
    private String onUnitFailure;
    /** 단계가 전체 성공이 아닐 때 기본값. 비면 STOP_ALL */
    private String onStepStop;
    /** 모든 단계 공통 요청 값 (tenantLevel1~5, chunkSize, attributeN 등) */
    private Map<String, Object> common = new LinkedHashMap<>();
    private List<Step> steps = new ArrayList<>();

    @Data
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class Step {
        private String plugin;
        private String table;
        /** 화면 표시용 설명 (선택) */
        private String label;
        /** 날짜 분할 (선택). 없으면 단위 1개 */
        private Divide divide;
        /** 단계별 덮어쓰기 (선택) */
        private String onUnitFailure;
        private String onStepStop;
        /** 이 단계에만 적용할 요청 값 (common 위에 덮어씀) */
        private Map<String, Object> options = new LinkedHashMap<>();
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Divide {
        /** 1d·1w·1m·1y 등 (DateDivideConfig) */
        private String unit;
        /** yyyyMMdd 또는 yyyy-MM-dd 등 (DateInputs) */
        private String startDate;
        private String endDate;
    }
}
