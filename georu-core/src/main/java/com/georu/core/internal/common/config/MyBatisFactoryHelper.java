package com.georu.core.internal.common.config;

import java.util.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * MyBatis SqlSessionFactory 생성 헬퍼
 * - 엔진 매퍼 XML(classpath*:georu/internal/**)은 항상 넣고, 앱 매퍼 XML 은 설정({prefix}.mapper-locations)이 없으면 기본 위치에서 찾는다
 * - 중복 제거 + Fail-Fast + 로깅
 * - 빈 매퍼 로딩 시 예외 발생으로 즉시 실패
 * - 로딩된 XML 파일 수 INFO 로그로 확인 가능
 */
@Component
public class MyBatisFactoryHelper {
    private static final Logger log = LoggerFactory.getLogger(MyBatisFactoryHelper.class);
    private final Environment env;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Autowired
    private SqlLoggingInterceptor sqlLoggingInterceptor;

    public MyBatisFactoryHelper(Environment env) {
        this.env = env;
    }

    /**
     * SqlSessionFactory 생성
     * @param ds DataSource
     * @param prefix 설정 프리픽스 (예: georu.datasource.target-m.mybatis)
     * @return SqlSessionFactory
     * @throws Exception 매퍼 XML이 없거나 설정 오류 시
     */
    public SqlSessionFactory buildFactory(DataSource ds, String prefix) throws Exception {
        return buildFactory(ds, prefix, List.of(), List.of());
    }

    /**
     * @param engineLocations 엔진 매퍼 XML 위치. 설정과 관계없이 항상 넣는다
     * @param defaultAppLocations 앱 매퍼 XML 기본 위치. {prefix}.mapper-locations 가 없을 때만 쓴다
     */
    public SqlSessionFactory buildFactory(DataSource ds, String prefix, List<String> engineLocations,
                                          List<String> defaultAppLocations) throws Exception {
        return buildFactory(ds, prefix, engineLocations, defaultAppLocations, r -> true);
    }

    /**
     * @param appFilter 앱 매퍼 XML 중 이 DataSource 에 실을 것 (엔진 XML 은 거르지 않는다)
     */
    public SqlSessionFactory buildFactory(DataSource ds, String prefix, List<String> engineLocations,
                                          List<String> defaultAppLocations, java.util.function.Predicate<Resource> appFilter) throws Exception {
        SqlSessionFactoryBean fb = new SqlSessionFactoryBean();
        fb.setDataSource(ds);

        // mapper-locations 로딩 - Spring Boot YAML 배열 읽기
        List<String> patternList = new ArrayList<>();

        // YAML 배열을 인덱스로 읽기
        int index = 0;
        while (true) {
            String pattern = env.getProperty(prefix + ".mapper-locations[" + index + "]");
            if (pattern == null || pattern.isBlank()) {
                break;
            }
            patternList.add(pattern);
            index++;
        }

        // 인덱스 방식으로 읽지 못했다면 단일 문자열로 시도
        if (patternList.isEmpty()) {
            String singlePattern = env.getProperty(prefix + ".mapper-locations");
            if (singlePattern != null && !singlePattern.isBlank()) {
                patternList.add(singlePattern);
            }
        }

        boolean configured = !patternList.isEmpty();
        if (!configured) {
            patternList.addAll(defaultAppLocations);
        }
        Set<String> enginePatterns = new HashSet<>(engineLocations);
        List<String> patterns = new ArrayList<>(engineLocations);
        patterns.addAll(patternList);
        patternList = patterns;
        log.info("[MyBatis] {} mapper-locations patterns: {}", prefix, patternList);

        // 같은 파일이 엔진 위치와 앱 위치에 함께 잡히면(예: georu/** 패턴) 한 번만 넣는다
        Map<String, Resource> unique = new LinkedHashMap<>();
        for (String pattern : patternList) {
            try {
                Resource[] found = resolver.getResources(pattern);
                boolean engine = enginePatterns.contains(pattern);
                for (Resource r : found) {
                    if (engine || unique.containsKey(r.getURL().toString()) || appFilter.test(r)) {
                        unique.putIfAbsent(r.getURL().toString(), r);
                    }
                }
                log.debug("[MyBatis] {} pattern '{}' found {} files", prefix, pattern, found.length);
            } catch (Exception e) {
                log.warn("[MyBatis] {} pattern '{}' failed: {}", prefix, pattern, e.getMessage());
            }
        }
        
        List<Resource> all = new ArrayList<>(unique.values());

        // Fail-Fast: 직접 지정한 위치에 매퍼 XML 이 없으면 즉시 실패. 기본 위치만 쓰는데 없으면(플러그인 0개) 경고만
        if (all.isEmpty()) {
            if (configured) {
                throw new IllegalStateException("No MyBatis mapper XMLs found for prefix: " + prefix +
                    ", patterns: " + patternList);
            }
            log.warn("[MyBatis] {} 매퍼 XML 이 없습니다 (patterns: {})", prefix, patternList);
        } else {
            fb.setMapperLocations(all.toArray(Resource[]::new));
        }
        log.info("[MyBatis] {} loaded mapper xml count = {}", prefix, all.size());
        
        // 로딩된 파일 목록 출력 (DEBUG 레벨)
        if (log.isDebugEnabled()) {
            for (Resource resource : all) {
                log.debug("[MyBatis] {} loaded: {}", prefix, resource.getFilename());
            }
        }

        // type-aliases 설정
        String aliases = env.getProperty(prefix + ".type-aliases-package");
        if (aliases != null && !aliases.isBlank()) {
            fb.setTypeAliasesPackage(aliases);
            log.info("[MyBatis] {} type-aliases-package = {}", prefix, aliases);
        }

        // configuration 설정
        org.apache.ibatis.session.Configuration c = new org.apache.ibatis.session.Configuration();
        c.setMapUnderscoreToCamelCase(env.getProperty(prefix + ".configuration.map-underscore-to-camel-case", Boolean.class, true));
        c.setCallSettersOnNulls(env.getProperty(prefix + ".configuration.call-setters-on-nulls", Boolean.class, true));
        c.setCacheEnabled(env.getProperty(prefix + ".configuration.cache-enabled", Boolean.class, true));
        c.setLazyLoadingEnabled(env.getProperty(prefix + ".configuration.lazy-loading-enabled", Boolean.class, false));
        c.setDefaultFetchSize(env.getProperty(prefix + ".configuration.default-fetch-size", Integer.class, 1000));
        c.setDefaultStatementTimeout(env.getProperty(prefix + ".configuration.default-statement-timeout", Integer.class, 30));
        c.setJdbcTypeForNull(org.apache.ibatis.type.JdbcType.NULL);
        
        // 권장: 안전한 기본값들
        c.setAggressiveLazyLoading(env.getProperty(prefix + ".configuration.aggressive-lazy-loading", Boolean.class, false));
        c.setSafeRowBoundsEnabled(env.getProperty(prefix + ".configuration.safe-row-bounds-enabled", Boolean.class, true));
        c.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.valueOf(
                env.getProperty(prefix + ".configuration.local-cache-scope", String.class, "STATEMENT")
        ));
        
        // 🔍 SQL 로깅 인터셉터 추가
        c.addInterceptor(sqlLoggingInterceptor);
        log.info("[MyBatis] {} SQL 로깅 인터셉터 추가됨", prefix);

        fb.setConfiguration(c);
        log.info("[MyBatis] {} configuration applied", prefix);

        SqlSessionFactory factory = fb.getObject();
        if (factory == null) {
            throw new IllegalStateException("Failed to create SqlSessionFactory for prefix: " + prefix);
        }
        
        log.info("[MyBatis] {} SqlSessionFactory created successfully", prefix);
        return factory;
    }
}
