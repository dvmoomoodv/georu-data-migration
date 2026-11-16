package com.georu.core.internal.common.config;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * 🔍 MyBatis SQL 로그 인터셉터
 * 실행되는 모든 SQL과 파라미터를 상세하게 로깅
 */
@Component
@Intercepts({
    @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
    @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
})
public class SqlLoggingInterceptor implements Interceptor {

    private static final Logger logger = LoggerFactory.getLogger("SQL_LOGGER");

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement mappedStatement = (MappedStatement) invocation.getArgs()[0];
        Object parameter = null;
        if (invocation.getArgs().length > 1) {
            parameter = invocation.getArgs()[1];
        }

        String sqlId = mappedStatement.getId();
        BoundSql boundSql = mappedStatement.getBoundSql(parameter);
        Configuration configuration = mappedStatement.getConfiguration();

        long start = System.currentTimeMillis();

        try {
            // SQL 실행 전 로깅
            logSqlBefore(sqlId, boundSql, parameter, configuration);
            
            // 실제 SQL 실행
            Object result = invocation.proceed();
            
            long end = System.currentTimeMillis();
            
            // SQL 실행 후 로깅
            logSqlAfter(sqlId, result, end - start);
            
            return result;
        } catch (Exception e) {
            long end = System.currentTimeMillis();
            logger.error("🚨 SQL 실행 실패 - ID: {}, 실행시간: {}ms, 에러: {}", 
                sqlId, end - start, e.getMessage());
            throw e;
        }
    }

    private void logSqlBefore(String sqlId, BoundSql boundSql, Object parameter, Configuration configuration) {
        String sql = boundSql.getSql();
        if (sql == null || sql.trim().isEmpty()) {
            return;
        }

        // SQL 포맷팅 (개행 및 들여쓰기 유지)
        String formattedSql = formatSql(sql);
        
        // 파라미터 바인딩된 SQL 생성
        String sqlWithParams = getSqlWithParameters(boundSql, parameter, configuration);

        logger.debug("🔍 ==================== SQL 실행 시작 ====================");
        logger.debug("📋 Mapper ID: {}", sqlId);
        logger.debug("📝 원본 SQL (포맷팅):\n{}", formattedSql);
        
        if (parameter != null) {
            logger.debug("📊 파라미터: {}", parameter);
        }
        
        logger.debug("🎯 실행 SQL (파라미터 바인딩):\n{}", sqlWithParams);
        logger.debug("🔍 ========================================================");
    }

    private void logSqlAfter(String sqlId, Object result, long executionTime) {
        if (result instanceof List) {
            List<?> list = (List<?>) result;
            logger.debug("✅ SQL 실행 완료 - ID: {}, 결과: {}건, 실행시간: {}ms", 
                sqlId, list.size(), executionTime);
        } else {
            logger.debug("✅ SQL 실행 완료 - ID: {}, 결과: {}, 실행시간: {}ms", 
                sqlId, result, executionTime);
        }
    }

    private String formatSql(String sql) {
        if (sql == null) return "";
        
        return sql.replaceAll("\\s+", " ")  // 여러 공백을 하나로
                  .replaceAll("\\s*,\\s*", ",\n    ")  // 콤마 후 개행
                  .trim();
                //   .replaceAll("\\s+(SELECT|FROM|WHERE|AND|OR|ORDER BY|GROUP BY|HAVING|UNION|INNER JOIN|LEFT JOIN|RIGHT JOIN)\\s+", 
                //              "\n$1 ")  // 주요 키워드 앞에 개행
                //   .replaceAll("\\s*\\(\\s*", "\n(\n    ")  // 여는 괄호 후 개행
                //   .replaceAll("\\s*\\)\\s*", "\n)")  // 닫는 괄호 앞 개행
                  
    }

    private String getSqlWithParameters(BoundSql boundSql, Object parameter, Configuration configuration) {
        String sql = boundSql.getSql();
        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();

        if (parameterMappings != null && !parameterMappings.isEmpty() && parameter != null) {
            MetaObject metaObject = configuration.newMetaObject(parameter);
            
            for (ParameterMapping parameterMapping : parameterMappings) {
                if (parameterMapping.getMode() != ParameterMode.OUT) {
                    Object value;
                    String propertyName = parameterMapping.getProperty();
                    
                    if (boundSql.hasAdditionalParameter(propertyName)) {
                        value = boundSql.getAdditionalParameter(propertyName);
                    } else if (parameter == null) {
                        value = null;
                    } else if (typeHandlerRegistry.hasTypeHandler(parameter.getClass())) {
                        value = parameter;
                    } else {
                        value = metaObject == null ? null : metaObject.getValue(propertyName);
                    }
                    
                    String parameterValue = getParameterValue(value);
                    // 정규식 특수 문자를 안전하게 이스케이프 처리
                    sql = sql.replaceFirst("\\?", java.util.regex.Matcher.quoteReplacement(parameterValue));
                }
            }
        }
        
        return formatSql(sql);
    }

    private String getParameterValue(Object obj) {
        if (obj == null) {
            return "NULL";
        } else if (obj instanceof String) {
            // 특수문자를 'a'로 변환하여 안전하게 처리
            String cleanValue = sanitizeSpecialCharacters((String) obj);
            return "'" + cleanValue + "'";
        } else if (obj instanceof Date) {
            DateFormat formatter = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, Locale.KOREA);
            return "'" + formatter.format((Date) obj) + "'";
        } else {
            return obj.toString();
        }
    }

    /**
     * 특수문자를 'a'로 변환하여 SQL 로깅 시 안전하게 처리
     * @param input 원본 문자열
     * @return 특수문자가 'a'로 변환된 문자열
     */
    private String sanitizeSpecialCharacters(String input) {
        if (input == null) {
            return null;
        }

        // 영문자, 숫자, 기본 구두점(. - _ /)만 유지하고 나머지는 'a'로 변환
        return input.replaceAll("[^a-zA-Z0-9._/\\-]", "a");
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // 설정이 필요한 경우 여기서 처리
    }
}
