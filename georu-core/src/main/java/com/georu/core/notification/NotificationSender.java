package com.georu.core.notification;

/**
 * 알림 채널 확장 지점. 앱이 이 인터페이스를 구현한 Bean 을 등록하면 엔진이 보내는 알림(작업 시작·완료·실패,
 * 시나리오 단계, 지연 경고 등)을 텔레그램과 같은 내용으로 받는다. 텔레그램도 이 인터페이스의 구현이다.
 *
 * <pre>
 * &#64;Component
 * public class MailSender implements NotificationSender {
 *     public String name() { return "mail"; }
 *     public boolean isEnabled() { return true; }
 *     public void send(String message) { ... }
 * }
 * </pre>
 *
 * <p>메시지 본문은 텔레그램 HTML 서식(&lt;b&gt;, &lt;code&gt; 등)을 쓸 수 있다. 다른 채널은 필요하면 태그를 지운다.
 * {@link #send} 는 엔진 실행 스레드에서 불리므로 오래 걸리는 전송은 비동기로 처리하고, 예외는 던지지 않는 것이 좋다
 * (던져도 엔진이 잡아 로그만 남긴다).</p>
 */
public interface NotificationSender {

    /** 채널 이름 (로그용) */
    String name();

    /** false 면 보내지 않는다 */
    boolean isEnabled();

    /** 이미 만든 알림 메시지를 보낸다 */
    void send(String message);
}
