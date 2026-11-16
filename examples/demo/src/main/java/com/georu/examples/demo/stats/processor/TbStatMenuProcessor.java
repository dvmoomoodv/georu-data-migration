package com.georu.examples.demo.stats.processor;

import com.georu.core.processor.AbstractItemProcessor;
import com.georu.core.utils.ValidationUtils;
import com.georu.examples.demo.common.DemoConvertUtils;
import com.georu.examples.demo.source.dto.TbStatMenuSourceDto;
import com.georu.examples.demo.target.dto.TbStatMenuTargetDto;
import lombok.extern.slf4j.Slf4j;

/**
 * [Processor] TOBE tb_stat_menu ← ASIS STAT_MENU_HIT (매핑 추정, 미확정)
 * 데이터 흐름: TbStatMenuSourceDto (Reader 출력) → TbStatMenuTargetDto (Writer 입력)
 * 컬럼 변환은 georu/demo/source/TbStatMenuSourceMapper.xml 의 SELECT 에서 한다 (자동 매핑 7 / 수기 매핑 1, TOBE 컬럼 8 - 완료)
 * 여기서는 필수값 검증, Target 옮겨 담기, 감사 컬럼 기본값만 처리한다.
 * 자동 생성: 코드 생성기
 */
@Slf4j
public class TbStatMenuProcessor extends AbstractItemProcessor<TbStatMenuSourceDto, TbStatMenuTargetDto> {

    public TbStatMenuProcessor() {
        super("MIGRATION", "SOURCE");
        log.debug("{} created", getEntityName());
    }

    @Override
    protected ValidationUtils.ValidationResult validateItem(TbStatMenuSourceDto item) {
        if (item == null) {
            return ValidationUtils.ValidationResult.failure("item is null");
        }
        // 수기 검증 시작 (apply_demo_mapping.py)
        if (item.getStatYm() == null || item.getMenuCd() == null || item.getDeviceCd() == null) {
            return ValidationUtils.ValidationResult.failure("STAT_YM/MENU_CD/DEVICE_CD 누락");
        }
        // 수기 검증 끝
        return ValidationUtils.ValidationResult.success();
    }

    /** Source → Target 옮겨 담기 (SQL 에서 변환이 끝난 값. Java 후처리가 필요한 컬럼만 여기서 바꾼다) */
    @Override
    protected TbStatMenuTargetDto transformItem(TbStatMenuSourceDto item) {
        return TbStatMenuTargetDto.builder()
                .statMenuId(item.getStatMenuId())
                .statYm(item.getStatYm())
                .menuCd(item.getMenuCd())
                .deviceCd(item.getDeviceCd())
                .hitCnt(item.getHitCnt())
                .memberHitCnt(item.getMemberHitCnt())
                .guestHitCnt(item.getGuestHitCnt())
                .indate(item.getIndate())
                .build();
    }

    @Override
    protected Object getItemId(TbStatMenuSourceDto item) {
        return item != null ? item.getStatYm() + ":" + item.getMenuCd() + ":" + item.getDeviceCd() : null;    // 입력 식별 (자연키)
    }

    @Override
    protected Object getOutputItemId(TbStatMenuTargetDto item) {
        return item != null ? item.getStatMenuId() : null;    // 출력 식별 (채번된 STAT_MENU_ID)
    }

    /** 감사 컬럼 기본값 (원천 값이 있으면 유지) */
    @Override
    protected void setUpdateTimestamp(TbStatMenuTargetDto item) {
        if (item == null) {
            return;
        }
        if (item.getIndate() == null) {
            item.setIndate(DemoConvertUtils.nowYmdHms());
        }
    }
}
