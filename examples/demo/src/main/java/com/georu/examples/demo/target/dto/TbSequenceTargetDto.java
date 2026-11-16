package com.georu.examples.demo.target.dto;

import com.georu.core.plugin.MigrationId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Target] TOBE(MySQL) tb_sequence : 공통시퀀스관리 (업무 05 시퀀스관리)
 * 자동 생성: 코드 생성기 (tobe-table-column.csv)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbSequenceTargetDto {

    /** TABLES VARCHAR(50) : 테이블명 */
    @MigrationId
    private String tables;

    /** COL VARCHAR(50) : 시퀀스컬럼 */
    private String col;

    /** SEQ DECIMAL(18,0) : 시퀀스번호 */
    private Long seq;
}
