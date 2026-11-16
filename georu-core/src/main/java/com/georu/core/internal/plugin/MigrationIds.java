package com.georu.core.internal.plugin;

import com.georu.core.plugin.MigrationId;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DTO 클래스의 {@link MigrationId} 필드를 찾아 행 식별자를 만든다. 필드가 없으면 null (식별 불가, 로그에는 toString).
 */
final class MigrationIds {

    private static final Map<Class<?>, MigrationIds> CACHE = new ConcurrentHashMap<>();

    private final List<Field> fields;

    private MigrationIds(List<Field> fields) {
        this.fields = fields;
    }

    static MigrationIds of(Class<?> type) {
        if (type == null) {
            return new MigrationIds(List.of());
        }
        return CACHE.computeIfAbsent(type, t -> {
            List<Field> found = new ArrayList<>();
            for (Class<?> c = t; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.isAnnotationPresent(MigrationId.class)) {
                        f.setAccessible(true);
                        found.add(f);
                    }
                }
            }
            return new MigrationIds(List.copyOf(found));
        });
    }

    boolean isEmpty() {
        return fields.isEmpty();
    }

    Object idOf(Object item) {
        if (item == null || fields.isEmpty()) {
            return null;
        }
        try {
            if (fields.size() == 1) {
                return fields.get(0).get(item);
            }
            StringBuilder sb = new StringBuilder();
            for (Field f : fields) {
                if (sb.length() > 0) sb.append('|');
                sb.append(f.get(item));
            }
            return sb.toString();
        } catch (IllegalAccessException e) {
            return null;
        }
    }
}
