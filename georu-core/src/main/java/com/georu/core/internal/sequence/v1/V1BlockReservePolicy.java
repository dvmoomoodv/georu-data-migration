package com.georu.core.internal.sequence.v1;

import com.georu.core.sequence.SequenceAllocationPolicy;
import com.georu.core.internal.sequence.SequenceException;
import com.georu.core.sequence.SequenceProperties;
import com.georu.core.sequence.SequenceRunMode;
import com.georu.core.internal.sequence.SequenceVersions;
import com.georu.core.sequence.model.SequenceBlock;
import com.georu.core.sequence.model.SequenceKey;
import com.georu.core.sequence.model.SequenceRow;
import com.georu.core.sequence.model.SequenceStepStats;
import com.georu.core.sequence.model.SequenceTableSpec;
import com.georu.core.internal.sequence.v1.dialect.SequenceDialect;
import com.georu.core.internal.sequence.v1.dialect.SequenceDialects;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1: 블록 선점 정책.
 *
 * <ul>
 *   <li>관리 테이블에서 {@code block-size} 개를 한 번에 선점(+N)하고 메모리 커서로 하나씩 내준다. block-size 1 이면 건별 +1 갱신</li>
 *   <li>SQL 은 방언({@link SequenceDialect})에 위임. mysql 은 upsert 한 문장으로 원자 처리, generic 은 FOR UPDATE + UPDATE</li>
 *   <li>선점 트랜잭션은 타깃 DB 트랜잭션 매니저로 REQUIRES_NEW. 청크 트랜잭션(운영 저장소)과 독립</li>
 *   <li>DRY_RUN 키는 관리 테이블을 읽기만 하고 현재값+1 부터 메모리에서 채번</li>
 *   <li>{@code sync-before-run} 이면 Step 시작 때 현재값을 타겟 MAX 이상으로 맞춘다 (관리행 없음 · 값 뒤처짐 · NULL 값 대비)</li>
 *   <li>미사용 선점 번호는 반환하지 않는다 (번호 공백 허용)</li>
 * </ul>
 * Processor 는 단일 스레드지만 운영 API 와 겹칠 수 있어 정책 단위로 동기화한다.
 */
@Slf4j
public class V1BlockReservePolicy implements SequenceAllocationPolicy {

    private final SequenceProperties props;
    private final V1SequenceProperties v1Props;
    private final SequenceDialects dialects;
    private final SequenceTableSpec spec;
    private final String keyPrefix;
    private final DataSource sequenceDataSource;
    private final SqlSessionTemplate sequenceSession;
    private final DataSource targetDataSource;
    private final SqlSessionTemplate targetSession;
    private final TransactionTemplate tx;
    private final Map<SequenceKey, KeyState> states = new ConcurrentHashMap<>();
    private volatile SequenceDialect dialect;
    private volatile SequenceDialect targetDialect;

    /** 채번 테이블이 타겟 DB 에 있고 방언이 이미 매퍼에 붙어 있을 때 (테스트·단일 DB) */
    public V1BlockReservePolicy(SequenceProperties props, V1SequenceProperties v1Props, SequenceDialects dialects,
                                DataSource targetDataSource, PlatformTransactionManager targetTxManager) {
        this(props, v1Props, dialects, props.toSpec(), "", targetDataSource, targetTxManager, null, targetDataSource, null);
    }

