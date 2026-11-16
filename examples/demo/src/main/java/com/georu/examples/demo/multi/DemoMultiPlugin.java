package com.georu.examples.demo.multi;

import com.georu.core.plugin.GeoruPlugin;
import com.georu.core.plugin.TableCatalog;
import com.georu.core.plugin.TableDef;
import com.georu.core.plugin.TargetDef;
import com.georu.core.strategy.TableOptions;
import com.georu.examples.demo.code.processor.TbCodeProcessor;
import com.georu.examples.demo.multi.dto.BrBranchDto;
import com.georu.examples.demo.multi.dto.BrMemberSourceDto;
import com.georu.examples.demo.multi.dto.StatDailyFanoutDto;
import com.georu.examples.demo.multi.dto.StgMemberAllDto;
import com.georu.examples.demo.multi.dto.TbBrMemberDto;
import com.georu.examples.demo.multi.dto.TbMemberAllDto;
import com.georu.examples.demo.multi.dto.TbMemberContactDto;
import com.georu.examples.demo.multi.dto.TbMemberPointDto;
import com.georu.examples.demo.multi.dto.TbPostNoticeDto;
import com.georu.examples.demo.multi.processor.TbMemberPointProcessor;
import com.georu.examples.demo.source.dto.TbCodeSourceDto;
import com.georu.examples.demo.source.dto.TbMemberSourceDto;
import com.georu.examples.demo.source.dto.TbPostSourceDto;
import com.georu.examples.demo.source.dto.TbStatDailySourceDto;
import com.georu.examples.demo.target.dto.TbCodeTargetDto;

import java.util.List;

/**
 * 여러 DB 예제 (손으로 쓴 참고용). DataSource 4개: source-m 본사(Oracle DEMO) · source-1 지사(Oracle DEMO_BR) ·
 * target-m 운영(MySQL demo_tobe) · target-1 분석(MySQL demo_dw). 시나리오 demo-2to1 · demo-1to2 · demo-2to2 · demo-multi.
 *
 * <ul>
 *   <li>2:1 테이블별 소스 {@code tb_branch} · 합치기 {@code stg_member_hq + stg_member_br → stg_member_all} · 결합 {@code tb_member_point}</li>
 *   <li>1:2 복제 {@code tb_code_to_dw} · 나누기 {@code tb_member_contact} · 조건 분기 {@code tb_post_notice} ·
 *       한 번 읽어 두 곳에 쓰기 {@code tb_stat_daily_fanout}</li>
 *   <li>2:2 짝지어 나누기 {@code tb_br_member} (target-1 채번은 자기 DB 의 seq_master — 관리 테이블 이름·열이 다른 예, yml georu.datasource.target-1.sequence) ·
 *       2단계 적재 {@code tb_member_all} · 결합 후 나누기 {@code tb_member_point_dw}</li>
 * </ul>
 * 기존 데모 플러그인의 소스 SQL 은 {@code sourceNamespace} 로 다시 쓴다. 설명은 안내서 guides/demo.md 의 "여러 DB".
 * {@code georu.plugins.demo.multi.enabled} 로 켠다 (docker 프로파일).
 */
@GeoruPlugin(name = "demo.multi", description = "여러 DB 예제 - 본사·지사 Oracle 2곳 → 운영·분석 MySQL 2곳")
public class DemoMultiPlugin implements TableCatalog {

    static final String MEMBER_SOURCE = "georu.demo.member.TbMemberSourceMapper";
    static final String BR_MEMBER_SOURCE = "georu.demo.multi.StgMemberBrSourceMapper";

