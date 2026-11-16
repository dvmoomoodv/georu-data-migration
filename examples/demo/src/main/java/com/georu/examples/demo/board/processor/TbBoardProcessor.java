package com.georu.examples.demo.board.processor;

import com.georu.core.processor.AbstractItemProcessor;
import com.georu.core.utils.ValidationUtils;
import com.georu.examples.demo.utils.DemoCodeRules;
import com.georu.examples.demo.common.DemoConvertUtils;
import com.georu.examples.demo.source.dto.TbBoardSourceDto;
import com.georu.examples.demo.target.dto.TbBoardTargetDto;
import lombok.extern.slf4j.Slf4j;

/**
 * [Processor] TOBE tb_board ← ASIS BRD_BOARD (매핑 추정, 미확정)
 * 데이터 흐름: TbBoardSourceDto (Reader 출력) → TbBoardTargetDto (Writer 입력)
 * 컬럼 변환은 georu/demo/source/TbBoardSourceMapper.xml 의 SELECT 에서 한다 (자동 매핑 9 / 수기 매핑 2, TOBE 컬럼 11 - 완료)
 * 여기서는 필수값 검증, Target 옮겨 담기, 감사 컬럼 기본값만 처리한다.
 * 자동 생성: 코드 생성기
 */
@Slf4j
public class TbBoardProcessor extends AbstractItemProcessor<TbBoardSourceDto, TbBoardTargetDto> {

    public TbBoardProcessor() {
        super("MIGRATION", "SOURCE");
        log.debug("{} created", getEntityName());
    }

    @Override
    protected ValidationUtils.ValidationResult validateItem(TbBoardSourceDto item) {
        if (item == null) {
            return ValidationUtils.ValidationResult.failure("item is null");
        }
        // 수기 검증 시작 (apply_demo_mapping.py)
        if (item.getBoardId() == null || item.getBoardCd() == null) {
            return ValidationUtils.ValidationResult.failure("BOARD_ID/BOARD_CD 누락");
        }
        // 수기 검증 끝
        return ValidationUtils.ValidationResult.success();
    }

    /** Source → Target 옮겨 담기 (SQL 에서 변환이 끝난 값. Java 후처리가 필요한 컬럼만 여기서 바꾼다) */
    @Override
    protected TbBoardTargetDto transformItem(TbBoardSourceDto item) {
        return TbBoardTargetDto.builder()
                .boardId(item.getBoardId())
                .boardCd(item.getBoardCd())
                .boardNm(item.getBoardNm())
                .boardTypeCd(item.getBoardTypeCd())
                .useYn(DemoCodeRules.yn(item.getUseYn()))    // Y/N → 1/0
                .anonYn(DemoCodeRules.yn(item.getAnonYn()))    // Y/N → 1/0
                .attachLimit(item.getAttachLimit())
                .inuserid(item.getInuserid())
                .indate(item.getIndate())
                .luserid(item.getLuserid())
                .ldate(item.getLdate())
                .build();
    }

    @Override
    protected Object getItemId(TbBoardSourceDto item) {
        return item != null ? item.getBoardId() : null;    // PK (05 CSV 비고)
    }

    @Override
    protected Object getOutputItemId(TbBoardTargetDto item) {
        return item != null ? item.getBoardId() : null;    // PK (05 CSV 비고)
    }

    /** 감사 컬럼 기본값 (원천 값이 있으면 유지) */
    @Override
    protected void setUpdateTimestamp(TbBoardTargetDto item) {
        if (item == null) {
            return;
        }
        if (item.getIndate() == null) {
            item.setIndate(DemoConvertUtils.nowYmdHms());
        }
        if (item.getInuserid() == null) {
            item.setInuserid(DemoConvertUtils.MIG_USER);
        }
        if (item.getLdate() == null) {
            item.setLdate(DemoConvertUtils.nowYmdHms());
        }
        if (item.getLuserid() == null) {
            item.setLuserid(DemoConvertUtils.MIG_USER);
        }
    }
}
