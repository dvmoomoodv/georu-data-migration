package com.georu.core.internal.strategy;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;

public interface TableMigrationStrategy {

    // public JobExecution execute(String tableName, String tenantId, String testMode, Integer testMaxItems,
    //                            String attribute1, String attribute2, String attribute3, String attribute4, String attribute5,
    //                            String tenantLevel1, String tenantLevel2, String tenantLevel3, String tenantLevel4, String tenantLevel5) throws Exception {
    JobExecution execute(String tableName, String tenantId,String tenantLevel1, String tenantLevel2, String tenantLevel3, String tenantLevel4, String tenantLevel5,
                        String attribute1, String attribute2, String attribute3, String attribute4, String attribute5,
                         String testMode, Integer testMaxItems) throws Exception;

    /**
     * JobParameters를 직접 사용한 마이그레이션 실행 (최적화된 방식)
     *
     * @param tableName 테이블명
     * @param jobParameters JobParametersFactory에서 생성된 최적화된 파라미터
     * @return JobExecution 실행 결과
     * @throws Exception 실행 중 오류 발생 시
     */
    default JobExecution executeWithJobParameters(String tableName, JobParameters jobParameters) throws Exception {
        // 기본 구현: JobParameters에서 파라미터를 추출하여 기존 execute 메서드 호출
        String tenantId = jobParameters.getString("tenantId");
        String tenantLevel1 = jobParameters.getString("tenantLevel1");
        String tenantLevel2 = jobParameters.getString("tenantLevel2");
        String tenantLevel3 = jobParameters.getString("tenantLevel3");
        String tenantLevel4 = jobParameters.getString("tenantLevel4");
        String tenantLevel5 = jobParameters.getString("tenantLevel5");
        String attribute1 = jobParameters.getString("attribute1");
        String attribute2 = jobParameters.getString("attribute2");
        String attribute3 = jobParameters.getString("attribute3");
        String attribute4 = jobParameters.getString("attribute4");
        String attribute5 = jobParameters.getString("attribute5");
        String testMode = jobParameters.getString("testMode");
        Long testMaxItemsLong = jobParameters.getLong("testMaxItems");
        Integer testMaxItems = testMaxItemsLong != null ? testMaxItemsLong.intValue() : null;

        return execute(tableName, tenantId, tenantLevel1, tenantLevel2, tenantLevel3, tenantLevel4, tenantLevel5,
                      attribute1, attribute2, attribute3, attribute4, attribute5, testMode, testMaxItems);
    }

    boolean supports(String tableName);
}