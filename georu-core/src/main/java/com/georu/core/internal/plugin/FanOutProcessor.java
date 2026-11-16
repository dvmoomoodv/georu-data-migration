package com.georu.core.internal.plugin;

import com.georu.core.config.TestModeContext;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.ItemReadListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.lang.NonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 한 번 읽어 여러 곳에 쓰기의 Processor. 소스 행 하나를 타겟마다 그 타겟의 Processor 로 변환해 {@link FanOut} 하나로 넘긴다.
 * 모든 타겟이 걸렀으면 null (Spring Batch 필터). 타겟 Processor 의 Step·청크·읽기 리스너를 대신 불러 준다
 * (Step 빌더는 이 Processor 만 리스너로 등록하므로).
 *
 * <p>Step 실행 정보: {@code fanout.<타겟>.filtered} = 그 타겟이 거른 건수. 단위의 변환 건수·오류 건수는 타겟을 합쳐 다시 쓴다.</p>
 */
public class FanOutProcessor implements ItemProcessor<Object, FanOut>, StepExecutionListener, ChunkListener, ItemReadListener<Object> {

    public static String ctxFiltered(String target) {
        return "fanout." + target + ".filtered";
    }

    private final List<String> targets;
    private final Function<String, ItemProcessor<Object, Object>> processorOf;
    private final Map<String, ItemProcessor<Object, Object>> processors = new LinkedHashMap<>();
    private final Map<String, AtomicLong> filtered = new ConcurrentHashMap<>();
    private final AtomicLong produced = new AtomicLong();

    public FanOutProcessor(List<String> targets, Function<String, ItemProcessor<Object, Object>> processorOf) {
        this.targets = List.copyOf(targets);
        this.processorOf = processorOf;
    }

    private Map<String, ItemProcessor<Object, Object>> processors() {
        if (processors.isEmpty()) {
            synchronized (processors) {
                if (processors.isEmpty()) {
                    for (String t : targets) {
                        processors.put(t, processorOf.apply(t));
                    }
                }
            }
        }
        return processors;
    }

    @Override
    public FanOut process(@NonNull Object item) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, ItemProcessor<Object, Object>> e : processors().entrySet()) {
            Object o = e.getValue().process(item);
            if (o == null) {
                filtered.computeIfAbsent(e.getKey(), k -> new AtomicLong()).incrementAndGet();
            } else {
                out.put(e.getKey(), o);
            }
        }
        if (out.isEmpty()) {
            return null;
        }
        produced.incrementAndGet();
        return new FanOut(out);
    }

    @Override
    public void beforeStep(@NonNull StepExecution stepExecution) {
        filtered.clear();
        produced.set(0);
        for (ItemProcessor<Object, Object> p : processors().values()) {
            if (p instanceof StepExecutionListener l) {
                l.beforeStep(stepExecution);
            }
        }
    }

    @Override
    public ExitStatus afterStep(@NonNull StepExecution stepExecution) {
        long errors = 0;
        for (ItemProcessor<Object, Object> p : processors().values()) {
            if (p instanceof StepExecutionListener l) {
                l.afterStep(stepExecution);
                errors += TestModeContext.getLong(stepExecution, TestModeContext.CTX_PROCESS_ERRORS);
            }
        }
        // 타겟 Processor 들이 같은 키에 덮어쓴 값을 단위 기준으로 다시 쓴다
        TestModeContext.putLong(stepExecution, TestModeContext.CTX_PROCESSED, produced.get());
        TestModeContext.putLong(stepExecution, TestModeContext.CTX_PROCESS_ERRORS, errors);
        for (String t : targets) {
            AtomicLong n = filtered.get(t);
            TestModeContext.putLong(stepExecution, ctxFiltered(t), n == null ? 0 : n.get());
        }
        return stepExecution.getExitStatus();
    }

    @Override
    public void beforeChunk(@NonNull ChunkContext context) {
        for (ItemProcessor<Object, Object> p : processors().values()) {
            if (p instanceof ChunkListener l) {
                l.beforeChunk(context);
            }
        }
    }

    @Override
    public void afterChunk(@NonNull ChunkContext context) {
        for (ItemProcessor<Object, Object> p : processors().values()) {
            if (p instanceof ChunkListener l) {
                l.afterChunk(context);
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void afterRead(@NonNull Object item) {
        for (ItemProcessor<Object, Object> p : processors().values()) {
            if (p instanceof ItemReadListener<?> l) {
                ((ItemReadListener<Object>) l).afterRead(item);
            }
        }
    }

    public List<String> targets() {
        return targets;
    }
}
