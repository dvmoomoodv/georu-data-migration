package com.georu.examples.demo.board.processor;

import com.georu.core.processor.AbstractItemProcessor;
import com.georu.core.utils.ValidationUtils;
import com.georu.examples.demo.utils.DemoPathRules;
import com.georu.examples.demo.common.DemoConvertUtils;
import com.georu.examples.demo.source.dto.TbPostFileSourceDto;
import com.georu.examples.demo.target.dto.TbPostFileTargetDto;
import lombok.extern.slf4j.Slf4j;

/**
 * [Processor] TOBE tb_post_file ← ASIS BRD_POST_FILE (매핑 추정, 미확정)
 * 데이터 흐름: TbPostFileSourceDto (Reader 출력) → TbPostFileTargetDto (Writer 입력)
 * 컬럼 변환은 georu/demo/source/TbPostFileSourceMapper.xml 의 SELECT 에서 한다 (자동 매핑 8 / 수기 매핑 4, TOBE 컬럼 12 - 완료)
 * 여기서는 필수값 검증, Target 옮겨 담기, 감사 컬럼 기본값만 처리한다.
 * 자동 생성: 코드 생성기
 */
@Slf4j
public class TbPostFileProcessor extends AbstractItemProcessor<TbPostFileSourceDto, TbPostFileTargetDto> {

    public TbPostFileProcessor() {
        super("MIGRATION", "SOURCE");
        log.debug("{} created", getEntityName());
    }

    @Override
    protected ValidationUtils.ValidationResult validateItem(TbPostFileSourceDto item) {
        if (item == null) {
            return ValidationUtils.ValidationResult.failure("item is null");
        }
        // 수기 검증 시작 (apply_demo_mapping.py)
        if (item.getAsisId() == null || item.getPostId() == null) {
            return ValidationUtils.ValidationResult.failure("ASIS_ID/POST_ID 누락");
        }
        if (item.getFilePath() == null) {
            return ValidationUtils.ValidationResult.failure("FILE_PATH 누락");
        }
        // 수기 검증 끝
        return ValidationUtils.ValidationResult.success();
    }

    /** Source → Target 옮겨 담기 (SQL 에서 변환이 끝난 값. Java 후처리가 필요한 컬럼만 여기서 바꾼다) */
    @Override
    protected TbPostFileTargetDto transformItem(TbPostFileSourceDto item) {
        return TbPostFileTargetDto.builder()
                .fileId(item.getFileId())
                .asisId(item.getAsisId())
                .postId(item.getPostId())
                .originNm(item.getOriginNm())
                .filePath(DemoPathRules.toTobePath(item.getFilePath()))    // /Upload_Board/… → file/board/…
                .fileSize(item.getFileSize())
                .mimeType(item.getMimeType())
                .downloadCnt(item.getDownloadCnt())
                .inuserid(item.getInuserid())
                .indate(item.getIndate())
                .luserid(item.getLuserid())
                .ldate(item.getLdate())
                .build();
    }

    @Override
    protected Object getItemId(TbPostFileSourceDto item) {
        return item != null ? item.getAsisId() : null;    // 입력 식별 (ASIS FILE_ID, FILE_ID 는 채번 전)
    }

    @Override
    protected Object getOutputItemId(TbPostFileTargetDto item) {
        return item != null ? item.getFileId() : null;    // 출력 식별 (채번된 FILE_ID)
    }

    /** 감사 컬럼 기본값 (원천 값이 있으면 유지) */
    @Override
    protected void setUpdateTimestamp(TbPostFileTargetDto item) {
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
