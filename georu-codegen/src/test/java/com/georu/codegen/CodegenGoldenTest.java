package com.georu.codegen;

import com.georu.codegen.relation.RelationSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 코드 생성 요약 골든: 고정 데이터(데모 정의 + 규칙 커버용 가상 테이블) → generation-report.csv 와 메시지가 골든과 같은지.
 * 규칙(원천 매핑 · 공통 컬럼 · 감사 · 사전 · Writer · 채번 안내)을 고치면 여기 줄이 바뀐다.
 */
@DisplayName("골든: 코드 생성 리포트")
class CodegenGoldenTest {

    static final LocalDateTime AT = LocalDateTime.of(2026, 10, 29, 9, 0);

    /** 세 골든 테스트가 같이 쓰는 업체 규칙 (실제 업체 규칙과 같은 꼴: 감사 4개 · *_MEMBER · 업체 사전). 규칙 파일 쓰기 → 읽기를 거친다 */
    static RuleFile rules() {
        return RuleFile.read("acme-rules.xlsx", RuleFile.of(
                Map.of("company", "acme", "sourceDialect", "oracle", "targetDialect", "mysql"),
                Map.of("강좌", "subj"),
                Map.of("INDATE", "REG_DATE", "INUSERID", "REG_ID", "LDATE", "MOD_DATE", "LUSERID", "MOD_ID"),
                List.of(new RelationSettings.SuffixRule("_MEMBER", "MEM_MEMBER", "MEMBER_ID", true)),
                List.of("REG_MEMBER")).write());
    }

    static GenerationResult generate() {
        TableSpecReader reader = new TableSpecReader();
        SpecReadResult tobe = reader.read("tobe-definition.csv", Golden.fixture("tobe-definition.csv"));
        SpecReadResult asis = reader.readDefinition("asis-definition.csv", Golden.fixture("asis-definition.csv"));
        assertThat(tobe.errors()).isEmpty();
        assertThat(asis.errors()).isEmpty();
        RuleFile rf = rules();
        assertThat(rf.error()).isNull();
        CodegenSettings s = CodegenSettings.of(rf.defaultValue("company"))
                .sourceDialect(rf.defaultValue("sourceDialect")).targetDialect(rf.defaultValue("targetDialect"))
                .audit(rf.audit()).workNames(rf.workNames())
                .sourceCatalog(SourceCatalog.of(asis))
                .fileName("tobe-definition.csv");
        return new CodeGenerator(CodegenGoldenTest.class.getClassLoader(), () -> AT).generate(tobe, s);
    }

    @Test
    void report() {
        GenerationResult r = generate();
        assertThat(r.errors()).as("고정 데이터는 오류 없이 생성되어야 한다").isEmpty();
        assertThat(r.toZip()).isNotEmpty();
        Golden.assertSame("codegen-report.csv", r.files().get("generation-report.csv"));
        // TODO 목록은 리포트 CSV 에 이미 있다. 여기는 경고와 파일 수 · 업무별 파일 목록만
        StringBuilder msgs = new StringBuilder();
        r.warnings().forEach(w -> msgs.append("경고: ").append(w).append('\n'));
        msgs.append("파일 ").append(r.fileCount()).append('\n');
        r.files().keySet().stream().filter(p -> !p.contains("/dto/") && !p.contains("/reader/") && !p.contains("/processor/") && !p.contains("/writer/") && !p.endsWith("Mapper.xml"))
                .forEach(p -> msgs.append(p).append('\n'));
        Golden.assertSame("codegen-messages.txt", msgs.toString());
    }
}
