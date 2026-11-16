package com.georu.core.internal.plugin;

import com.georu.core.config.TestModeContext;
import com.georu.core.internal.common.writer.queue.ExistingKeyFilter;
import com.georu.core.internal.fill.FillMissingService;
import com.georu.core.internal.fill.TargetKeyFilterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.lang.NonNull;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 한 번 읽어 여러 곳에 쓰기의 Writer. {@link FanOut} 묶음을 타겟마다 선언 순서대로 그 타겟의 Writer 로 쓴다.
 * 타겟 하나가 실패하면 묶음 전체가 실패하고, 다시 돌리면 이미 쓴 타겟에도 다시 쓰므로 타겟마다 멱등 쓰기여야 한다
 * (upsert SQL, 또는 {@code skipExisting} - 타겟 PK 로 이미 있는 행을 거른다. 빠진 행 채우기 실행이면 모든 타겟에서 거른다).
 *
 * <p>Queue Writer 로 감싸면 여러 스레드가 부르므로 상태는 원자적으로 둔다. Step 실행 정보:
 * {@code fanout.<타겟>.written}(적재), {@code fanout.<타겟>.alreadyPresent}(이미 있어 건너뜀).</p>
 */
public class FanOutWriter implements ItemWriter<FanOut>, StepExecutionListener {

    private static final Logger LOG = LoggerFactory.getLogger(FanOutWriter.class);

    public static String ctxWritten(String target) {
        return "fanout." + target + ".written";
    }

    public static String ctxAlreadyPresent(String target) {
        return "fanout." + target + ".alreadyPresent";
    }

    /** 타겟 하나 (dataSource 는 DataSource 이름) */
    public record Target(String name, String dataSource, String table, boolean skipExisting) {
    }

    private final String unit;
    private final List<Target> targets;
    private final Function<String, ItemWriter<Object>> writerOf;
    private final Function<String, DataSource> dataSourceOf;
    private final Supplier<TargetKeyFilterFactory> keyFilters;

    private final Map<String, ItemWriter<Object>> writers = new LinkedHashMap<>();
    private final Map<String, ExistingKeyFilter<Object>> filters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> written = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> alreadyPresent = new ConcurrentHashMap<>();
    private final AtomicLong attempted = new AtomicLong();
    private final AtomicLong skippedByMode = new AtomicLong();
    private volatile TestModeContext testModeContext = TestModeContext.NORMAL;

    public FanOutWriter(String unit, List<Target> targets, Function<String, ItemWriter<Object>> writerOf,
                        Function<String, DataSource> dataSourceOf, Supplier<TargetKeyFilterFactory> keyFilters) {
        this.unit = unit;
        this.targets = List.copyOf(targets);
        this.writerOf = writerOf;
        this.dataSourceOf = dataSourceOf;
        this.keyFilters = keyFilters;
    }

    public List<Target> targets() {
        return targets;
    }

    public DataSource dataSource(Target target) {
        return dataSourceOf.apply(target.dataSource());
    }

    private Map<String, ItemWriter<Object>> writers() {
        if (writers.isEmpty()) {
            synchronized (writers) {
                if (writers.isEmpty()) {
                    for (Target t : targets) {
                        writers.put(t.name(), writerOf.apply(t.name()));
                    }
                }
            }
        }
        return writers;
    }

    @Override
    public void beforeStep(@NonNull StepExecution stepExecution) {
        testModeContext = TestModeContext.from(stepExecution);
        attempted.set(0);
        skippedByMode.set(0);
        written.clear();
        alreadyPresent.clear();
        filters.clear();
        for (ItemWriter<Object> w : writers().values()) {
            if (w instanceof StepExecutionListener l) {
                l.beforeStep(stepExecution);
            }
        }
        if (!testModeContext.runWriter()) {
            return;
        }
        boolean fillMissing = "true".equalsIgnoreCase(stepExecution.getJobParameters().getString(FillMissingService.PARAM_FILL_MISSING));
        for (Target t : targets) {
            if (!t.skipExisting() && !fillMissing) {
                continue;
            }
            TargetKeyFilterFactory factory = keyFilters.get();
            ExistingKeyFilter<Object> filter = factory == null ? null
                    : factory.<Object>create(dataSource(t), t.table()).orElse(null);
            if (filter == null) {
                throw new IllegalStateException(unit + " 의 타겟 " + t.name() + "(" + t.table() + ") 에 PK 가 없어 이미 있는 행을 거를 수 없습니다"
                        + (t.skipExisting() ? " (skipExisting)" : " (빠진 행 채우기)"));
            }
            filters.put(t.name(), filter);
            LOG.info("🔎 [{}] 타겟 {} 이미 있는 행 거르기: {}", unit, t.name(), filter.describe());
        }
    }

    @Override
    public void write(@NonNull Chunk<? extends FanOut> chunk) throws Exception {
        if (chunk.isEmpty()) {
            return;
        }
        if (testModeContext.runWriter()) {
            attempted.addAndGet(chunk.size());
        } else {
            skippedByMode.addAndGet(chunk.size());
        }
        for (Target t : targets) {
            List<Object> items = new ArrayList<>(chunk.size());
            for (FanOut f : chunk) {
                Object o = f.of(t.name());
                if (o != null) {
                    items.add(o);
                }
            }
            if (items.isEmpty()) {
                continue;
            }
            ExistingKeyFilter<Object> filter = filters.get(t.name());
            if (filter != null) {
                int before = items.size();
                items = filter.removeExisting(items);
                long skipped = before - items.size();
                if (skipped > 0) {
                    alreadyPresent.computeIfAbsent(t.name(), k -> new AtomicLong()).addAndGet(skipped);
                }
                if (items.isEmpty()) {
                    continue;
                }
            }
            try {
                writers().get(t.name()).write(new Chunk<>(items));
            } catch (Exception e) {
                throw new IllegalStateException(unit + " 의 타겟 " + t.name() + " 적재 실패: " + e.getMessage(), e);
            }
            if (testModeContext.runWriter()) {
                written.computeIfAbsent(t.name(), k -> new AtomicLong()).addAndGet(items.size());
            }
        }
    }

    @Override
    public ExitStatus afterStep(@NonNull StepExecution stepExecution) {
        for (ItemWriter<Object> w : writers().values()) {
            if (w instanceof StepExecutionListener l) {
                l.afterStep(stepExecution);
            }
        }
        // 타겟 Writer 들이 같은 키에 덮어쓴 값을 단위 기준으로 다시 쓴다
        TestModeContext.putLong(stepExecution, TestModeContext.CTX_WRITE_ATTEMPTED, attempted.get());
        TestModeContext.putLong(stepExecution, TestModeContext.CTX_WRITE_SKIPPED, skippedByMode.get());
        List<String> summary = new ArrayList<>();
        for (Target t : targets) {
            long w = count(written, t.name());
            long a = count(alreadyPresent, t.name());
            TestModeContext.putLong(stepExecution, ctxWritten(t.name()), w);
            TestModeContext.putLong(stepExecution, ctxAlreadyPresent(t.name()), a);
            summary.add(t.name() + " " + w + "건" + (a > 0 ? " (이미 있어 건너뜀 " + a + ")" : ""));
        }
        LOG.info("[{}] 타겟별 적재: {}", unit, String.join(", ", summary));
        return stepExecution.getExitStatus();
    }

    private static long count(Map<String, AtomicLong> m, String key) {
        AtomicLong n = m.get(key);
        return n == null ? 0 : n.get();
    }
}
