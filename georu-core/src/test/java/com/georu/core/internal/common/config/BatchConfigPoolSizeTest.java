package com.georu.core.internal.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BatchConfig - 단일 실행 규칙 실행기 검증")
class BatchConfigPoolSizeTest {
    @Test
    void poolSizeMustBeOneWhenEnforced() {
        BatchConfig c = new BatchConfig();
        assertThrows(IllegalStateException.class, () -> c.batchTaskExecutor(2, 2, 50, "t-", 60, true, 60, true, true));
        assertThrows(IllegalStateException.class, () -> c.batchTaskExecutor(1, 3, 50, "t-", 60, true, 60, true, true));
        assertNotNull(c.batchTaskExecutor(1, 1, 50, "t-", 60, true, 60, true, true));
        assertNotNull(c.batchTaskExecutor(3, 3, 50, "t-", 60, true, 60, true, false), "enforce=false 면 허용");
        assertNotNull(c.batchTaskExecutor(3, 3, 50, "t-", 60, true, 60, false, true), "게이트 비활성이면 허용");
    }
}