    /**
     * @param spec           채번 테이블·컬럼 이름 (채번 DB 기준)
     * @param keyPrefix      관리 테이블의 테이블명 값 앞에 붙일 접두어 (같은 테이블명을 쓰는 타겟끼리 번호 공간을 나눌 때)
     * @param sequenceSession 채번 DB 세션. null 이면 방언을 그대로 쓴다
     * @param targetSession   타겟 DB 세션 (MAX 동기화). 채번 DB 와 같으면 같은 값
     */
    public V1BlockReservePolicy(SequenceProperties props, V1SequenceProperties v1Props, SequenceDialects dialects,
                                SequenceTableSpec spec, String keyPrefix,
                                DataSource sequenceDataSource, PlatformTransactionManager sequenceTxManager, SqlSessionTemplate sequenceSession,
                                DataSource targetDataSource, SqlSessionTemplate targetSession) {
        this.props = props;
        this.v1Props = v1Props;
        this.dialects = dialects;
        this.spec = spec;
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
        this.sequenceDataSource = sequenceDataSource;
        this.sequenceSession = sequenceSession;
        this.targetDataSource = targetDataSource;
        this.targetSession = targetSession;
        this.tx = new TransactionTemplate(sequenceTxManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setTimeout(Math.max(1, v1Props.getLockTimeoutSeconds()));
    }

    /** 관리 테이블에 저장할 키 (접두어 적용) */
    private SequenceKey stored(SequenceKey key) {
        return keyPrefix.isEmpty() ? key
                : new SequenceKey(keyPrefix + key.getTable(), key.getColumn(), key.getTargetTable(), key.getTargetColumn());
    }

    /** 키별 Step 상태 */
    private static final class KeyState {
        SequenceRunMode mode = SequenceRunMode.NORMAL;
        long start;
        long reserved;
        long used;
        long last;
        SequenceBlock block;
        V1DryRunAllocator dryRun;
    }

    // ------------------------------------------------------------------ SPI

    @Override
    public String version() {
        return SequenceVersions.V1;
    }

    @Override
    public String description() {
        return "블록 선점 (block-size " + v1Props.getBlockSize() + ", dialect " + (dialect == null ? v1Props.getDialect() : dialect.name()) + ")";
    }

    @Override
    public synchronized void beginStep(SequenceKey key, SequenceRunMode mode) {
        KeyState s = new KeyState();
        s.mode = mode == null ? SequenceRunMode.NORMAL : mode;
        s.start = startValue(key, s.mode);
        s.last = s.start;
        if (s.mode == SequenceRunMode.DRY_RUN) {
            s.dryRun = new V1DryRunAllocator(s.start);
        }
        states.put(key, s);
        log.info("[seq v1] beginStep {} mode={} current={} (table={})", stored(key).display(), s.mode, s.start, spec.getTable());
    }

    @Override
    public long current(SequenceKey key) {
        Long v = tx.execute(status -> dialect().current(spec(), stored(key)));
        return v == null ? props.getInitialValue() : v;
    }

    @Override
    public synchronized long next(SequenceKey key) {
        KeyState s = states.computeIfAbsent(key, k -> {
            KeyState n = new KeyState();
            n.start = current(k);
            n.last = n.start;
            return n;
        });
        long value;
        if (s.mode == SequenceRunMode.DRY_RUN) {
            value = s.dryRun.next();
        } else {
            if (s.block == null || s.block.exhausted()) {
                s.block = reserveInternal(key, v1Props.getBlockSize());
                s.reserved += s.block.size();
            }
            value = s.block.next();
        }
        s.used++;
        s.last = value;
        return value;
    }

    @Override
    public synchronized SequenceBlock reserve(SequenceKey key, int count) {
        KeyState s = states.get(key);
        if (s != null && s.mode == SequenceRunMode.DRY_RUN) {
            SequenceBlock block = s.dryRun.reserve(count);
            s.used += count;
            s.last = block.to();
            return block;
        }
        SequenceBlock block = reserveInternal(key, count);
        if (s != null) {
            s.reserved += count;
        }
        return block;
    }

    @Override
    public void set(SequenceKey key, long value) {
        tx.executeWithoutResult(status -> dialect().set(spec(), stored(key), value, props.isCreateIfMissing()));
        log.info("[seq v1] set {} = {}", key.display(), value);
    }

    @Override
    public long syncToMax(SequenceKey key) {
        // MAX 는 타겟 DB 에서, 현재값·갱신은 채번 DB 에서 (두 DB 가 다르면 연결을 차례로 쓴다)
        Long max = targetDialect().maxOfTarget(key.getTargetTable(), key.getTargetColumn());
        SequenceKey sk = stored(key);
        return tx.execute(status -> {
            SequenceDialect d = dialect();
            Long cur = d.current(spec(), sk);
            long current = cur == null ? props.getInitialValue() : cur;
            if (max == null || max <= current) {
                log.info("[seq v1] syncToMax {}: MAX={} current={} → 유지", key.display(), max, current);
                return current;
            }
            d.set(spec(), sk, max, props.isCreateIfMissing());
            log.info("[seq v1] syncToMax {}: {} → {}", key.display(), current, max);
            return max;
        });
    }

    @Override
    public synchronized SequenceStepStats endStep(SequenceKey key) {
        KeyState s = states.remove(key);
        if (s == null) {
            long cur = current(key);
            return new SequenceStepStats(key, version(), SequenceRunMode.NORMAL, cur, cur, 0, 0);
        }
        long end = s.used == 0 ? s.start : s.last;
        SequenceStepStats stats = new SequenceStepStats(key, version(), s.mode, s.start, end, s.reserved, s.used);
        log.info("[seq v1] endStep {}", stats.summary());
        return stats;
    }

    @Override
    public List<SequenceRow> list() {
        return tx.execute(status -> dialect().list(spec()));
    }

    // ------------------------------------------------------------------ 내부

    /** Step 시작값. sync-before-run 이면 타겟 MAX 이상으로 (NORMAL 은 관리 테이블에 반영, DRY_RUN 은 계산만) */
    private long startValue(SequenceKey key, SequenceRunMode mode) {
        if (!v1Props.isSyncBeforeRun()) {
            return current(key);
        }
        if (mode == SequenceRunMode.DRY_RUN) {
            long current = current(key);
            Long max = targetDialect().maxOfTarget(key.getTargetTable(), key.getTargetColumn());
            return max == null ? current : Math.max(current, max);
        }
        return syncToMax(key);
    }

    private SequenceBlock reserveInternal(SequenceKey key, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("선점 수는 1 이상이어야 합니다: " + count);
        }
        SequenceTableSpec cfg = spec();
        SequenceKey sk = stored(key);
        Long end = tx.execute(status -> dialect().reserveEnd(cfg, sk, count, props.getInitialValue(), props.isCreateIfMissing()));
        if (end == null) {
            throw new SequenceException("시퀀스 선점 결과가 없습니다: " + cfg.getTable() + " " + key.display());
        }
        long from = end - count + 1;
        log.info("[seq v1/{}] {} {} 선점 {} ~ {}", dialect().name(), cfg.getTable(), key.display(), from, end);
        return new SequenceBlock(from, end);
    }

