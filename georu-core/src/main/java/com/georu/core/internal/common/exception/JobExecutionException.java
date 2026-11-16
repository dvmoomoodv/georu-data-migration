package com.georu.core.internal.common.exception;

/*
 * 기능: 배치 작업 실행 관련 예외 클래스
 *       Job, Step 실행 및 스케줄링 오류
 */
public class JobExecutionException extends BatchMigrationException {

    /*
     * 기능: Job 시작 실패 예외 생성
     */
    public static JobExecutionException jobStartFailure(String domain, String jobType, String dataSource, 
                                                       String jobName, String message, Throwable cause) {
        return new JobExecutionException("JE001", ErrorLevel.ERROR, 
                "Job start failure: " + message, domain, jobType, dataSource, 
                null, "Job: " + jobName, cause);
    }

    /*
     * 기능: Step 실행 실패 예외 생성
     */
    public static JobExecutionException stepExecutionFailure(String domain, String jobType, String dataSource, 
                                                           String stepName, String message, Throwable cause) {
        return new JobExecutionException("JE002", ErrorLevel.ERROR, 
                "Step execution failure: " + message, domain, jobType, dataSource, 
                null, "Step: " + stepName, cause);
    }

    /*
     * 기능: Job 파라미터 오류 예외 생성
     */
    public static JobExecutionException invalidJobParameters(String domain, String jobType, String dataSource, 
                                                           String parameterName, String parameterValue) {
        return new JobExecutionException("JE003", ErrorLevel.ERROR, 
                "Invalid job parameter: " + parameterName + "=" + parameterValue, 
                domain, jobType, dataSource, null, 
                "Parameter: " + parameterName + ", Value: " + parameterValue, null);
    }

    /*
     * 기능: Job 중복 실행 예외 생성
     */
    public static JobExecutionException duplicateJobExecution(String domain, String jobType, String dataSource, 
                                                            String jobName, Long existingExecutionId) {
        return new JobExecutionException("JE004", ErrorLevel.WARN, 
                "Duplicate job execution detected", domain, jobType, dataSource, 
                null, "Job: " + jobName + ", Existing execution: " + existingExecutionId, null);
    }

    /*
     * 기능: Job 타임아웃 예외 생성
     */
    public static JobExecutionException jobTimeout(String domain, String jobType, String dataSource, 
                                                  String jobName, long timeoutMinutes) {
        return new JobExecutionException("JE005", ErrorLevel.ERROR, 
                "Job execution timeout", domain, jobType, dataSource, 
                null, "Job: " + jobName + ", Timeout: " + timeoutMinutes + " minutes", null);
    }

    /*
     * 기능: Job 중단 예외 생성
     */
    public static JobExecutionException jobInterrupted(String domain, String jobType, String dataSource, 
                                                      String jobName, String reason) {
        return new JobExecutionException("JE006", ErrorLevel.WARN, 
                "Job execution interrupted: " + reason, domain, jobType, dataSource, 
                null, "Job: " + jobName + ", Reason: " + reason, null);
    }

    /*
     * 기능: 리소스 부족 예외 생성
     */
    public static JobExecutionException resourceExhaustion(String domain, String jobType, String dataSource, 
                                                          String resourceType, String message) {
        return new JobExecutionException("JE007", ErrorLevel.FATAL, 
                "Resource exhaustion: " + message, domain, jobType, dataSource, 
                null, "Resource type: " + resourceType, null);
    }

    /*
     * 기능: Job 재시작 실패 예외 생성
     */
    public static JobExecutionException jobRestartFailure(String domain, String jobType, String dataSource, 
                                                         String jobName, Long executionId, Throwable cause) {
        return new JobExecutionException("JE008", ErrorLevel.ERROR, 
                "Job restart failure", domain, jobType, dataSource, 
                null, "Job: " + jobName + ", Execution ID: " + executionId, cause);
    }

    /*
     * 기능: 병렬 처리 오류 예외 생성
     */
    public static JobExecutionException parallelProcessingFailure(String domain, String jobType, String dataSource, 
                                                                int threadCount, String message, Throwable cause) {
        return new JobExecutionException("JE009", ErrorLevel.ERROR, 
                "Parallel processing failure: " + message, domain, jobType, dataSource, 
                null, "Thread count: " + threadCount, cause);
    }

    /*
     * 기능: Job 설정 오류 예외 생성
     */
    public static JobExecutionException jobConfigurationError(String domain, String configKey, 
                                                            String configValue, String message) {
        return new JobExecutionException("JE010", ErrorLevel.FATAL, 
                "Job configuration error: " + message, domain, null, null, 
                null, "Config: " + configKey + "=" + configValue, null);
    }

    // private 생성자 (팩토리 메서드를 통해서만 생성)
    private JobExecutionException(String errorCode, ErrorLevel errorLevel, String message,
                                  String domain, String jobType, String dataSource,
                                  String itemId, String contextInfo, Throwable cause) {
        super(errorCode, errorLevel, message, domain, jobType, dataSource, itemId, contextInfo, cause);
    }
}
