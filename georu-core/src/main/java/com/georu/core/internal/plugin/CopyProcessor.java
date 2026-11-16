package com.georu.core.internal.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.georu.core.processor.AbstractItemProcessor;
import com.georu.core.utils.ValidationUtils;
import com.georu.core.internal.rule.AuditRules;

import java.lang.reflect.Method;

/**
 * Processor 를 따로 쓰지 않은 테이블의 기본 변환: Source DTO 의 같은 이름 필드를 Target DTO 로 옮기고,
 * 감사 컬럼(등록자)이 비어 있으면 {@link AuditRules} 의 이관 사용자를 넣는다. 채번은 기반 클래스가 한다.
 */
public class CopyProcessor<I, O> extends AbstractItemProcessor<I, O> {

    private static final Logger LOG = LoggerFactory.getLogger(CopyProcessor.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true);

    private final Class<O> targetType;
    private final String entityName;
    private final MigrationIds inputIds;
    private final MigrationIds outputIds;

    public CopyProcessor(Class<I> sourceType, Class<O> targetType, String table) {
        super("MIGRATION", "SOURCE");
        this.targetType = targetType;
        this.entityName = BeanNames.camel(table) + "Processor";
        this.inputIds = MigrationIds.of(sourceType);
        this.outputIds = MigrationIds.of(targetType);
    }

    @Override
    protected ValidationUtils.ValidationResult validateItem(I item) {
        return item == null ? ValidationUtils.ValidationResult.failure("item is null") : ValidationUtils.ValidationResult.success();
    }

    @Override
    protected O transformItem(I item) {
        return MAPPER.convertValue(item, targetType);
    }

    @Override
    protected Object getItemId(I item) {
        Object id = inputIds.idOf(item);
        return id != null ? id : item;
    }

    @Override
    protected Object getOutputItemId(O item) {
        Object id = outputIds.idOf(item);
        return id != null ? id : item;
    }

    @Override
    protected String getEntityName() {
        return entityName;
    }

    /** 감사 컬럼 setter(AuditRuleSet.auditSetterNames) 가 있고 값이 없으면 이관 사용자를 넣는다 */
    @Override
    protected void setUpdateTimestamp(O item) {
        if (item == null) {
            return;
        }
        String user = AuditRules.current().migrationUser();
        for (String setter : AuditRules.current().auditSetterNames()) {
            try {
                Method set = targetType.getMethod(setter, String.class);
                Method get = targetType.getMethod("g" + setter.substring(1));
                if (get.invoke(item) == null) {
                    set.invoke(item, user);
                }
            } catch (NoSuchMethodException ignored) {
                // 해당 감사 컬럼이 없는 DTO
            } catch (Exception e) {
                LOG.debug("{} 감사 컬럼 {} 설정 실패: {}", entityName, setter, e.getMessage());
            }
        }
    }
}
