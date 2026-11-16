package com.georu.core.internal.plugin;

import com.georu.core.plugin.TargetDef;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 한 번 읽어 여러 곳에 쓰기의 기동 확인: 타겟 이름이 겹치지 않고, 모든 타겟이 멱등 쓰기인지.
 * 멱등 쓰기 = 타겟 XML 의 insertData 가 upsert 이거나, {@code skipExisting} 이고 TargetDto 에 {@code @MigrationId} 가 있음.
 */
final class FanOutChecks {

    static final Pattern UPSERT = Pattern.compile(
            "ON\\s+DUPLICATE\\s+KEY\\s+UPDATE|INSERT\\s+IGNORE|MERGE\\s+INTO|ON\\s+CONFLICT|REPLACE\\s+INTO|WHEN\\s+NOT\\s+MATCHED|INSERT\\s+OR\\s+IGNORE|INSERT\\s+OR\\s+REPLACE",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMESPACE = Pattern.compile("<mapper\\s+namespace\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern INSERT_DATA = Pattern.compile(
            "<(insert|update)\\b[^>]*\\bid\\s*=\\s*\"insertData\"[^>]*>(.*?)</\\1>", Pattern.DOTALL);

    /** namespace → insertData 본문 (없으면 키만, 값 null) */
    private Map<String, String> statements;

    /** 문제 목록 (비면 통과) */
    List<String> check(String plugin, String table, List<TargetDef> targets) {
        List<String> errors = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (TargetDef t : targets) {
            String where = plugin + "." + table + " 의 타겟 " + t.name();
            if (!names.add(t.name())) {
                errors.add(plugin + "." + table + " 에 타겟 이름 '" + t.name() + "' 이 두 번 있습니다. 같은 DataSource 에 두 번 쓰면 TargetDef.name(...) 으로 구분하세요");
                continue;
            }
            if (t.targetDto() == null) {
                errors.add(where + " 에 TargetDto 가 없습니다 (TargetDef.of(dataSource, TargetDto.class))");
                continue;
            }
            if (t.isSkipExisting()) {
                if (MigrationIds.of(t.targetDto()).isEmpty()) {
                    errors.add(where + " 은 skipExisting 인데 " + t.targetDto().getSimpleName() + " 에 @MigrationId 가 없습니다");
                }
                continue;
            }
            String ns = t.resolveTargetNamespace(plugin, table);
            String sql = statements().get(ns);
            if (sql == null) {
                errors.add(where + " 의 타겟 XML(" + ns + ".insertData)을 찾지 못해 멱등 쓰기인지 확인할 수 없습니다");
            } else if (!UPSERT.matcher(sql).find()) {
                errors.add(where + " 의 " + ns + ".insertData 가 멱등 쓰기가 아닙니다. upsert(ON DUPLICATE KEY UPDATE · INSERT IGNORE · MERGE 등)로 쓰거나"
                        + " TargetDef.skipExisting() 으로 이미 있는 행을 거르세요 (타겟 하나가 실패해 다시 돌리면 이미 쓴 타겟에도 다시 쓰기 때문)");
            }
        }
        return errors;
    }

    private Map<String, String> statements() {
        if (statements == null) {
            statements = new HashMap<>();
            try {
                for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:georu/**/*.xml")) {
                    try (InputStream in = r.getInputStream()) {
                        String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        Matcher ns = NAMESPACE.matcher(xml);
                        if (!ns.find()) {
                            continue;
                        }
                        Matcher ins = INSERT_DATA.matcher(xml);
                        if (ins.find()) {
                            statements.put(ns.group(1), ins.group(2));
                        }
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("타겟 XML 을 읽지 못했습니다: " + e.getMessage(), e);
            }
        }
        return statements;
    }
}
