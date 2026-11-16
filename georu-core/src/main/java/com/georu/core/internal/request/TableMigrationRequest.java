package com.georu.core.internal.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 📋 테이블 마이그레이션 요청 DTO
 *
 * 📋 기능 설명:
 * - 특정 테이블의 데이터를 소스 DB에서 타겟 DB로 마이그레이션
 * - 즉시 실행 또는 스케줄 기반 실행 지원
 * - 테스트 모드 및 커스텀 속성 설정 가능
 *
 * 🔄 실행 흐름:
 * 1. 플러그인 및 테이블 존재 여부 확인
 * 2. 스케줄 실행 vs 즉시 실행 분기 처리
 * 3. Spring Batch Job 생성 및 실행
 * 4. 청크 단위 데이터 처리 (Reader → Processor → Writer)
 * 5. 진행률 실시간 업데이트 및 알림
 *
 * ⚠️ 유의사항:
 * - chunkSize는 메모리 사용량과 성능에 직접적인 영향
 * - 스케줄 실행: cronExpression 을 주면 스케줄 등록 (executeImmediately 를 true 로 명시한 경우만 즉시 실행)
 * - 테스트 모드에서는 실제 데이터 변경 없이 검증만 수행
 * - attribute1~5는 테이블별 특화 설정으로 플러그인에서 해석
 */
@Data
@Schema(description = "테이블 마이그레이션 요청")
public class TableMigrationRequest {

    /**
     * 플러그인명 (도메인명)
     * 예: hr.member, hr.board, hr.code
     */
    /** 실행자. 헤더 X-Executed-By 또는 본문으로 전달. 미지정 시 API_USER (migration_jobs.executed_by) */
    @Size(max = 100, message = "executedBy 는 100자를 초과할 수 없습니다")
    @Schema(description = "실행자 (미지정 시 API_USER). 헤더 X-Executed-By 로도 전달 가능", example = "hong.gd")
    private String executedBy;

    @NotBlank(message = "플러그인명은 필수입니다")
    @Schema(description = "플러그인명", example = "hr.member")
    @JsonProperty(required = true)
    private String pluginName;

    /**
     * 마이그레이션할 테이블명
     * 예: tb_member, tb_board, tb_post
     */
    @NotBlank(message = "테이블명은 필수입니다")
    @Schema(description = "테이블명", example = "tb_member")
    @JsonProperty(required = true)
    private String tableName;

    // ========================================
    // 테넌트 계층 구조 (5단계) - 필수 항목
    // ========================================

    /**
     * 그룹 레벨 (1단계) - 최상위 조직
     * 예: GROUP (그룹사)
     */
    @Schema(description = "그룹 레벨", example = "GROUP")
    @NotBlank(message = "그룹 레벨은 필수입니다")
    @JsonProperty(required = true)
    private String tenantLevel1;

    /**
     * 계열사 레벨 (2단계) - 그룹 내 계열사
     * 예: AA (계열사 A), AB (계열사 B)
     */
    @Schema(description = "계열사 레벨", example = "AA")
    @NotBlank(message = "계열사 레벨은 필수입니다")
    @JsonProperty(required = true)
    private String tenantLevel2;

    /**
     * 사업부 레벨 (3단계) - 계열사 내 사업부
     * 예: HEADQUATERS (본사), FACTORY (공장)
     */
    @Schema(description = "사업부 레벨", example = "HEADQUATERS")
    @NotBlank(message = "사업부 레벨은 필수입니다")
    @JsonProperty(required = true)
    private String tenantLevel3;

    /**
     * 부서 레벨 (4단계) - 사업부 내 부서
     * 예: DEPARTMENTS (부서), DIVISIONS (사업부)
     */
    @Schema(description = "부서 레벨", example = "DEPARTMENTS")
    @NotBlank(message = "부서 레벨은 필수입니다")
    @JsonProperty(required = true)
    private String tenantLevel4;

    /**
     * 팀 레벨 (5단계) - 부서 내 팀
     * 예: TEAMS (팀), UNITS (유닛)
     */
    @Schema(description = "팀 레벨", example = "TEAMS")
    @NotBlank(message = "팀 레벨은 필수입니다")
    @JsonProperty(required = true)
    private String tenantLevel5;