    private SequenceTableSpec spec() {
        return spec;
    }

    /** 방언은 첫 사용 시 결정 (기동 시 DB 접속을 강제하지 않기 위해) */
    private SequenceDialect dialect() {
        SequenceDialect d = dialect;
        if (d == null) {
            synchronized (this) {
                if (dialect == null) {
                    SequenceDialect chosen = dialects.select(v1Props.getDialect(), sequenceDataSource);
                    dialect = sequenceSession == null ? chosen : chosen.bind(sequenceSession);
                }
                d = dialect;
            }
        }
        return d;
    }

    /** MAX 를 읽을 타겟 DB 방언. 채번 DB 와 같으면 채번 방언 */
    private SequenceDialect targetDialect() {
        if (targetDataSource == sequenceDataSource) {
            return dialect();
        }
        SequenceDialect d = targetDialect;
        if (d == null) {
            synchronized (this) {
                if (targetDialect == null) {
                    SequenceDialect chosen = dialects.select(V1SequenceProperties.DIALECT_AUTO, targetDataSource);
                    targetDialect = targetSession == null ? chosen : chosen.bind(targetSession);
                }
                d = targetDialect;
            }
        }
        return d;
    }

    /** 현재 선택된 방언 이름 (API 노출용). 아직 미결정이면 설정값 */
    public String dialectName() {
        return dialect == null ? v1Props.getDialect() : dialect.name();
    }
}
