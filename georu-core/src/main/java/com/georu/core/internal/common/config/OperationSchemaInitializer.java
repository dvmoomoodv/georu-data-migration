package com.georu.core.internal.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * 운영 저장소 스키마 초기화
 *
 * georu.operation.schema-init
 *  - auto  : 테이블이 없을 때만 벤더별 DDL 실행 (기본값, 기존 테이블이 있으면 아무것도 하지 않음)
 *  - never : 실행하지 않음 (DBA 가 DDL 관리)
 *
 * 실행 스크립트
 *  - Spring Batch 메타 : org/springframework/batch/core/schema-{vendor}.sql (BATCH_JOB_INSTANCE 없을 때)
 *  - 새 운영 저장소     : operation/schema/georu-operation-{vendor}.sql (MIGRATION_JOBS 없을 때, V2~V13 을 모두 담은 최신 전체 DDL. 아래 버전 파일은 건너뛴다)
 *  - 이미 쓰던 저장소   : operation/schema/old/ 의 버전 파일 중 빠진 것만 순서대로 (테이블 · 열 유무로 판단)
 *  - (old) 기본 테이블  : operation/schema/old/operation-{vendor}.sql
 *  - V2 추가 테이블     : operation/schema/old/V2__product_p0-{vendor}.sql (MIGRATION_JOB_EXT 없을 때)
 *  - V3 시스템 지표     : operation/schema/old/V3__system_metrics-{vendor}.sql (MIGRATION_SYSTEM_METRICS 없을 때)
 *  - V4 실행 그룹       : operation/schema/old/V4__exec_group-{vendor}.sql (MIGRATION_EXEC_GROUP 없을 때)
 *  - V5 사용자 시나리오 : operation/schema/old/V5__scenario_def-{vendor}.sql (MIGRATION_SCENARIO_DEF 없을 때)
 *  - V6 시나리오 이력   : operation/schema/old/V6__scenario_history-{vendor}.sql (MIGRATION_SCENARIO_DEF_HISTORY 없을 때)
 *  - V7 엑셀 파일 대장  : operation/schema/old/V7__excel_file-{vendor}.sql (MIGRATION_EXCEL_FILE 없을 때)
 *  - V8 cron 스케줄     : operation/schema/old/V8__schedule-{vendor}.sql (MIGRATION_SCHEDULE 없을 때)
 *  - V9 설계 도구 기록  : operation/schema/old/V9__gen_run-{vendor}.sql (MIGRATION_GEN_RUN 없을 때)
 *  - V10 기록에 설정    : operation/schema/old/V10__gen_run_settings-{vendor}.sql (MIGRATION_GEN_RUN.SETTINGS_JSON 열 없을 때)
 *  - V11 로그인 계정    : operation/schema/old/V11__user-{vendor}.sql (MIGRATION_USER 없을 때)
 *  - V12 진행 체크리스트: operation/schema/old/V12__checklist-{vendor}.sql (MIGRATION_CHECKLIST_ITEM 없을 때)
 *  - V13 권한          : operation/schema/old/V13__roles-{vendor}.sql (MIGRATION_USER.ROLE_ID 열 없을 때. 기본 권한 행은 앱이 넣는다)
 *    버전 파일은 번호 순서대로 추가한다. schema-init=never 환경은 DBA 가 같은 파일을 순서대로 실행한다.
 *    테이블을 만드는 버전은 테이블 유무로, 열을 더하는 버전은 열 유무로 실행 여부를 정한다.
 *
 * 지원 벤더: H2(내장 기본), MySQL / MariaDB(원격)
 */
@Slf4j
public class OperationSchemaInitializer implements InitializingBean {

    private final DataSource dataSource;
    private final String mode;

