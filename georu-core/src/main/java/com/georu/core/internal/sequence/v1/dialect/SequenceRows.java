package com.georu.core.internal.sequence.v1.dialect;

import com.georu.core.sequence.model.SequenceRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 매퍼의 {@code resultType="map"} 결과를 {@link SequenceRow} 로. 드라이버마다 컬럼 별칭 대소문자가 달라 무시하고 찾는다 */
public final class SequenceRows {

    public static final String ALIAS_TABLE = "SEQ_TBL";
    public static final String ALIAS_COLUMN = "SEQ_COL";
    public static final String ALIAS_VALUE = "SEQ_VAL";

    private SequenceRows() {
    }

    public static List<SequenceRow> fromMaps(List<Map<String, Object>> rows) {
        List<SequenceRow> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (Map<String, Object> r : rows) {
            Object t = get(r, ALIAS_TABLE);
            Object c = get(r, ALIAS_COLUMN);
            Object v = get(r, ALIAS_VALUE);
            out.add(new SequenceRow(t == null ? null : t.toString(), c == null ? null : c.toString(), toLong(v)));
        }
        return out;
    }

    private static Object get(Map<String, Object> row, String key) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    public static long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(v.toString().trim());
    }
}