    /**
     * 청크 크기 - 한 번에 처리할 데이터 건수
     *
     * 🔧 성능 튜닝 가이드:
     * - 작은 값 (100-500): 메모리 사용량 적음, 안정성 높음, 속도 느림
     * - 중간 값 (500-1000): 균형잡힌 성능, 일반적 권장값
     * - 큰 값 (1000-5000): 빠른 처리, 메모리 사용량 많음, 장애 시 롤백 범위 큼
     *
     * 📊 테이블 크기별 권장값:
     * - 소규모 (1만건 이하): 100-500
     * - 중규모 (1만-100만건): 500-1000
     * - 대규모 (100만건 이상): 1000-2000
     *
     * ⚠️ 주의사항:
     * - 너무 큰 값 설정 시 OutOfMemory 오류 발생 가능
     * - 트랜잭션 타임아웃 고려 (큰 청크는 긴 트랜잭션)
     * - 네트워크 대역폭과 DB 성능에 따라 최적값 상이
     *
     * 💡 최적화 팁:
     * - 초기에는 작은 값으로 시작하여 점진적 증가
     * - 시스템 리소스 모니터링하며 조정
     * - 테이블별 특성 고려 (BLOB 데이터 포함 시 작게 설정)
     */
    @Min(value = 1, message = "청크 크기는 1 이상이어야 합니다")
    @Max(value = 10000, message = "청크 크기는 10000 이하여야 합니다")
    @Schema(description = "청크 크기 - 한 번에 처리할 데이터 건수 (성능에 직접적 영향)",
            example = "100",
            minimum = "1",
            maximum = "10000",
            defaultValue = "100")
    private Integer chunkSize = 200;

    // ========================================
    // 스케줄링 관련 필드
    // ========================================

    /**
     * Cron 표현식 - 스케줄 실행을 위한 시간 설정
     * - 형식: 초 분 시 일 월 요일
     * - 예: "0 0 2 * * ?" (매일 새벽 2시)
     * - 예: "0 30 1 * * MON-FRI" (평일 오전 1시 30분)
     * - null이면 즉시 실행
     */
    @Schema(description = "스케줄 실행을 위한 Cron 표현식 (선택사항)",
            example = "0 0 2 * * ?")
    @Pattern(regexp = "^$|^[0-9\\s\\*\\?\\-\\/,LW#]+$",
             message = "올바른 Cron 표현식 형식이 아닙니다")
    private String cronExpression;

    /**
     * 스케줄 작업 이름 - 관리 및 모니터링용
     * 예: daily-approval-migration, weekly-board-sync
     */
    @Schema(description = "스케줄 작업 이름 (선택사항)",
            example = "daily-sample-migration")
    @Size(max = 100, message = "스케줄 이름은 100자를 초과할 수 없습니다")
    private String scheduleName;

    /**
     * 스케줄 작업 설명 - 작업 목적 및 상세 정보
     */
    @Schema(description = "스케줄 작업 설명 (선택사항)",
            example = "Sample 테이블 일일 마이그레이션 작업")
    @Size(max = 500, message = "스케줄 설명은 500자를 초과할 수 없습니다")
    private String scheduleDescription;

    /**
     * 즉시 실행 여부
     * - 생략(null): cronExpression 이 있으면 스케줄 등록, 없으면 즉시 실행
     * - true: cronExpression 이 있어도 즉시 실행 (스케줄 주기 실행 내부 요청이 이 값을 쓴다)
     * - false: 스케줄 등록 (cronExpression 필수)
     * 예전 기본값 true 는 cronExpression 만 보내면 cron 을 무시하고 바로 실행하는 함정이 있었다
     */
    @Schema(description = "즉시 실행 여부. 생략하면 cronExpression 이 있을 때 스케줄 등록, 없으면 즉시 실행",
            example = "false")
    private Boolean executeImmediately;

