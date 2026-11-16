package com.georu.codegen;

/**
 * 올린 파일의 컬럼 한 줄.
 *
 * @param name    물리 컬럼명 (파일에 적힌 그대로)
 * @param type    자료형 (예: VARCHAR)
 * @param length  길이·정밀도 (예: 50, 10,2). 없으면 빈 문자열
 * @param comment 컬럼 설명 (논리명). 없으면 빈 문자열
 * @param pk      PK 여부
 * @param notNull Not Null 여부
 * @param row     파일의 행 번호 (첫 행 = 1, 열 이름 행 포함)
 */
public record SpecColumn(String name, String type, String length, String comment, boolean pk, boolean notNull, int row) {

    /** VARCHAR(50) · DECIMAL(10,2) · INT */
    public String dbType() {
        return length.isEmpty() ? type : type + "(" + length + ")";
    }
}
