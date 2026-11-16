package com.georu.core.internal.excel;

import com.georu.core.reader.AbstractPagingItemReader;
import com.georu.core.config.TestModeContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.ItemReader;
import org.springframework.lang.NonNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 엑셀/CSV 파일을 행 단위로 읽는 ItemReader. Job 마다 새로 만든다 (싱글톤 아님).
 * 파일 경로는 첫 read() 때 {@code pathSupplier} 로 구한다 (Job 시작 리스너가 pending → processing 으로 옮긴 뒤 경로가 바뀌므로).
 * 테스트 모드 읽기 제한(READER_TEST 5건 등)은 플러그인 Reader 와 같은 규칙({@link TestModeContext})을 따른다.
 */
@Slf4j
public class ExcelRowItemReader implements ItemReader<ExcelRow>, StepExecutionListener {

    private final Supplier<Path> pathSupplier;
    private final String entityName;
    /** 파일 대장의 행 수 (업로드 검증 때 셈). 진행률의 전체 건수로 Step 컨텍스트에 남긴다 (todo-task 39). 모르면 null */
    private final Supplier<Integer> rowCountSupplier;
    private ExcelRowSource source;
    private TestModeContext testModeContext = TestModeContext.NORMAL;
    private StepExecution stepExecution;
    private int readCount;
    private boolean exhausted;

    public ExcelRowItemReader(Supplier<Path> pathSupplier, String entityName) {
        this(pathSupplier, entityName, () -> null);
    }

    public ExcelRowItemReader(Supplier<Path> pathSupplier, String entityName, Supplier<Integer> rowCountSupplier) {
        this.pathSupplier = pathSupplier;
        this.entityName = entityName;
        this.rowCountSupplier = rowCountSupplier;
    }

    @Override
    public void beforeStep(@NonNull StepExecution stepExecution) {
        this.stepExecution = stepExecution;
        this.testModeContext = TestModeContext.from(stepExecution);
        this.readCount = 0;
        this.exhausted = false;
        try {
            Integer rows = rowCountSupplier.get();
            if (rows != null && rows >= 0) {
                TestModeContext.putLong(stepExecution, AbstractPagingItemReader.CTX_READER_TOTAL, rows);   // 진행률 전체 건수 (todo-task 39)
            }
        } catch (RuntimeException e) {
            log.debug("{} reader - 행 수 조회 실패 (진행률은 직전 실행 기준): {}", entityName, e.getMessage());
        }
        if (testModeContext.isTest()) {
            log.info("{} {} reader - 테스트 모드 {} (읽기 제한 {}건)", testModeContext.label(), entityName, testModeContext.mode(),
                    testModeContext.limitRead() ? testModeContext.maxItems() : "없음");
        }
    }

    @Override
    public ExcelRow read() throws Exception {
        if (exhausted) {
            return null;
        }
        if (testModeContext.limitRead() && readCount >= testModeContext.maxItems()) {
            log.info("{} {} reader - 읽기 제한 {}건 도달, 읽기 종료", testModeContext.label(), entityName, testModeContext.maxItems());
            if (stepExecution != null) {
                TestModeContext.putLong(stepExecution, TestModeContext.CTX_READ_LIMIT_HIT, 1L);
            }
            exhausted = true;
            closeQuietly();
            return null;
        }
        if (source == null) {
            Path path = pathSupplier.get();
            log.info("{} reader - 파일 열기: {}", entityName, path);
            source = ExcelRowSources.open(path);
        }
        ExcelRow row = source.next();
        if (row == null) {
            exhausted = true;
            closeQuietly();
            log.info("{} reader - 읽기 완료: {}건", entityName, readCount);
            return null;
        }
        readCount++;
        return row;
    }

    @Override
    public ExitStatus afterStep(@NonNull StepExecution stepExecution) {
        closeQuietly();
        return stepExecution.getExitStatus();
    }

    public int getReadCount() {
        return readCount;
    }

    private void closeQuietly() {
        if (source != null) {
            try {
                source.close();
            } catch (IOException e) {
                log.debug("{} reader - 파일 닫기 실패: {}", entityName, e.getMessage());
            }
            source = null;
        }
    }
}
