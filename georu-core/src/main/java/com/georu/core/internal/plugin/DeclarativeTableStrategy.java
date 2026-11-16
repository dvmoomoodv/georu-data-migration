package com.georu.core.internal.plugin;

import com.georu.core.plugin.TableDef;

import com.georu.core.internal.strategy.AbstractTableMigrationStrategy;
import com.georu.core.strategy.TableOptions;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link TableCatalog} 로 선언한 테이블 목록을 Strategy 로 바꾼다. 테이블마다 Strategy 클래스를 쓰지 않아도 된다.
 * Reader·Processor·Writer·소스 매퍼는 {@link GeoruPluginRegistrar} 가 이름 규칙으로 등록해 두므로 기반 클래스의 조회가 그대로 동작한다.
 */
public class DeclarativeTableStrategy extends AbstractTableMigrationStrategy {

    private final String pluginName;
    private final Map<String, TableDef> tables = new LinkedHashMap<>();

    public DeclarativeTableStrategy(String pluginName, List<TableDef> defs,
                                    JobLauncher jobLauncher, JobRepository jobRepository,
                                    PlatformTransactionManager transactionManager, ApplicationContext applicationContext) {
        super(jobLauncher, jobRepository, transactionManager, applicationContext);
        this.pluginName = pluginName;
        for (TableDef d : defs) {
            tables.put(d.table(), d);
        }
    }

    @Override
    protected Set<String> getSupportedTables() {
        return tables.keySet();
    }

    @Override
    protected String getPluginName() {
        return pluginName;
    }

    @Override
    protected TableOptions getTableOptions(String tableName) {
        TableDef d = tables.get(tableName);
        return d == null ? TableOptions.DEFAULT : d.options();
    }

    public List<String> tableOrder() {
        return List.copyOf(tables.keySet());
    }

    public TableDef tableDef(String tableName) {
        return tables.get(tableName);
    }
}
