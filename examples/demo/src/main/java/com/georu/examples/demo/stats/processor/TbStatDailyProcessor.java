package com.georu.examples.demo.stats.processor;

import com.georu.core.processor.AbstractItemProcessor;
import com.georu.core.utils.ValidationUtils;
import com.georu.examples.demo.common.DemoConvertUtils;
import com.georu.examples.demo.source.dto.TbStatDailySourceDto;
import com.georu.examples.demo.target.dto.TbStatDailyTargetDto;
import lombok.extern.slf4j.Slf4j;

/**
 * [Processor] TOBE tb_stat_daily ← ASIS STAT_DAILY_VISIT (매핑 추정, 미확정)
 * 데이터 흐름: TbStatDailySourceDto (Reader 출력) → TbStatDailyTargetDto (Writer 입력)
 * 컬럼 변환은 georu/demo/source/TbStatDailySourceMapper.xml 의 SELECT 에서 한다 (자동 매핑 7 / 수기 매핑 2, TOBE 컬럼 9 - 완료)
 * 여기서는 필수값 검증, Target 옮겨 담기, 감사 컬럼 기본값만 처리한다.
 * 자동 생성: 코드 생성기
 */
@Slf4j
public class TbStatDailyProcessor extends AbstractItemProcessor<TbStatDailySourceDto, TbStatDailyTargetDto> {

    public TbStatDailyProcessor() {
        super("MIGRATION", "SOURCE");
        log.debug("{} created", getEntityName());
    }

    @Override
    protected ValidationUtils.ValidationResult validateItem(TbStatDailySourceDto item) {
        if (item == null) {
            return ValidationUtils.ValidationResult.failure("item is null");
        }
        // 수기 검증 시작 (apply_demo_mapping.py)
        if (item.getStatYmd() == null || item.getBoardId() == null) {
            return ValidationUtils.ValidationResult.failure("STAT_YMD/BOARD_ID 누락");
        }
        // 수기 검증 끝
        return ValidationUtils.ValidationResult.success();
    }

    /** Source → Target 옮겨 담기 (SQL 에서 변환이 끝난 값. Java 후처리가 필요한 컬럼만 여기서 바꾼다) */
    @Override
    protected TbStatDailyTargetDto transformItem(TbStatDailySourceDto item) {
        return TbStatDailyTargetDto.builder()
                .statId(item.getStatId())
                .statYmd(item.getStatYmd())
                .boardId(item.getBoardId())
                .visitCnt(item.getVisitCnt())
                .uniqueVisitCnt(item.getUniqueVisitCnt())
                .postCnt(item.getPostCnt())
                .commentCnt(item.getCommentCnt())
                .avgStaySec(item.getAvgStaySec())
                .indate(item.getIndate())
                .build();
    }

    @Override
    protected Object getItemId(TbStatDailySourceDto item) {
        return item != null ? item.getStatYmd() + ":" + item.getBoardId() : null;    // 입력 식별 (자연키 STAT_YMD, BOARD_ID)
    }

    @Override
    protected Object getOutputItemId(TbStatDailyTargetDto item) {
        return item != null ? item.getStatId() : null;    // 출력 식별 (채번된 STAT_ID)
    }

    /** 감사 컬럼 기본값 (원천 값이 있으면 유지) */
    @Override
    protected void setUpdateTimestamp(TbStatDailyTargetDto item) {
        if (item == null) {
            return;
        }
        if (item.getIndate() == null) {
            item.setIndate(DemoConvertUtils.nowYmdHms());
        }
    }
}
