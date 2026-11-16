package com.georu.core.writer;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;

import java.util.List;

/*
 * Function: Abstract batch migration writer base class (BATCH 모드)
 *           대량 데이터 처리를 위한 BATCH 모드 Writer
 *           flushStatements()를 통한 배치 처리 지원
 *
 * 주의사항:
 *   - MySQL rewriteBatchedStatements=true 설정 시 충돌 가능
 *   - 충돌 발생 시 AbstractMigrationWriter (REUSE 모드) 사용 권장
 *
 * @param <T> Entity type
 * @see AbstractMigrationWriter REUSE 모드 (MySQL 호환성 보장)
 */
@Slf4j
public abstract class AbstractBatchMigrationWriter<T> extends AbstractMigrationWriter<T> {

    /*
     * Function: Constructor - Initialize with job configuration
     */
    public AbstractBatchMigrationWriter(String jobType, String dataSource) {
        super(jobType, dataSource);
        this.executorType = ExecutorType.BATCH;  // BATCH 모드로 설정
        log.debug("{} initialized with BATCH mode", getEntityName());
    }

    /*
     * Function: Write items using BATCH mode
     * 하위 클래스에서 writeItems() 구현 시 이 메서드를 활용
     */
    @Override
    protected void writeItems(List<? extends T> items) throws Exception {
        // 테스트 모드 분기는 AbstractMigrationWriter.write() 가 담당 (Writer 미실행 / WRITER_TEST 는 실제 적재 + MIG-TEST)
        writeToDatabaseBatch(items);
    }

    /*
     * Function: BATCH 모드로 데이터베이스에 쓰기
     * flushStatements()를 통한 배치 처리
     */
    protected void writeToDatabaseBatch(List<? extends T> items) throws Exception {
        log.debug("Writing {} {} items using BATCH mode", items.size(), getEntityName());

        try {
            writeTransaction().executeWithoutResult(status -> writeBatch(items));
        } catch (Exception e) {
            log.error("Batch insert failed for {}", getEntityName(), e);
            throw new RuntimeException("Batch insert failed for " + getEntityName(), e);
        }
    }

    private void writeBatch(List<? extends T> items) {
        try (SqlSession batchSession = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            int successCount = 0;

            for (T item : items) {
                try {
                    insertItem(batchSession, item);
                    successCount++;
                } catch (Exception e) {
                    log.error("Failed to prepare {} item: {}", getEntityName(), getItemId(item), e);
                    throw new RuntimeException("Failed to prepare " + getEntityName() + " item: " + getItemId(item), e);
                }
            }

            // BATCH 모드 필수: flushStatements() 호출
            batchSession.flushStatements();
            batchSession.commit();

            log.debug("Batch inserted {} {} items", successCount, getEntityName());
        }
    }

    /*
     * Function: 단일 아이템 삽입 - 하위 클래스에서 구현
     * SqlSession과 Mapper를 사용하여 실제 insert 수행
     *
     * @param session SqlSession (BATCH 모드)
     * @param item 삽입할 아이템
     */
    @Override
    protected abstract void insertItem(SqlSession session, T item) throws Exception;

    /*
     * Function: ExecutorType 반환 (BATCH)
     */
    @Override
    public ExecutorType getExecutorType() {
        return ExecutorType.BATCH;
    }
}

