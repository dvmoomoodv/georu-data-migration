package com.georu.core.plugin;

import com.georu.core.internal.plugin.BeanNames;

import com.georu.core.strategy.TableOptions;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;

/**
 * 테이블 1개의 이관 정의. 사람이 쓰는 것은 Source SQL·Target SQL(XML) 과 필요할 때의 Processor 뿐이다.
 *
 * <ul>
 *   <li>소스 XML namespace 기본값: {@code georu.<plugin>.<CamelTable>SourceMapper}, 문장 id {@code selectTotalCount}·{@code selectPageData}</li>
 *   <li>타겟 XML namespace 기본값: {@code georu.<plugin>.<CamelTable>TargetMapper}, 문장 id {@code insertData}</li>
 *   <li>Processor 를 주지 않으면 같은 이름 필드를 옮기는 {@link CopyProcessor}</li>
 *   <li>Reader·Writer 를 직접 만든 경우 {@link #reader(Class)}·{@link #writer(Class)} 로 지정한다 (탈출구)</li>
 * </ul>
 */
public final class TableDef {

    private final String table;
    private final Class<?> sourceDto;
    private final Class<?> targetDto;
    private Class<? extends ItemProcessor<?, ?>> processor;
    private Class<? extends ItemReader<?>> reader;
    private Class<? extends ItemWriter<?>> writer;
    private TableOptions options = TableOptions.DEFAULT;
    private String sourceNamespace;
    private String targetNamespace;
    private int pageSize = 1000;
    private String source;
    private String target;
    private final java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
    private final java.util.List<TargetDef> targets = new java.util.ArrayList<>();

    private TableDef(String table, Class<?> sourceDto, Class<?> targetDto) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table 은 비울 수 없습니다");
        }
        this.table = table.toLowerCase(java.util.Locale.ROOT);
        this.sourceDto = sourceDto;
        this.targetDto = targetDto;
    }

    public static TableDef of(String table, Class<?> sourceDto, Class<?> targetDto) {
        return new TableDef(table, sourceDto, targetDto);
    }

    /** 한 번 읽어 여러 곳에 쓰는 단위 ({@link #targets(TargetDef...)} 와 같이 쓴다. TargetDto 는 타겟마다) */
    public static TableDef of(String table, Class<?> sourceDto) {
        return new TableDef(table, sourceDto, null);
    }

    public TableDef processor(Class<? extends ItemProcessor<?, ?>> processor) {
        this.processor = processor;
        return this;
    }

    public TableDef reader(Class<? extends ItemReader<?>> reader) {
        this.reader = reader;
        return this;
    }

    public TableDef writer(Class<? extends ItemWriter<?>> writer) {
        this.writer = writer;
        return this;
    }

    public TableDef options(TableOptions options) {
        this.options = options == null ? TableOptions.DEFAULT : options;
        return this;
    }

    public TableDef sourceNamespace(String ns) {
        this.sourceNamespace = ns;
        return this;
    }

    public TableDef targetNamespace(String ns) {
        this.targetNamespace = ns;
        return this;
    }

    /** 이 테이블만 다른 소스 DataSource 에서 읽을 때 (예: "source-1", 타겟 DB 를 다시 읽는 2단계 적재면 "target-m"). 없으면 플러그인의 source */
    public TableDef source(String dataSource) {
        this.source = dataSource;
        return this;
    }

    /** 이 테이블만 다른 타겟 DataSource 에 쓸 때 (예: "target-1"). 없으면 플러그인의 target */
    public TableDef target(String dataSource) {
        this.target = dataSource;
        return this;
    }

    /** 소스 SQL 에 넘길 고정 조건. SQL 에서 {@code #{params.<key>}} 로 쓴다 (예: 조건마다 다른 타겟으로 보내기) */
    public TableDef params(String key, String value) {
        this.params.put(key, value);
        return this;
    }

    public TableDef params(java.util.Map<String, String> values) {
        if (values != null) {
            this.params.putAll(values);
        }
        return this;
    }

    /**
     * 한 번 읽어 여러 곳에 쓰기: 청크 하나를 읽어 타겟마다 그 타겟의 Processor 를 거친 뒤 선언 순서대로 쓴다.
     * 지정하면 {@link #target(String)}·{@link #processor(Class)}·{@link #writer(Class)}·TargetDto 는 쓰지 않는다.
     * 모든 타겟이 멱등 쓰기여야 한다 ({@link TargetDef})
     */
    public TableDef targets(TargetDef... targets) {
        this.targets.clear();
        if (targets != null) {
            this.targets.addAll(java.util.List.of(targets));
        }
        return this;
    }

    /** 소스 페이징 크기 (기본 1000) */
    public TableDef pageSize(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    public String table() { return table; }
    public Class<?> sourceDto() { return sourceDto; }
    public Class<?> targetDto() { return targetDto; }
    public Class<? extends ItemProcessor<?, ?>> processor() { return processor; }
    public Class<? extends ItemReader<?>> reader() { return reader; }
    public Class<? extends ItemWriter<?>> writer() { return writer; }
    public TableOptions options() { return options; }
    public int pageSize() { return pageSize; }
    /** 테이블에서 지정한 소스 DataSource (없으면 null → 플러그인의 source) */
    public String source() { return source; }
    /** 테이블에서 지정한 타겟 DataSource (없으면 null → 플러그인의 target) */
    public String target() { return target; }
    public java.util.Map<String, String> params() { return java.util.Collections.unmodifiableMap(params); }
    /** 한 번 읽어 여러 곳에 쓰기의 타겟 (비어 있으면 보통 단위) */
    public java.util.List<TargetDef> targets() { return java.util.List.copyOf(targets); }
    public boolean isFanOut() { return !targets.isEmpty(); }

    public String resolveSourceNamespace(String pluginName) {
        return sourceNamespace != null ? sourceNamespace : "georu." + pluginName + "." + BeanNames.camel(table) + "SourceMapper";
    }

    public String resolveTargetNamespace(String pluginName) {
        return targetNamespace != null ? targetNamespace : "georu." + pluginName + "." + BeanNames.camel(table) + "TargetMapper";
    }

    @Override
    public String toString() {
        return "TableDef[" + table + "]";
    }
}
