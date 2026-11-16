package com.georu.examples.demo.source.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [Source] TOBE tb_notice_read_log : 공지 읽음 이력 ← ASIS(Oracle) NTC_READ_LOG
 * 이관 상태: 이관 제외 (TOBE 에서 읽음 이력을 보관하지 않음) - DemoNoticePlugin
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TbNoticeReadLogSourceDto {

    /** READ_ID BIGINT : 읽음ID */
    private Long readId;

    /** NOTICE_ID INT : 공지ID */
    private Integer noticeId;

    /** MEMBER_ID INT : 회원ID */
    private Integer memberId;

    /** READ_DATE VARCHAR(14) : 읽은 일시 */
    private String readDate;
}
