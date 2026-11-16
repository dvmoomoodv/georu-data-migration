package com.georu.core.internal.sequence;

/**
 * 시퀀스 할당 정책 버전 상수.
 *
 * <p>정책 구현은 {@code core.sequence.v{n}} 패키지에 두고 {@link SequenceAllocationPolicy#version()} 이 이 상수를 돌려준다.
 * 문자열 리터럴("v1")을 코드에 직접 쓰지 않는다. 새 정책을 추가하면 여기에 상수를 먼저 더한다.</p>
 */
public final class SequenceVersions {

    /** v1: 블록 선점 (관리 테이블 +N 갱신 후 메모리 커서 소비). 방언: mysql / generic */
    public static final String V1 = "v1";

    /** 설정(georu.sequence.policy)이 비어 있을 때 쓰는 기본 버전 */
    public static final String DEFAULT = V1;

    private SequenceVersions() {
    }

    /** null/공백 → null, 나머지는 trim + 소문자 */
    public static String normalize(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        return version.trim().toLowerCase();
    }
}
