package com.georu.core.internal.excel;

import com.georu.core.product.precheck.ColumnMeta;
import com.georu.core.internal.product.precheck.TableMeta;
import lombok.Builder;
import lombok.Value;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 엑셀 헤더(= TOBE 컬럼) → Target DTO 프로퍼티 대응표. 테이블 1개에 대해 한 번 계산한다 ({@link ExcelBindingService}).
 *
 * <p>컬럼 출처: 타겟 테이블 메타(정보 스키마) ∪ Target 매퍼 INSERT 의 컬럼. 프로퍼티는 INSERT 의 {@code #{...}} 를 우선 쓰고,
 * 없으면 컬럼명을 camelCase 로 바꿔 DTO setter 를 찾는다.</p>
 */
@Value
@Builder
public class ExcelTargetBinding {

    String pluginName;
    String tableName;
    /** 실제 타겟 테이블명 (TableOptions.targetTable 또는 tableName) */
    String targetTable;
    /** Target 매퍼 INSERT 의 parameterType (Writer 가 받는 DTO) */
    Class<?> dtoClass;
    /** 타겟 메타 (조회 실패면 found=false) */
    TableMeta tableMeta;
    /** 컬럼 순서: INSERT 순 → 메타에만 있는 컬럼 */
    List<Column> columns;
    /** 시퀀스 채번 컬럼 (대문자) - 엑셀에 없어도 된다 */
    List<String> sequenceColumns;
    /** 바인딩 경고 (INSERT 해석 불가, 메타 조회 불가 등) */
    List<String> notes;

    @Value
    @Builder
    public static class Column {
        /** 컬럼명 (대문자) */
        String name;
        /** DTO 프로퍼티명 (setter 가 없으면 null) */
        String property;
        /** setter (없으면 null) */
        Method setter;
        /** setter 파라미터 타입 단순명 (String, Long, BigDecimal …) */
        String javaType;
        /** 타겟 메타 (없으면 null) */
        ColumnMeta meta;
        /** INSERT 에서 값을 받는 방식: PARAM(DTO 값) / LITERAL / EXPRESSION / NULL / NONE(INSERT 에 없음) */
        String insertKind;
        /** 엑셀 값이 적재에 쓰이는지 (setter 가 있고 INSERT 가 DTO 값을 쓴다) */
        boolean accepted;
        /** 엑셀에 반드시 있어야 하는지 (NOT NULL, 기본값·자동증가·채번·감사 컬럼이 아님) */
        boolean required;
        /** 감사 컬럼 (등록자·수정자·일시) - 비어 있으면 실행 시 자동으로 채운다 */
        boolean audit;
        /** 시퀀스 채번 컬럼 */
        boolean sequence;
        /** PK 컬럼 */
        boolean primaryKey;
        /** 사람이 읽는 타입 설명 (VARCHAR(40) NOT NULL 등) */
        String describe;
        /** 비고 */
        String note;
    }

    public Optional<Column> column(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String key = name.trim().toUpperCase(Locale.ROOT);
        return columns.stream().filter(c -> c.getName().equals(key)).findFirst();
    }

    public Map<String, Column> columnMap() {
        Map<String, Column> m = new java.util.LinkedHashMap<>();
        for (Column c : columns) {
            m.put(c.getName(), c);
        }
        return m;
    }

    public List<Column> requiredColumns() {
        return columns.stream().filter(Column::isRequired).toList();
    }

    public List<String> primaryKey() {
        return columns.stream().filter(Column::isPrimaryKey).map(Column::getName).toList();
    }

    public boolean metaFound() {
        return tableMeta != null && tableMeta.isFound();
    }
}
