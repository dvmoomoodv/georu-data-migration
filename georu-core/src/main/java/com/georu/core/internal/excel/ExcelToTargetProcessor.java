package com.georu.core.internal.excel;

import com.georu.core.processor.AbstractItemProcessor;
import com.georu.core.utils.ValidationUtils;
import com.georu.core.internal.rule.AuditRules;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 엑셀 행 → Target DTO 변환 Processor (TOBE 컬럼 형식). Job 마다 새로 만든다.
 *
 * <ul>
 *   <li>헤더에 있고 적재 가능한 컬럼만 DTO setter 로 넣는다 (타입 변환은 {@link ExcelValueConverter})</li>
 *   <li>필수·길이·타입 검증은 {@link ExcelValidationService#checkValue} 와 같은 규칙. 오류면 {@link ExcelRowException}
 *       (onRowError=SKIP 이면 건너뛰고, STOP 이면 Step 실패)</li>
 *   <li>감사 컬럼(등록자·수정자·일시)이 비어 있으면 실행 시각·MIG-DATA 로 채운다 (WRITER_TEST 는 Writer 가 MIG-TEST 로 바꾼다)</li>
 *   <li>시퀀스 채번·테스트 모드 분기는 부모({@link AbstractItemProcessor})가 JobParameters 로 처리한다</li>
 * </ul>
 */
@Slf4j
public class ExcelToTargetProcessor extends AbstractItemProcessor<ExcelRow, Object> {

    private static final DateTimeFormatter YMDHMS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ExcelTargetBinding binding;
    private final ExcelValidationService validation;
    private final List<String> auditDateSetterNames;
    private final List<ExcelTargetBinding.Column> accepted;
    private final List<Method> auditUserSetters = new ArrayList<>();
    private final List<Method> auditDateSetters = new ArrayList<>();

    public ExcelToTargetProcessor(ExcelTargetBinding binding, ExcelValidationService validation, List<String> auditDateSetterNames) {
        super("MIGRATION", "EXCEL");
        this.binding = binding;
        this.validation = validation;
        this.auditDateSetterNames = auditDateSetterNames == null ? List.of() : auditDateSetterNames;
        this.accepted = binding.getColumns().stream().filter(ExcelTargetBinding.Column::isAccepted).toList();
        Class<?> dto = binding.getDtoClass();
        for (String name : AuditRules.current().auditSetterNames()) {
            findSetter(dto, name, String.class).ifPresent(auditUserSetters::add);
        }
        for (String name : this.auditDateSetterNames) {
            findSetter(dto, name, null).ifPresent(auditDateSetters::add);
        }
    }

    @Override
    protected String getEntityName() {
        return "Excel(" + binding.getTableName() + ")";
    }

    @Override
    protected ValidationUtils.ValidationResult validateItem(ExcelRow item) {
        return item == null ? ValidationUtils.ValidationResult.failure("row is null") : ValidationUtils.ValidationResult.success();
    }

    @Override
    protected Object transformItem(ExcelRow row) {
        Object dto = ExcelBindingService.newInstance(binding.getDtoClass());
        if (dto == null) {
            throw new IllegalStateException(binding.getDtoClass().getSimpleName() + " 를 만들 수 없습니다 (기본 생성자 필요)");
        }
        for (ExcelTargetBinding.Column c : accepted) {
            String v = row.get(c.getName());
            String err = validation.checkValue(c, v);
            if (err != null) {
                throw new ExcelRowException(row.rowNum(), c.getName(), v, err);
            }
            if (v == null) {
                continue;
            }
            Class<?> type = c.getSetter().getParameterTypes()[0];
            Object converted;
            try {
                converted = ExcelValueConverter.convert(v, type);
            } catch (IllegalArgumentException e) {
                throw new ExcelRowException(row.rowNum(), c.getName(), v, e.getMessage());
            }
            try {
                c.getSetter().invoke(dto, converted);
            } catch (Exception e) {
                throw new ExcelRowException(row.rowNum(), c.getName(), v, "값을 넣지 못했습니다: " + e.getMessage());
            }
        }
        return dto;
    }

    @Override
    protected Object getItemId(ExcelRow item) {
        return item == null ? null : item.rowNum() + "행";
    }

    @Override
    protected Object getOutputItemId(Object item) {
        return item == null ? null : item.getClass().getSimpleName();
    }

    /** 감사 컬럼 채우기: 등록자·수정자는 MIG-DATA, 일시는 실행 시각 (비어 있을 때만) */
    @Override
    protected void setUpdateTimestamp(Object dto) {
        if (dto == null) {
            return;
        }
        String user = AuditRules.current().migrationUser();
        for (Method m : auditUserSetters) {
            if (isEmpty(dto, m)) {
                invokeQuietly(dto, m, user);
            }
        }
        LocalDateTime now = LocalDateTime.now();
        for (Method m : auditDateSetters) {
            if (!isEmpty(dto, m)) {
                continue;
            }
            Class<?> t = m.getParameterTypes()[0];
            Object v = t == String.class ? now.format(YMDHMS)
                    : t == LocalDateTime.class ? now
                    : t == Timestamp.class || t == java.util.Date.class ? Timestamp.valueOf(now)
                    : t == java.time.LocalDate.class ? now.toLocalDate()
                    : null;
            if (v != null) {
                invokeQuietly(dto, m, v);
            }
        }
    }

    private static boolean isEmpty(Object dto, Method setter) {
        String getterName = "get" + setter.getName().substring(3);
        try {
            Method g = dto.getClass().getMethod(getterName);
            Object v = g.invoke(dto);
            return v == null || (v instanceof String s && s.isBlank());
        } catch (Exception e) {
            return true;
        }
    }

    private static void invokeQuietly(Object dto, Method m, Object v) {
        try {
            m.invoke(dto, v);
        } catch (Exception e) {
            log.debug("감사 컬럼 설정 실패 {}.{}: {}", dto.getClass().getSimpleName(), m.getName(), e.getMessage());
        }
    }

    private static Optional<Method> findSetter(Class<?> type, String name, Class<?> paramType) {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1
                    && (paramType == null || m.getParameterTypes()[0] == paramType)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    public ExcelTargetBinding getBinding() {
        return binding;
    }
}
