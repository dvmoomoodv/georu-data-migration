package com.georu.core.internal.sequence.v1.dialect;

import com.georu.core.internal.sequence.SequenceException;
import com.georu.core.internal.sequence.v1.V1SequenceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 등록된 {@link SequenceDialect} 중 하나를 고른다 (실행 전 점검의 DialectRules 와 같은 패턴).
 * <ul>
 *   <li>설정이 {@code auto}: 타깃 DataSource 의 DB 제품명으로 {@link SequenceDialect#supports(String)} 를 @Order 순서로 평가</li>
 *   <li>설정이 이름: 그 이름의 방언. 없으면 {@link SequenceException}</li>
 * </ul>
 * generic 방언은 항상 supports=true 이고 @Order 가 가장 뒤라 마지막 후보가 된다.
 */
@Slf4j
@Component
public class SequenceDialects {

    private final List<SequenceDialect> dialects;

    public SequenceDialects(ObjectProvider<SequenceDialect> provider) {
        this.dialects = provider.orderedStream().toList();
    }

    public List<SequenceDialect> all() {
        return dialects;
    }

    public Optional<SequenceDialect> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        return dialects.stream().filter(d -> d.name().equalsIgnoreCase(n)).findFirst();
    }

    /** 설정값(auto | 이름)과 DataSource 로 방언 선택 */
    public SequenceDialect select(String configured, DataSource dataSource) {
        if (dialects.isEmpty()) {
            throw new SequenceException("등록된 시퀀스 방언이 없습니다 (타깃 DataSource 설정 확인)");
        }
        String c = configured == null || configured.isBlank() ? V1SequenceProperties.DIALECT_AUTO : configured.trim().toLowerCase(Locale.ROOT);
        if (!V1SequenceProperties.DIALECT_AUTO.equals(c)) {
            return byName(c).orElseThrow(() -> new SequenceException(
                    "georu.sequence.v1.dialect=" + configured + " 에 해당하는 방언이 없습니다. 등록: " + names()));
        }
        String product = detectProduct(dataSource);
        SequenceDialect chosen = dialects.stream().filter(d -> d.supports(product)).findFirst().orElse(dialects.get(dialects.size() - 1));
        log.info("[seq v1] dialect auto → {} (product={})", chosen.name(), product);
        return chosen;
    }

    public List<String> names() {
        return dialects.stream().map(SequenceDialect::name).toList();
    }

    static String detectProduct(DataSource dataSource) {
        if (dataSource == null) {
            return null;
        }
        try (Connection c = dataSource.getConnection()) {
            return c.getMetaData().getDatabaseProductName();
        } catch (Exception e) {
            log.warn("[seq v1] DB 제품명 조회 실패 - generic 방언으로 진행: {}", e.getMessage());
            return null;
        }
    }
}
