package com.georu.core.product.precheck;

import lombok.Builder;
import lombok.Value;

import java.sql.Types;

/** {@code DatabaseMetaData.getColumns} 결과 1행 (JDBC 표준 항목만) */
@Value
@Builder
public class ColumnMeta {
    String name;
    int dataType;            // java.sql.Types
    String typeName;         // DB 고유 타입명 (UNSIGNED 등은 여기에만 나온다)
    int size;                // COLUMN_SIZE (문자 길이 또는 숫자 자릿수)
    int decimalDigits;       // DECIMAL_DIGITS (소수 자릿수)
    boolean nullable;        // NULLABLE != columnNoNulls
    String defaultValue;     // COLUMN_DEF
    boolean autoIncrement;   // IS_AUTOINCREMENT
    int charOctetLength;     // CHAR_OCTET_LENGTH (최대 바이트 수, 0 = 정보 없음)

    public boolean hasDefault() {
        return defaultValue != null && !defaultValue.isBlank() && !"NULL".equalsIgnoreCase(defaultValue.trim());
    }

    public boolean isCharacter() {
        return switch (dataType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                 Types.CLOB, Types.NCLOB -> true;
            default -> false;
        };
    }

    public boolean isInteger() {
        return switch (dataType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> true;
            default -> false;
        };
    }

    public boolean isDecimal() {
        return dataType == Types.DECIMAL || dataType == Types.NUMERIC;
    }

    public boolean isFloating() {
        return dataType == Types.FLOAT || dataType == Types.REAL || dataType == Types.DOUBLE;
    }

    public boolean isBoolean() {
        return dataType == Types.BIT || dataType == Types.BOOLEAN;
    }

    public boolean isTemporal() {
        return switch (dataType) {
            case Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE -> true;
            default -> false;
        };
    }

    public boolean isBinary() {
        return switch (dataType) {
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> true;
            default -> false;
        };
    }

    /** 화면 표시용 정의 (예: VARCHAR(50) NOT NULL) */
    public String describe() {
        StringBuilder sb = new StringBuilder(typeName == null ? String.valueOf(dataType) : typeName);
        if (isCharacter() || isDecimal() || isBinary()) {
            sb.append('(').append(size);
            if (isDecimal()) sb.append(',').append(decimalDigits);
            sb.append(')');
        }
        if (!nullable) sb.append(" NOT NULL");
        if (hasDefault()) sb.append(" DEFAULT ").append(defaultValue);
        if (autoIncrement) sb.append(" AUTO_INCREMENT");
        return sb.toString();
    }
}
