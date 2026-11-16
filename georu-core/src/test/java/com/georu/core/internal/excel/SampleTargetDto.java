package com.georu.core.internal.excel;

import lombok.Data;

import java.math.BigDecimal;

/** 테스트용 Target DTO (생성기 산출물과 같은 모양: Lombok @Data, 문자 일시 LDATE, 감사 LUSERID) */
@Data
public class SampleTargetDto {
    private String code;
    private String name;
    private Long seq;
    private Integer orders;
    private BigDecimal amt;
    private String useyn;
    private String luserid;
    private String ldate;
}
