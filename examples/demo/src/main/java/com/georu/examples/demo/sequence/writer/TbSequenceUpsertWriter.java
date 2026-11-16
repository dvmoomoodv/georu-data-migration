package com.georu.examples.demo.sequence.writer;

import com.georu.core.plugin.GeoruDataSources;
import com.georu.core.writer.AbstractBatchMigrationWriter;
import com.georu.examples.demo.target.dto.TbSequenceTargetDto;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSession;

/**
 * 직접 만든 Writer 의 예 (탈출구). 공용 {@code SqlBatchWriter} 는 {@code insertData} 만 실행하지만,
 * 채번 관리 테이블은 런타임 채번이 먼저 만든 행을 작은 값으로 덮으면 안 되므로
 * {@code upsertData}(ON DUPLICATE KEY UPDATE SEQ = GREATEST) 를 쓴다.
 *
 * <p>{@code TableDef.writer(TbSequenceUpsertWriter.class)} 로 지정하면 엔진이 이 클래스를 {@code tbSequenceWriter} Bean 으로 등록한다.
 * 생성자 주입은 Spring 이 한다 (타겟 SqlSessionFactory 는 {@link GeoruDataSources} 에서 이름으로).</p>
 */
@Slf4j
public class TbSequenceUpsertWriter extends AbstractBatchMigrationWriter<TbSequenceTargetDto> {

    private static final String UPSERT = "georu.demo.sequence.TbSequenceTargetMapper.upsertData";

    public TbSequenceUpsertWriter(GeoruDataSources dataSources) {
        super("MIGRATION", "TARGET");
        this.sqlSessionFactory = dataSources.sqlSessionFactory("target-m");
        log.debug("{} created (upsert)", getEntityName());
    }

    @Override
    protected void insertItem(SqlSession session, TbSequenceTargetDto item) throws Exception {
        session.insert(UPSERT, item);
    }

    @Override
    protected Object getItemId(TbSequenceTargetDto item) {
        return item != null ? item.getTables() + ":" + item.getCol() : null;    // PK (TABLES, COL)
    }

    @Override
    protected String getEntityName() {
        return "TbSequenceWriter";
    }
}
