package com.georu.core.internal.product.notice;


import com.georu.core.internal.product.mapping.MappingStatusServiceTestAccess;
import com.georu.core.product.notice.UiNotificationRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("알림 서식 - HTML 이스케이프, 소요시간, 차단 사유 표시")
class ProductNoticeFormatTest {

    @Test
    @DisplayName("텔레그램 HTML parse_mode 에서 깨지지 않도록 < > & 를 이스케이프한다")
    void escapeHtml() {
        assertEquals("a &lt; b &amp;&amp; c &gt; d", ProductNotifier.escapeHtml("a < b && c > d"));
        assertEquals("", ProductNotifier.escapeHtml(null));
    }

    @Test
    @DisplayName("소요시간 표시")
    void duration() {
        assertEquals("500ms", ProductNotifier.duration(500));
        assertEquals("5초", ProductNotifier.duration(5_000));
        assertEquals("2분 5초", ProductNotifier.duration(125_000));
        assertEquals("1시간 1분 1초", ProductNotifier.duration(3_661_000));
    }

    @Test
    @DisplayName("묶음 요약의 차단 사유 한국어 표시")
    void reasonLabel() {
        assertEquals("기존 데이터", BatchSummaryService.reasonLabel("EXISTING_DATA"));
        assertEquals("실행 전 점검", BatchSummaryService.reasonLabel("PRECHECK"));
        assertEquals("-", BatchSummaryService.reasonLabel(null));
    }

    @Test
    @DisplayName("소스 Mapper bean 이름 → 스캔 키 (ASIS 이름 Mapper 도 찾기 위해)")
    void beanToTableKey() {
        assertEquals("asis_auto_class_budget", MappingStatusServiceTestAccess.beanToTableKey("asisAutoClassBudgetSourceMapper"));
        assertEquals("fb_member", MappingStatusServiceTestAccess.beanToTableKey("fbMemberSourceMapper"));
        assertNull(MappingStatusServiceTestAccess.beanToTableKey(""));
    }

    @Test
    @DisplayName("시나리오 단계 알림 문구: 번호·결과·처리·다음 단계, 차단은 사유")
    void scenarioStepMessage() {
        ProductNotifier n = new ProductNotifier(org.mockito.Mockito.mock(com.georu.core.notification.TelegramNotificationService.class),
                org.mockito.Mockito.mock(UiNotificationRecorder.class));
        String ok = n.scenarioStepMessage(ProductNotifier.ScenarioStep.builder()
                .groupId("SCF-1").scenarioName("데모 전체 이관").seq(5).stepCount(11).label("로그인 로그 (월별)")
                .pluginName("demo.member").tableName("tb_member_login_log").status("COMPLETED").statusLabel("전체 성공")
                .unitTotal(13).unitSuccess(13).processedSum(10_000).durationMs(1_200).next("다음: 6단계 게시판 (tb_board)").build());
        assertTrue(ok.startsWith("✅ [시나리오 5/11단계] 데모 전체 이관 · 로그인 로그 (월별)"), ok);
        assertTrue(ok.contains("결과: 전체 성공 · 단위 13/13 · 처리 10,000 · 소요 1초"), ok);
        assertTrue(ok.contains("다음: 6단계 게시판 (tb_board)"), ok);

        String blocked = n.scenarioStepMessage(ProductNotifier.ScenarioStep.builder()
                .scenarioName("데모").seq(1).stepCount(2).tableName("a").status("BLOCKED").statusLabel("실행 전 차단")
                .unitTotal(1).unitNotRun(1).detail("타겟에 기존 데이터").build());
        assertTrue(blocked.startsWith("⛔"), blocked);
        assertFalse(blocked.contains("단위"), blocked);
        assertTrue(blocked.contains("사유: 타겟에 기존 데이터"), blocked);
    }
}
