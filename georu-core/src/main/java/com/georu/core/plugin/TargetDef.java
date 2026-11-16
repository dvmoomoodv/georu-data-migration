package com.georu.core.plugin;

import com.georu.core.internal.plugin.BeanNames;
import org.springframework.batch.item.ItemProcessor;

/**
 * 한 번 읽어 여러 곳에 쓰기({@link TableDef#targets(TargetDef...)})의 타겟 하나.
 * 타겟마다 TargetDto·Processor·타겟 테이블을 따로 둘 수 있어 복제(같은 행을 두 DB 에)와 나누기(행을 타겟마다 다르게)를 모두 담는다.
 *
 * <pre>
 * TableDef.of("tb_stat_daily", StatDailySource.class)
 *         .targets(
 *             TargetDef.of("target-m", StatHistTarget.class).targetTable("tb_stat_daily_hist").skipExisting(),
 *             TargetDef.of("target-1", StatDailyTarget.class).processor(StatDwProcessor.class));   // 타겟 XML 이 upsert
 * </pre>
 *
 * <p><b>멱등 쓰기가 필요하다</b>: 타겟 하나가 실패해 다시 돌리면 이미 쓴 타겟에도 다시 쓰기 때문이다. 타겟마다 둘 중 하나여야 하며, 아니면 기동 실패.</p>
 * <ul>
 *   <li>타겟 XML 의 {@code insertData} 가 upsert (ON DUPLICATE KEY UPDATE · INSERT IGNORE · MERGE · ON CONFLICT · REPLACE)</li>
 *   <li>{@link #skipExisting()}: 타겟 PK 로 이미 있는 행을 거르고 쓴다. TargetDto 에 {@link MigrationId} 가, 타겟 테이블에 PK 가 있어야 한다</li>
 * </ul>
 * 타겟 XML namespace 기본값은 {@code georu.<plugin>.<Camel(타겟 테이블)>TargetMapper}.
 */
public final class TargetDef {

    private final String dataSource;
    private final Class<?> targetDto;
    private String name;
    private String targetTable;
    private String targetNamespace;
    private Class<? extends ItemProcessor<?, ?>> processor;
    private boolean skipExisting;

    private TargetDef(String dataSource, Class<?> targetDto) {
        if (dataSource == null || dataSource.isBlank()) {
            throw new IllegalArgumentException("TargetDef 에는 타겟 DataSource 이름이 필요합니다");
        }
        this.dataSource = dataSource;
        this.targetDto = targetDto;
    }

    public static TargetDef of(String dataSource, Class<?> targetDto) {
        return new TargetDef(dataSource, targetDto);
    }

    /** 타겟 이름 (기록·로그·Bean 이름에 쓴다). 기본은 DataSource 이름. 같은 DataSource 에 두 번 쓰면 지정한다 */
    public TargetDef name(String name) {
        this.name = name;
        return this;
    }

    /** 타겟 테이블 (기본: 실행 단위 테이블) */
    public TargetDef targetTable(String table) {
        this.targetTable = table == null ? null : table.toLowerCase();
        return this;
    }

    public TargetDef targetNamespace(String ns) {
        this.targetNamespace = ns;
        return this;
    }

    /** 이 타겟만의 Processor (없으면 같은 이름 필드를 옮기는 복사). null 을 돌려주면 이 타겟에만 쓰지 않는다 */
    public TargetDef processor(Class<? extends ItemProcessor<?, ?>> processor) {
        this.processor = processor;
        return this;
    }

    /** 타겟 PK 로 이미 있는 행을 거르고 쓴다 (upsert 가 아닌 타겟의 멱등 쓰기) */
    public TargetDef skipExisting() {
        this.skipExisting = true;
        return this;
    }

    public String dataSource() { return dataSource; }
    public Class<?> targetDto() { return targetDto; }
    public String name() { return name != null && !name.isBlank() ? name : dataSource; }
    /** 지정한 타겟 테이블 (없으면 null → 실행 단위 테이블) */
    public String targetTable() { return targetTable; }
    public Class<? extends ItemProcessor<?, ?>> processor() { return processor; }
    public boolean isSkipExisting() { return skipExisting; }

    public String resolveTargetTable(String unitTable) {
        return targetTable != null ? targetTable : unitTable;
    }

    public String resolveTargetNamespace(String pluginName, String unitTable) {
        return targetNamespace != null ? targetNamespace
                : "georu." + pluginName + "." + BeanNames.camel(resolveTargetTable(unitTable)) + "TargetMapper";
    }

    @Override
    public String toString() {
        return "TargetDef[" + name() + " → " + targetTable + "]";
    }
}
