package com.georu.examples.demo.member.processor;

import com.georu.core.processor.AbstractItemProcessor;
import com.georu.core.utils.ValidationUtils;
import com.georu.examples.demo.utils.DemoCodeRules;
import com.georu.examples.demo.common.DemoConvertUtils;
import com.georu.examples.demo.source.dto.TbMemberSourceDto;
import com.georu.examples.demo.target.dto.TbMemberTargetDto;
import lombok.extern.slf4j.Slf4j;

/**
 * [Processor] TOBE tb_member ← ASIS MEM_MEMBER (매핑 추정, 미확정)
 * 데이터 흐름: TbMemberSourceDto (Reader 출력) → TbMemberTargetDto (Writer 입력)
 * 컬럼 변환은 georu/demo/source/TbMemberSourceMapper.xml 의 SELECT 에서 한다 (자동 매핑 12 / 수기 매핑 5, TOBE 컬럼 17 - 완료)
 * 여기서는 필수값 검증, Target 옮겨 담기, 감사 컬럼 기본값만 처리한다.
 * 자동 생성: 코드 생성기
 */
@Slf4j
public class TbMemberProcessor extends AbstractItemProcessor<TbMemberSourceDto, TbMemberTargetDto> {

    public TbMemberProcessor() {
        super("MIGRATION", "SOURCE");
        log.debug("{} created", getEntityName());
    }

    @Override
    protected ValidationUtils.ValidationResult validateItem(TbMemberSourceDto item) {
        if (item == null) {
            return ValidationUtils.ValidationResult.failure("item is null");
        }
        // 수기 검증 시작 (apply_demo_mapping.py)
        if (item.getMemberId() == null) {
            return ValidationUtils.ValidationResult.failure("MEMBER_ID 누락");
        }
        if (item.getLoginId() == null || item.getLoginId().isBlank()) {
            return ValidationUtils.ValidationResult.failure("LOGIN_ID 누락");
        }
        if (item.getMemberNm() == null || item.getMemberNm().length() > 50) {
            return ValidationUtils.ValidationResult.failure("MEMBER_NM 누락 또는 50자 초과");
        }
        if (item.getJoinDate() == null) {
            return ValidationUtils.ValidationResult.failure("JOIN_DATE 누락");
        }
        if (item.getEmail() != null && !item.getEmail().contains("@")) {
            return ValidationUtils.ValidationResult.failure("EMAIL 형식 오류");
        }
        // 수기 검증 끝
        return ValidationUtils.ValidationResult.success();
    }

    /** Source → Target 옮겨 담기 (SQL 에서 변환이 끝난 값. Java 후처리가 필요한 컬럼만 여기서 바꾼다) */
    @Override
    protected TbMemberTargetDto transformItem(TbMemberSourceDto item) {
        return TbMemberTargetDto.builder()
                .memberId(item.getMemberId())
                .loginId(item.getLoginId())
                .memberNm(item.getMemberNm())
                .passwordHash(item.getPasswordHash())
                .gender(DemoCodeRules.gender(item.getGender()))    // M/F → 1/2
                .birthYmd(item.getBirthYmd())
                .email(item.getEmail())
                .phone(item.getPhone())
                .gradeCd(item.getGradeCd())
                .status(DemoCodeRules.status(item.getStatus()))    // A/I/D → ACTIVE/INACTIVE/DELETED
                .joinDate(item.getJoinDate())
                .lastLoginDate(item.getLastLoginDate())
                .point(item.getPoint())
                .inuserid(item.getInuserid())
                .indate(item.getIndate())
                .luserid(item.getLuserid())
                .ldate(item.getLdate())
                .build();
    }

    @Override
    protected Object getItemId(TbMemberSourceDto item) {
        return item != null ? item.getMemberId() : null;    // PK (05 CSV 비고)
    }

    @Override
    protected Object getOutputItemId(TbMemberTargetDto item) {
        return item != null ? item.getMemberId() : null;    // PK (05 CSV 비고)
    }

    /** 감사 컬럼 기본값 (원천 값이 있으면 유지) */
    @Override
    protected void setUpdateTimestamp(TbMemberTargetDto item) {
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
