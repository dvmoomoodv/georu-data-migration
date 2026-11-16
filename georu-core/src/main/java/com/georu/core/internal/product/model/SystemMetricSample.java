package com.georu.core.internal.product.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** migration_system_metrics 1행 - 5분마다 수집한 앱 부하 지표 (시스템 화면·대시보드 그래프) */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "5분마다 수집한 앱 부하 지표 1건")
public class SystemMetricSample {
    @Schema(description = "지표 ID", example = "1")
    private Long id;
    @Schema(description = "수집 시각 (초 단위)")
    private LocalDateTime collectedAt;
    /** 앱 프로세스 CPU 사용률 (%) */
    @Schema(description = "앱 프로세스 CPU 사용률 (%)", example = "12.5")
    private Double processCpu;
    /** 시스템(컨테이너) CPU 사용률 (%) */
    @Schema(description = "시스템(컨테이너) CPU 사용률 (%)", example = "30.1")
    private Double systemCpu;
    @Schema(description = "힙 사용량 (MB)", example = "512")
    private Long heapUsedMb;
    @Schema(description = "힙 최대 크기 (MB). 최대값이 없으면 committed 크기", example = "2048")
    private Long heapMaxMb;
    /** 힙 사용률 (%) */
    @Schema(description = "힙 사용률 (%)", example = "25.0")
    private Double heapUsage;
    @Schema(description = "JVM 스레드 수", example = "80")
    private Integer threadCount;
    @Schema(description = "수집 시점에 실행 중인 Job 수. 조회 불가면 null", example = "1")
    private Integer runningJobs;
    /** DataSource 별 커넥션 풀 {"이름":{"active":n,"total":n,"max":n}} (JSON) */
    @Schema(description = "DataSource 별 커넥션 풀 상태 JSON ({\"이름\":{\"active\",\"idle\",\"total\",\"max\",\"usage\"}})")
    private String poolStats;
}
