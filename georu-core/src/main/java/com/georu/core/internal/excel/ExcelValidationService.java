package com.georu.core.internal.excel;

import com.georu.core.product.precheck.ColumnMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 엑셀 파일 검증. 헤더를 {@link ExcelTargetBinding} 과 대조하고 행마다 필수·길이·타입·PK 중복을 확인한다.
 *
 * <ul>
 *   <li>차단(BLOCK): 필수 컬럼 헤더 없음, 필수 값 비어 있음, 길이 초과, 타입 변환 불가, 데이터 행 없음</li>
 *   <li>경고(WARN): 타겟에 없거나 적재되지 않는 헤더, 파일 안 PK 중복, 메타 없음(길이·필수 판단 불가)</li>
 * </ul>
 * 같은 규칙을 실행 중 Processor({@link ExcelToTargetProcessor})도 쓴다 ({@link #checkValue}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelValidationService {

    private final ExcelLoadProperties props;

    public ExcelValidationReport validate(Path file, ExcelTargetBinding binding, String fileId) throws IOException {
        List<ExcelValidationReport.Issue> issues = new ArrayList<>();
        int[] counts = new int[3];   // block, warn, truncatedFlag
        int max = Math.max(50, props.getMaxIssues());

        try (ExcelRowSource src = ExcelRowSources.open(file)) {
            List<String> headers = src.headers();
            Map<String, ExcelTargetBinding.Column> cols = binding.columnMap();

            // 1. 헤더 대조
            List<String> mapped = new ArrayList<>();
            List<String> ignored = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String h : headers) {
                if (h == null) {
                    continue;
                }
                if (!seen.add(h)) {
                    add(issues, counts, max, 0, h, ExcelValidationReport.BLOCK, "헤더가 중복됩니다", null);
                    continue;
                }
                ExcelTargetBinding.Column c = cols.get(h);
                if (c == null) {
                    ignored.add(h);
                    add(issues, counts, max, 0, h, ExcelValidationReport.WARN, "타겟 테이블에 없는 컬럼이라 무시합니다", null);
                } else if (!c.isAccepted()) {
                    ignored.add(h);
                    add(issues, counts, max, 0, h, ExcelValidationReport.WARN, c.getNote() != null ? c.getNote() : "적재되지 않는 컬럼이라 무시합니다", null);
                } else {
                    mapped.add(h);
                }
            }
            List<String> missing = new ArrayList<>();
            for (ExcelTargetBinding.Column c : binding.requiredColumns()) {
                if (!seen.contains(c.getName())) {
                    missing.add(c.getName());
                    add(issues, counts, max, 0, c.getName(), ExcelValidationReport.BLOCK, "필수 컬럼(NOT NULL)이 헤더에 없습니다 - " + c.getDescribe(), null);
                }
            }
            if (mapped.isEmpty()) {
                add(issues, counts, max, 0, null, ExcelValidationReport.BLOCK, "적재할 수 있는 컬럼이 하나도 없습니다 (템플릿을 내려받아 헤더를 맞추세요)", null);
            }
            if (!binding.metaFound()) {
                add(issues, counts, max, 0, null, ExcelValidationReport.WARN, "타겟 메타를 읽지 못해 필수·길이 검증을 생략했습니다", null);
            }

            // 2. 행 검증
            int rows = 0;
            Set<String> pkSeen = new HashSet<>();
            List<String> pk = binding.primaryKey();
            boolean checkPk = !pk.isEmpty() && pk.stream().allMatch(seen::contains);
            ExcelRow row;
            while ((row = src.next()) != null) {
                rows++;
                for (String h : mapped) {
                    ExcelTargetBinding.Column c = cols.get(h);
                    String v = row.get(h);
                    String err = checkValue(c, v);
                    if (err != null) {
                        add(issues, counts, max, row.rowNum(), h, ExcelValidationReport.BLOCK, err, v);
                    }
                }
                if (checkPk) {
                    StringBuilder key = new StringBuilder();
                    for (String p : pk) {
                        key.append(row.get(p)).append('\u0001');
                    }
                    if (!pkSeen.add(key.toString())) {
                        add(issues, counts, max, row.rowNum(), String.join(",", pk), ExcelValidationReport.WARN, "파일 안에서 PK 가 중복됩니다", key.toString().replace('\u0001', '|'));
                    }
                }
            }
            if (rows == 0) {
                add(issues, counts, max, 0, null, ExcelValidationReport.BLOCK, "데이터 행이 없습니다", null);
            }

            return ExcelValidationReport.builder()
                    .fileId(fileId).pluginName(binding.getPluginName()).tableName(binding.getTableName()).targetTable(binding.getTargetTable())
                    .headers(headers.stream().map(h -> h == null ? "" : h).toList())
                    .mappedHeaders(mapped).ignoredHeaders(ignored).missingRequired(missing)
                    .rowCount(rows).blockCount(counts[0]).warnCount(counts[1]).truncated(counts[2] == 1)
                    .issues(issues).notes(binding.getNotes())
                    .build();
        } catch (IllegalStateException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * 값 1개 검증. 문제 없으면 null, 있으면 사용자용 메시지.
     * 필수 비어 있음 / 길이 초과 / 타입 변환 불가 / 숫자 자릿수 초과
     */
    public String checkValue(ExcelTargetBinding.Column c, String v) {
        if (c == null) {
            return null;
        }
        boolean empty = v == null || v.isBlank();
        if (empty) {
            return c.isRequired() ? "필수 값이 비어 있습니다 (" + c.getDescribe() + ")" : null;
        }
        ColumnMeta m = c.getMeta();
        if (c.getSetter() != null) {
            Class<?> type = c.getSetter().getParameterTypes()[0];
            if (!ExcelValueConverter.supports(type)) {
                return "지원하지 않는 DTO 타입입니다: " + type.getSimpleName();
            }
            try {
                ExcelValueConverter.convert(v, type);
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        }
        if (m != null) {
            if (m.isCharacter() && m.getSize() > 0 && v.length() > m.getSize()) {
                return "길이 초과: " + v.length() + "자 > " + m.getSize() + "자 (" + m.describe() + ")";
            }
            if ((m.isInteger() || m.isDecimal()) && c.getSetter() != null && String.class != c.getSetter().getParameterTypes()[0]) {
                try {
                    java.math.BigDecimal d = ExcelValueConverter.decimal(v);
                    if (m.isInteger() && d.scale() > 0 && d.stripTrailingZeros().scale() > 0) {
                        return "정수 컬럼에 소수가 들어왔습니다 (" + m.describe() + ")";
                    }
                    if (m.isDecimal() && m.getSize() > 0) {
                        int intDigits = d.precision() - d.scale();
                        if (intDigits > m.getSize() - Math.max(0, m.getDecimalDigits())) {
                            return "숫자 자릿수 초과 (" + m.describe() + ")";
                        }
                    }
                } catch (NumberFormatException e) {
                    return "'" + v + "' 은(는) 숫자가 아닙니다";
                }
            }
        }
        return null;
    }

    private static void add(List<ExcelValidationReport.Issue> issues, int[] counts, int max, int row, String column,
                            String severity, String message, String value) {
        if (ExcelValidationReport.BLOCK.equals(severity)) {
            counts[0]++;
        } else {
            counts[1]++;
        }
        if (issues.size() >= max) {
            counts[2] = 1;
            return;
        }
        issues.add(ExcelValidationReport.Issue.builder().row(row).column(column).severity(severity).message(message)
                .value(value != null && value.length() > 200 ? value.substring(0, 200) + "…" : value).build());
    }
}