    /**
     * 스케줄 시작 일시 - 스케줄 활성화 시작 시점
     * 이 시간 이전에는 스케줄이 실행되지 않음
     */
    @Schema(description = "스케줄 시작 일시 (선택사항)",
            example = "2024-12-01T00:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime scheduleStartTime;

    /**
     * 스케줄 종료 일시 - 스케줄 비활성화 시점
     * 이 시간 이후에는 스케줄이 실행되지 않음
     */
    @Schema(description = "스케줄 종료 일시 (선택사항)",
            example = "2024-12-31T23:59:59")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime scheduleEndTime;

    // ========================================
    // 테스트 관련 필드
    // ========================================

    /**
     * 테스트 모드 설정
     *
     * 🧪 테스트 모드별 동작:
     * - **NORMAL**: 실제 마이그레이션 실행 (운영 모드)
     * - **READER_TEST**: 소스 데이터 읽기만 테스트 (데이터 변경 없음)
     * - **PROCESSOR_TEST**: 데이터 변환 로직만 테스트 (변환 결과 로그 출력)
     * - **WRITER_TEST**: 타겟 DB 쓰기만 테스트 (실제 쓰기 없이 검증)
     * - **FULL_TEST**: 전체 파이프라인 테스트 (실제 데이터 변경 없음)
     *
     * 💡 사용 시나리오:
     * - **개발 단계**: READER_TEST로 소스 데이터 구조 확인
     * - **변환 로직 검증**: PROCESSOR_TEST로 변환 규칙 테스트
     * - **타겟 DB 연결 확인**: WRITER_TEST로 쓰기 권한 및 스키마 검증
     * - **전체 검증**: FULL_TEST로 end-to-end 테스트
     *
     * ⚠️ 주의사항:
     * - 테스트 모드에서는 실제 데이터 변경이 발생하지 않음
     * - NORMAL 모드만 실제 마이그레이션 수행
     * - 테스트 결과는 로그와 migration_logs 테이블에 기록
     */
    @Pattern(regexp = "(?i)NORMAL|READER_TEST|PROCESSOR_TEST|WRITER_TEST",
             message = "testMode 는 NORMAL, READER_TEST, PROCESSOR_TEST, WRITER_TEST 중 하나여야 합니다")
    @Schema(description = """
            테스트 모드 - 테스트하는 컴포넌트까지만 실행
            - NORMAL: 전량 이관 (등록자 MIG-DATA)
            - READER_TEST: Reader 만 (testMaxItems 기본 5, 최대 100). 적재 없음
            - PROCESSOR_TEST: Reader + Processor 변환 검증, 오류는 집계 후 계속 (기본 100). 적재 없음
            - WRITER_TEST: 실제 적재 최대 100건, 등록자 MIG-TEST (정리는 수작업, /ui/guide?name=manual-cleanup)""",
            example = "READER_TEST",
            allowableValues = {"NORMAL", "READER_TEST", "PROCESSOR_TEST", "WRITER_TEST"},
            defaultValue = "NORMAL")
    private String testMode;

    /**
     * 테스트 시 처리할 최대 데이터 건수
     *
     * 🔢 동작 방식:
     * - 테스트 모드에서만 적용 (NORMAL 모드에서는 무시)
     * - 지정된 건수만큼만 처리하여 빠른 테스트 가능
     * - 0 또는 null 설정 시 제한 없음
     *
     * 📊 권장 설정값:
     * - **개발/디버깅**: 5-10건 (상세 로그 확인용)
     * - **성능 테스트**: 100-1000건 (처리 속도 측정용)
     * - **전체 검증**: 10000건 이상 (실제 운영 환경 시뮬레이션)
     *
     * 💡 활용 팁:
     * - 초기 개발 시 작은 값으로 설정하여 빠른 피드백
     * - 데이터 변환 로직 검증 시 다양한 패턴 포함하도록 적절히 설정
     * - 성능 테스트 시 점진적으로 증가시켜 임계점 파악
     */
    @Min(value = 1, message = "테스트 최대 건수는 1 이상이어야 합니다")
    @Max(value = 100, message = "테스트 최대 건수는 100 이하여야 합니다 (상한)")
    @Schema(description = "테스트 모드에서 읽을 최대 건수. 미지정 시 READER_TEST 5, PROCESSOR_TEST/WRITER_TEST 100. 상한 100. NORMAL 에서는 무시",
            example = "5",
            minimum = "1",
            maximum = "100")
    private Integer testMaxItems;

    /**
     * 시간 윈도우 제어 활성화 여부
     *
     * true: application.yml의 run-window 설정에 따라 시간대별 실행 제어 적용
     * false: 시간 윈도우 제어 무시하고 항상 실행 허용 (기본값)
     *
     * 사용 예시:
     * - 업무시간(09:00-18:00) 중에는 배치 실행 금지
     * - 야간 유지보수 시간(02:00-04:00) 중에는 배치 실행 금지
     * - 주말에만 대용량 배치 실행 허용
     */
    @Schema(description = "시간 윈도우 제어 활성화 여부 - true시 설정된 시간대에 따라 실행 제어",
            example = "false",
            defaultValue = "false")
    private Boolean enableTimeWindow = false;

    // ========================================
    // 실행 전 확인 (상품화 P0) - 제품은 TRUNCATE/DELETE 를 하지 않는다
    // ========================================

    @Schema(description = """
            타겟에 기존 데이터가 있어도 적재할지 (기본 false).
            false: 기존 데이터가 있으면 경고 후 적재 중단 → 수작업 정리 후 재실행
            true : 경고를 기록하고 적재 (쿼리가 UPSERT·INSERT IGNORE 방식일 때 사용).
            미지정 시 Strategy 의 테이블 기본값(TableOptions.ignoreExistingData)을 따른다""",
            example = "false")
    private Boolean ignoreExistingData;

    @Schema(description = "NORMAL 실행 시 자동 실행 전 점검을 끌지 (기본 false = 점검함)", example = "false")
    private Boolean skipPrecheck;

    @Schema(description = "실행 전 점검의 차단 항목(NOT NULL·타입·길이 오류)을 무시하고 적재할지 (기본 false, 권장하지 않음)", example = "false")
    private Boolean ignorePrecheckErrors;

    // ========================================
    // 시퀀스 채번 (core/sequence/README.md). 미지정 시 Strategy 의 TableOptions 값
    // ========================================

    @Schema(description = "시퀀스를 채번해 넣을 Target 컬럼 목록 (예: [\"TEMPLATE_SEQ\"]). 미지정 시 Strategy 의 TableOptions.sequenceColumns",
            example = "[\"TEMPLATE_SEQ\"]")
    private java.util.List<String> sequenceColumns;

    @Schema(description = "시퀀스 정책 버전 (예: v1). 미지정 시 TableOptions.sequencePolicy → georu.sequence.policy", example = "v1")
    @Size(max = 16, message = "sequencePolicy 는 16자를 초과할 수 없습니다")
    private String sequencePolicy;

    @Schema(description = "Step 종료 후 시퀀스 관리 테이블을 대상 컬럼 MAX 로 동기화할지. 미지정 시 TableOptions.sequenceSync", example = "false")
    private Boolean sequenceSync;

    @Schema(description = "묶음 실행 ID. 실행 목록·시나리오가 넣는다. 있으면 테이블별 완료 알림 대신 끝날 때 요약 1회 (경고·실패는 즉시)")
    @Size(max = 64, message = "batchId 는 64자를 초과할 수 없습니다")
    private String batchId;

    /** @deprecated 제품은 데이터를 지우지 않는다. true 로 보내면 요청을 거부한다 (수작업 정리 안내) */
    @Deprecated
    @Schema(description = "더 이상 지원하지 않음. true 면 요청 거부 (제품은 데이터를 지우지 않음)", deprecated = true)
    private Boolean truncateTarget;

    /** @deprecated 적재 방식은 Mapper 쿼리로 정한다. 값이 와도 무시하고 안내만 한다 */
    @Deprecated
    @Schema(description = "더 이상 쓰지 않음. 값이 와도 무시 (적재 방식은 Mapper 쿼리로 결정)", deprecated = true)
    private String upsertMode;

    // ========================================
    // 엑셀 이관 (core/excel). 안내: resources/guides/excel-load.md
    // ========================================

    @Schema(description = "소스 종류. 비우면 DB(플러그인 Reader). EXCEL 이면 excelFileId 의 업로드 파일(TOBE 컬럼 헤더)을 읽어 같은 Target 매퍼로 적재",
            example = "EXCEL")
    @Size(max = 16, message = "source 는 16자를 초과할 수 없습니다")
    private String source;

    @Schema(description = "엑셀 이관 파일 ID (source=EXCEL 일 때 필수. POST /api/excel-load/files 로 업로드)", example = "XF-20261021153000-a1b2")
    @Size(max = 40, message = "excelFileId 는 40자를 초과할 수 없습니다")
    private String excelFileId;

    /**
     * 빠진 행 채우기. 직접 넣지 않고 {@code POST /api/migrate/jobs/{migrationId}/fill-missing} 이 원 실행의 요청으로 만든다.
     * true 면 타겟에 이미 있는 키를 거르고 없는 행만 적재하며, 실행 전 확인의 "기존 데이터" 차단 대신 원 실행 확인을 한다
     */
    @Schema(hidden = true)
    private Boolean fillMissing;

    /** 빠진 행 채우기의 원 실행 ID (migration_jobs.parent_migration_id) */
    @Schema(hidden = true)
    private String parentMigrationId;

    @Schema(description = "엑셀 이관 오류 행 처리. STOP(기본): 검증 차단이 있으면 실행하지 않고 실행 중 오류는 Job 실패 / SKIP: 오류 행만 건너뛰고 적재, 목록 기록",
            example = "STOP")
    @Size(max = 8, message = "onRowError 는 8자를 초과할 수 없습니다")
    private String onRowError;

    /** source=EXCEL 인지 */
    @Schema(description = "엑셀 소스 여부 (source 가 EXCEL 이면 true)", example = "false")
    public boolean isExcelSource() {
        return source != null && "EXCEL".equalsIgnoreCase(source.trim());
    }

    // ========================================
    // 데이터 범위 필터링 필드
    // ========================================

    /**
     * 마이그레이션 시작 일자
     *
     * 🗓️ 기능 설명:
     * - 이 날짜 이후의 데이터만 마이그레이션 대상으로 처리
     * - 증분 마이그레이션이나 특정 기간 데이터 이관 시 활용
     * - 날짜 기준 컬럼은 테이블별로 다를 수 있음 (date_created, date_modified 등)
     *
     * 📅 지원 형식:
     * - "YYYY-MM-DD" (예: "2025-01-01")
     * - "YYYY-MM-DD HH:mm:ss" (예: "2025-01-01 00:00:00")
     * - ISO 8601 형식 (예: "2025-01-01T00:00:00")
     *
     * 💡 사용 시나리오:
     * - **증분 마이그레이션**: 마지막 실행 이후 데이터만 처리
     * - **기간별 이관**: 특정 연도/월 데이터만 선별 이관
     * - **테스트 환경**: 최근 데이터만으로 빠른 테스트
     *
     * ⚠️ 주의사항:
     * - null 설정 시 날짜 필터링 없이 전체 데이터 처리
     * - 테이블에 적절한 날짜 컬럼이 없으면 무시됨
     * - 시간대는 시스템 기본 시간대 적용
     */
    @Schema(description = "마이그레이션 시작 일자 yyyyMMdd (포함) - 소스 SQL 이 TO_DATE(#{startDate}, 'YYYYMMDD') 로 받는다. "
            + "날짜 분할(enableDateDivision=true)이면 2025-01-01 같은 형식도 yyyyMMdd 로 맞춘다 (선택사항)",
            example = "20250101")
    private String startDate;

    /**
     * 마이그레이션 종료 일자
     *
     * 🗓️ 기능 설명:
     * - 이 날짜 이전의 데이터만 마이그레이션 대상으로 처리
     * - startDate와 함께 사용하여 특정 기간 데이터만 선별 이관
     * - 과거 데이터 아카이빙이나 연도별 분할 이관 시 활용
     *
     * 📅 지원 형식:
     * - "YYYY-MM-DD" (예: "2025-12-31")
     * - "YYYY-MM-DD HH:mm:ss" (예: "2025-12-31 23:59:59")
     * - ISO 8601 형식 (예: "2025-12-31T23:59:59")
     *
     * 💡 사용 시나리오:
     * - **연도별 이관**: 2024년 데이터만 별도 처리
     * - **아카이빙**: 특정 기간 이전 데이터를 별도 저장소로 이관
     * - **성능 최적화**: 대용량 테이블을 기간별로 분할 처리
     *
     * ⚠️ 주의사항:
     * - null 설정 시 날짜 필터링 없이 전체 데이터 처리
     * - startDate보다 이전 날짜 설정 시 데이터가 없을 수 있음
     * - 시간 부분 생략 시 해당 날짜의 23:59:59로 처리
     */
    @Schema(description = "마이그레이션 종료 일자 yyyyMMdd (포함) - 소스 SQL 이 < TO_DATE(#{endDate}, 'YYYYMMDD') + 1 로 받는다. "
            + "날짜 분할이면 다른 형식도 yyyyMMdd 로 맞춘다 (선택사항)",
            example = "20251231")
    private String endDate;

    // ========================================
    // 날짜 분할 설정
    // ========================================

    /**
     * 날짜 분할 적용 여부
     *
     * 🔄 기능 설명:
     * - true: startDate~endDate 범위를 dateDivideUnit 단위로 분할하여 순차 실행
     * - false: 기존 방식 (통합 실행)
     *
     * 💡 사용 시나리오:
     * - 대용량 데이터 마이그레이션 시 메모리 부담 감소
     * - 장기간 데이터를 월별/주별로 나누어 안정적 처리
     * - 실패 시 해당 범위만 재실행 가능
     *
     * ⚠️ 주의사항:
     * - startDate와 endDate가 모두 설정되어야 함
     * - 분할 실행 시 각 범위는 독립적인 Job으로 실행됨
     */
    @Schema(description = "날짜 분할 적용 여부 (true: 분할 실행, false: 통합 실행)",
            example = "false",
            defaultValue = "false")
    private Boolean enableDateDivision = false;

    /**
     * 날짜 분할 단위
     *
     * 📅 지원 형식:
     * - "1d" ~ "99d": 일 단위 (예: "7d" = 7일 단위)
     * - "1w" ~ "99w": 주 단위 (예: "2w" = 2주 단위)
     * - "1m" ~ "99m": 월 단위 (예: "1m" = 1개월 단위) - 기본값
     * - "1y" ~ "99y": 년 단위 (예: "1y" = 1년 단위)
     *
     * 💡 권장 설정:
     * - 소규모 데이터: "1m" (월별)
     * - 대규모 데이터: "1w" 또는 "7d" (주별/일별)
     * - 연간 아카이빙: "1y" (년별)
     *
     * ⚠️ 주의사항:
     * - enableDateDivision=true일 때만 적용
     * - null이면 기본값 "1m" (월별) 사용
     */
    @Schema(description = "날짜 분할 단위 (예: 1d, 2w, 1m, 1y)",
            example = "1m",
            defaultValue = "1m")
    private String dateDivideUnit;

    /**
     * 날짜 분할 구간(Job)이 실패했을 때
     *
     * - STOP (기본): 남은 구간을 실행하지 않음 (미실행으로 기록). 실패한 구간이 빈 채로 다음 구간이 쌓이지 않게 한다
     * - CONTINUE: 남은 구간을 계속 실행
     *
     * 결과는 전체 성공 / 부분 성공 / 실패로 판정하고 성공·실패·미실행 횟수를 남긴다 (GET /api/migrate/groups/{groupId})
     */
    @Schema(description = "날짜 분할 구간 실패 시 STOP(기본, 남은 구간 미실행) / CONTINUE(남은 구간 계속)",
            example = "STOP", allowableValues = {"STOP", "CONTINUE"})
    private String onUnitFailure;

    // ========================================
    // 커스텀 속성 필드 (각 테이블별 특화 설정)
    // ========================================

    /**
     * 커스텀 속성 1 - 테이블별 특화 설정
     * 예: status=ACTIVE (활성 상태 데이터만 마이그레이션)
     * 예: priority=HIGH (우선순위 높은 데이터만)
     */
    @Schema(description = "커스텀 속성 1 - 테이블별 특화 설정 (예: 필터링 조건, 변환 규칙 등)",
            example = "status=ACTIVE")
    @Size(max = 500, message = "attribute1은 500자를 초과할 수 없습니다")
    private String attribute1;

    /**
     * 커스텀 속성 2 - 테이블별 특화 설정
     * 예: dateRange=2024-01-01,2024-12-31 (날짜 범위 필터)
     * 예: batchSize=1000 (배치 처리 크기)
     */
    @Schema(description = "커스텀 속성 2 - 테이블별 특화 설정 (예: 날짜 범위, 배치 크기 등)",
            example = "dateRange=2024-01-01,2024-12-31")
    @Size(max = 500, message = "attribute2는 500자를 초과할 수 없습니다")
    private String attribute2;

    /**
     * 커스텀 속성 3 - 테이블별 특화 설정
     * 예: transformMode=FULL (전체 변환)
     * 예: encoding=UTF-8 (문자 인코딩)
     */
    @Schema(description = "커스텀 속성 3 - 테이블별 특화 설정 (예: 데이터 변환 옵션 등)",
            example = "transformMode=FULL")
    @Size(max = 500, message = "attribute3은 500자를 초과할 수 없습니다")
    private String attribute3;

    /**
     * 커스텀 속성 4 - 테이블별 특화 설정
     * 예: performanceMode=HIGH (고성능 모드)
     * 예: parallelThreads=4 (병렬 처리 스레드 수)
     */
    @Schema(description = "커스텀 속성 4 - 테이블별 특화 설정 (예: 성능 튜닝 옵션 등)",
            example = "performanceMode=HIGH")
    @Size(max = 500, message = "attribute4는 500자를 초과할 수 없습니다")
    private String attribute4;

    /**
     * 커스텀 속성 5 - 테이블별 특화 설정
     * 예: logLevel=DEBUG (로그 레벨)
     * 예: notifyOnComplete=true (완료 시 알림)
     */
    @Schema(description = "커스텀 속성 5 - 테이블별 특화 설정 (예: 로깅 레벨, 알림 설정 등)",
            example = "logLevel=DEBUG")
    @Size(max = 500, message = "attribute5는 500자를 초과할 수 없습니다")
    private String attribute5;

    /** 커스텀 속성 6 */
    @Schema(description = "커스텀 속성 6")
    @Size(max = 500, message = "attribute6은 500자를 초과할 수 없습니다")
    private String attribute6;

    /** 커스텀 속성 7 */
    @Schema(description = "커스텀 속성 7")
    @Size(max = 500, message = "attribute7은 500자를 초과할 수 없습니다")
    private String attribute7;

    /** 커스텀 속성 8 */
    @Schema(description = "커스텀 속성 8")
    @Size(max = 500, message = "attribute8은 500자를 초과할 수 없습니다")
    private String attribute8;

    /** 커스텀 속성 9 */
    @Schema(description = "커스텀 속성 9")
    @Size(max = 500, message = "attribute9는 500자를 초과할 수 없습니다")
    private String attribute9;

    /** 커스텀 속성 10 */
    @Schema(description = "커스텀 속성 10")
    @Size(max = 500, message = "attribute10은 500자를 초과할 수 없습니다")
    private String attribute10;

    /** 커스텀 속성 11 */
    @Schema(description = "커스텀 속성 11")
    @Size(max = 500, message = "attribute11은 500자를 초과할 수 없습니다")
    private String attribute11;

    /** 커스텀 속성 12 */
    @Schema(description = "커스텀 속성 12")
    @Size(max = 500, message = "attribute12는 500자를 초과할 수 없습니다")
    private String attribute12;

    /** 커스텀 속성 13 */
    @Schema(description = "커스텀 속성 13")
    @Size(max = 500, message = "attribute13은 500자를 초과할 수 없습니다")
    private String attribute13;

    /** 커스텀 속성 14 */
    @Schema(description = "커스텀 속성 14")
    @Size(max = 500, message = "attribute14는 500자를 초과할 수 없습니다")
    private String attribute14;

    /** 커스텀 속성 15 */
    @Schema(description = "커스텀 속성 15")
    @Size(max = 500, message = "attribute15는 500자를 초과할 수 없습니다")
    private String attribute15;

    /** 커스텀 속성 16 */
    @Schema(description = "커스텀 속성 16")
    @Size(max = 500, message = "attribute16은 500자를 초과할 수 없습니다")
    private String attribute16;

    /** 커스텀 속성 17 */
    @Schema(description = "커스텀 속성 17")
    @Size(max = 500, message = "attribute17은 500자를 초과할 수 없습니다")
    private String attribute17;

    /** 커스텀 속성 18 */
    @Schema(description = "커스텀 속성 18")
    @Size(max = 500, message = "attribute18은 500자를 초과할 수 없습니다")
    private String attribute18;

    /** 커스텀 속성 19 */
    @Schema(description = "커스텀 속성 19")
    @Size(max = 500, message = "attribute19는 500자를 초과할 수 없습니다")
    private String attribute19;

    /** 커스텀 속성 20 */
    @Schema(description = "커스텀 속성 20")
    @Size(max = 500, message = "attribute20은 500자를 초과할 수 없습니다")
    private String attribute20;

    /** 커스텀 속성 21 */
    @Schema(description = "커스텀 속성 21")
    @Size(max = 500, message = "attribute21은 500자를 초과할 수 없습니다")
    private String attribute21;

    /** 커스텀 속성 22 */
    @Schema(description = "커스텀 속성 22")
    @Size(max = 500, message = "attribute22는 500자를 초과할 수 없습니다")
    private String attribute22;

    /** 커스텀 속성 23 */
    @Schema(description = "커스텀 속성 23")
    @Size(max = 500, message = "attribute23은 500자를 초과할 수 없습니다")
    private String attribute23;

    /** 커스텀 속성 24 */
    @Schema(description = "커스텀 속성 24")
    @Size(max = 500, message = "attribute24는 500자를 초과할 수 없습니다")
    private String attribute24;

    /** 커스텀 속성 25 */
    @Schema(description = "커스텀 속성 25")
    @Size(max = 500, message = "attribute25는 500자를 초과할 수 없습니다")
    private String attribute25;

    /**
     * 전체 테넌트 ID 생성 (점으로 구분)
     */
    @Schema(description = "전체 테넌트 ID (tenantLevel1~5 를 점으로 연결). 하나라도 비면 null. 입력하면 점으로 나눠 각 레벨에 설정",
            example = "GROUP.AA.HEADQUATERS.DEPARTMENTS.TEAMS")
    public String getFullTenantId() {
        if (tenantLevel1 == null || tenantLevel2 == null || tenantLevel3 == null ||
            tenantLevel4 == null || tenantLevel5 == null) {
            return null;
        }
        return String.join(".", tenantLevel1, tenantLevel2, tenantLevel3, tenantLevel4, tenantLevel5);
    }

    /**
     * 전체 테넌트 ID를 개별 레벨로 분리 설정
     */
    public void setFullTenantId(String fullTenantId) {
        if (fullTenantId == null || fullTenantId.trim().isEmpty()) {
            return;
        }

        String[] levels = fullTenantId.split("\\.");
        if (levels.length >= 1) tenantLevel1 = levels[0];
        if (levels.length >= 2) tenantLevel2 = levels[1];
        if (levels.length >= 3) tenantLevel3 = levels[2];
        if (levels.length >= 4) tenantLevel4 = levels[3];
        if (levels.length >= 5) tenantLevel5 = levels[4];
    }

    /**
     * 스케줄 실행 여부 확인
     */
    @Schema(description = "스케줄 실행 여부 (cronExpression 이 있고 executeImmediately 가 true 가 아니면 true)", example = "false")
    public boolean isScheduledExecution() {
        return StringUtils.hasText(cronExpression) && !Boolean.TRUE.equals(executeImmediately);
    }

    /**
     * Attribute 배열 변환 (DynamicBeanFactory 전달용)
     *
     * - 항상 25개 배열 반환 (고정 크기)
     * - 없는 값은 null
     * - index 0 = attribute1, index 24 = attribute25
     *
     * @return String[25] 배열 (null 값 포함 가능)
     */
    public String[] toAttributeArray() {
        return new String[] {
            attribute1,   // [0]
            attribute2,   // [1]
            attribute3,   // [2]
            attribute4,   // [3]
            attribute5,   // [4]
            attribute6,   // [5]
            attribute7,   // [6]
            attribute8,   // [7]
            attribute9,   // [8]
            attribute10,  // [9]
            attribute11,  // [10]
            attribute12,  // [11]
            attribute13,  // [12]
            attribute14,  // [13]
            attribute15,  // [14]
            attribute16,  // [15]
            attribute17,  // [16]
            attribute18,  // [17]
            attribute19,  // [18]
            attribute20,  // [19]
            attribute21,  // [20]
            attribute22,  // [21]
            attribute23,  // [22]
            attribute24,  // [23]
            attribute25   // [24]
        };
    }

    /**
     * 다른 요청에서 attribute1~25를 복사
     *
     * @param source 원본 요청
     */
    public void copyAttributesFrom(TableMigrationRequest source) {
        if (source == null) return;

        this.attribute1 = source.getAttribute1();
        this.attribute2 = source.getAttribute2();
        this.attribute3 = source.getAttribute3();
        this.attribute4 = source.getAttribute4();
        this.attribute5 = source.getAttribute5();
        this.attribute6 = source.getAttribute6();
        this.attribute7 = source.getAttribute7();
        this.attribute8 = source.getAttribute8();
        this.attribute9 = source.getAttribute9();
        this.attribute10 = source.getAttribute10();
        this.attribute11 = source.getAttribute11();
        this.attribute12 = source.getAttribute12();
        this.attribute13 = source.getAttribute13();
        this.attribute14 = source.getAttribute14();
        this.attribute15 = source.getAttribute15();
        this.attribute16 = source.getAttribute16();
        this.attribute17 = source.getAttribute17();
        this.attribute18 = source.getAttribute18();
        this.attribute19 = source.getAttribute19();
        this.attribute20 = source.getAttribute20();
        this.attribute21 = source.getAttribute21();
        this.attribute22 = source.getAttribute22();
        this.attribute23 = source.getAttribute23();
        this.attribute24 = source.getAttribute24();
        this.attribute25 = source.getAttribute25();
    }

    /**
     * 다른 요청에서 테넌트 정보를 복사
     *
     * @param source 원본 요청
     */
    public void copyTenantInfoFrom(TableMigrationRequest source) {
        if (source == null) return;

        this.tenantLevel1 = source.getTenantLevel1();
        this.tenantLevel2 = source.getTenantLevel2();
        this.tenantLevel3 = source.getTenantLevel3();
        this.tenantLevel4 = source.getTenantLevel4();
        this.tenantLevel5 = source.getTenantLevel5();
    }

    /**
     * 다른 요청에서 기본 정보를 복사 (플러그인, 테이블, 청크 크기 등)
     *
     * @param source 원본 요청
     */
    public void copyBasicInfoFrom(TableMigrationRequest source) {
        if (source == null) return;

        this.pluginName = source.getPluginName();
        this.tableName = source.getTableName();
        this.chunkSize = source.getChunkSize();
        this.testMode = source.getTestMode();
        this.testMaxItems = source.getTestMaxItems();
    }
}
