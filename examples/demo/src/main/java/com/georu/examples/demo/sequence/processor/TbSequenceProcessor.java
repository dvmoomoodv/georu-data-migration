package com.georu.examples.demo.sequence.processor;

import com.georu.core.processor.AbstractItemProcessor;
import com.georu.core.utils.ValidationUtils;
import com.georu.examples.demo.source.dto.TbSequenceSourceDto;
import com.georu.examples.demo.target.dto.TbSequenceTargetDto;
import lombok.extern.slf4j.Slf4j;

/**
 * [Processor] TOBE tb_sequence ← ASIS SEQ_MASTER (매핑 추정, 미확정)
 * 데이터 흐름: TbSequenceSourceDto (Reader 출력) → TbSequenceTargetDto (Writer 입력)
 * 컬럼 변환은 georu/demo/source/TbSequenceSourceMapper.xml 의 SELECT 에서 한다 (자동 매핑 0 / 수기 매핑 3, TOBE 컬럼 3 - 완료)
 * 여기서는 필수값 검증, Target 옮겨 담기, 감사 컬럼 기본값만 처리한다.
 * 자동 생성: 코드 생성기
 */
@Slf4j
public class TbSequenceProcessor extends AbstractItemProcessor<TbSequenceSourceDto, TbSequenceTargetDto> {

    public TbSequenceProcessor() {
        super("MIGRATION", "SOURCE");
        log.debug("{} created", getEntityName());
    }

    @Override
    protected ValidationUtils.ValidationResult validateItem(TbSequenceSourceDto item) {
        if (item == null) {
            return ValidationUtils.ValidationResult.failure("item is null");
        }
        // 수기 검증 시작 (apply_demo_mapping.py)
        if (item.getTables() == null || item.getCol() == null) {
            return ValidationUtils.ValidationResult.failure("TABLES/COL 누락");
        }
        // 수기 검증 끝
        return ValidationUtils.ValidationResult.success();
    }

    /** Source → Target 옮겨 담기 (SQL 에서 변환이 끝난 값. Java 후처리가 필요한 컬럼만 여기서 바꾼다) */
    @Override
    protected TbSequenceTargetDto transformItem(TbSequenceSourceDto item) {
        return TbSequenceTargetDto.builder()
                .tables(item.getTables())
                .col(item.getCol())
                .seq(item.getSeq())
                .build();
    }

    @Override
    protected Object getItemId(TbSequenceSourceDto item) {
        return item != null ? item.getTables() + ":" + item.getCol() : null;    // 입력 식별 (PK TABLES, COL)
    }

    @Override
    protected Object getOutputItemId(TbSequenceTargetDto item) {
        return item != null ? item.getTables() + ":" + item.getCol() : null;    // 출력 식별 (PK TABLES, COL)
    }

    /** 감사 컬럼 기본값 (원천 값이 있으면 유지) */
    @Override
    protected void setUpdateTimestamp(TbSequenceTargetDto item) {
        // TOBE 감사 컬럼(INDATE/INUSERID/LDATE/LUSERID) 없음
    }
}
