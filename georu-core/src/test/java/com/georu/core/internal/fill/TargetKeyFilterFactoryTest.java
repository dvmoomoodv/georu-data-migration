package com.georu.core.internal.fill;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TargetKeyFilterFactory - 타겟 PK 로 이미 있는 행 거르기")
class TargetKeyFilterFactoryTest {

    @Data
    @AllArgsConstructor
    public static class CodeDto {
        private String groupCd;
        private Integer code;
        private String codeNm;
    }

    @Data
    @AllArgsConstructor
    public static class NoKeyDto {
        private String other;
    }

    private DriverManagerDataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        ds = new DriverManagerDataSource();
        ds.setUrl("jdbc:h2:mem:keyfilter_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE tb_code (GROUP_CD VARCHAR(20), CODE INT, CODE_NM VARCHAR(100), PRIMARY KEY (GROUP_CD, CODE))");
            st.execute("INSERT INTO tb_code VALUES ('G1', 1, 'x'), ('G1', 2, 'y')");
        }
    }

    @Test
    @DisplayName("복합 PK: 이미 있는 (G1,1)(G1,2) 는 빼고 없는 행만 순서대로 남긴다")
    void removesExistingByCompositeKey() throws Exception {
        TargetKeyFilterFactory.KeyFilter<CodeDto> f = new TargetKeyFilterFactory.KeyFilter<>(
                new TargetKeyFilterFactory.TargetKey(ds, "tb_code", List.of("GROUP_CD", "CODE")));
        List<CodeDto> left = f.removeExisting(List.of(
                new CodeDto("G1", 1, "a"), new CodeDto("G1", 3, "b"), new CodeDto("G1", 2, "c"), new CodeDto("G2", 1, "d")));
        assertThat(left).extracting(CodeDto::getCode, CodeDto::getGroupCd)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(3, "G1"), org.assertj.core.groups.Tuple.tuple(1, "G2"));
        assertThat(f.describe()).isEqualTo("tb_code(GROUP_CD, CODE)");
    }

    @Test
    @DisplayName("PK 속성이 DTO 에 없으면 예외 (그 묶음은 최종 실패로 처리돼 중복을 만들지 않는다)")
    void missingPropertyFails() {
        TargetKeyFilterFactory.KeyFilter<NoKeyDto> f = new TargetKeyFilterFactory.KeyFilter<>(
                new TargetKeyFilterFactory.TargetKey(ds, "tb_code", List.of("GROUP_CD", "CODE")));
        assertThatThrownBy(() -> f.removeExisting(List.of(new NoKeyDto("z")))).hasMessageContaining("groupCd");
    }

    @Test
    @DisplayName("컬럼 → 속성 이름 규칙 (생성기와 같음)")
    void propertyNames() {
        assertThat(TargetKeyFilterFactory.propertyOf("GROUP_CD")).isEqualTo("groupCd");
        assertThat(TargetKeyFilterFactory.propertyOf("INUSERID")).isEqualTo("inuserid");
        assertThat(TargetKeyFilterFactory.propertyOf("MEMBER_LOGIN_SEQ")).isEqualTo("memberLoginSeq");
    }
}