    @Override
    public List<TableDef> tables() {
        return List.of(
                // ---------------------------------------------------------------- 2:1
                // 테이블별 소스: 이 테이블만 지사 DB 에서 읽는다
                TableDef.of("tb_branch", BrBranchDto.class, BrBranchDto.class).source("source-1"),
                // 합치기: 본사·지사 회원을 한 테이블로. 회원 번호가 겹치므로 SRC_CD(HQ/BR) 를 키에 넣고, countGroup 으로 묶어
                // 두 번째 단위가 기존 데이터로 막히지 않게 하고 합계(본사 + 지사 = 타겟)를 확인한다
                TableDef.of("stg_member_hq", TbMemberSourceDto.class, StgMemberAllDto.class).sourceNamespace(MEMBER_SOURCE)
                        .options(TableOptions.builder().targetTable("stg_member_all").countGroup("member_all").build()),
                TableDef.of("stg_member_br", BrMemberSourceDto.class, StgMemberAllDto.class).source("source-1")
                        .options(TableOptions.builder().targetTable("stg_member_all").countGroup("member_all").build()),
                // 결합: 본사 회원 + 지사 DB 의 포인트·지점명 (SourceLookup)
                TableDef.of("tb_member_point", TbMemberSourceDto.class, TbMemberPointDto.class).sourceNamespace(MEMBER_SOURCE)
                        .processor(TbMemberPointProcessor.class),

                // ---------------------------------------------------------------- 1:2
                // 복제: demo.code 의 tb_code 와 같은 소스 SQL·Processor 로 분석 DB 에 한 번 더. 타겟 SQL 은 따로 둔다
                // (기존 TbCodeTargetMapper 는 `demo_tobe`.`tb_code` 로 스키마를 박아 두어 다른 DB 에서 다시 쓰면 운영 DB 에 쓴다)
                TableDef.of("tb_code_to_dw", TbCodeSourceDto.class, TbCodeTargetDto.class).processor(TbCodeProcessor.class)
                        .sourceNamespace("georu.demo.code.TbCodeSourceMapper").target("target-1")
                        .options(TableOptions.builder().targetTable("tb_code").build()),
                // 나누기: demo.member 의 tb_member 와 같은 소스에서 연락처만 분석 DB 로
                TableDef.of("tb_member_contact", TbMemberSourceDto.class, TbMemberContactDto.class).sourceNamespace(MEMBER_SOURCE)
                        .target("target-1"),
                // 조건 분기: 공지 게시판 글만 분석 DB 로 (소스 SQL 의 #{params.boardType})
                TableDef.of("tb_post_notice", TbPostSourceDto.class, TbPostNoticeDto.class).target("target-1")
                        .params("boardType", "NOTICE"),
                // 한 번 읽어 두 곳에 쓰기: 일별통계를 운영 DB 이력과 분석 DB 에. 두 타겟 모두 자연키 upsert 라 다시 돌려도 건수가 같다
                TableDef.of("tb_stat_daily_fanout", TbStatDailySourceDto.class).sourceNamespace("georu.demo.stats.TbStatDailySourceMapper")
                        .targets(TargetDef.of("target-m", StatDailyFanoutDto.class).targetTable("tb_stat_daily_hist"),
                                TargetDef.of("target-1", StatDailyFanoutDto.class).targetTable("tb_stat_daily")),

                // ---------------------------------------------------------------- 2:2
                // 짝지어 나누기: 지사 → 분석 DB (본사 → 운영 DB 는 demo.member 의 tb_member). 분석용 번호는 target-1 자체의 seq_master 에서 채번 (yml georu.datasource.target-1.sequence)
                TableDef.of("tb_br_member", BrMemberSourceDto.class, TbBrMemberDto.class).sourceNamespace(BR_MEMBER_SOURCE)
                        .source("source-1").target("target-1")
                        .options(TableOptions.builder().sequenceColumns(List.of("BR_MEMBER_SEQ")).sequenceSync(true).build()),
                // 2단계 적재: 합치기 결과(target-m stg_member_all)를 다시 읽어 분석 DB 로
                TableDef.of("tb_member_all", StgMemberAllDto.class, TbMemberAllDto.class).source("target-m").target("target-1"),
                // 결합 후 나누기: tb_member_point 와 같은 결합을 타겟만 바꿔 한 번 더
                TableDef.of("tb_member_point_dw", TbMemberSourceDto.class, TbMemberPointDto.class).sourceNamespace(MEMBER_SOURCE)
                        .processor(TbMemberPointProcessor.class).target("target-1")
                        .targetNamespace("georu.demo.multi.TbMemberPointTargetMapper")
                        .options(TableOptions.builder().targetTable("tb_member_point").build())
        );
    }
}
