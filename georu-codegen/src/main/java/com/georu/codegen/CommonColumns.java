package com.georu.codegen;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 공통 컬럼 규칙 (기본 켜짐, {@link CodegenSettings#commonColumns(boolean)}). TOBE 표준 이름은
 * 감사 컬럼 {@code REG_DATE · REG_ID · UPT_DATE · UPT_ID} 와 일반 컬럼 {@code USE_YN · DEL_YN} 이다.
 *
 * <ul>
 *   <li>원천에 같은 이름이 없으면 흔히 쓰는 이름(예: REG_DT · CREATED_AT · MOD_ID · USE_AT · DEL_FLAG)을 순서대로 찾는다</li>
 *   <li>DEL_YN · USE_YN 은 삭제일(DEL_DATE 등)이나 서로의 반대 값으로도 채운다</li>
 *   <li>Processor 는 값이 비면 기본값을 넣는다: 일시 = 지금, 등록자 · 수정자 = 이관 등록자, USE_YN = Y, DEL_YN = N</li>
 * </ul>
 */
final class CommonColumns {

    static final Map<String, List<String>> SYNONYMS = Map.of(
            "REG_DATE", List.of("REG_DT", "REG_DTM", "REG_DATETIME", "REGDATE", "REG_YMD", "CREATE_DATE", "CREATED_DATE", "CREATED_AT",
                    "CRT_DATE", "CRT_DT", "INS_DATE", "INS_DT", "INDATE", "FRST_REG_DT", "FIRST_REG_DATE"),
            "REG_ID", List.of("REG_USER", "REG_USER_ID", "REG_EMP", "REGID", "REGISTER_ID", "CREATED_BY", "CREATE_ID", "CREATE_USER",
                    "CRT_ID", "INS_ID", "INS_USER", "INUSERID", "FRST_REG_ID", "FIRST_REG_ID"),
            "UPT_DATE", List.of("UPD_DATE", "UPD_DT", "UPD_DTM", "UPDT_DATE", "UPDT_DT", "UPDATE_DATE", "UPDATED_DATE", "UPDATED_AT",
                    "MOD_DATE", "MOD_DT", "MODIFY_DATE", "CHG_DATE", "CHG_DT", "LDATE", "LAST_UPD_DT", "LAST_UPDATE_DATE"),
            "UPT_ID", List.of("UPD_ID", "UPD_USER", "UPDT_ID", "UPDATE_ID", "UPDATED_BY", "MOD_ID", "MOD_USER", "MODIFY_ID",
                    "CHG_ID", "LUSERID", "LAST_UPD_ID"),
            "USE_YN", List.of("USE_AT", "USE_FLAG", "USEYN", "USED_YN", "ENABLE_YN", "ACTIVE_YN"),
            "DEL_YN", List.of("DEL_AT", "DEL_FLAG", "DELYN", "DELETE_YN", "DELETED_YN"));

    /** 삭제일 컬럼 (있으면 DEL_YN · USE_YN 을 유무로 채운다) */
    static final List<String> DELETED_DATE = List.of("DEL_DATE", "DEL_DT", "DELETE_DATE", "DELETED_DATE", "DELETED_AT");

    private CommonColumns() {
    }

    static boolean standard(String column) {
        return SYNONYMS.containsKey(column.toUpperCase(Locale.ROOT));
    }

    /** 원천에서 이 표준 컬럼을 채울 컬럼 (흔히 쓰는 이름). 없으면 null */
    static SourceCatalog.SourceColumn synonym(String column, SourceCatalog.SourceTable meta) {
        for (String s : SYNONYMS.getOrDefault(column.toUpperCase(Locale.ROOT), List.of())) {
            SourceCatalog.SourceColumn c = meta.column(s);
            if (c != null) return c;
        }
        return null;
    }

    /** DEL_YN · USE_YN 을 다른 컬럼으로 만드는 식. [식, 주석에 쓸 원천 설명], 없으면 null */
    static String[] derived(String column, SourceCatalog.SourceTable meta, SqlDialect src) {
        String c = column.toUpperCase(Locale.ROOT);
        if (!c.equals("DEL_YN") && !c.equals("USE_YN")) return null;
        boolean del = c.equals("DEL_YN");
        for (String d : DELETED_DATE) {
            SourceCatalog.SourceColumn dc = meta.column(d);
            if (dc != null) {
                String e = "A." + src.quote(dc.name());
                return new String[]{"CASE WHEN " + e + " IS NULL THEN '" + (del ? "N" : "Y") + "' ELSE '" + (del ? "Y" : "N") + "' END",
                        dc.name() + " 유무"};
            }
        }
        String other = del ? "USE_YN" : "DEL_YN";
        SourceCatalog.SourceColumn oc = meta.column(other);
        if (oc == null) oc = synonym(other, meta);
        if (oc != null) {
            return new String[]{"CASE WHEN A." + src.quote(oc.name()) + " = 'Y' THEN 'N' ELSE 'Y' END", oc.name() + " 반대 값"};
        }
        return null;
    }

    /** Processor 기본값 식 (값이 비었을 때). 타입이 맞지 않으면 null */
    static String defaultValue(String column, String javaType, int length, String convertUtils) {
        String c = column.toUpperCase(Locale.ROOT);
        return switch (c) {
            case "REG_DATE", "UPT_DATE" -> switch (javaType) {
                case "String" -> length == 8 ? convertUtils + ".toYmd(java.time.LocalDateTime.now())" : convertUtils + ".nowYmdHms()";
                case "LocalDateTime" -> "java.time.LocalDateTime.now()";
                case "LocalDate" -> "java.time.LocalDate.now()";
                default -> null;
            };
            case "REG_ID", "UPT_ID" -> javaType.equals("String") ? convertUtils + ".MIG_USER" : null;
            case "USE_YN" -> javaType.equals("String") ? "\"Y\"" : null;
            case "DEL_YN" -> javaType.equals("String") ? "\"N\"" : null;
            default -> null;
        };
    }
}
