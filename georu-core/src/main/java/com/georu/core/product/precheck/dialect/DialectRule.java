package com.georu.core.product.precheck.dialect;

import com.georu.core.product.precheck.ColumnMeta;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * 실행 전 점검의 DB 별 보정 규칙.
 *
 * <p>공통 점검(NOT NULL, 숫자 범위·정밀도, 타입 분류, PK)은 JDBC 표준 {@code DatabaseMetaData} 로 모든 DB 에서 한다.
 * DB 마다 다른 부분(문자 길이 단위, 날짜 범위, 빈 문자열, 표준 정보에 없는 타입 속성, 메타 조회 기준)만 여기서 처리한다.</p>
 *
 * <p>새 DB 를 추가할 때는 이 인터페이스를 구현한 {@code @Component} 클래스 하나만 만들면 된다.
 * 모르는 항목은 억지로 채우지 말고 UNKNOWN 을 돌려준다 → 해당 점검은 "판단 불가(경고)" 가 된다.</p>
 */
public interface DialectRule {

    /** 이 규칙이 담당하는 DB 인지 ({@code DatabaseMetaData.getDatabaseProductName()} 값으로 판단) */
    boolean supports(String databaseProductName);

    /** 규칙 이름 (보고서 표시용) */
    String name();

    /** 메타 조회에 쓸 카탈로그 / 스키마 / 테이블명 결정 (대소문자, 시노님 처리 포함) */
    TableRef resolveTable(Connection conn, String tableName) throws SQLException;

    /** 문자 컬럼의 최대 길이와 단위. 모르면 {@link LengthLimit#UNKNOWN} */
    LengthLimit lengthLimit(TableRef table, ColumnMeta column, Connection conn) throws SQLException;

    /** 값의 길이를 이 DB 기준으로 측정 (문자 수 또는 바이트 수) */
    default int measureLength(String value, LengthLimit limit) {
        if (limit.unit() == LengthLimit.Unit.BYTE) {
            return value.getBytes(limit.charset()).length;
        }
        return value.codePointCount(0, value.length());
    }

    /** 날짜 컬럼이 허용하는 범위. 모르면 {@link DateRange#UNKNOWN} */
    DateRange dateRange(ColumnMeta column);

    /** NOT NULL 판단 시 null 로 볼 값인지 (Oracle: 빈 문자열도 null) */
    default boolean isNullEquivalent(Object value) {
        return value == null;
    }

    /** 표준 정보로 알 수 없는 추가 점검 (UNSIGNED 음수 등). 없으면 빈 목록. 반환값은 사유 문장 */
    default List<String> extraChecks(ColumnMeta column, Object value) {
        return List.of();
    }

    /** 자릿수 없는 숫자 컬럼처럼 범위 점검을 하지 말아야 하는 경우 true (Oracle NUMBER 등) */
    default boolean skipNumericRange(ColumnMeta column) {
        return column.getSize() <= 0;
    }
}
