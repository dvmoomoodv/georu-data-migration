package com.georu.core.sequence;

import com.georu.core.sequence.model.SequenceBlock;
import com.georu.core.sequence.model.SequenceKey;
import com.georu.core.sequence.model.SequenceRow;
import com.georu.core.sequence.model.SequenceStepStats;

import java.util.List;

/**
 * 시퀀스 할당 정책 SPI (버전 무관 계약).
 *
 * <p>구현은 {@code core.sequence.v{n}} 패키지에 두고 Bean 이름은 {@code sequencePolicyV{n}},
 * {@link #version()} 은 {@link SequenceVersions} 상수를 돌려준다. {@link SequencePolicyRegistry} 가 버전으로 찾는다.</p>
 *
 * <p>의미 규칙 (모든 정책 공통):</p>
 * <ul>
 *   <li>관리 테이블의 값은 "마지막으로 사용된 번호" 다. 다음 번호는 값 + 1. 새 행은 initial-value 로 시작한다.</li>
 *   <li>{@link #reserve(SequenceKey, int)} 는 구간을 선점하고 돌려준다. 선점한 번호는 반환하지 않는다 (번호 공백 허용).</li>
 *   <li>{@link #beginStep(SequenceKey, SequenceRunMode)} 를 {@link SequenceRunMode#DRY_RUN} 으로 열면 그 키는 관리 테이블을 갱신하지 않는다.</li>
 *   <li>{@link #next(SequenceKey)} 는 beginStep 없이도 동작한다 (API·직접 호출). 이때는 NORMAL 로 간주한다.</li>
 * </ul>
 */
public interface SequenceAllocationPolicy {

    /** 정책 버전 ("v1"). {@link SequenceVersions} 상수 사용 */
    String version();

    /** 로그·API 노출용 한 줄 설명 */
    String description();

    /** Step 시작: 키 상태 초기화, 시작값 기록, 실행 모드 지정 */
    void beginStep(SequenceKey key, SequenceRunMode mode);

    /** 관리 테이블의 현재값 (행이 없으면 initial-value) */
    long current(SequenceKey key);

    /** 1건 채번 */
    long next(SequenceKey key);

    /** 일괄 선점. 반환 구간은 호출자가 소비한다 (정책 내부 커서와 별개) */
    SequenceBlock reserve(SequenceKey key, int count);

    /** 값 설정 (운영 보정). 감소도 허용하므로 호출자가 정책을 정한다 */
    void set(SequenceKey key, long value);

    /** 대상 테이블 MAX(column) 로 끌어올린다 (현재값이 더 크면 유지). 반환: 동기화 후 값 */
    long syncToMax(SequenceKey key);

    /** Step 종료: 통계 반환, 키 상태 정리 */
    SequenceStepStats endStep(SequenceKey key);

    /** 관리 테이블 전체 목록 */
    List<SequenceRow> list();
}
