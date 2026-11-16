package com.georu.core.internal.excel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 큰 파일을 행 범위로 나눠 올리는 안내. 한 테이블에 파일 여러 개를 차례로 올린다
 * (예: 1~1,000행 파일 1개, 1,001~2,000행 파일 1개). 파일마다 헤더 행을 그대로 둔다.
 *
 * @param dataRows    데이터 행 수 (헤더 제외)
 * @param columns     열 수
 * @param rowsPerFile 파일당 데이터 행 수 (권장 셀 수 ÷ 열 수, 보기 좋은 수로 내림)
 * @param files       나눌 파일 수
 * @param ranges      파일별 범위 (데이터 행 번호, 1부터)
 */
@Schema(description = "큰 엑셀 파일을 행 범위로 나눠 올리는 안내")
public record ExcelSplitPlan(@Schema(description = "대상 테이블명", example = "tb_member") String tableName,
                             @Schema(description = "데이터 행 수 (헤더 제외)", example = "50000") int dataRows,
                             @Schema(description = "열 수", example = "20") int columns,
                             @Schema(description = "셀 수 (헤더 포함 행 수 × 열 수). CSV 행 수 초과 안내에서는 0", example = "1000020") long cells,
                             @Schema(description = "상한 (xlsx 는 셀 수 상한, CSV 행 수 초과 안내에서는 행 수 상한)", example = "500000") long maxCells,
                             @Schema(description = "권장 셀 수", example = "200000") long recommendedCells,
                             @Schema(description = "파일당 데이터 행 수 (권장 셀 수 ÷ 열 수 - 헤더 1행, 보기 좋은 수로 내림)", example = "9000") int rowsPerFile,
                             @Schema(description = "나눌 파일 수", example = "6") int files,
                             @Schema(description = "파일별 범위 (최대 200개)") List<Range> ranges) {

    @Schema(description = "분할 파일 1개의 행 범위")
    public record Range(@Schema(description = "파일 순번 (1부터)", example = "1") int no,
                        @Schema(description = "시작 데이터 행 번호 (1부터, 포함)", example = "1") int fromRow,
                        @Schema(description = "끝 데이터 행 번호 (포함)", example = "9000") int toRow,
                        @Schema(description = "권장 파일명", example = "tb_member_01_1-9000.xlsx") String suggestedName) {
    }

    public static ExcelSplitPlan of(String tableName, int dataRows, int columns, long cells, long maxCells, long recommendedCells) {
        int perFile = rowsPerFile(columns, recommendedCells);
        int files = dataRows <= 0 ? 1 : (int) Math.ceil(dataRows / (double) perFile);
        List<Range> ranges = new ArrayList<>();
        String base = tableName == null || tableName.isBlank() ? "data" : tableName.toLowerCase(Locale.ROOT);
        int width = Math.max(2, String.valueOf(files).length());
        for (int i = 0; i < files && i < 200; i++) {
            int from = i * perFile + 1;
            int to = Math.min(dataRows, (i + 1) * perFile);
            String no = String.format("%0" + width + "d", i + 1);
            ranges.add(new Range(i + 1, from, to, base + "_" + no + "_" + from + "-" + to + ".xlsx"));
        }
        return new ExcelSplitPlan(tableName, dataRows, columns, cells, maxCells, recommendedCells, perFile, files, ranges);
    }

    /** 파일당 데이터 행 수 = (권장 셀 ÷ 열 수) - 헤더 1행, 1,000 이상이면 1,000 단위, 100 이상이면 100 단위로 내림 */
    public static int rowsPerFile(int columns, long recommendedCells) {
        long raw = recommendedCells / Math.max(1, columns) - 1;
        if (raw >= 1000) {
            raw = raw / 1000 * 1000;
        } else if (raw >= 100) {
            raw = raw / 100 * 100;
        }
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, raw));
    }

    public String summary() {
        return String.format("데이터 %,d행 × %,d열 → 파일당 %,d행씩 %d개로 나눠 올리세요 (예: %s)", dataRows, columns, rowsPerFile, files,
                ranges.isEmpty() ? "" : ranges.get(0).suggestedName() + (ranges.size() > 1 ? ", " + ranges.get(1).suggestedName() + " …" : ""));
    }
}
