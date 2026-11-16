package com.georu.core.internal.excel;

import com.georu.core.processor.AbstractItemProcessor;
import com.georu.core.internal.product.guard.TargetTableResolver;
import com.georu.core.product.precheck.ColumnMeta;
import com.georu.core.internal.product.precheck.InsertStatementInspector;
import com.georu.core.internal.product.precheck.MetadataReader;
import com.georu.core.internal.product.precheck.TableMeta;
import com.georu.core.internal.sequence.support.SequenceValueInjector;
import com.georu.core.strategy.TableOptions;
import com.georu.core.internal.utils.StrategyUtils;
import com.georu.core.internal.rule.AuditRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.mapping.MappedStatement;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 테이블 1개의 {@link ExcelTargetBinding} 을 계산한다.
 *
 * <ol>
 *   <li>타겟 DB·테이블: {@link TargetTableResolver} (Writer 의 SqlSessionFactory, TableOptions.targetTable)</li>
 *   <li>DTO 클래스: Target 매퍼 INSERT 의 parameterType → 없으면 Processor 의 제네릭 출력 타입</li>
 *   <li>컬럼: INSERT 컬럼(프로퍼티 포함) ∪ 타겟 메타 컬럼. setter 는 프로퍼티명 → 컬럼명 camelCase 순으로 찾는다</li>
 *   <li>필수: NOT NULL 이고 기본값·자동증가·채번·감사 컬럼이 아니며 INSERT 가 DTO 값을 쓰는 컬럼</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelBindingService {

    private final ApplicationContext applicationContext;
    private final TargetTableResolver targetTableResolver;
    private final MetadataReader metadataReader;
    private final InsertStatementInspector insertInspector;
    private final ExcelLoadProperties props;

    /** @param requestSequenceColumns API 요청의 채번 컬럼 (없으면 null → 테이블 설정) */
    public ExcelTargetBinding bind(String pluginName, String tableName, Collection<String> requestSequenceColumns) {
        List<String> notes = new ArrayList<>();
        if (targetTableResolver.strategy(pluginName, tableName).isEmpty()) {
            throw new IllegalArgumentException(pluginName + " 플러그인에 등록되지 않은 테이블입니다: " + tableName
                    + " (실행 Strategy 가 없어 적재할 수 없습니다. 화면의 테이블 목록에서 고르세요)");
        }
        Optional<TargetTableResolver.TargetRef> ref = targetTableResolver.resolve(pluginName, tableName);
        if (ref.isEmpty()) {
            throw new IllegalArgumentException(pluginName + "." + tableName + " 의 타겟 DB 를 찾을 수 없습니다 (Writer 가 AbstractMigrationWriter 가 아니거나 테이블이 플러그인에 없음)");
        }
        TargetTableResolver.TargetRef target = ref.get();
        TableOptions options = targetTableResolver.options(pluginName, tableName);

        // 1. INSERT 해석
        Optional<MappedStatement> ms = insertInspector.find(target.sqlSessionFactory(), tableName);
        Class<?> dtoClass = ms.map(m -> m.getParameterMap() != null ? m.getParameterMap().getType() : null).orElse(null);
        if (dtoClass == null || dtoClass == Object.class || dtoClass == Map.class) {
            dtoClass = processorOutputType(tableName).orElse(null);
        }
        if (dtoClass == null) {
            throw new IllegalArgumentException(tableName + " 의 Target DTO 클래스를 알 수 없습니다 (TargetMapper.xml 의 insertData parameterType 또는 Processor 제네릭 타입)");
        }
        Object sample = newInstance(dtoClass);
        List<InsertStatementInspector.ColumnParam> params = List.of();
        if (ms.isPresent()) {
            Optional<List<InsertStatementInspector.ColumnParam>> cp = sample == null ? Optional.empty() : insertInspector.columnParams(ms.get(), sample);
            if (cp.isPresent()) {
                params = cp.get();
            } else {
                notes.add("Target 매퍼 INSERT 를 해석할 수 없어 메타 컬럼만으로 대응표를 만들었습니다 (" + ms.get().getId() + ")");
            }
        } else {
            notes.add("Target 매퍼 INSERT(insertData) 를 찾지 못했습니다. 메타 컬럼만으로 대응표를 만들었습니다");
        }

        // 2. 타겟 메타
        TableMeta meta = metadataReader.read(target.dataSource(), target.tableName());
        if (!meta.isFound()) {
            notes.add("타겟 메타를 읽지 못했습니다: " + meta.getError() + " (필수·길이·타입 검증은 생략됩니다)");
        }

        // 3. 컬럼 합치기 (INSERT 순 → 메타 순)
        Set<String> seqCols = new LinkedHashSet<>();
        if (requestSequenceColumns != null && !requestSequenceColumns.isEmpty()) {
            requestSequenceColumns.forEach(c -> seqCols.add(c.trim().toUpperCase(Locale.ROOT)));
        } else if (options.getSequenceColumns() != null) {
            options.getSequenceColumns().forEach(c -> seqCols.add(c.trim().toUpperCase(Locale.ROOT)));
        }
        Set<String> auditCols = new LinkedHashSet<>();
        AuditRules.current().auditColumns().forEach(c -> auditCols.add(c.toUpperCase(Locale.ROOT)));
        Set<String> auditSetters = new LinkedHashSet<>(AuditRules.current().auditSetterNames());
        auditSetters.addAll(props.getAuditDateSetters());

        Map<String, InsertStatementInspector.ColumnParam> byInsert = new LinkedHashMap<>();
        for (InsertStatementInspector.ColumnParam p : params) {
            byInsert.put(p.column().toUpperCase(Locale.ROOT), p);
        }
        List<String> order = new ArrayList<>(byInsert.keySet());
        for (String c : meta.getColumns().keySet()) {
            if (!byInsert.containsKey(c)) {
                order.add(c);
            }
        }

        List<ExcelTargetBinding.Column> columns = new ArrayList<>();
        for (String name : order) {
            InsertStatementInspector.ColumnParam p = byInsert.get(name);
            ColumnMeta cm = meta.column(name);
            String property = p != null && p.property() != null ? p.property() : null;
            Method setter = null;
            if (property != null) {
                setter = findSetter(dtoClass, property).orElse(null);
            }
            if (setter == null) {
                setter = SequenceValueInjector.setter(dtoClass, name).orElse(null);
                if (setter != null && property == null) {
                    property = SequenceValueInjector.propertyName(name);
                }
            }
            String kind = p == null ? "NONE" : p.kind().name();
            boolean fromDto = p == null ? setter != null : p.kind() == InsertStatementInspector.Kind.PARAM;
            boolean accepted = setter != null && fromDto;
            boolean audit = auditCols.contains(name) || (setter != null && auditSetters.contains(setter.getName()));
            boolean sequence = seqCols.contains(name);
            boolean pk = meta.getPrimaryKey().contains(name);
            boolean required = accepted && cm != null && !cm.isNullable() && !cm.hasDefault() && !cm.isAutoIncrement() && !sequence && !audit;
            String note = null;
            if (p != null && p.kind() != InsertStatementInspector.Kind.PARAM) {
                note = "INSERT 가 고정값(" + p.expression() + ")을 넣으므로 엑셀 값은 쓰이지 않습니다";
            } else if (setter == null) {
                note = "DTO 에 setter 가 없어 엑셀 값을 넣을 수 없습니다";
            } else if (p == null && ms.isPresent() && !params.isEmpty()) {
                note = "INSERT 에 없는 컬럼이라 적재되지 않습니다";
                accepted = false;
                required = false;
            } else if (sequence) {
                note = "시퀀스 채번 컬럼 (비우면 자동 채번)";
            } else if (audit) {
                note = "감사 컬럼 (비우면 실행 시 자동 입력)";
            }
            columns.add(ExcelTargetBinding.Column.builder()
                    .name(name).property(property).setter(setter)
                    .javaType(setter != null ? setter.getParameterTypes()[0].getSimpleName() : null)
                    .meta(cm).insertKind(kind).accepted(accepted).required(required).audit(audit).sequence(sequence)
                    .primaryKey(pk).describe(cm != null ? cm.describe() : "(메타 없음)").note(note)
                    .build());
        }

        return ExcelTargetBinding.builder()
                .pluginName(pluginName).tableName(tableName).targetTable(target.tableName())
                .dtoClass(dtoClass).tableMeta(meta).columns(columns)
                .sequenceColumns(new ArrayList<>(seqCols)).notes(notes)
                .build();
    }

    /** Processor Bean 의 AbstractItemProcessor<I, O> 에서 O */
    private Optional<Class<?>> processorOutputType(String tableName) {
        try {
            ItemProcessor<?, ?> processor = StrategyUtils.createProcessor(applicationContext, tableName, "NORMAL");
            if (processor == null) {
                return Optional.empty();
            }
            ResolvableType rt = ResolvableType.forClass(processor.getClass()).as(AbstractItemProcessor.class);
            Class<?> out = rt.getGeneric(1).resolve();
            return Optional.ofNullable(out);
        } catch (Exception e) {
            log.debug("Processor 출력 타입 확인 실패 {}: {}", tableName, e.getMessage());
            return Optional.empty();
        }
    }

    static Optional<Method> findSetter(Class<?> type, String property) {
        if (property == null || property.isBlank()) {
            return Optional.empty();
        }
        String name = "set" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    static Object newInstance(Class<?> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            log.debug("DTO 기본 생성자 호출 실패 {}: {}", type.getName(), e.getMessage());
            return null;
        }
    }
}
