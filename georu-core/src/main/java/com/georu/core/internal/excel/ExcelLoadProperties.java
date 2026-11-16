package com.georu.core.internal.excel;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 엑셀 이관 설정 ({@code georu.excel.*}).
 *
 * <pre>
 * app:
 *   excel:
 *     dir: ./data/excel          # 파일 보관 폴더. 아래에 pending/processing/completed/failed 를 만든다
 *     preview-rows: 20           # 미리보기 행 수 상한
 *     max-issues: 2000           # 검증·실행 오류 목록 보관 상한
 *     allowed-extensions: xlsx,csv
 * </pre>
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "georu.excel")
public class ExcelLoadProperties {

    /** 파일 보관 루트. 컨테이너는 /app/data/excel (data 볼륨) */
    private String dir = "./data/excel";

    /** 미리보기 행 수 상한 */
    private int previewRows = 20;

    /** 검증·실행 오류 목록 보관 상한 (행 단위). 넘으면 건수만 센다 */
    private int maxIssues = 2000;

    /** 허용 확장자 (소문자, 점 없이) */
    private List<String> allowedExtensions = List.of("xlsx", "csv");

    /**
     * xlsx 셀 수 상한 (첫 시트, 헤더 포함). 넘으면 업로드를 거부하고 나눠 올리도록 안내한다.
     * POI 가 워크북 전체를 메모리에 올리므로 힙 크기에 맞춰 정한다 (기본값은 힙 약 2GB 기준 추정값).
     */
    private long xlsxMaxCells = 500_000;

    /** xlsx 셀 수 권장값. 넘으면 업로드는 받되 경고한다. 분할 안내의 파일당 행 수 계산에도 쓴다 */
    private long xlsxRecommendedCells = 200_000;

    /** CSV 데이터 행 수 상한 (0 = 제한 없음). CSV 는 한 줄씩 읽으므로 메모리 제한은 아니다 */
    private long csvMaxRows = 0;

    /**
     * 감사 일시 컬럼으로 취급할 DTO setter 이름 (값이 비어 있으면 실행 시각으로 채운다).
     * String 타입이면 yyyyMMddHHmmss, LocalDateTime 이면 now. 기본 규약(LDATE) 기준.
     */
    private List<String> auditDateSetters = List.of("setLdate", "setIndate", "setLDate", "setInDate");
}
