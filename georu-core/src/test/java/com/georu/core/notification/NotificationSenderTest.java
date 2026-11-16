package com.georu.core.notification;

import com.georu.common.monitoring.BatchResourceMonitor;
import com.georu.common.monitoring.JobParameterCollector;
import com.georu.common.monitoring.PerformanceCalculator;
import com.georu.core.product.notice.UiNotificationRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("NotificationSender - 앱이 등록한 알림 채널도 같은 메시지를 받는다")
class NotificationSenderTest {

    static class Recording implements NotificationSender {
        final List<String> got = new ArrayList<>();
        final boolean enabled;

        Recording(boolean enabled) {
            this.enabled = enabled;
        }

        public String name() { return "recording"; }
        public boolean isEnabled() { return enabled; }
        public void send(String message) { got.add(message); }
    }

    private TelegramNotificationService telegram(StaticListableBeanFactory beans) {
        TelegramNotificationService t = new TelegramNotificationService(mock(BatchResourceMonitor.class), mock(PerformanceCalculator.class),
                mock(JobParameterCollector.class), beans.getBeanProvider(UiNotificationRecorder.class), beans.getBeanProvider(NotificationSender.class));
        beans.addBean("telegram", t);
        return t;
    }

    @Test
    @DisplayName("켜진 채널은 받고, 꺼진 채널과 텔레그램 자신은 다시 받지 않는다")
    void forwards() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        Recording on = new Recording(true);
        Recording off = new Recording(false);
        beans.addBean("on", on);
        beans.addBean("off", off);
        TelegramNotificationService t = telegram(beans);

        t.sendMessage("작업 완료");

        assertThat(on.got).containsExactly("작업 완료");
        assertThat(off.got).isEmpty();
        assertThat(t.name()).isEqualTo("telegram");
    }

    @Test
    @DisplayName("채널이 예외를 던져도 엔진 알림은 계속된다")
    void channelErrorIsIgnored() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("broken", new NotificationSender() {
            public String name() { return "broken"; }
            public boolean isEnabled() { return true; }
            public void send(String message) { throw new IllegalStateException("down"); }
        });
        Recording after = new Recording(true);
        beans.addBean("after", after);

        telegram(beans).sendMessage("시작");

        assertThat(after.got).containsExactly("시작");
    }
}
