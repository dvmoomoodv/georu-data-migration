package com.georu.core.internal.plugin;

import com.georu.common.dto.CommonSourceDto;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.List;

/**
 * 매퍼 인터페이스 없이 XML namespace 로 소스 SQL 을 호출하는 공용 소스 매퍼.
 * 엔진(총 건수 조회, 사전 점검)이 리플렉션으로 {@code selectTotalCount(CommonSourceDto)}·{@code selectPageData(CommonSourceDto)} 를
 * 찾으므로 메서드 이름·파라미터 타입을 그대로 둔다.
 */
public class GenericSourceMapper {

    private final SqlSessionFactory sqlSessionFactory;
    private final String namespace;
    private final java.util.Map<String, String> fixedParams;

    public GenericSourceMapper(SqlSessionFactory sqlSessionFactory, String namespace) {
        this(sqlSessionFactory, namespace, java.util.Map.of());
    }

    /** @param fixedParams 테이블의 고정 조회 조건 ({@code TableDef.params}). SQL 의 {@code #{params.<키>}} */
    public GenericSourceMapper(SqlSessionFactory sqlSessionFactory, String namespace, java.util.Map<String, String> fixedParams) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.namespace = namespace;
        this.fixedParams = fixedParams == null ? java.util.Map.of() : java.util.Map.copyOf(fixedParams);
    }

    /** 고정 조건을 요청 조건에 더한다 (요청에 같은 키가 있으면 요청 값) */
    private CommonSourceDto withParams(CommonSourceDto params) {
        if (params == null || fixedParams.isEmpty()) {
            return params;
        }
        java.util.Map<String, String> merged = new java.util.LinkedHashMap<>(fixedParams);
        if (params.getParams() != null) {
            merged.putAll(params.getParams());
        }
        params.setParams(merged);
        return params;
    }

    public int selectTotalCount(CommonSourceDto params) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            Integer n = session.selectOne(namespace + ".selectTotalCount", withParams(params));
            return n == null ? 0 : n;
        }
    }

    public <T> List<T> selectPageData(CommonSourceDto params) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.selectList(namespace + ".selectPageData", withParams(params));
        }
    }

    public String getNamespace() {
        return namespace;
    }
}
