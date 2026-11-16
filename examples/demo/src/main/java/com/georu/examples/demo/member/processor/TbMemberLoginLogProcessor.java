package com.georu.examples.demo.member.processor;

import com.georu.core.processor.AbstractItemProcessor;
import com.georu.core.utils.ValidationUtils;
import com.georu.examples.demo.common.DemoConvertUtils;
import com.georu.examples.demo.source.dto.TbMemberLoginLogSourceDto;
import com.georu.examples.demo.target.dto.TbMemberLoginLogTargetDto;
import lombok.extern.slf4j.Slf4j;

/**
 * [Processor] TOBE tb_member_login_log ← ASIS MEM_LOGIN_HIS (매핑 추정, 미확정)
 * 데이터 흐름: TbMemberLoginLogSourceDto (Reader 출력) → TbMemberLoginLogTargetDto (Writer 입력)
 * 컬럼 변환은 georu/demo/source/TbMemberLoginLogSourceMapper.xml 의 SELECT 에서 한다 (자동 매핑 6 / 수기 매핑 3, TOBE 컬럼 9 - 완료)
 * 여기서는 필수값 검증, Target 옮겨 담기, 감사 컬럼 기본값만 처리한다.
 * 자동 생성: 코드 생성기
 */
@Slf4j
public class TbMemberLoginLogProcessor extends AbstractItemProcessor<TbMemberLoginLogSourceDto, TbMemberLoginLogTargetDto> {

    public TbMemberLoginLogProcessor() {
        super("MIGRATION", "SOURCE");
        log.debug("{} created", getEntityName());
    }

    @Override
    protected ValidationUtils.ValidationResult validateItem(TbMemberLoginLogSourceDto item) {
        if (item == null) {
            return ValidationUtils.ValidationResult.failure("item is null");
        }
        // 수기 검증 시작 (apply_demo_mapping.py)
        if (item.getAsisId() == null || item.getMemberId() == null) {
            return ValidationUtils.ValidationResult.failure("ASIS_ID/MEMBER_ID 누락");
        }
        if (item.getLoginDate() == null || item.getLoginDate().startsWith("0001")) {
            return ValidationUtils.ValidationResult.failure("LOGIN_DATE 누락 또는 비정상 일시");
        }
        // 수기 검증 끝
        return ValidationUtils.ValidationResult.success();
    }

    /** Source → Target 옮겨 담기 (SQL 에서 변환이 끝난 값. Java 후처리가 필요한 컬럼만 여기서 바꾼다) */
    @Override
    protected TbMemberLoginLogTargetDto transformItem(TbMemberLoginLogSourceDto item) {
        return TbMemberLoginLogTargetDto.builder()
                .loginLogId(item.getLoginLogId())
                .asisId(item.getAsisId())
                .memberId(item.getMemberId())
                .loginDate(item.getLoginDate())
                .loginIp(item.getLoginIp())
                .deviceCd(item.getDeviceCd())
                .resultCd(item.getResultCd())
                .failReason(item.getFailReason())
                .indate(item.getIndate())
                .build();
    }

    @Override
    protected Object getItemId(TbMemberLoginLogSourceDto item) {
        return item != null ? item.getAsisId() : null;    // 입력 식별 (ASIS LOGIN_HIS_ID, LOGIN_LOG_ID 는 채번 전)
    }

    @Override
    protected Object getOutputItemId(TbMemberLoginLogTargetDto item) {
        return item != null ? item.getLoginLogId() : null;    // 출력 식별 (채번된 LOGIN_LOG_ID)
    }

    /** 감사 컬럼 기본값 (원천 값이 있으면 유지) */
    @Override
    protected void setUpdateTimestamp(TbMemberLoginLogTargetDto item) {
        if (item == null) {
            return;
        }
        if (item.getIndate() == null) {
            item.setIndate(DemoConvertUtils.nowYmdHms());
        }
    }
}
