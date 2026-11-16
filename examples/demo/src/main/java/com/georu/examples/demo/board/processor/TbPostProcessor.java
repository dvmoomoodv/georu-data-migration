package com.georu.examples.demo.board.processor;

import com.georu.core.processor.AbstractItemProcessor;
import com.georu.core.utils.ValidationUtils;
import com.georu.examples.demo.utils.DemoCodeRules;
import com.georu.examples.demo.common.DemoConvertUtils;
import com.georu.examples.demo.source.dto.TbPostSourceDto;
import com.georu.examples.demo.target.dto.TbPostTargetDto;
import lombok.extern.slf4j.Slf4j;

/**
 * [Processor] TOBE tb_post ← ASIS BRD_POST (매핑 추정, 미확정)
 * 데이터 흐름: TbPostSourceDto (Reader 출력) → TbPostTargetDto (Writer 입력)
 * 컬럼 변환은 georu/demo/source/TbPostSourceMapper.xml 의 SELECT 에서 한다 (자동 매핑 11 / 수기 매핑 2, TOBE 컬럼 13 - 완료)
 * 여기서는 필수값 검증, Target 옮겨 담기, 감사 컬럼 기본값만 처리한다.
 * 자동 생성: 코드 생성기
 */
@Slf4j
public class TbPostProcessor extends AbstractItemProcessor<TbPostSourceDto, TbPostTargetDto> {

    public TbPostProcessor() {
        super("MIGRATION", "SOURCE");
        log.debug("{} created", getEntityName());
    }

    @Override
    protected ValidationUtils.ValidationResult validateItem(TbPostSourceDto item) {
        if (item == null) {
            return ValidationUtils.ValidationResult.failure("item is null");
        }
        // 수기 검증 시작 (apply_demo_mapping.py)
        if (item.getPostId() == null || item.getBoardId() == null) {
            return ValidationUtils.ValidationResult.failure("POST_ID/BOARD_ID 누락");
        }
        if (item.getTitle() == null || item.getTitle().isBlank()) {
            return ValidationUtils.ValidationResult.failure("TITLE 누락");
        }
        // 수기 검증 끝
        return ValidationUtils.ValidationResult.success();
    }

    /** Source → Target 옮겨 담기 (SQL 에서 변환이 끝난 값. Java 후처리가 필요한 컬럼만 여기서 바꾼다) */
    @Override
    protected TbPostTargetDto transformItem(TbPostSourceDto item) {
        return TbPostTargetDto.builder()
                .postId(item.getPostId())
                .boardId(item.getBoardId())
                .title(item.getTitle())
                .content(item.getContent())
                .writerId(item.getWriterId())
                .viewCnt(item.getViewCnt())
                .noticeYn(DemoCodeRules.yn(item.getNoticeYn()))    // Y/N → 1/0
                .secretYn(DemoCodeRules.yn(item.getSecretYn()))    // Y/N → 1/0
                .status(DemoCodeRules.status(item.getStatus()))    // A/I/D → ACTIVE/INACTIVE/DELETED
                .inuserid(item.getInuserid())
                .indate(item.getIndate())
                .luserid(item.getLuserid())
                .ldate(item.getLdate())
                .build();
    }

    @Override
    protected Object getItemId(TbPostSourceDto item) {
        return item != null ? item.getPostId() : null;    // PK (05 CSV 비고)
    }

    @Override
    protected Object getOutputItemId(TbPostTargetDto item) {
        return item != null ? item.getPostId() : null;    // PK (05 CSV 비고)
    }

    /** 감사 컬럼 기본값 (원천 값이 있으면 유지) */
    @Override
    protected void setUpdateTimestamp(TbPostTargetDto item) {
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
