package com.georu.codegen;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 생성 템플릿 (Thymeleaf TEXT 모드). 기본본은 {@code classpath:georu/codegen/default/} 에 있고,
 * 업체 앱이 같은 이름으로 {@code classpath:georu/codegen/} 에 두면 그것을 먼저 쓴다.
 * 변수 없이 그대로 복사하는 파일({@link #raw})도 같은 순서로 찾는다.
 */
final class TemplateRenderer {

    static final String OVERRIDE = "georu/codegen/";
    static final String DEFAULT = "georu/codegen/default/";

    private final ClassLoader classLoader;
    private final TemplateEngine engine = new TemplateEngine();

    TemplateRenderer(ClassLoader classLoader) {
        this.classLoader = classLoader;
        engine.addTemplateResolver(resolver(OVERRIDE, 1, true));
        engine.addTemplateResolver(resolver(DEFAULT, 2, false));
    }

    private ClassLoaderTemplateResolver resolver(String prefix, int order, boolean checkExistence) {
        ClassLoaderTemplateResolver r = new ClassLoaderTemplateResolver(classLoader);
        r.setPrefix(prefix);
        r.setTemplateMode(TemplateMode.TEXT);
        r.setCharacterEncoding("UTF-8");
        r.setOrder(order);
        r.setCheckExistence(checkExistence);
        r.setCacheable(true);
        return r;
    }

    String render(String template, Map<String, Object> variables) {
        Context ctx = new Context();
        ctx.setVariables(variables);
        return lf(engine.process(template, ctx));
    }

    /** 변수 없이 그대로 쓰는 파일 */
    String raw(String name) {
        InputStream in = classLoader.getResourceAsStream(OVERRIDE + name);
        if (in == null) in = classLoader.getResourceAsStream(DEFAULT + name);
        if (in == null) throw new IllegalStateException("생성 템플릿이 없습니다: " + DEFAULT + name);
        try (InputStream s = in) {
            return lf(new String(s.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 생성 파일의 줄 끝은 체크아웃 환경과 무관하게 LF 로 통일한다.
     * 템플릿이 CRLF 로 체크아웃되면(Windows, .gitattributes 의 text 속성) 출력도 CRLF 가 되어 같은 입력에 다른 zip 이 나오고,
     * 할 일 검사 정규식과 테스트의 텍스트 블록 비교가 깨진다
     */
    static String lf(String s) {
        return s.indexOf('\r') < 0 ? s : s.replace("\r\n", "\n").replace('\r', '\n');
    }
}
