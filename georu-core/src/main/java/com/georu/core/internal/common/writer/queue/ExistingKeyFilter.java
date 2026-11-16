package com.georu.core.internal.common.writer.queue;

import java.util.List;

/**
 * 타겟에 이미 있는 키의 행을 걸러 낸다.
 * 연결이 끊겨 commit 여부를 모르는 적재를 다시 할 때, 그리고 "빠진 행 채우기" 실행에서 쓴다.
 * 키를 맞출 수 없으면(PK 없음·속성 없음) 예외를 던진다 → 호출자는 최종 실패로 처리한다.
 */
public interface ExistingKeyFilter<T> {

    /** @return 타겟에 아직 없는 행만 (순서 유지) */
    List<T> removeExisting(List<T> items) throws Exception;

    /** 로그용 설명 (테이블·키 컬럼) */
    String describe();
}
