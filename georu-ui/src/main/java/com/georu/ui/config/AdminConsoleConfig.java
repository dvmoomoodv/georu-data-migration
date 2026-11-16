package com.georu.ui.config;

import de.codecentric.boot.admin.server.config.EnableAdminServer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * 서버 진단 화면 (Spring Boot Admin, 경로 {@code /admin}).
 *
 * <p>앱 안에 SBA 서버를 두고, SBA 클라이언트가 자기 자신을 등록한다(내장형). 별도 서버·포트가 없다.
 * 운영 UI 메뉴 "도구 › 서버 진단" 이 새 탭으로 연다.</p>
 *
 * <ul>
 *   <li>켜고 끄기: {@code georu.admin-console.enabled} (환경변수 {@code APP_ADMIN_CONSOLE_ENABLED}). 바꾸면 재기동.
 *       같은 값이 SBA 서버·클라이언트({@code spring.boot.admin.server/client.enabled})와 메뉴 표시를 함께 정한다.
 *       끄면 이 설정이 빠져 {@code /admin} 경로가 없다(404)</li>
 *   <li>보기 위주. SBA 는 노출된 Actuator 엔드포인트만 보여 주므로 프로파일별 {@code management.endpoints.web.exposure} 가
 *       기능 범위다. 힙 덤프는 모든 프로파일에서 끈다(메모리에 DB 비밀번호 등이 들어 있음)</li>
 *   <li>SBA 자체 알림은 끈다. 내장형은 앱이 죽으면 SBA 도 죽어 알릴 수 없고, 기존 알림과 겹친다</li>
 * </ul>
 * 인증은 없다(운영 UI 와 같은 사내망 전용 전제).
 */
@Configuration
@EnableAdminServer
@ConditionalOnProperty(prefix = "georu.admin-console", name = "enabled", havingValue = "true")
public class AdminConsoleConfig {
}
