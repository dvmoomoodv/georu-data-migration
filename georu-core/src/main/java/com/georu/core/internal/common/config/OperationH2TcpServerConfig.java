package com.georu.core.internal.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 운영 저장소(H2 파일 DB) 를 외부 툴(DBeaver 등)에서 고정 주소로 보기 위한 H2 TCP 서버.
 *
 * 왜 필요한가
 *  - URL 의 AUTO_SERVER=TRUE 는 먼저 연 프로세스가 임의 포트로 내부 서버를 띄우고 그 주소와 id 를 lock 파일에 적는다.
 *    컨테이너에서는 그 주소가 Docker 내부 IP(172.x)·임의 포트라 호스트에서 닿지 않고, 재기동마다 바뀐다.
 *  - 이 서버는 앱 JVM 안에서 고정 포트로 떠서, 앱이 열고 있는 같은 DB 를 파일 경로로 공유한다.
 *
 * 설정 (georu.operation.h2-tcp)
 *  - enabled : 기본 false. docker 프로파일에서만 true (로컬 전용. 계정 sa / 빈 비밀번호라 운영 환경에서 켜지 않는다)
 *  - port    : 기본 9092. compose 는 127.0.0.1 에만 노출한다
 *  - allow-others : 기본 false (같은 PC 에서만 접속). 컨테이너는 포트 매핑 접속이 외부 IP 로 보이므로 compose 가 true 로 준다
 *
 * 접속 (호스트 → 컨테이너): jdbc:h2:tcp://localhost:9092//app/data/operation/migops;MODE=MySQL;...
 *  - 경로는 서버(컨테이너) 기준. -ifExists 라 경로가 틀리면 빈 DB 를 만들지 않고 오류를 낸다.
 *
 * H2 는 runtimeOnly 의존성이라 org.h2.tools.Server 를 리플렉션으로 호출한다.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "georu.operation.h2-tcp", name = "enabled", havingValue = "true")
public class OperationH2TcpServerConfig implements InitializingBean, DisposableBean {

    private final int port;
    private final boolean allowOthers;
    private final String operationUrl;
    private Object server;

    /**
     * operationSchemaInitializer 를 주입받아 앱이 먼저 DB 를 열고 스키마를 확인한 뒤에 TCP 서버를 띄운다.
     */
    public OperationH2TcpServerConfig(OperationSchemaInitializer operationSchemaInitializer,
                                      @Value("${georu.operation.h2-tcp.port:9092}") int port,
                                      @Value("${georu.operation.h2-tcp.allow-others:false}") boolean allowOthers,
                                      @Value("${georu.datasource.operation.hikari.jdbc-url:}") String operationUrl) {
        this.port = port;
        this.allowOthers = allowOthers;
        this.operationUrl = operationUrl;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (operationUrl == null || !operationUrl.startsWith("jdbc:h2:")) {
            log.warn("[Operation] h2-tcp.enabled=true 이지만 운영 저장소가 H2 가 아님 - TCP 서버 생략 ({})", operationUrl);
            return;
        }
        List<String> args = new ArrayList<>(List.of("-tcpPort", String.valueOf(port), "-ifExists", "-tcpDaemon"));
        if (allowOthers) {
            args.add("-tcpAllowOthers");   // 컨테이너 포트 매핑으로 들어오는 접속은 localhost 가 아니므로 필요
        }
        Class<?> serverClass = Class.forName("org.h2.tools.Server");
        Method create = serverClass.getMethod("createTcpServer", String[].class);
        server = create.invoke(null, (Object) args.toArray(String[]::new));
        serverClass.getMethod("start").invoke(server);
        String status = (String) serverClass.getMethod("getStatus").invoke(server);
        log.info("[Operation] H2 TCP 서버 기동: port={} allowOthers={} - {}", port, allowOthers, status);
    }

    @Override
    public void destroy() throws Exception {
        if (server != null) {
            server.getClass().getMethod("stop").invoke(server);
            log.info("[Operation] H2 TCP 서버 종료: port={}", port);
        }
    }
}
