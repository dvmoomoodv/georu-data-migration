package com.georu.core.internal.sequence;

/** 시퀀스 채번 오류 (정책 미등록, 관리행 없음, DTO setter 없음, 식별자 검증 실패 등) */
public class SequenceException extends RuntimeException {

    public SequenceException(String message) {
        super(message);
    }

    public SequenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
