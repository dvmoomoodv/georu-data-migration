package com.georu.core.internal.product.guard;

import com.georu.core.writer.AbstractMigrationWriter;
import com.georu.core.internal.strategy.AbstractTableMigrationStrategy;
import com.georu.core.internal.strategy.TableMigrationStrategy;
import com.georu.core.internal.strategy.TableMigrationStrategyProvider;
import com.georu.core.strategy.TableOptions;
import com.georu.core.internal.utils.StrategyUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.batch.item.ItemWriter;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Optional;

/**
 * 테이블의 타겟 DataSource 와 타겟 테이블명을 찾는다.
 * <ul>
 *   <li>DataSource: 그 테이블 Writer 가 쓰는 SqlSessionFactory 의 DataSource (Writer 가 실제로 쓰는 DB 와 같다)</li>
 *   <li>테이블명: Strategy 의 TableOptions.targetTable, 없으면 Job 키(tableName)</li>
 * </ul>
 * Writer 가 {@link AbstractMigrationWriter} 가 아니면 찾지 못한다 (기존 데이터 확인·건수 비교는 "확인 불가" 로 처리).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TargetTableResolver {

    private final ApplicationContext applicationContext;
    private final TableMigrationStrategyProvider strategyProvider;

    public record TargetRef(DataSource dataSource, String tableName, SqlSessionFactory sqlSessionFactory) {}

    /** 한 번 읽어 여러 곳에 쓰기의 타겟 하나 */
    public record NamedTarget(String name, String dataSourceName, DataSource dataSource, String tableName) {}

    /** 한 번 읽어 여러 곳에 쓰기 단위면 타겟 목록, 아니면 빈 목록 */
    public java.util.List<NamedTarget> fanOutTargets(String pluginName, String tableName) {
        try {
            ItemWriter<?> writer = StrategyUtils.createWriter(applicationContext, tableName, "NORMAL");
            if (writer instanceof com.georu.core.internal.plugin.FanOutWriter f) {
                return f.targets().stream().map(t -> new NamedTarget(t.name(), t.dataSource(), f.dataSource(t), t.table())).toList();
            }
        } catch (Exception e) {
            log.debug("타겟 목록 확인 불가 {}.{}: {}", pluginName, tableName, e.getMessage());
        }
        return java.util.List.of();
    }

    public Optional<TargetRef> resolve(String pluginName, String tableName) {
        try {
            ItemWriter<?> writer = StrategyUtils.createWriter(applicationContext, tableName, "NORMAL");
            if (!(writer instanceof AbstractMigrationWriter<?> amw) || amw.getSqlSessionFactory() == null) {
                log.debug("타겟 확인 불가 - Writer 가 AbstractMigrationWriter 가 아님: {}", tableName);
                return Optional.empty();
            }
            SqlSessionFactory factory = amw.getSqlSessionFactory();
            DataSource ds = factory.getConfiguration().getEnvironment().getDataSource();
            String targetTable = options(pluginName, tableName).getTargetTable();
            return Optional.of(new TargetRef(ds, targetTable != null ? targetTable : tableName, factory));
        } catch (Exception e) {
            log.debug("타겟 확인 불가 {}.{}: {}", pluginName, tableName, e.getMessage());
            return Optional.empty();
        }
    }

    /** Strategy 의 테이블 옵션 (Strategy 를 못 찾으면 기본값) */
    public TableOptions options(String pluginName, String tableName) {
        return strategy(pluginName, tableName)
                .map(s -> s.resolveTableOptions(tableName))
                .orElse(TableOptions.DEFAULT);
    }

    public Optional<AbstractTableMigrationStrategy> strategy(String pluginName, String tableName) {
        try {
            Optional<TableMigrationStrategy> s = strategyProvider.getStrategy(pluginName, tableName);
            return s.filter(AbstractTableMigrationStrategy.class::isInstance).map(AbstractTableMigrationStrategy.class::cast);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
