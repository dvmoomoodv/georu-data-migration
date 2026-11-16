package com.georu.core.internal.excel;

import com.georu.core.config.TestModeContext;
import com.georu.core.internal.sequence.SequenceJobParameters;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.step.builder.SimpleStepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 테이블 이관 Strategy 가 {@code source=EXCEL} Job 에서 쓰는 지원 객체.
 * Reader·Processor 를 엑셀용으로 바꾸고, 오류 행 처리(STOP/SKIP)를 Step 에 적용하고, Job 시작·종료에 파일 대장을 갱신한다.
 * Writer·시퀀스·게이트·이력·알림은 기존 파이프라인 그대로다.
 */
@Slf4j
@Component
public class ExcelSourceSupport {

    private final ApplicationContext applicationContext;
    private final ExcelFileService fileService;
    private final ExcelBindingService bindingService;
    private final ExcelValidationService validationService;
    private final ExcelFileStore store;
    private final ExcelLoadProperties props;
    private final ExcelRunIssues runIssues;

    public ExcelSourceSupport(ApplicationContext applicationContext, ExcelFileService fileService, ExcelBindingService bindingService,
                              ExcelValidationService validationService, ExcelFileStore store, ExcelLoadProperties props) {
        this.applicationContext = applicationContext;
        this.fileService = fileService;
        this.bindingService = bindingService;
        this.validationService = validationService;
        this.store = store;
        this.props = props;
        this.runIssues = new ExcelRunIssues(props.getMaxIssues());
    }

    public boolean isExcel(JobParameters params) {
        return ExcelJobParameters.isExcel(params);
    }

    /** 파일 경로는 첫 read() 때 대장에서 다시 읽는다 (Job 시작 리스너가 processing 으로 옮긴 뒤) */
    public ItemReader<Object> createReader(JobParameters params, String tableName) {
        String fileId = ExcelJobParameters.fileId(params);
        @SuppressWarnings("unchecked")
        ItemReader<Object> reader = (ItemReader<Object>) (ItemReader<?>) new ExcelRowItemReader(
                () -> {
                    Path p = store.resolve(fileService.get(fileId));
                    if (!java.nio.file.Files.exists(p)) {
                        throw new IllegalStateException("엑셀 파일이 없습니다: " + p);
                    }
                    return p;
                }, "Excel(" + tableName + ")", () -> rowCount(params));
        return reader;
    }

    public ItemProcessor<Object, Object> createProcessor(JobParameters params, String pluginName, String tableName) {
        ExcelTargetBinding binding = bindingService.bind(pluginName, tableName, SequenceJobParameters.columns(params));
        ExcelToTargetProcessor processor = new ExcelToTargetProcessor(binding, validationService, props.getAuditDateSetters());
        applicationContext.getAutowireCapableBeanFactory().autowireBean(processor);   // 시퀀스 레지스트리 등 부모의 @Autowired 필드
        log.info("[Excel] {} 변환기 준비 - DTO {}, 적재 컬럼 {}개, 필수 {}개, 채번 {}", tableName, binding.getDtoClass().getSimpleName(),
                binding.getColumns().stream().filter(ExcelTargetBinding.Column::isAccepted).count(),
                binding.requiredColumns().size(), binding.getSequenceColumns());
        @SuppressWarnings("unchecked")
        ItemProcessor<Object, Object> p = (ItemProcessor<Object, Object>) (ItemProcessor<?, ?>) processor;
        return p;
    }

    /**
     * 오류 행 처리. SKIP: 변환 오류({@link ExcelRowException})와 적재 오류(DB 예외)를 행 단위로 건너뛰고 기록.
     * STOP: 내결함성 없음 (첫 오류에서 Step 실패).
     */
    public SimpleStepBuilder<Object, Object> configureFaultTolerance(SimpleStepBuilder<Object, Object> builder, JobParameters params) {
        if (!ExcelJobParameters.skipRows(params)) {
            log.info("[Excel] 오류 행 처리 STOP - 첫 오류에서 Step 실패");
            return builder;
        }
        String fileId = ExcelJobParameters.fileId(params);
        log.info("[Excel] 오류 행 처리 SKIP - 변환·적재 오류 행은 건너뛰고 기록 (파일 {})", fileId);
        return builder.faultTolerant()
                .skip(ExcelRowException.class)
                .skip(DataAccessException.class)
                .skip(PersistenceException.class)
                .skipLimit(Integer.MAX_VALUE)
                .listener(new RowSkipListener(fileId));
    }

