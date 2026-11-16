package com.georu.core.internal.sequence;

import com.georu.core.strategy.TableOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SequenceJobParameters - TableOptions/API 값 → JobParameters → Processor")
class SequenceJobParametersTest {

    @Test
    void 옵션이_없으면_아무것도_넣지_않는다() {
        JobParametersBuilder b = new JobParametersBuilder().addString("tableName", "fb_x");
        SequenceJobParameters.addFromOptions(b, TableOptions.DEFAULT);
        JobParameters p = b.toJobParameters();
        assertNull(p.getString(SequenceJobParameters.PARAM_COLUMNS));
        assertTrue(SequenceJobParameters.columns(p).isEmpty());
        assertEquals("fb_x", SequenceJobParameters.targetTable(p), "targetTable 없으면 tableName");
    }

    @Test
    void 옵션_값_적재() {
        TableOptions opts = TableOptions.builder()
                .sequenceColumns(List.of("TEMPLATE_SEQ", "ITEM_SEQ"))
                .sequenceSync(true).sequencePolicy("V1").targetTable("fb_real")
                .sequenceSyncColumns(List.of("OTHER_SEQ"))
                .build();
        JobParametersBuilder b = new JobParametersBuilder().addString("tableName", "fb_x");
        SequenceJobParameters.addFromOptions(b, opts);
        JobParameters p = b.toJobParameters();
        assertEquals(List.of("TEMPLATE_SEQ", "ITEM_SEQ"), SequenceJobParameters.columns(p));
        assertEquals("v1", SequenceJobParameters.policy(p));
        assertTrue(SequenceJobParameters.sync(p));
        assertEquals(List.of("OTHER_SEQ"), SequenceJobParameters.syncColumns(p));
        assertEquals("fb_real", SequenceJobParameters.targetTable(p));
        assertFalse(p.getParameters().get(SequenceJobParameters.PARAM_COLUMNS).isIdentifying(), "식별 파라미터가 아님");
    }

    @Test
    void API_값이_옵션보다_우선() {
        TableOptions opts = TableOptions.builder().sequenceColumns(List.of("A")).sequenceSync(true).sequencePolicy("v1").build();
        JobParametersBuilder b = new JobParametersBuilder();
        SequenceJobParameters.add(b, opts, List.of("B", "C"), "v2", false);
        JobParameters p = b.toJobParameters();
        assertEquals(List.of("B", "C"), SequenceJobParameters.columns(p));
        assertEquals("v2", SequenceJobParameters.policy(p));
        assertFalse(SequenceJobParameters.sync(p));
    }

    @Test
    void 동기화_전용_컬럼만_있어도_적재() {
        TableOptions opts = TableOptions.builder().sequenceSyncColumns(List.of("SEQ")).build();
        assertTrue(opts.needsSequencePolicy());
        assertFalse(opts.hasSequenceColumns());
        JobParametersBuilder b = new JobParametersBuilder();
        SequenceJobParameters.addFromOptions(b, opts);
        JobParameters p = b.toJobParameters();
        assertTrue(SequenceJobParameters.columns(p).isEmpty());
        assertEquals(List.of("SEQ"), SequenceJobParameters.syncColumns(p));
    }

    @Test
    void 컬럼_파싱() {
        assertEquals(List.of("A", "b", "C"), SequenceJobParameters.parseColumns(" A, b ,,C ,A"));
        assertTrue(SequenceJobParameters.parseColumns(null).isEmpty());
        assertEquals("A,B", SequenceJobParameters.joinColumns(List.of("A", "B")));
        assertEquals("seq.TEMPLATE_SEQ.start", SequenceJobParameters.ctxKey("TEMPLATE_SEQ", "start"));
    }

    @Test
    void resolve_우선순위_API_옵션_없음() {
        TableOptions opts = TableOptions.builder().sequenceColumns(List.of("TEMPLATE_SEQ")).sequenceSync(true).sequencePolicy("v1").build();
        SequenceJobParameters.Resolved byOpts = SequenceJobParameters.resolve(opts, null, null, null);
        assertTrue(byOpts.uses());
        assertEquals(List.of("TEMPLATE_SEQ"), byOpts.columns());
        assertTrue(byOpts.sync());
        assertEquals("v1", byOpts.policy());

        SequenceJobParameters.Resolved byApi = SequenceJobParameters.resolve(opts, List.of("ITEM_SEQ"), "v2", false);
        assertEquals(List.of("ITEM_SEQ"), byApi.columns());
        assertFalse(byApi.sync());
        assertEquals("v2", byApi.policy());

        SequenceJobParameters.Resolved apiOnly = SequenceJobParameters.resolve(TableOptions.DEFAULT, List.of("TEMPLATE_SEQ"), null, null);
        assertTrue(apiOnly.uses(), "테이블 설정이 없어도 API 로 지정하면 사용");
        assertFalse(apiOnly.sync());

        SequenceJobParameters.Resolved none = SequenceJobParameters.resolve(TableOptions.DEFAULT, List.of(), null, true);
        assertFalse(none.uses());
        assertFalse(none.sync(), "채번 컬럼이 없으면 sync 는 의미 없음");
    }

    @Test
    void resolve_와_add_가_같은_판정() {
        TableOptions opts = TableOptions.builder().sequenceSyncColumns(List.of("SEQ")).build();
        SequenceJobParameters.Resolved r = SequenceJobParameters.resolve(opts, List.of("TEMPLATE_SEQ"), null, true);
        JobParametersBuilder b = new JobParametersBuilder();
        SequenceJobParameters.add(b, opts, List.of("TEMPLATE_SEQ"), null, true);
        JobParameters p = b.toJobParameters();
        assertEquals(r.columns(), SequenceJobParameters.columns(p));
        assertEquals(r.syncColumns(), SequenceJobParameters.syncColumns(p));
        assertEquals(r.sync(), SequenceJobParameters.sync(p));
        assertEquals(List.of("TEMPLATE_SEQ", "SEQ"), r.allColumns());
    }
}
