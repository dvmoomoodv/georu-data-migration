package com.georu.examples.demo.source.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Source] TOBE tb_sequence : 공통시퀀스관리 ← ASIS(Oracle) SEQ_MASTER : 시퀀스 현재값 (매핑 추정, 미확정)
 * TbSequenceSourceMapper.selectPageData 결과 (ASIS 값을 TOBE 컬럼명·타입으로 변환한 값)
 * Reader 출력 = Processor 입력. Processor 가 TbSequenceTargetDto 로 옮긴다.
 * 자동 생성: 코드 생성기
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbSequenceSourceDto {

    /** TABLES VARCHAR(50) : 테이블명 */
    private String tables;

    /** COL VARCHAR(50) : 시퀀스컬럼 */
    private String col;

    /** SEQ DECIMAL(18,0) : 시퀀스번호 */
    private Long seq;
}
