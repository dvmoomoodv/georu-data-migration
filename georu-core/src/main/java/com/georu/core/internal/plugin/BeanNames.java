package com.georu.core.internal.plugin;

/**
 * 테이블명 → 엔진이 찾는 Bean 이름. {@code tb_member} → {@code tbMemberReader} / {@code tbMemberProcessor} /
 * {@code tbMemberWriter} / {@code tbMemberSourceMapper}. DynamicBeanFactory·StrategyUtils 의 규칙과 같다.
 */
public final class BeanNames {

    private BeanNames() {
    }

    /** tb_member → TbMember */
    public static String camel(String table) {
        StringBuilder sb = new StringBuilder();
        for (String part : table.split("_")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) sb.append(part.substring(1).toLowerCase(java.util.Locale.ROOT));   // 생성기 Names · DynamicBeanFactory 와 같게 (터키어 로케일 I → ı 방지)
        }
        return sb.toString();
    }

    /** tb_member → tbMember */
    public static String lowerCamel(String table) {
        String c = camel(table);
        return c.isEmpty() ? c : Character.toLowerCase(c.charAt(0)) + c.substring(1);
    }

    public static String reader(String table) { return lowerCamel(table) + "Reader"; }
    public static String processor(String table) { return lowerCamel(table) + "Processor"; }
    public static String writer(String table) { return lowerCamel(table) + "Writer"; }
    public static String sourceMapper(String table) { return lowerCamel(table) + "SourceMapper"; }

    /** hr.member → georuPlugin.hr.member */
    public static String plugin(String pluginName) { return "georuPlugin." + pluginName; }
    public static String strategy(String pluginName) { return "georuStrategy." + pluginName; }
}