    public OperationSchemaInitializer(DataSource dataSource, String mode) {
        this.dataSource = dataSource;
        this.mode = mode == null ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if ("never".equals(mode)) {
            log.info("[Operation] schema-init=never - 운영 스키마 초기화 생략");
            return;
        }
        if (!"auto".equals(mode)) {
            throw new IllegalStateException("georu.operation.schema-init 은 auto | never 만 허용: " + mode);
        }

        String vendor;
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            vendor = resolveVendor(md.getDatabaseProductName());
            log.info("[Operation] 운영 저장소: {} ({}) / vendor={}", md.getDatabaseProductName(), md.getURL(), vendor);
        }
        State before = detect();
        if (!before.hasBatch) {
            runScript("org/springframework/batch/core/schema-" + vendor + ".sql");
        }
        if (!before.hasOperation) {
            // 새 저장소: 전체 DDL 하나 (V2~V13 포함, 모두 IF NOT EXISTS). 그 뒤 다시 살펴 그래도 빠진 것만 버전 파일로 채운다
            runScript("operation/schema/georu-operation-" + vendor + ".sql");
        }
        State s = before.hasOperation ? before : detect();
        if (!s.hasV2) runScript("operation/schema/old/V2__product_p0-" + vendor + ".sql");
        if (!s.hasV3) runScript("operation/schema/old/V3__system_metrics-" + vendor + ".sql");
        if (!s.hasV4) runScript("operation/schema/old/V4__exec_group-" + vendor + ".sql");
        if (!s.hasV5) runScript("operation/schema/old/V5__scenario_def-" + vendor + ".sql");
        if (!s.hasV6) runScript("operation/schema/old/V6__scenario_history-" + vendor + ".sql");
        if (!s.hasV7) runScript("operation/schema/old/V7__excel_file-" + vendor + ".sql");
        if (!s.hasV8) runScript("operation/schema/old/V8__schedule-" + vendor + ".sql");
        if (!s.hasV9) runScript("operation/schema/old/V9__gen_run-" + vendor + ".sql");
        if (!s.hasV10) runScript("operation/schema/old/V10__gen_run_settings-" + vendor + ".sql");
        if (!s.hasV11) runScript("operation/schema/old/V11__user-" + vendor + ".sql");
        if (!s.hasV12) runScript("operation/schema/old/V12__checklist-" + vendor + ".sql");
        if (!s.hasV13) runScript("operation/schema/old/V13__roles-" + vendor + ".sql");
        log.info("[Operation] 운영 스키마 확인 완료 - batch:{}, operation:{}{}", before.hasBatch ? "기존" : "생성",
                before.hasOperation ? "기존" : "생성(전체 DDL)",
                before.hasOperation ? String.format(", V2:%s, V3:%s, V4:%s, V5:%s, V6:%s, V7:%s, V8:%s, V9:%s, V10:%s, V11:%s, V12:%s, V13:%s",
                        w(s.hasV2), w(s.hasV3), w(s.hasV4), w(s.hasV5), w(s.hasV6), w(s.hasV7), w(s.hasV8), w(s.hasV9), w(s.hasV10), w(s.hasV11), w(s.hasV12), w(s.hasV13)) : "");
    }

    private static String w(boolean has) {
        return has ? "기존" : "생성";
    }

    /** 테이블 · 열 유무로 본 현재 상태 */
    private record State(boolean hasBatch, boolean hasOperation, boolean hasV2, boolean hasV3, boolean hasV4, boolean hasV5, boolean hasV6,
                         boolean hasV7, boolean hasV8, boolean hasV9, boolean hasV10, boolean hasV11, boolean hasV12, boolean hasV13) {
    }

    private State detect() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            boolean v9 = tableExists(conn, md, "MIGRATION_GEN_RUN");
            boolean v11 = tableExists(conn, md, "MIGRATION_USER");
            return new State(
                    tableExists(conn, md, "BATCH_JOB_INSTANCE"),
                    tableExists(conn, md, "MIGRATION_JOBS"),
                    tableExists(conn, md, "MIGRATION_JOB_EXT"),
                    tableExists(conn, md, "MIGRATION_SYSTEM_METRICS"),
                    tableExists(conn, md, "MIGRATION_EXEC_GROUP"),
                    tableExists(conn, md, "MIGRATION_SCENARIO_DEF"),
                    tableExists(conn, md, "MIGRATION_SCENARIO_DEF_HISTORY"),
                    tableExists(conn, md, "MIGRATION_EXCEL_FILE"),
                    tableExists(conn, md, "MIGRATION_SCHEDULE"),
                    v9,
                    v9 && columnExists(conn, md, "MIGRATION_GEN_RUN", "SETTINGS_JSON"),   // V9 가 없으면 V9 → V10 순서로 둘 다
                    v11,
                    tableExists(conn, md, "MIGRATION_CHECKLIST_ITEM"),
                    v11 && columnExists(conn, md, "MIGRATION_USER", "ROLE_ID"));   // V11 이 없으면 V11 → V13 순서로 둘 다
        }
    }

    static String resolveVendor(String productName) {
        String p = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        if (p.contains("h2")) {
            return "h2";
        }
        if (p.contains("mysql") || p.contains("mariadb")) {
            return "mysql";
        }
        throw new IllegalStateException("운영 저장소 미지원 DB: " + productName
                + " (지원: H2, MySQL/MariaDB). 다른 DB 는 georu.operation.schema-init=never 로 두고 DDL·SQL 호환을 별도 확보해야 함");
    }

    private boolean tableExists(Connection conn, DatabaseMetaData md, String table) throws SQLException {
        String catalog = conn.getCatalog();
        for (String name : new String[]{table, table.toLowerCase(Locale.ROOT)}) {
            try (ResultSet rs = md.getTables(catalog, null, name, new String[]{"TABLE", "BASE TABLE"})) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 열 유무 (테이블 · 열 이름은 대문자 · 소문자 둘 다 본다). 열을 더하는 버전 스크립트의 실행 여부에 쓴다 */
    private boolean columnExists(Connection conn, DatabaseMetaData md, String table, String column) throws SQLException {
        String catalog = conn.getCatalog();
        for (String t : new String[]{table, table.toLowerCase(Locale.ROOT)}) {
            for (String c : new String[]{column, column.toLowerCase(Locale.ROOT)}) {
                try (ResultSet rs = md.getColumns(catalog, null, t, c)) {
                    if (rs.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void runScript(String path) {
        log.info("[Operation] 스키마 생성: {}", path);
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource(path));
        populator.setSqlScriptEncoding("UTF-8");
        DatabasePopulatorUtils.execute(populator, dataSource);
    }
}
