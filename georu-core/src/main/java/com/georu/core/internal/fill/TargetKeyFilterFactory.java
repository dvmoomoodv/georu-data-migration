package com.georu.core.internal.fill;

import com.georu.core.internal.common.writer.queue.ExistingKeyFilter;
import com.georu.core.internal.product.guard.TargetTableResolver;
import com.georu.core.internal.product.precheck.MetadataReader;
import com.georu.core.internal.product.precheck.TableMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 타겟 PK 로 "이미 있는 행" 을 거르는 필터를 만든다 (적재 재시도·빠진 행 채우기).
 *
 * <ul>
 *   <li>타겟 DataSource·테이블은 {@link TargetTableResolver}(Writer 가 실제로 쓰는 DB), PK 는 {@link MetadataReader}</li>
 *   <li>PK 컬럼 → Target DTO 속성은 생성기 규칙(GROUP_CD → groupCd). 첫 묶음에서 속성이 없으면 예외 → 그 묶음은 최종 실패</li>
 *   <li>PK 가 없으면 만들지 않는다(키를 맞출 수 없음)</li>
 * </ul>
 * 조회는 묶음을 500건씩 나눠 {@code SELECT pk.. FROM t WHERE (pk..) IN (...)} 로 한다(타겟 PK 인덱스 사용).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TargetKeyFilterFactory {

    static final int IN_BATCH = 500;

    private final TargetTableResolver targetTableResolver;
    private final MetadataReader metadataReader;

    /** 타겟 키 정보 (빠진 행 채우기 확인에도 쓴다) */
    public record TargetKey(DataSource dataSource, String table, List<String> pkColumns) {}

    public Optional<TargetKey> targetKey(String pluginName, String tableName) {
        Optional<TargetTableResolver.TargetRef> ref = targetTableResolver.resolve(pluginName, tableName);
        if (ref.isEmpty()) {
            return Optional.empty();
        }
        TableMeta meta = metadataReader.read(ref.get().dataSource(), ref.get().tableName());
        if (meta == null || !meta.isFound() || meta.getPrimaryKey() == null || meta.getPrimaryKey().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TargetKey(ref.get().dataSource(), ref.get().tableName(), List.copyOf(meta.getPrimaryKey())));
    }

    /** DataSource·테이블을 직접 줄 때 (한 번 읽어 여러 곳에 쓰기의 타겟마다). PK 가 없으면 empty */
    public <T> Optional<ExistingKeyFilter<T>> create(DataSource dataSource, String tableName) {
        TableMeta meta = metadataReader.read(dataSource, tableName);
        if (meta == null || !meta.isFound() || meta.getPrimaryKey() == null || meta.getPrimaryKey().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new KeyFilter<>(new TargetKey(dataSource, tableName, List.copyOf(meta.getPrimaryKey()))));
    }

    public <T> Optional<ExistingKeyFilter<T>> create(String pluginName, String tableName) {
        try {
            return targetKey(pluginName, tableName).map(KeyFilter::new);
        } catch (Exception e) {
            log.warn("타겟 키 거르기를 만들지 못함 {}.{}: {}", pluginName, tableName, e.getMessage());
            return Optional.empty();
        }
    }

    /** GROUP_CD → groupCd */
    static String propertyOf(String column) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : column.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '_') {
                upper = sb.length() > 0;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }

    static String keyOf(List<Object> values) {
        StringBuilder sb = new StringBuilder();
        for (Object v : values) {
            sb.append(v == null ? "\u0000" : String.valueOf(v).trim()).append('\u0001');
        }
        return sb.toString();
    }

    static final class KeyFilter<T> implements ExistingKeyFilter<T> {
        private final TargetKey key;
        private final List<String> properties;

        KeyFilter(TargetKey key) {
            this.key = key;
            this.properties = key.pkColumns().stream().map(TargetKeyFilterFactory::propertyOf).toList();
        }

        @Override
        public List<T> removeExisting(List<T> items) throws Exception {
            List<List<Object>> keys = new ArrayList<>(items.size());
            for (T item : items) {
                BeanWrapperImpl bw = new BeanWrapperImpl(item);
                List<Object> values = new ArrayList<>(properties.size());
                for (String p : properties) {
                    if (!bw.isReadableProperty(p)) {
                        throw new IllegalStateException("타겟 키 대조 불가: " + item.getClass().getSimpleName() + " 에 PK 속성 '" + p + "' 가 없습니다 (" + describe() + ")");
                    }
                    values.add(bw.getPropertyValue(p));
                }
                keys.add(values);
            }
            Set<String> existing = new HashSet<>();
            try (Connection conn = key.dataSource().getConnection()) {
                for (int from = 0; from < keys.size(); from += IN_BATCH) {
                    List<List<Object>> part = keys.subList(from, Math.min(keys.size(), from + IN_BATCH));
                    existing.addAll(query(conn, part));
                }
            }
            List<T> remaining = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                if (!existing.contains(keyOf(keys.get(i)))) {
                    remaining.add(items.get(i));
                }
            }
            return remaining;
        }

        private Set<String> query(Connection conn, List<List<Object>> part) throws Exception {
            List<String> cols = key.pkColumns();
            String colList = String.join(", ", cols);
            String one = cols.size() == 1 ? "?" : "(" + String.join(", ", java.util.Collections.nCopies(cols.size(), "?")) + ")";
            String in = String.join(", ", java.util.Collections.nCopies(part.size(), one));
            String sql = "SELECT " + colList + " FROM " + key.table() + " WHERE "
                    + (cols.size() == 1 ? cols.get(0) : "(" + colList + ")") + " IN (" + in + ")";
            Set<String> found = new HashSet<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                for (List<Object> values : part) {
                    for (Object v : values) {
                        ps.setObject(idx++, v);
                    }
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        List<Object> row = new ArrayList<>(cols.size());
                        for (int c = 1; c <= cols.size(); c++) {
                            row.add(rs.getObject(c));
                        }
                        found.add(keyOf(row));
                    }
                }
            }
            return found;
        }

        @Override
        public String describe() {
            return key.table() + "(" + String.join(", ", key.pkColumns()) + ")";
        }
    }
}
