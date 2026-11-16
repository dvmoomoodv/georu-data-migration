package com.georu.core.internal.gate;

import java.util.List;
import java.util.Map;

/**
 * 활성 cron 스케줄 조회 (게이트가 사용자 실행을 막을 때 쓴다).
 * 활성 = 등록되어 있고 일시정지되지 않았으며 종료 시간이 지나지 않은 스케줄. 구현은 MigrationSchedulerService
 */
public interface ActiveScheduleCheck {

    /** 활성 스케줄 요약 (scheduleId·이름·플러그인·테이블·cron). 없으면 빈 목록 */
    List<Map<String, Object>> activeSchedules();
}