    public JobExecutionListener jobListener() {
        return new RunListener();
    }

    /** 현재 Job 이 엑셀이면 파일 행 수 (총 건수·완료 판정용), 아니면 null */
    public Integer rowCount(JobParameters params) {
        if (!ExcelJobParameters.isExcel(params)) {
            return null;
        }
        ExcelFileRecord r = fileService.find(ExcelJobParameters.fileId(params));
        return r == null || r.getRowCount() == null ? null : r.getRowCount();
    }

    // ------------------------------------------------------------------ 리스너

    /** 건너뛴 행 기록 */
    final class RowSkipListener implements SkipListener<Object, Object> {
        private final String fileId;

        RowSkipListener(String fileId) {
            this.fileId = fileId;
        }

        @Override
        public void onSkipInRead(@NonNull Throwable t) {
            runIssues.add(fileId, 0, null, null, "읽기 오류: " + t.getMessage());
        }

        @Override
        public void onSkipInProcess(@NonNull Object item, @NonNull Throwable t) {
            if (t instanceof ExcelRowException e) {
                runIssues.add(fileId, e.getRowNum(), e.getColumn(), e.getValue(), e.getMessage());
            } else {
                int row = item instanceof ExcelRow r ? r.rowNum() : 0;
                runIssues.add(fileId, row, null, null, "변환 오류: " + t.getMessage());
            }
            log.warn("[Excel] 행 건너뜀 (변환): {}", t.getMessage());
        }

        @Override
        public void onSkipInWrite(@NonNull Object item, @NonNull Throwable t) {
            String v = String.valueOf(item);
            runIssues.add(fileId, 0, null, v, "적재 오류: " + rootMessage(t));
            log.warn("[Excel] 행 건너뜀 (적재): {}", rootMessage(t));
        }
    }

    /** Job 시작·종료에 파일 대장 갱신 */
    final class RunListener implements JobExecutionListener {
        @Override
        public void beforeJob(@NonNull JobExecution jobExecution) {
            JobParameters p = jobExecution.getJobParameters();
            String fileId = ExcelJobParameters.fileId(p);
            runIssues.start(fileId);
            fileService.runStart(fileId, p.getString("migrationId"), p.getString("testMode"), ExcelJobParameters.onRowError(p));
        }

        @Override
        public void afterJob(@NonNull JobExecution jobExecution) {
            JobParameters p = jobExecution.getJobParameters();
            String fileId = ExcelJobParameters.fileId(p);
            try {
                int read = 0, written = 0, skipped = 0;
                for (StepExecution se : jobExecution.getStepExecutions()) {
                    int writeFailed = (int) TestModeContext.getLong(se, TestModeContext.CTX_WRITE_FAILED);
                    int writeSkippedByMode = (int) TestModeContext.getLong(se, TestModeContext.CTX_WRITE_SKIPPED);
                    read += (int) se.getReadCount();
                    written += Math.max(0, (int) se.getWriteCount() - writeFailed - writeSkippedByMode);
                    // 건너뜀 = SKIP 으로 건너뛴 행 + PROCESSOR_TEST 변환 오류로 제외된 행(filterCount 에 포함) + 적재 실패 행
                    skipped += (int) (se.getSkipCount() + se.getFilterCount()) + writeFailed;
                }
                boolean success = jobExecution.getStatus().isUnsuccessful() == false
                        && !ExitStatus.FAILED.getExitCode().equals(jobExecution.getExitStatus().getExitCode());
                String detail = success ? null : jobExecution.getExitStatus().getExitDescription();
                if (detail != null && detail.length() > 300) {
                    detail = detail.substring(0, 300);
                }
                fileService.runEnd(fileId, success, p.getString("testMode"), read, written, skipped, runIssues.finish(fileId), detail);
            } catch (Exception e) {
                log.error("[Excel] 실행 종료 반영 실패 {}: {}", fileId, e.getMessage(), e);
            }
        }
    }

    static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : m.length() > 300 ? m.substring(0, 300) : m;
    }
}
