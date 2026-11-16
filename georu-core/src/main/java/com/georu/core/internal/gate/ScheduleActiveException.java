package com.georu.core.internal.gate;

import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * 활성 cron 스케줄이 남아 있어 테이블·시나리오 등 사용자 실행을 시작할 수 없을 때 (HTTP 409, SCHEDULE_ACTIVE).
 * 스케줄을 모두 일시정지하거나 취소한 뒤 실행한다
 */
@Getter
public class ScheduleActiveException extends ExecutionBusyException {
    private final transient List<Map<String, Object>> schedules;

    public ScheduleActiveException(List<Map<String, Object>> schedules) {
        super("활성 스케줄 " + schedules.size() + "개가 있어 실행할 수 없습니다. 실행 현황 화면에서 스케줄을 모두 일시정지(또는 취소)한 뒤 다시 실행하세요: "
                + schedules.stream().map(s -> String.valueOf(s.get("scheduleId"))).limit(5).toList()
                + (schedules.size() > 5 ? " 외 " + (schedules.size() - 5) + "개" : ""));
        this.schedules = schedules;
    }
}
