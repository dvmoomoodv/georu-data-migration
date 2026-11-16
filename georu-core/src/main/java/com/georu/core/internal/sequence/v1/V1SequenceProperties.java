package com.georu.core.internal.sequence.v1;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * v1(블록 선점) 정책 전용 설정 ({@code georu.sequence.v1.*}).
 *
 * <pre>
 * migration:
 *   sequence:
 *     v1:
 *       dialect: auto            # auto | mysql | generic
 *       block-size: 1000         # 일괄 선점 크기. 1 이면 건별 +1 갱신
 *       overwrite-existing: false
 *       lock-timeout-seconds: 10 # generic 방언(FOR UPDATE) 트랜잭션 타임아웃
 *       sync-before-run: true    # Step 시작 때 관리 테이블 값을 타겟 MAX 이상으로 맞춘 뒤 채번
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "georu.sequence.v1")
public class V1SequenceProperties {

    public static final String DIALECT_AUTO = "auto";

    /** 방언 선택. auto 면 타깃 DataSource 의 DB 제품명으로 판별 */
    private String dialect = DIALECT_AUTO;

    /** 한 번에 선점하는 번호 수 */
    private int blockSize = 1000;

    /** DTO 에 이미 값이 있어도 채번값으로 덮어쓸지 */
    private boolean overwriteExisting = false;

    /** 선점 트랜잭션 타임아웃(초) */
    private int lockTimeoutSeconds = 10;

    /**
     * Step 시작 때 관리 테이블 현재값을 타겟 MAX(채번 컬럼) 이상으로 올린 뒤 채번한다.
     * 관리행이 없거나 값이 타겟보다 뒤처져도 기존 데이터 다음 번호부터 나간다. false 면 관리 테이블 값만 본다.
     * DRY_RUN 은 관리 테이블을 바꾸지 않고 시작값만 계산한다.
     */
    private boolean syncBeforeRun = true;

    @PostConstruct
    public void validate() {
        if (blockSize < 1) {
            throw new IllegalStateException("georu.sequence.v1.block-size 는 1 이상이어야 합니다: " + blockSize);
        }
        if (lockTimeoutSeconds < 1) {
            lockTimeoutSeconds = 10;
        }
        if (dialect == null || dialect.isBlank()) {
            dialect = DIALECT_AUTO;
        }
    }
}
