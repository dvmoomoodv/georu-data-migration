package com.georu.core.internal.excel;

import java.io.Closeable;
import java.util.List;

/**
 * 행 단위 읽기 추상화 (xlsx·csv). 첫 행이 헤더이고, 이후 행을 차례로 돌려준다. 빈 행은 건너뛴다.
 * 구현은 {@link ExcelRowSources#open}.
 */
public interface ExcelRowSource extends Closeable {

    /** 헤더 (원문 순서, 대문자·공백 제거). 빈 헤더 셀은 {@code null} 로 둔다 */
    List<String> headers();

    /** 다음 데이터 행. 없으면 null */
    ExcelRow next();
}
