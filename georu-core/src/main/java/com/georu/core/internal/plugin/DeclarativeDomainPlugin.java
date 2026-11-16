package com.georu.core.internal.plugin;

import com.georu.core.plugin.GeoruPlugin;

import com.georu.core.internal.config.JobConfiguration;
import com.georu.core.internal.config.JobRequest;
import com.georu.core.internal.spi.DomainPlugin;
import com.georu.core.internal.strategy.TableMigrationStrategy;
import com.georu.core.internal.validation.ValidationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link GeoruPlugin} 선언을 엔진의 {@link DomainPlugin} 으로 노출한다. 플러그인마다 DomainPlugin 클래스를 쓰지 않아도 된다.
 */
public class DeclarativeDomainPlugin implements DomainPlugin {

    private final GeoruPlugin meta;
    private final DeclarativeTableStrategy strategy;

    public DeclarativeDomainPlugin(GeoruPlugin meta, DeclarativeTableStrategy strategy) {
        this.meta = meta;
        this.strategy = strategy;
    }

    @Override
    public String getDomainName() {
        return meta.name();
    }

    @Override
    public String getPluginName() {
        return meta.name();
    }

    @Override
    public String getVersion() {
        return meta.version();
    }

    @Override
    public String getDescription() {
        return meta.description().isEmpty() ? meta.name() + " Migration Plugin" : meta.description();
    }

    @Override
    public List<String> getSupportedJobTypes() {
        return List.of("all", "incremental", "validation");
    }

    @Override
    public List<String> getSupportedDataSources() {
        return List.of(meta.source(), meta.target());
    }

    @Override
    public JobConfiguration createJobConfiguration(JobRequest request) {
        return JobConfiguration.builder()
                .jobName(String.format("%s-%s-job", meta.name().replace('.', '-'), request.getCustomParameter("tableName")))
                .description(getDescription())
                .build();
    }

    @Override
    public ValidationResult validateConfiguration(JobRequest request) {
        List<String> errors = new ArrayList<>();
        if (request.getDomain() == null || !request.getDomain().equals(meta.name())) {
            errors.add("Invalid domain for " + meta.name() + " plugin");
        }
        return ValidationResult.builder().errors(errors).warnings(new ArrayList<>()).build();
    }

    @Override
    public List<String> recommendedOrder() {
        return strategy.tableOrder();
    }

    @Override
    public Map<String, Object> getMetadata() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", getVersion());
        m.put("description", getDescription());
        m.put("supportedJobTypes", getSupportedJobTypes());
        m.put("supportedDataSources", getSupportedDataSources());
        m.put("tables", strategy.tableOrder());
        m.put("declarative", true);
        return m;
    }

    @Override
    public boolean supports(String tableName) {
        return strategy.supports(tableName);
    }

    @Override
    public Optional<TableMigrationStrategy> getTableMigrationStrategy(String tableName) {
        return strategy.supports(tableName) ? Optional.of(strategy) : Optional.empty();
    }
}
