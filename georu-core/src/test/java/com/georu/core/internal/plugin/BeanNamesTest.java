package com.georu.core.internal.plugin;

import com.georu.core.plugin.TableDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("이름 규칙: 테이블 이름 → Bean · namespace (JVM 로케일과 무관)")
class BeanNamesTest {

    @Test
    @DisplayName("터키어 로케일에서도 I 가 ı 가 되지 않는다 (생성 XML namespace · Bean 조회 이름과 같아야 한다)")
    void localeIndependent() {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertThat(BeanNames.camel("TB_LIST_ITEM")).isEqualTo("TbListItem");
            assertThat(BeanNames.reader("TB_LIST")).isEqualTo("tbListReader");
            TableDef def = TableDef.of("TB_LIST", Object.class, Object.class);
            assertThat(def.table()).isEqualTo("tb_list");
            assertThat(def.resolveSourceNamespace("acme.x")).isEqualTo("georu.acme.x.TbListSourceMapper");
        } finally {
            Locale.setDefault(before);
        }
    }
}
