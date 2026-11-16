package com.georu.examples.demo.code.processor;

import com.georu.core.processor.AbstractItemProcessor;
import com.georu.core.utils.ValidationUtils;
import com.georu.examples.demo.utils.DemoCodeRules;
import com.georu.examples.demo.common.DemoConvertUtils;
import com.georu.examples.demo.source.dto.TbCodeSourceDto;
import com.georu.examples.demo.target.dto.TbCodeTargetDto;
import lombok.extern.slf4j.Slf4j;

/**
 * [Processor] TOBE tb_code ← ASIS CD_CODE (매핑 추정, 미확정)
 * 데이터 흐름: TbCodeSourceDto (Reader 출력) → TbCodeTargetDto (Writer 입력)
 * 컬럼 변환은 georu/demo/source/TbCodeSourceMapper.xml 의 SELECT 에서 한다 (자동 매핑 8 / 수기 매핑 2, TOBE 컬럼 10 - 완료)
 * 여기서는 필수값 검증, Target 옮겨 담기, 감사 컬럼 기본값만 처리한다.
 * 자동 생성: 코드 생성기
 */
@Slf4j
public class TbCodeProcessor extends AbstractItemProcessor<TbCodeSourceDto, TbCodeTargetDto> {

    public TbCodeProcessor() {
        super("MIGRATION", "SOURCE");
        log.debug("{} created", getEntityName());
    }

    @Override
    protected ValidationUtils.ValidationResult validateItem(TbCodeSourceDto item) {
        if (item == null) {
            return ValidationUtils.ValidationResult.failure("item is null");
        }
        // 수기 검증 시작 (apply_demo_mapping.py)
        if (item.getGroupCd() == null || item.getCode() == null) {
            return ValidationUtils.ValidationResult.failure("GROUP_CD/CODE 누락");
        }
        // 수기 검증 끝
        return ValidationUtils.ValidationResult.success();
    }

    /** Source → Target 옮겨 담기 (SQL 에서 변환이 끝난 값. Java 후처리가 필요한 컬럼만 여기서 바꾼다) */
    @Override
    protected TbCodeTargetDto transformItem(TbCodeSourceDto item) {
        return TbCodeTargetDto.builder()
                .groupCd(item.getGroupCd())
                .code(item.getCode())
                .codeNm(item.getCodeNm())
                .codeDesc(item.getCodeDesc())
                .useYn(DemoCodeRules.yn(item.getUseYn()))    // Y/N → 1/0
                .sortNo(item.getSortNo())
                .inuserid(item.getInuserid())
                .indate(item.getIndate())
                .luserid(item.getLuserid())
                .ldate(item.getLdate())
                .build();
    }

    @Override
    protected Object getItemId(TbCodeSourceDto item) {
        return item != null ? item.getGroupCd() + ":" + item.getCode() : null;    // 입력 식별 (PK GROUP_CD, CODE)
    }

    @Override
    protected Object getOutputItemId(TbCodeTargetDto item) {
        return item != null ? item.getGroupCd() + ":" + item.getCode() : null;    // 출력 식별 (PK GROUP_CD, CODE)
    }

    /** 감사 컬럼 기본값 (원천 값이 있으면 유지) */
    @Override
    protected void setUpdateTimestamp(TbCodeTargetDto item) {
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
