package com.georu.core.rule;

import java.util.List;

/**
 * 이관 데이터의 등록자/수정자(감사) 규칙.
 *
 * <p>업체별 구현체(예: 등록자 MIG-DATA / MIG-TEST)는 plugins/&lt;업체&gt;/utils 에 Spring Bean 으로 두고,
 * core 는 {@link AuditRules#current()} 를 통해서만 사용한다. Bean 이 없으면 {@link #DEFAULT} 가 쓰인다.</p>
 */
public interface AuditRuleSet {

    /** 실제 이관 적재 시 등록자 값 */
    String migrationUser();

    /** WRITER_TEST 적재 시 등록자 값 (정리 API 가 이 값으로 삭제) */
    String testUser();

    /** 타겟 테이블에서 등록자/수정자로 쓰이는 컬럼명 후보 (우선순위 순) */
    List<String> auditColumns();

    /** DTO 에서 등록자/수정자를 바꾸는 setter 이름 후보 */
    List<String> auditSetterNames();

    /** 업체 Bean 이 없을 때의 기본값 (기본 규약) */
    AuditRuleSet DEFAULT = new AuditRuleSet() {
        @Override public String migrationUser() { return "MIG-DATA"; }
        @Override public String testUser() { return "MIG-TEST"; }
        @Override public List<String> auditColumns() { return List.of("INUSERID", "LUSERID", "IN_USER_ID", "L_USER_ID"); }
        @Override public List<String> auditSetterNames() {
            return List.of("setInuserid", "setLuserid", "setInUserId", "setLUserId", "setInuserId", "setLuserId");
        }
    };
}
