package com.georu.core.internal.sequence.v1.generic;

import com.georu.core.internal.sequence.SequenceException;
import com.georu.core.sequence.model.SequenceKey;
import com.georu.core.sequence.model.SequenceRow;
import com.georu.core.sequence.model.SequenceTableSpec;
import com.georu.core.internal.sequence.support.IdentifierValidator;
import com.georu.core.internal.sequence.v1.dialect.SequenceDialect;
import com.georu.core.internal.sequence.v1.dialect.SequenceRows;
import com.georu.core.internal.sequence.v1.generic.mapper.V1GenericSequenceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * v1 / generic 방언: SELECT ... FOR UPDATE 로 행을 잠근 뒤 UPDATE +N. MySQL·Oracle·PostgreSQL·H2 공통 구문.
 * 호출자가 하나의 트랜잭션으로 감싸야 한다. @Order 가 가장 뒤라 auto 판별의 마지막 후보다.
 * MSSQL 은 잠금 힌트가 달라 별도 방언(v1/mssql)으로 추가한다.
 */
@Slf4j
@Component
@Order(Integer.MAX_VALUE)
public class GenericSequenceDialect implements SequenceDialect {

    public static final String NAME = "generic";

    private final V1GenericSequenceMapper mapper;

    /** 등록용 틀 (매퍼 없음). {@link #bind} 로 DataSource 마다 매퍼가 붙은 인스턴스를 만든다 */
    public GenericSequenceDialect() {
        this(null);
    }

    public GenericSequenceDialect(V1GenericSequenceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public SequenceDialect bind(SqlSessionTemplate session) {
        return new GenericSequenceDialect(session.getMapper(V1GenericSequenceMapper.class));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(String databaseProductName) {
        return true;
    }

    @Override
    public long reserveEnd(SequenceTableSpec cfg, SequenceKey key, int count, long initialValue, boolean createIfMissing) {
        if (count < 1) {
            throw new IllegalArgumentException("선점 수는 1 이상이어야 합니다: " + count);
        }
        Long current = mapper.selectForUpdate(cfg, key, initialValue);
        if (current == null) {
            if (!createIfMissing) {
                throw new SequenceException("시퀀스 관리행이 없습니다: " + cfg.getTable() + " " + key.display()
                        + " (georu.sequence.create-if-missing=false)");
            }
            mapper.insertRow(cfg, key, initialValue);
            current = initialValue;
            // 새 행을 다시 잠근다 (같은 트랜잭션)
            Long locked = mapper.selectForUpdate(cfg, key, initialValue);
            if (locked != null) {
                current = locked;
            }
        }
        int updated = mapper.increment(cfg, key, count, initialValue);
        if (updated != 1) {
            throw new SequenceException("시퀀스 갱신 건수가 1 이 아닙니다(" + updated + "): " + cfg.getTable() + " " + key.display()
                    + " - (테이블명, 컬럼명) 유일 제약을 확인하세요");
        }
        return current + count;
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
