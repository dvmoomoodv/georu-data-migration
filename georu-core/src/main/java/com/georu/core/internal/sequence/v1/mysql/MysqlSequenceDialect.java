package com.georu.core.internal.sequence.v1.mysql;

import com.georu.core.internal.sequence.SequenceException;
import com.georu.core.sequence.model.SequenceKey;
import com.georu.core.sequence.model.SequenceRow;
import com.georu.core.sequence.model.SequenceTableSpec;
import com.georu.core.internal.sequence.support.IdentifierValidator;
import com.georu.core.internal.sequence.v1.dialect.SequenceDialect;
import com.georu.core.internal.sequence.v1.dialect.SequenceRows;
import com.georu.core.internal.sequence.v1.mysql.mapper.V1MysqlSequenceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * v1 / MySQL(MariaDB) 방언.
 *
 * <p>업체 CommonSequnceMapper(nextval / manyseq / currval) 의 upsert 구조를 가져오되
 * 테이블명·컬럼명은 {@code ${cfg.*}} 로 받고, 갱신값 회수를 {@code LAST_INSERT_ID(expr)} 로 원자화했다
 * (UPDATE 후 별도 SELECT 는 동시 호출 시 같은 값을 줄 수 있다).</p>
 *
 * <p>전제: 관리 테이블에 (테이블명, 컬럼명) PK 또는 UNIQUE 가 있어야 ON DUPLICATE KEY 가 동작한다.</p>
 */
@Slf4j
@Component
@Order(10)
public class MysqlSequenceDialect implements SequenceDialect {

    public static final String NAME = "mysql";

    private final V1MysqlSequenceMapper mapper;

    /** 등록용 틀 (매퍼 없음). {@link #bind} 로 DataSource 마다 매퍼가 붙은 인스턴스를 만든다 */
    public MysqlSequenceDialect() {
        this(null);
    }

    public MysqlSequenceDialect(V1MysqlSequenceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public SequenceDialect bind(SqlSessionTemplate session) {
        return new MysqlSequenceDialect(session.getMapper(V1MysqlSequenceMapper.class));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(String databaseProductName) {
        if (databaseProductName == null) {
            return false;
        }
        String p = databaseProductName.toLowerCase(Locale.ROOT);
        return p.contains("mysql") || p.contains("mariadb");
    }

    @Override
    public long reserveEnd(SequenceTableSpec cfg, SequenceKey key, int count, long initialValue, boolean createIfMissing) {
        if (count < 1) {
            throw new IllegalArgumentException("선점 수는 1 이상이어야 합니다: " + count);
        }
        if (!createIfMissing && mapper.selectCurrent(cfg, key) == null) {
            throw new SequenceException("시퀀스 관리행이 없습니다: " + cfg.getTable() + " " + key.display()
                    + " (georu.sequence.create-if-missing=false)");
        }
        V1MysqlReserveParam p = V1MysqlReserveParam.of(cfg, key, count, initialValue);
        mapper.upsertAdd(p);
        if (p.getResult() == null) {
            throw new SequenceException("LAST_INSERT_ID 를 읽지 못했습니다: " + cfg.getTable() + " " + key.display());
        }
        return p.getResult();
    }

    @Override
    public Long current(SequenceTableSpec cfg, SequenceKey key) {
        return mapper.selectCurrent(cfg, key);
    }

    @Override
    public void set(SequenceTableSpec cfg, SequenceKey key, long value, boolean createIfMissing) {
        int updated = mapper.setValue(cfg, key, value);
        if (updated == 0) {
            if (!createIfMissing) {
                throw new SequenceException("시퀀스 관리행이 없습니다: " + cfg.getTable() + " " + key.display());
            }
            mapper.insertRow(cfg, key, value);
        }
    }

    @Override
    public Long maxOfTarget(String targetTable, String column) {
        String t = IdentifierValidator.requireTable("대상 테이블", targetTable);
        String c = IdentifierValidator.requireColumn("대상 컬럼", column);
        return mapper.selectMaxOfTarget(t, c);
    }

    @Override
    public List<SequenceRow> list(SequenceTableSpec cfg) {
        return SequenceRows.fromMaps(mapper.selectAll(cfg));
    }
}
