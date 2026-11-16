package com.georu.core.internal.excel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ExcelToTargetProcessor - 행 → DTO 변환·감사 컬럼")
class ExcelToTargetProcessorTest {

    private final ExcelToTargetProcessor processor = new ExcelToTargetProcessor(
            TestBindings.sample(), new ExcelValidationService(new ExcelLoadProperties()), new ExcelLoadProperties().getAuditDateSetters());

    @Test
    void transform() {
        ExcelRow row = new ExcelRow(2, Map.of("CODE", "A1", "NAME", "홍길동", "ORDERS", "3", "AMT", "10.50", "USEYN", "Y"));
        SampleTargetDto dto = (SampleTargetDto) processor.transformItem(row);
        assertEquals("A1", dto.getCode());
        assertEquals(3, dto.getOrders());
        assertEquals(new BigDecimal("10.50"), dto.getAmt());
        assertNull(dto.getSeq(), "채번 컬럼은 비워 둔다 (부모가 채번)");
        assertNull(dto.getLuserid());

        processor.setUpdateTimestamp(dto);
        assertEquals("MIG-DATA", dto.getLuserid(), "감사 등록자는 MIG-DATA (WRITER_TEST 는 Writer 가 MIG-TEST 로 바꾼다)");
        assertEquals(14, dto.getLdate().length(), "LDATE 는 yyyyMMddHHmmss");
    }

    @Test
    void auditKeepsGivenValue() {
        ExcelRow row = new ExcelRow(2, Map.of("CODE", "A1", "USEYN", "Y", "LUSERID", "admin", "LDATE", "20260101000000"));
        SampleTargetDto dto = (SampleTargetDto) processor.transformItem(row);
        processor.setUpdateTimestamp(dto);
        assertEquals("admin", dto.getLuserid(), "엑셀에 값이 있으면 유지");
        assertEquals("20260101000000", dto.getLdate());
    }

    @Test
    void rowErrors() {
        ExcelRowException e1 = assertThrows(ExcelRowException.class,
                () -> processor.transformItem(new ExcelRow(5, Map.of("CODE", "A1", "USEYN", "Y", "ORDERS", "abc"))));
        assertEquals(5, e1.getRowNum());
        assertEquals("ORDERS", e1.getColumn());
        assertTrue(e1.getMessage().startsWith("5행 ORDERS:"));
        ExcelRowException e2 = assertThrows(ExcelRowException.class,
                () -> processor.transformItem(new ExcelRow(6, Map.of("NAME", "x", "USEYN", "Y"))));
        assertEquals("CODE", e2.getColumn(), "필수 값 비어 있음");
        assertEquals(List.of("SEQ"), processor.getBinding().getSequenceColumns());
    }

    @Test
    void entityName() {
        assertEquals("Excel(fb_sample)", processor.getEntityName());
        assertEquals("7행", processor.getItemId(new ExcelRow(7, Map.of())));
    }
}
