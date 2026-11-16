package com.georu.core.internal.rule;

import com.georu.core.rule.AuditRuleSet;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 현재 활성 {@link AuditRuleSet} 홀더. 업체 플러그인이 Bean 으로 제공하면 기동 시 등록되고,
 * 없으면 {@link AuditRuleSet#DEFAULT}. static 접근은 Spring 컨텍스트 밖(단위 테스트, record 메서드)에서도 쓰기 위함.
 */
@Slf4j
@Configuration
public class AuditRules {

    private static final AtomicReference<AuditRuleSet> CURRENT = new AtomicReference<>(AuditRuleSet.DEFAULT);

    public AuditRules(ObjectProvider<AuditRuleSet> provider) {
        AuditRuleSet rs = provider.getIfUnique();
        if (rs != null) {
            CURRENT.set(rs);
            log.info("감사 규칙 적용: {} (migration={}, test={}, columns={})", rs.getClass().getSimpleName(),
                    rs.migrationUser(), rs.testUser(), rs.auditColumns());
        } else {
            log.info("감사 규칙 Bean 없음 - 기본 규칙 사용 (migration={}, test={})",
                    AuditRuleSet.DEFAULT.migrationUser(), AuditRuleSet.DEFAULT.testUser());
        }
    }

    public static AuditRuleSet current() { return CURRENT.get(); }

    /** 테스트용 교체 */
    public static void set(AuditRuleSet rs) { CURRENT.set(rs == null ? AuditRuleSet.DEFAULT : rs); }
}
