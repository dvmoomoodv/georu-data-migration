package com.georu.codegen;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 이름 규칙. 테이블 → 클래스 이름은 엔진의 Bean 이름 규칙(tb_member → TbMember, 각 마디 첫 글자만 대문자)과 같다.
 */
final class Names {

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern PACKAGE_WORD = Pattern.compile("[a-z][a-z0-9]*");
    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "var", "record",
            "yield", "sealed", "permits");

    private Names() {
    }

    /** tb_member → TbMember */
    static String pascal(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.split("_")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) sb.append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /** GRADE_CD → gradeCd */
    static String camel(String name) {
        String p = pascal(name);
        return p.isEmpty() ? p : Character.toLowerCase(p.charAt(0)) + p.substring(1);
    }

    /** 컬럼 → DTO 필드. 자바 예약어면 Value 를 붙인다 (CLASS → classValue) */
    static String field(String column) {
        String c = camel(column);
        return keyword(c) ? c + "Value" : c;
    }

    static boolean keyword(String s) {
        return KEYWORDS.contains(s);
    }

    /** 패키지 한 마디로 쓸 수 있는 이름 (소문자 영문으로 시작, 소문자·숫자, 예약어 아님) */
    static boolean packageWord(String s) {
        return s != null && PACKAGE_WORD.matcher(s).matches() && !KEYWORDS.contains(s);
    }

    /** 자바 식별자로 쓸 수 있는 이름 */
    static boolean identifier(String s) {
        return s != null && !s.isEmpty() && IDENT.matcher(s).matches() && !KEYWORDS.contains(s);
    }
}
