package com.georu.core.internal.excel;

/**
 * 엑셀 행 1건의 변환·검증 오류. onRowError=SKIP 이면 이 예외만 건너뛰고, STOP 이면 Step 이 실패한다.
 */
public class ExcelRowException extends RuntimeException {

    private final int rowNum;
    private final String column;
    private final String value;

    public ExcelRowException(int rowNum, String column, String value, String message) {
        super(rowNum + "행" + (column != null ? " " + column : "") + ": " + message);
        this.rowNum = rowNum;
        this.column = column;
        this.value = value;
    }

    public int getRowNum() {
        return rowNum;
    }

    public String getColumn() {
        return column;
    }

    public String getValue() {
        return value;
    }
}
