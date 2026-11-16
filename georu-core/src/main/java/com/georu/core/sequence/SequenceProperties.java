package com.georu.core.sequence;

import com.georu.core.internal.sequence.SequenceVersions;

import com.georu.core.sequence.model.KeyCase;
import com.georu.core.sequence.model.SequenceTableSpec;
import com.georu.core.internal.sequence.support.IdentifierValidator;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 시퀀스 관리 테이블 공통 설정 ({@code georu.sequence.*}). 정책 버전과 무관한 값만 둔다.
 * 정책별 설정은 각 버전 패키지의 Properties 가 갖는다 (예: {@code georu.sequence.v1.*}).
 *
 * <pre>
 * migration:
 *   sequence:
 *     enabled: true
 *     policy: v1
 *     table: mig_sequence
 *     table-name-column: TABLES
 *     column-name-column: COL
 *     value-column: SEQ
 *     key-case: UPPER
 *     create-if-missing: true
 *     initial-value: 0
 * </pre>
 *
 * 테이블명·컬럼명은 매퍼 XML 에 {@code ${cfg.*}} 로 바인딩되므로 기동 시 식별자 검증을 통과해야 한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "georu.sequence")
public class SequenceProperties {

    /** 기능 전체 on/off. false 면 채번 컬럼이 지정돼도 Processor 가 건너뛴다 (경고 로그) */
    private boolean enabled = true;

    /** 활성 정책 버전. 비어 있으면 {@link SequenceVersions#DEFAULT} */
    private String policy = SequenceVersions.DEFAULT;

    /** 관리 테이블명 */
    private String table = "mig_sequence";

    /** 대상 테이블명을 담는 컬럼 */
    private String tableNameColumn = "TABLES";

    /** 대상 컬럼명을 담는 컬럼 */
    private String columnNameColumn = "COL";

    /** 현재값(마지막 사용값) 컬럼 */
    private String valueColumn = "SEQ";

    /** 키 값 대소문자 규칙 */
    private KeyCase keyCase = KeyCase.UPPER;

    /** 관리행이 없으면 initial-value 로 생성할지. false 면 오류 */
    private boolean createIfMissing = true;

    /** 새 관리행의 시작값 ("마지막 사용값" 이므로 첫 번호는 initial-value + 1) */
    private long initialValue = 0L;

    @PostConstruct
    public void validate() {
        IdentifierValidator.requireTable("georu.sequence.table", table);
        IdentifierValidator.requireColumn("georu.sequence.table-name-column", tableNameColumn);
        IdentifierValidator.requireColumn("georu.sequence.column-name-column", columnNameColumn);
        IdentifierValidator.requireColumn("georu.sequence.value-column", valueColumn);
        if (keyCase == null) {
            keyCase = KeyCase.UPPER;
        }
    }

    /** 매퍼에 넘기는 불변 스펙 */
    public SequenceTableSpec toSpec() {
        return new SequenceTableSpec(table, tableNameColumn, columnNameColumn, valueColumn);
    }

    /** 활성 정책 버전 (정규화, 기본값 적용) */
    public String policyVersion() {
        String v = SequenceVersions.normalize(policy);
        return v == null ? SequenceVersions.DEFAULT : v;
    }
}
