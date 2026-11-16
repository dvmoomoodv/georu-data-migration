package com.georu.examples.demo.processor;

import com.georu.examples.demo.board.processor.TbPostFileProcessor;
import com.georu.examples.demo.board.processor.TbPostProcessor;
import com.georu.examples.demo.member.processor.TbMemberProcessor;
import com.georu.examples.demo.source.dto.TbMemberSourceDto;
import com.georu.examples.demo.source.dto.TbPostFileSourceDto;
import com.georu.examples.demo.source.dto.TbPostSourceDto;
import com.georu.examples.demo.target.dto.TbMemberTargetDto;
import com.georu.examples.demo.target.dto.TbPostFileTargetDto;
import com.georu.examples.demo.target.dto.TbPostTargetDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 생성 + 수기 매핑이 끝난 데모 Processor 의 변환 단정 (DB 없음, 테스트 모드 미설정 = NORMAL 경로의 process() 호출) */
@DisplayName("데모 Processor 변환")
class DemoProcessorTransformTest {

    @Test
    void memberCodesAndAudit() throws Exception {
        TbMemberProcessor p = new TbMemberProcessor();
        TbMemberSourceDto in = TbMemberSourceDto.builder().memberId(7).loginId("user0007").memberNm("김민수").gender("F").status("I")
                .joinDate("20250101090000").email("user0007@example.com").phone("01012345678").build();
        TbMemberTargetDto out = p.process(in);
        assertNotNull(out);
        assertEquals("2", out.getGender());
        assertEquals("INACTIVE", out.getStatus());
        assertEquals("MIG-DATA", out.getInuserid(), "원천 등록자 없으면 MIG-DATA");
        assertNotNull(out.getIndate());
    }

    @Test
    void memberValidationRejectsBadRows() throws Exception {
        TbMemberProcessor p = new TbMemberProcessor();
        assertNull(p.process(TbMemberSourceDto.builder().memberId(1).loginId("a").memberNm("가".repeat(60)).status("A").joinDate("20250101000000").build()), "50자 초과 → 검증 실패(null)");
        assertNull(p.process(TbMemberSourceDto.builder().memberId(2).loginId("b").memberNm("홍길동").status("A").build()), "JOIN_DATE 누락");
        assertNull(p.process(TbMemberSourceDto.builder().memberId(3).loginId("c").memberNm("홍길동").status("A").joinDate("20250101000000").email("not-an-email").build()), "EMAIL 형식");
    }

    @Test
    void postFlagsAndFilePath() throws Exception {
        TbPostTargetDto post = new TbPostProcessor().process(TbPostSourceDto.builder().postId(1L).boardId(1).title("t").noticeYn("Y").secretYn("N").status("A").build());
        assertEquals("1", post.getNoticeYn()); assertEquals("0", post.getSecretYn()); assertEquals("ACTIVE", post.getStatus());
        TbPostFileTargetDto f = new TbPostFileProcessor().process(TbPostFileSourceDto.builder().asisId(1L).postId(1L).originNm("a.pdf").filePath("/Upload_Board/2025/01/1.pdf").build());
        assertEquals("file/board/2025/01/1.pdf", f.getFilePath());
    }
}
