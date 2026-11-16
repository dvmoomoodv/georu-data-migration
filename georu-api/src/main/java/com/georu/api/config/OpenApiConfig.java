package com.georu.api.config;

import com.georu.core.internal.environment.MigrationEnvironmentService;
import com.georu.core.internal.environment.MigrationEnvironmentService.DbInfo;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * OpenAPI 3.0 설정 (Swagger UI).
 *
 * <p>머리말의 "소스 → 타겟" 과 DB 표는 {@link MigrationEnvironmentService} 가 설정에서 만든다. DB 종류는 JDBC URL 로,
 * 소스·타겟은 DataSource 이름(source-m·target-m …)에서 정하므로 고객사·DBMS 가 바뀌어도 이 클래스는 고치지 않는다.
 * 컨트롤러 {@code @Tag}·{@code @Operation} 설명의 {@value #FLOW} 는 같은 값으로 바꾼다.</p>
 */
@Configuration
public class OpenApiConfig {

    /** 설명 문구 안의 자리표시 → "Oracle → MySQL" */
    public static final String FLOW = "{flow}";

    @Value("${server.port:8080}")
    private String serverPort;

    @Value("${georu.batch.queue.enabled:false}")
    private boolean queueEnabled;

    @Bean
    public OpenAPI customOpenAPI(MigrationEnvironmentService env) {
        return new OpenAPI()
            .info(new Info()
                .title("배치 실행 시스템 API")
                .description(description(env))
                .version("1.0.0")
                .license(new License().name("Internal Use Only")))
            .servers(List.of(new Server().url("http://localhost:" + serverPort).description("로컬 개발 서버")));
    }

    /** 태그·API 설명의 {flow} 를 실제 소스 → 타겟 으로 */
    @Bean
    public OpenApiCustomizer flowDescriptionCustomizer(MigrationEnvironmentService env) {
        return openApi -> {
            String flow = env.flow();
            if (openApi.getTags() != null) {
                openApi.getTags().forEach(t -> {
                    if (t.getDescription() != null) t.setDescription(t.getDescription().replace(FLOW, flow));
                });
            }
            if (openApi.getPaths() != null) {
                openApi.getPaths().values().forEach(p -> p.readOperations().forEach(op -> {
                    if (op.getDescription() != null) op.setDescription(op.getDescription().replace(FLOW, flow));
                    if (op.getSummary() != null) op.setSummary(op.getSummary().replace(FLOW, flow));
                }));
            }
        };
    }

    /** Swagger 머리말: 소스 → 타겟, 프로파일, DataSource 표 */
    String description(MigrationEnvironmentService env) {
        Map<String, Object> d = env.describe();
        StringBuilder sb = new StringBuilder();
        sb.append("## 🚀 마이그레이션 실행 API - ").append(d.get("flow")).append(" 데이터 이관\n\n---\n\n");
        sb.append("### 🌐 현재 환경\n\n| 항목 | 값 |\n|------|-----|\n");
        sb.append("| **Profile** | `").append(d.get("profile")).append("`")
          .append(Boolean.TRUE.equals(d.get("production")) ? " ⚠️ **운영**" : "").append(" |\n");
        sb.append("| **Queue Writer** | ").append(queueEnabled ? "✅ 활성화" : "❌ 비활성화").append(" |\n\n---\n\n");
        sb.append("### 🗄️ DataSource\n\n| 역할 | 이름 | DBMS | JDBC URL | Username |\n|------|------|------|----------|----------|\n");
        @SuppressWarnings("unchecked")
        List<DbInfo> list = (List<DbInfo>) d.get("dataSources");
        for (DbInfo db : list) {
            sb.append("| ").append(roleLabel(db.role())).append(" | ").append(db.name()).append(" | ").append(db.dbms())
              .append(" | `").append(db.url() == null ? "-" : db.url()).append("` | `")
              .append(db.username() == null ? "-" : db.username()).append("` |\n");
        }
        sb.append("\n---\n\n⚠️ **주의**: API 실행 전 위 환경 정보가 올바른지 확인하세요! (운영 UI 대시보드에도 같은 정보가 있습니다)\n");
        return sb.toString();
    }

    private static String roleLabel(String role) {
        return switch (role) {
            case MigrationEnvironmentService.SOURCE -> "소스";
            case MigrationEnvironmentService.TARGET -> "타겟";
            case MigrationEnvironmentService.OPERATION -> "운영 저장소";
            default -> "역할 미지정";
        };
    }
}
