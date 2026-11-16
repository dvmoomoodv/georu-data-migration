package com.georu.core.internal.product.precheck;

/**
 * 실행 전 점검 유형과 기본 심각도.
 * 결정: NOT NULL·타입·길이 오류는 차단(BLOCK), 나머지는 경고(WARN). 판단할 수 없는 항목은 경고로 표시한다.
 */
public enum CheckType {

    NOT_NULL("NOT NULL 위반", Severity.BLOCK),
    LENGTH("길이 초과", Severity.BLOCK),
    TYPE_MISMATCH("타입 변환 불가", Severity.BLOCK),
    NUMERIC_RANGE("숫자 범위 초과", Severity.BLOCK),
    DATE_RANGE("날짜 범위·비정상 날짜", Severity.BLOCK),
    MISSING_NOT_NULL_COLUMN("INSERT 에 빠진 NOT NULL 컬럼", Severity.BLOCK),
    UNKNOWN_COLUMN("타겟에 없는 컬럼", Severity.BLOCK),
    PROCESS_ERROR("변환(Processor) 오류", Severity.BLOCK),
    SOURCE_ERROR("소스 조회 오류", Severity.BLOCK),
    INCOMPLETE_CODE("미완 코드 (원천 미지정)", Severity.BLOCK),
    TABLE_IN_USE("실행 중인 작업과 겹침 (점검 생략)", Severity.BLOCK),
    SEQUENCE_DISABLED("시퀀스 채번 기능 꺼짐", Severity.BLOCK),
    SEQUENCE_POLICY("시퀀스 정책 미등록", Severity.BLOCK),

    DECIMAL_SCALE("소수 자릿수 초과 (반올림됨)", Severity.WARN),
    PK_DUPLICATE("샘플 내 PK 중복", Severity.WARN),
    EXISTING_DATA("타겟 기존 데이터", Severity.WARN),
    TARGET_NOT_FOUND("타겟 조회 불가 (권한 또는 테이블 없음)", Severity.WARN),
    LENGTH_UNKNOWN("길이 판단 불가 (DB 규칙 없음)", Severity.WARN),
    DATE_UNKNOWN("날짜 범위 판단 불가 (DB 규칙 없음)", Severity.WARN),
    INSERT_NOT_PARSED("INSERT 해석 불가 (값 점검 생략)", Severity.WARN),
    SAMPLE_SKIPPED("샘플 구간 생략 (시간 초과)", Severity.WARN),
    /** 변환(참조 조회 포함)이 시간 안에 안 끝남. 실제 실행은 시간 제한 없이 같은 조회를 하므로 막지 않는다 */
    PROCESS_TIMEOUT("변환 생략 (시간 초과)", Severity.WARN),

    SAMPLE_INFO("샘플 정보", Severity.INFO),
    TABLE_EXCLUDED("이관 제외 테이블 (점검 생략)", Severity.INFO),
    SOURCE_CHECK_OFF("소스(ASIS) 점검 꺼짐", Severity.INFO),
    TARGET_COUNT_OFF("타겟 건수 점검 꺼짐", Severity.INFO),
    /** 이 점검이 실제로 보낸 쿼리 수 (DB 마다 한 줄, column = TOBE / ASIS) */
    DB_ACCESS("접속 기록 (읽기만)", Severity.INFO),
    /** 만들기만 하고 실행하지 않은 INSERT 문 (message = 문장 + "\n--\n" + 컬럼 = 값 줄) */
    INSERT_PREVIEW("실행하지 않은 INSERT 문", Severity.INFO),
    FILTERED("Processor 필터 (적재 제외)", Severity.INFO);

    public enum Severity { BLOCK, WARN, INFO }

    private final String label;
    private final Severity severity;

    CheckType(String label, Severity severity) {
        this.label = label;
        this.severity = severity;
    }

    public String label() {
        return label;
    }

    public Severity severity() {
        return severity;
    }
}
