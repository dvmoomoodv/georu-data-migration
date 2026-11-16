package com.georu.examples.demo.board;

import com.georu.core.plugin.GeoruPlugin;
import com.georu.core.plugin.TableCatalog;
import com.georu.core.plugin.TableDef;
import com.georu.core.strategy.TableOptions;
import com.georu.examples.demo.board.processor.TbBoardProcessor;
import com.georu.examples.demo.board.processor.TbPostCommentProcessor;
import com.georu.examples.demo.board.processor.TbPostFileProcessor;
import com.georu.examples.demo.board.processor.TbPostProcessor;
import com.georu.examples.demo.source.dto.TbBoardSourceDto;
import com.georu.examples.demo.source.dto.TbPostCommentSourceDto;
import com.georu.examples.demo.source.dto.TbPostFileSourceDto;
import com.georu.examples.demo.source.dto.TbPostSourceDto;
import com.georu.examples.demo.target.dto.TbBoardTargetDto;
import com.georu.examples.demo.target.dto.TbPostCommentTargetDto;
import com.georu.examples.demo.target.dto.TbPostFileTargetDto;
import com.georu.examples.demo.target.dto.TbPostTargetDto;

import java.util.List;

/**
 * 데모 게시판 (업무코드 03): 게시판 → 게시글 → 첨부 → 댓글. CLOB 본문, 파일 경로 변환, FILE_ID 채번(원본은 ASIS_ID 에 보존).
 * POST_ID·COMMENT_ID 는 다른 행이 참조하므로 번호 유지 + MAX 동기화.
 */
@GeoruPlugin(name = "demo.board", description = "데모 게시판 (03) Migration Plugin - ASIS Oracle to TOBE MySQL")
public class DemoBoardPlugin implements TableCatalog {

    @Override
    public List<TableDef> tables() {
        return List.of(
                TableDef.of("tb_board", TbBoardSourceDto.class, TbBoardTargetDto.class)
                        .processor(TbBoardProcessor.class),
                TableDef.of("tb_post", TbPostSourceDto.class, TbPostTargetDto.class)
                        .processor(TbPostProcessor.class)
                        .options(TableOptions.builder().sequenceSyncColumns(List.of("POST_ID")).build()),
                TableDef.of("tb_post_file", TbPostFileSourceDto.class, TbPostFileTargetDto.class)
                        .processor(TbPostFileProcessor.class)
                        .options(TableOptions.builder().sequenceColumns(List.of("FILE_ID")).sequenceSync(true).build()),
                TableDef.of("tb_post_comment", TbPostCommentSourceDto.class, TbPostCommentTargetDto.class)
                        .processor(TbPostCommentProcessor.class)
                        .options(TableOptions.builder().sequenceSyncColumns(List.of("COMMENT_ID")).build())
        );
    }
}
