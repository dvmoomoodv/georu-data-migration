package com.georu.core.internal.sequence.v1;

import com.georu.core.internal.common.config.AnyTargetDataSourceCondition;
import com.georu.core.internal.common.config.DataSourceNames;
import com.georu.core.internal.sequence.SequenceException;
import com.georu.core.internal.sequence.SequenceVersions;
import com.georu.core.internal.sequence.TargetAwarePolicy;
import com.georu.core.internal.sequence.v1.dialect.SequenceDialects;
import com.georu.core.sequence.SequenceAllocationPolicy;
import com.georu.core.sequence.SequenceProperties;
import com.georu.core.sequence.SequenceRunMode;
import com.georu.core.sequence.model.SequenceBlock;
import com.georu.core.sequence.model.SequenceKey;
import com.georu.core.sequence.model.SequenceRow;
import com.georu.core.sequence.model.SequenceStepStats;
import com.georu.core.sequence.model.SequenceTableSpec;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1 블록 선점 정책을 타겟 DataSource 마다 하나씩 만든다. 채번 위치는 설정으로 고른다.
 *
 * <ul>
 *   <li>채번 DataSource: 테이블의 {@code TableOptions.sequenceDataSource} → {@code georu.datasource.<타겟>.sequence.datasource} → 타겟 자신.
 *       {@code target-*} 또는 {@code sequence-*} 이름</li>
 *   <li>채번 테이블·컬럼 이름: {@code georu.datasource.<채번 DB>.sequence.{table, table-name-column, column-name-column, value-column}},
 *       없으면 {@code georu.sequence.*}</li>
 *   <li>키 접두어: {@code georu.datasource.<타겟>.sequence.key-prefix}. 여러 타겟이 한 채번 테이블을 같이 쓰고 테이블명이 겹칠 때 번호 공간을 나눈다</li>
 * </ul>
 *
 * <p>이 Bean 자체는 기본 타겟({@code target-m}, 없으면 첫 타겟)의 정책으로 동작한다 (운영 API·이전 호출 호환).</p>
 */
@Slf4j
@Component("sequencePolicyV1")
@Conditional(AnyTargetDataSourceCondition.class)
public class V1SequencePolicies implements SequenceAllocationPolicy, TargetAwarePolicy {

    private final SequenceProperties props;
    private final V1SequenceProperties v1Props;
    private final SequenceDialects dialects;
    private final BeanFactory beanFactory;
    private final Environment environment;
    private final Map<String, V1BlockReservePolicy> byLocation = new ConcurrentHashMap<>();

    public V1SequencePolicies(SequenceProperties props, V1SequenceProperties v1Props, SequenceDialects dialects,
                              BeanFactory beanFactory, Environment environment) {
        this.props = props;
        this.v1Props = v1Props;
        this.dialects = dialects;
        this.beanFactory = beanFactory;
        this.environment = environment;
    }

    @Override
    public SequenceAllocationPolicy forTarget(String targetDataSource, String sequenceDataSource) {
        String target = DataSourceNames.canonical(targetDataSource == null ? defaultTarget() : targetDataSource);
        String seq = DataSourceNames.canonical(sequenceDataSource != null && !sequenceDataSource.isBlank() ? sequenceDataSource
                : environment.getProperty(DataSourceNames.key(target, "sequence.datasource"), target));
        return byLocation.computeIfAbsent(target + "|" + seq, k -> create(target, seq));
    }

    private V1BlockReservePolicy create(String target, String seq) {
        for (String ds : List.of(target, seq)) {
            String role = DataSourceNames.role(ds);
            boolean usable = DataSourceNames.ROLE_TARGET.equals(role) || DataSourceNames.ROLE_SEQUENCE.equals(role) && ds.equals(seq);
            if (!usable || !DataSourceNames.enabled(environment, ds)) {
                throw new SequenceException("채번에 쓸 DataSource '" + ds + "' 가 없거나 꺼져 있습니다 (타겟 " + target + ", 채번 위치 " + seq
                        + "). georu.datasource." + ds + " 를 설정하세요");
            }
        }
        String keyPrefix = environment.getProperty(DataSourceNames.key(target, "sequence.key-prefix"), "");
        SequenceTableSpec spec = new SequenceTableSpec(
                environment.getProperty(DataSourceNames.key(seq, "sequence.table"), props.getTable()),
                environment.getProperty(DataSourceNames.key(seq, "sequence.table-name-column"), props.getTableNameColumn()),
                environment.getProperty(DataSourceNames.key(seq, "sequence.column-name-column"), props.getColumnNameColumn()),
                environment.getProperty(DataSourceNames.key(seq, "sequence.value-column"), props.getValueColumn()));
        log.info("[seq v1] 타겟 {} 의 채번 위치 {} (테이블 {}{})", target, seq, spec.getTable(), keyPrefix.isEmpty() ? "" : ", 키 접두어 " + keyPrefix);
        return new V1BlockReservePolicy(props, v1Props, dialects, spec, keyPrefix,
                beanFactory.getBean(DataSourceNames.dataSourceBean(seq), DataSource.class),
                beanFactory.getBean(DataSourceNames.transactionManagerBean(seq), PlatformTransactionManager.class),
                session(seq), beanFactory.getBean(DataSourceNames.dataSourceBean(target), DataSource.class), session(target));
    }

    private SqlSessionTemplate session(String name) {
        return new SqlSessionTemplate(beanFactory.getBean(DataSourceNames.sqlSessionFactoryBean(name), SqlSessionFactory.class));
    }

    /** 기본 타겟: target-m, 없으면 켜진 첫 target-* */
    private String defaultTarget() {
        if (DataSourceNames.enabled(environment, DataSourceNames.TARGET_DEFAULT)) {
            return DataSourceNames.TARGET_DEFAULT;
        }
        return DataSourceNames.configured(environment).stream()
                .filter(n -> DataSourceNames.ROLE_TARGET.equals(DataSourceNames.role(n)) && DataSourceNames.enabled(environment, n))
                .findFirst().orElseThrow(() -> new SequenceException("채번할 타겟 DataSource 가 없습니다 (georu.datasource.target-*)"));
    }

    private SequenceAllocationPolicy main() {
        return forTarget(null, null);
    }

    /** 기본 타겟 정책의 방언 이름 (API 노출용) */
    public String dialectName() {
        SequenceAllocationPolicy p = main();
        return p instanceof V1BlockReservePolicy v ? v.dialectName() : v1Props.getDialect();
    }

    // ------------------------------------------------------------------ 기본 타겟으로 위임

    @Override
    public String version() {
        return SequenceVersions.V1;
    }

    @Override
    public String description() {
        return "블록 선점 (block-size " + v1Props.getBlockSize() + ", 타겟별 채번 위치)";
    }

    @Override
    public void beginStep(SequenceKey key, SequenceRunMode mode) {
        main().beginStep(key, mode);
    }

    @Override
    public long current(SequenceKey key) {
        return main().current(key);
    }

    @Override
    public long next(SequenceKey key) {
        return main().next(key);
    }

    @Override
    public SequenceBlock reserve(SequenceKey key, int count) {
        return main().reserve(key, count);
    }

    @Override
    public void set(SequenceKey key, long value) {
        main().set(key, value);
    }

    @Override
    public long syncToMax(SequenceKey key) {
        return main().syncToMax(key);
    }

    @Override
    public SequenceStepStats endStep(SequenceKey key) {
        return main().endStep(key);
    }

    @Override
    public List<SequenceRow> list() {
        return main().list();
    }
}
