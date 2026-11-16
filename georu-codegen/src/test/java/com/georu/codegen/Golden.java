package com.georu.codegen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.fail;

/**
 * 요약 골든 비교. 고정 데이터(fixtures/anon)로 만든 결과의 요약 파일을 저장소의 골든과 줄 단위로 비교한다.
 *
 * <ul>
 *   <li>골든: {@code georu-codegen/src/test/resources/fixtures/anon/golden/*.csv}</li>
 *   <li>갱신: {@code ./gradlew :georu-codegen:test -PupdateGolden} (시스템 속성 {@code georu.golden.update=true}) — 비교 대신 골든을 덮어쓴다</li>
 *   <li>골든은 "지금 결과가 옳다" 가 아니라 "바뀌면 알자" 다. 규칙을 바꾼 PR 은 골든을 갱신하고 diff 이유를 적는다 (CONTRIBUTING)</li>
 * </ul>
 */
public final class Golden {

    static final Path FIXTURES = Path.of("src/test/resources/fixtures/anon");
    static final Path DIR = FIXTURES.resolve("golden");

    private Golden() {
    }

    static boolean updating() {
        return Boolean.getBoolean("georu.golden.update");
    }

    static byte[] fixture(String name) {
        try {
            return Files.readAllBytes(FIXTURES.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 줄 끝을 LF 로, 끝에 빈 줄 하나로 맞춘다 */
    static String normalize(String s) {
        String t = s.replace("\r\n", "\n").replace('\r', '\n');
        t = t.stripTrailing();
        return t + "\n";
    }

    /** 골든과 같은지. 갱신 모드면 덮어쓴다 */
    static void assertSame(String goldenName, String actual) {
        Path golden = DIR.resolve(goldenName);
        String act = normalize(actual);
        try {
            if (updating()) {
                Files.createDirectories(DIR);
                Files.writeString(golden, act, StandardCharsets.UTF_8);
                System.out.println("[golden] 갱신: " + golden);
                return;
            }
            if (!Files.exists(golden)) {
                fail("골든이 없습니다: " + golden + "\n처음이면 ./gradlew :georu-codegen:test -PupdateGolden 로 만든다");
            }
            String exp = normalize(Files.readString(golden, StandardCharsets.UTF_8));
            if (exp.equals(act)) return;
            fail(diff(goldenName, exp, act));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 다른 줄을 처음 50줄까지. 의도한 변경이면 -PupdateGolden 로 갱신 */
    static String diff(String name, String exp, String act) {
        List<String> e = exp.lines().toList();
        List<String> a = act.lines().toList();
        List<String> out = new ArrayList<>();
        out.add("골든과 다릅니다: " + name + " (기대 " + e.size() + "줄 · 실제 " + a.size() + "줄). 의도한 변경이면 ./gradlew :georu-codegen:test -PupdateGolden");
        int shown = 0;
        int n = Math.max(e.size(), a.size());
        for (int i = 0; i < n && shown < 50; i++) {
            String x = i < e.size() ? e.get(i) : null;
            String y = i < a.size() ? a.get(i) : null;
            if (x != null && x.equals(y)) continue;
            out.add("  :" + (i + 1) + (x == null ? "" : "  - " + x) + (y == null ? "" : "\n  :" + (i + 1) + "  + " + y));
            shown++;
        }
        if (shown == 50) out.add("  … (앞 50줄만)");
        return String.join("\n", out);
    }

    /** CSV 한 칸 (쉼표 · 따옴표 · 줄바꿈이 있으면 따옴표로 감싼다) */
    static String cell(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    static String row(Object... cells) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) b.append(',');
            b.append(cell(cells[i]));
        }
        return b.append('\n').toString();
    }
}
