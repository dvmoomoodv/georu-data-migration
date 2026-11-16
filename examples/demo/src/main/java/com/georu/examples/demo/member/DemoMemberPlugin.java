package com.georu.examples.demo.member;

import com.georu.core.plugin.GeoruPlugin;
import com.georu.core.plugin.TableCatalog;
import com.georu.core.plugin.TableDef;
import com.georu.core.strategy.TableOptions;
import com.georu.examples.demo.member.processor.TbMemberGradeProcessor;
import com.georu.examples.demo.member.processor.TbMemberLoginLogProcessor;
import com.georu.examples.demo.member.processor.TbMemberProcessor;
import com.georu.examples.demo.source.dto.TbMemberGradeSourceDto;
import com.georu.examples.demo.source.dto.TbMemberLoginLogSourceDto;
import com.georu.examples.demo.source.dto.TbMemberSourceDto;
import com.georu.examples.demo.target.dto.TbMemberGradeTargetDto;
import com.georu.examples.demo.target.dto.TbMemberLoginLogTargetDto;
import com.georu.examples.demo.target.dto.TbMemberTargetDto;

import java.util.List;

/**
 * 데모 회원관리 (업무코드 02): 회원 등급 → 회원 → 로그인 로그.
 * 삭제건 제외, 성별·상태 코드 변환, 전화번호 숫자만, 날짜 14자리, LOGIN_LOG_ID 채번.
 * MEMBER_ID 는 자식 테이블이 참조하므로 번호를 유지하고 Job 끝에 MAX 동기화만 한다.
 */
@GeoruPlugin(name = "demo.member", description = "데모 회원관리 (02) Migration Plugin - ASIS Oracle to TOBE MySQL")
public class DemoMemberPlugin implements TableCatalog {

    @Override
    public List<TableDef> tables() {
        return List.of(
                TableDef.of("tb_member_grade", TbMemberGradeSourceDto.class, TbMemberGradeTargetDto.class)
                        .processor(TbMemberGradeProcessor.class),
                TableDef.of("tb_member", TbMemberSourceDto.class, TbMemberTargetDto.class)
                        .processor(TbMemberProcessor.class)
                        .options(TableOptions.builder().sequenceSyncColumns(List.of("MEMBER_ID")).build()),
                TableDef.of("tb_member_login_log", TbMemberLoginLogSourceDto.class, TbMemberLoginLogTargetDto.class)
                        .processor(TbMemberLoginLogProcessor.class)
                        .options(TableOptions.builder().sequenceColumns(List.of("LOGIN_LOG_ID")).sequenceSync(true).build())
        );
    }
}
