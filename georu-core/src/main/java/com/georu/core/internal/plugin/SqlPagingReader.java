package com.georu.core.internal.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.georu.common.dto.CommonSourceDto;
import com.georu.core.reader.AbstractPagingItemReader;

import java.util.List;

/**
 * 공용 소스 Reader. 테이블별 Reader 클래스를 대신한다 - 페이징·테넌트·기간·테스트 모드 처리는 기반 클래스에 있다.
 */
public class SqlPagingReader<T> extends AbstractPagingItemReader<T, GenericSourceMapper> {

    private static final Logger LOG = LoggerFactory.getLogger(SqlPagingReader.class);

    private final String entityName;

    public SqlPagingReader(GenericSourceMapper mapper, String table, int pageSize) {
        super(mapper, "MIGRATION", "SOURCE", pageSize);
        this.entityName = BeanNames.camel(table) + "Reader";
    }

    @Override
    protected int getTotalCount() {
        return mapper.selectTotalCount(createFilterParams());
    }

    @Override
    protected List<T> getPageData(int offset, int limit) {
        CommonSourceDto params = createFilterParams();
        params.set_skiprows(offset);
        params.set_pagesize(limit);
        return mapper.selectPageData(params);
    }

    @Override
    protected void logReadItem(T item) {
        LOG.trace("Read {} item: {}", entityName, item);
    }

    @Override
    protected String getEntityName() {
        return entityName;
    }
}
