package com.georu.core.internal.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.georu.core.writer.AbstractBatchMigrationWriter;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

/**
 * 공용 타겟 Writer. {@code <targetNamespace>.insertData} 를 배치로 실행한다. 행 식별은 DTO 의 {@link MigrationId} 필드.
 * 테스트 모드·재시도·큐 적재는 기반 클래스와 QueueingItemWriter 가 처리한다.
 */
public class SqlBatchWriter<T> extends AbstractBatchMigrationWriter<T> {

    private static final Logger LOG = LoggerFactory.getLogger(SqlBatchWriter.class);

    private final String namespace;
    private final String entityName;
    private final MigrationIds ids;

    public SqlBatchWriter(SqlSessionFactory targetSqlSessionFactory, String namespace, String table, Class<T> dtoType) {
        super("MIGRATION", "TARGET");
        this.sqlSessionFactory = targetSqlSessionFactory;
        this.namespace = namespace;
        this.entityName = BeanNames.camel(table) + "Writer";
        this.ids = MigrationIds.of(dtoType);
    }

    @Override
    protected void insertItem(SqlSession session, T item) throws Exception {
        session.insert(namespace + ".insertData", item);
    }

    @Override
    protected Object getItemId(T item) {
        return ids.idOf(item);
    }

    @Override
    protected String getEntityName() {
        return entityName;
    }
}
