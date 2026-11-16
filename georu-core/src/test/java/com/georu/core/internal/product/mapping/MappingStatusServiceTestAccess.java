package com.georu.core.internal.product.mapping;

/** 패키지 전용 메서드를 다른 패키지 테스트에서 쓰기 위한 통로 */
public final class MappingStatusServiceTestAccess {
    private MappingStatusServiceTestAccess() {
    }

    public static String beanToTableKey(String beanName) {
        return MappingStatusService.beanToTableKey(beanName);
    }
}
