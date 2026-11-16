package com.georu.core.plugin;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * DTO 의 PK 필드 표시. 공용 Writer·Processor 가 실패 로그·빠진 행 채우기에서 행을 식별하는 데 쓴다.
 * 복합 키면 여러 필드에 붙인다 (순서대로 이어 붙여 표시).
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MigrationId {
}
