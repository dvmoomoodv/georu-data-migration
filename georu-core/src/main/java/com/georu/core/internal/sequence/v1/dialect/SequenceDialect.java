package com.georu.core.internal.sequence.v1.dialect;

import com.georu.core.sequence.model.SequenceKey;
import com.georu.core.sequence.model.SequenceRow;
import com.georu.core.sequence.model.SequenceTableSpec;

import org.mybatis.spring.SqlSessionTemplate;

import java.util.List;

/**
 * v1 정책의 DB 방언 SPI. SQL 은 방언 디렉터리({@code v1/mysql}, {@code v1/generic})의 매퍼가 갖는다.
 * 트랜잭션 경계는 정책({@code V1BlockReservePolicy})이 잡는다. 방언은 같은 커넥션에서 호출된다고 가정한다.
 *
 * <p>값의 의미: 관리 테이블의 현재값 = 마지막으로 사용된 번호.</p>
 */
public interface SequenceDialect {

    /** 방언 이름 ("mysql", "generic"). 설정 {@code georu.sequence.v1.dialect} 와 비교 */
    String name();

    /** 이 방언을 DataSource 의 세션(매퍼)에 붙인 인스턴스. 채번 DB·타겟 DB 마다 만든다 */
    SequenceDialect bind(SqlSessionTemplate session);

    /** {@code DatabaseMetaData.getDatabaseProductName()} 값으로 담당 여부 판단 */
    boolean supports(String databaseProductName);

    /**
     * count 개 선점. 행이 없으면 createIfMissing 에 따라 initialValue 로 만들고 선점한다.
     *
     * @return 선점 구간의 끝 (갱신 후 현재값). 구간은 [end - count + 1, end]
     */
    long reserveEnd(SequenceTableSpec cfg, SequenceKey key, int count, long initialValue, boolean createIfMissing);

    /** 현재값. 행이 없으면 null */
    Long current(SequenceTableSpec cfg, SequenceKey key);

    /** 값 설정. 행이 없으면 createIfMissing 에 따라 생성 */
    void set(SequenceTableSpec cfg, SequenceKey key, long value, boolean createIfMissing);

    /** 대상 테이블의 MAX(column). 행이 없으면 null */
    Long maxOfTarget(String targetTable, String column);

    /** 관리 테이블 전체 */
    List<SequenceRow> list(SequenceTableSpec cfg);
}
