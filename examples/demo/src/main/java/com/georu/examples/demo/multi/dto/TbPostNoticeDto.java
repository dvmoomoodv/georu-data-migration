package com.georu.examples.demo.multi.dto;

import com.georu.core.plugin.MigrationId;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 공지 게시글 (target-1 tb_post_notice) */
@Data
@NoArgsConstructor
public class TbPostNoticeDto {

    @MigrationId
    private Long postId;

    private Integer boardId;

    private String title;

    private Integer writerId;

    private Integer viewCnt;

    private String indate;
}
