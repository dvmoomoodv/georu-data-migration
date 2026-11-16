package com.georu.codegen;

import java.util.Locale;

/**
 * 생성 묶음에 Reader · Writer 파일을 만들지.
 *
 * <ul>
 *   <li>{@link #ENGINE} (기본): 만들지 않는다. 엔진 공용 Reader · Writer 가 SourceMapper · TargetMapper 이름 규칙으로 같은 일을 한다
 *       (데모와 같은 모양). 단, REUSE 테이블은 엔진 공용 Writer 가 BATCH 뿐이라 Writer 파일만 만든다</li>
 *   <li>{@link #FILES}: 테이블마다 Reader · Writer 파일을 만들어 업무 클래스에 연결한다. 테이블별로 손볼 자리가 처음부터 있다</li>
 * </ul>
 * 테스트 모드(READER_TEST · PROCESSOR_TEST · WRITER_TEST)는 둘 다 같은 기반 클래스가 처리하므로 결과가 같다.
 */
public enum ReaderWriterMode {
    ENGINE, FILES;

    /** 파일 · 화면 값 (engine · files · 대소문자 무시). 모르면 null */
    public static ReaderWriterMode of(String value) {
        if (value == null || value.isBlank()) return null;
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "ENGINE", "E", "NONE", "N" -> ENGINE;
            case "FILES", "FILE", "F", "Y" -> FILES;
            default -> null;
        };
    }
}
