package com.georu.core.internal.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** 켜진 타겟 DataSource({@code georu.datasource.target-*})가 하나라도 있으면 매치 */
public class AnyTargetDataSourceCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        ConditionMessage.Builder message = ConditionMessage.forCondition("AnyTargetDataSource");
        return DataSourceNames.configured(env).stream()
                .filter(n -> DataSourceNames.ROLE_TARGET.equals(DataSourceNames.role(n)) && DataSourceNames.enabled(env, n))
                .findFirst()
                .map(n -> ConditionOutcome.match(message.because(n)))
                .orElseGet(() -> ConditionOutcome.noMatch(message.because("타겟 DataSource 없음")));
    }
}
