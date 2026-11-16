package com.georu.core.internal.product.precheck.dialect;

import com.georu.core.product.precheck.dialect.DialectRule;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 등록된 {@link DialectRule} 중 DB 제품명에 맞는 규칙을 고른다. 없으면 {@link GenericDialectRule} */
@Component
@RequiredArgsConstructor
public class DialectRules {

    private static final DialectRule GENERIC = new GenericDialectRule();

    private final List<DialectRule> rules;   // @Order 순서

    public DialectRule forProduct(String databaseProductName) {
        return rules.stream().filter(r -> r.supports(databaseProductName)).findFirst().orElse(GENERIC);
    }
}
