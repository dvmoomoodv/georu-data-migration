package com.georu.core.plugin;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 플러그인 선언. {@link TableCatalog} 를 구현한 클래스에 붙이면 엔진이 테이블마다 Reader·Processor·Writer·소스 매퍼 Bean 을
 * 이름 규칙(<code>tbMemberReader</code> 등)으로 등록하고, 플러그인(DomainPlugin)과 Strategy 를 만든다.
 *
 * <pre>
 * &#64;GeoruPlugin(name = "hr.member", description = "회원", source = "source-1")   // target 생략 = target-m
 * public class MemberPlugin implements TableCatalog {
 *     public List&lt;TableDef&gt; tables() {
 *         return List.of(
 *             TableDef.of("tb_member_grade", TbMemberGradeSourceDto.class, TbMemberGradeTargetDto.class),
 *             TableDef.of("tb_member", TbMemberSourceDto.class, TbMemberTargetDto.class)
 *                     .processor(TbMemberProcessor.class)
 *                     .options(TableOptions.builder().sequenceSyncColumns(List.of("MEMBER_ID")).build()));
 *     }
 * }
 * </pre>
 *
 * <p>카탈로그 클래스는 기본 생성자로 만들어진다(설정 선언만 담는다). 토글은 {@code georu.plugins.<name>.enabled}
 * 또는 첫 마디({@code georu.plugins.hr.enabled})로 끈다.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface GeoruPlugin {

    /** 플러그인 이름. Job 키·실행 요청의 pluginName (예: hr.member) */
    String name();

    String description() default "";

    String version() default "1.0.0";

    /** 소스 DataSource 이름 (georu.datasource.&lt;이름&gt;). 기본 source-m. 옛 이름 oracle 도 받는다 */
    String source() default "source-m";

    /** 타겟 DataSource 이름 (georu.datasource.&lt;이름&gt;). 기본 target-m. 옛 이름 mysql 도 받는다 */
    String target() default "target-m";
}
