package com.georu.examples.demo.notice;

import com.georu.core.plugin.GeoruPlugin;
import com.georu.core.plugin.TableCatalog;
import com.georu.core.plugin.TableDef;
import com.georu.core.strategy.TableOptions;
import com.georu.core.strategy.TableOptions.TableState;
import com.georu.examples.demo.source.dto.TbBannerSourceDto;
import com.georu.examples.demo.source.dto.TbFaqSourceDto;
import com.georu.examples.demo.source.dto.TbNoticeReadLogSourceDto;
import com.georu.examples.demo.source.dto.TbNoticeSourceDto;
import com.georu.examples.demo.source.dto.TbNoticeTemplateSourceDto;
import com.georu.examples.demo.source.dto.TbPopupSourceDto;
import com.georu.examples.demo.target.dto.TbBannerTargetDto;
import com.georu.examples.demo.target.dto.TbFaqTargetDto;
import com.georu.examples.demo.target.dto.TbNoticeReadLogTargetDto;
import com.georu.examples.demo.target.dto.TbNoticeTargetDto;
import com.georu.examples.demo.target.dto.TbNoticeTemplateTargetDto;
import com.georu.examples.demo.target.dto.TbPopupTargetDto;

import java.util.List;

/**
 * 데모 공지 (업무코드 04): 테이블 이관 상태(수행 · 대기 · 이관 제외) 예제. Processor 없이 같은 이름 필드를 옮긴다 (CopyProcessor).
 * <ul>
 *   <li>수행: tb_notice · tb_faq - 지금처럼 매핑 확인 · 실행</li>
 *   <li>대기: tb_popup · tb_banner - 목록 · 매핑 현황에 보이지만 모든 모드에서 실행할 수 없다. tb_popup 은 컬럼 TODO 가 남아 있다</li>
 *   <li>이관 제외: tb_notice_read_log · tb_notice_template - 매핑 대상(미완 집계)에서 빠지고 실행할 수 없다. TOBE 테이블도 없다</li>
 * </ul>
 * 상태는 코드로만 바꾼다 ({@link TableOptions#getState()}). 시나리오에 대기 · 제외 테이블을 넣으면 정의 오류다.
 */
@GeoruPlugin(name = "demo.notice", description = "데모 공지 (04) Migration Plugin - 이관 상태(수행 · 대기 · 제외) 예제")
public class DemoNoticePlugin implements TableCatalog {

    @Override
    public List<TableDef> tables() {
        return List.of(
                TableDef.of("tb_notice", TbNoticeSourceDto.class, TbNoticeTargetDto.class),
                TableDef.of("tb_faq", TbFaqSourceDto.class, TbFaqTargetDto.class),
                TableDef.of("tb_popup", TbPopupSourceDto.class, TbPopupTargetDto.class)
                        .options(TableOptions.builder().state(TableState.HOLD).stateReason("TOBE 팝업 화면 설계 확정 전 (링크 규칙 미정)").build()),
                TableDef.of("tb_banner", TbBannerSourceDto.class, TbBannerTargetDto.class)
                        .options(TableOptions.builder().state(TableState.HOLD).stateReason("ASIS 배너 이미지 경로 규칙 확인 중").build()),
                TableDef.of("tb_notice_read_log", TbNoticeReadLogSourceDto.class, TbNoticeReadLogTargetDto.class)
                        .options(TableOptions.builder().state(TableState.EXCLUDED).stateReason("TOBE 에서 읽음 이력을 보관하지 않음").build()),
                TableDef.of("tb_notice_template", TbNoticeTemplateSourceDto.class, TbNoticeTemplateTargetDto.class)
                        .options(TableOptions.builder().state(TableState.EXCLUDED).stateReason("ASIS 전용 기능 폐기 (TOBE 는 에디터 기본 서식 사용)").build())
        );
    }
}
