package com.georu.examples.demo.multi.processor;

import com.georu.core.processor.AbstractItemProcessor;
import com.georu.core.processor.SourceLookup;
import com.georu.core.utils.ValidationUtils;
import com.georu.examples.demo.multi.dto.TbMemberPointDto;
import com.georu.examples.demo.source.dto.TbMemberSourceDto;

import java.util.Map;

/**
 * 2:1 결합 예제: 본사 회원(source-m, Reader) 에 지사 DB(source-1) 의 포인트·지점명을 붙인다.
 *
 * <ul>
 *   <li>지점 코드표 BR_BRANCH 는 작다 → {@link SourceLookup#cached}: Step 에서 한 번 통째로 읽는다</li>
 *   <li>회원 포인트 BR_MEMBER_POINT 는 회원 수만큼 크다 → {@link SourceLookup#byKeys}: 청크에서 읽은 회원 ID 로 IN 조회 한 번</li>
 * </ul>
 * 포인트가 없는 회원은 지점 없이 0 으로 넣는다. tb_member_point(→ target-m) 와 tb_member_point_dw(→ target-1) 가 같이 쓴다.
 * SQL: georu/demo/multi/BrBranchLookupMapper.xml, BrMemberPointLookupMapper.xml
 */
public class TbMemberPointProcessor extends AbstractItemProcessor<TbMemberSourceDto, TbMemberPointDto> {

    private final SourceLookup<String, Map<String, Object>> branches = lookup(
            SourceLookup.cached("source-1", "georu.demo.multi.BrBranchLookupMapper.selectAll", r -> (String) r.get("BRANCH_CD")));

    private final SourceLookup<Integer, Map<String, Object>> points = lookup(
            SourceLookup.byKeys("source-1", "georu.demo.multi.BrMemberPointLookupMapper.selectByKeys",
                    (TbMemberSourceDto m) -> m.getMemberId(), r -> ((Number) r.get("MEMBER_ID")).intValue()));

    public TbMemberPointProcessor() {
        super("MIGRATION", "SOURCE");
    }

    @Override
    protected ValidationUtils.ValidationResult validateItem(TbMemberSourceDto item) {
        return item == null || item.getMemberId() == null
                ? ValidationUtils.ValidationResult.failure("MEMBER_ID 누락") : ValidationUtils.ValidationResult.success();
    }

    @Override
    protected TbMemberPointDto transformItem(TbMemberSourceDto m) {
        TbMemberPointDto out = new TbMemberPointDto();
        out.setMemberId(m.getMemberId());
        out.setMemberNm(m.getMemberNm());
        Map<String, Object> point = points.get(m.getMemberId());
        if (point == null) {
            out.setPointBal(0L);
            return out;
        }
        String branchCd = (String) point.get("BRANCH_CD");
        Map<String, Object> branch = branches.get(branchCd);
        out.setBranchCd(branchCd);
        out.setBranchNm(branch == null ? null : (String) branch.get("BRANCH_NM"));
        out.setPointBal(((Number) point.get("POINT_BAL")).longValue());
        return out;
    }

    @Override
    protected Object getItemId(TbMemberSourceDto item) {
        return item.getMemberId();
    }

    @Override
    protected void setUpdateTimestamp(TbMemberPointDto item) {
        // 감사 컬럼 없음
    }
}
