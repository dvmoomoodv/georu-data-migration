package com.georu.core.internal.sequence.support;

import com.georu.core.internal.sequence.SequenceException;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 채번값을 Target DTO 의 setter 로 넣는다. 플러그인 DTO 를 수정하지 않기 위해 리플렉션을 쓴다
 * (Writer 의 applyTestAudit 와 같은 방식). 클래스·컬럼별로 한 번 찾아 캐시한다.
 *
 * <p>컬럼명 → 프로퍼티: {@code TEMPLATE_SEQ} → {@code templateSeq}, {@code SEQ} → {@code seq}, {@code templateSeq} 는 그대로.</p>
 * <p>지원 타입: long/Long, int/Integer, short/Short, double/Double, BigDecimal, BigInteger, String</p>
 */
public final class SequenceValueInjector {

    private record CacheKey(Class<?> type, String column) {
    }

    private static final Map<CacheKey, Optional<Method>> SETTERS = new ConcurrentHashMap<>();
    private static final Map<CacheKey, Optional<Method>> GETTERS = new ConcurrentHashMap<>();

    private SequenceValueInjector() {
    }

    /** 컬럼명을 JavaBean 프로퍼티명으로 */
    public static String propertyName(String column) {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("컬럼명이 비어 있습니다");
        }
        String c = column.trim();
        boolean snake = c.contains("_") || c.equals(c.toUpperCase(Locale.ROOT));
        if (!snake) {
            return Character.toLowerCase(c.charAt(0)) + c.substring(1);
        }
        StringBuilder sb = new StringBuilder();
        boolean upperNext = false;
        for (char ch : c.toLowerCase(Locale.ROOT).toCharArray()) {
            if (ch == '_') {
                upperNext = sb.length() > 0;
                continue;
            }
            sb.append(upperNext ? Character.toUpperCase(ch) : ch);
            upperNext = false;
        }
        return sb.toString();
    }

    public static Optional<Method> setter(Class<?> type, String column) {
        return SETTERS.computeIfAbsent(new CacheKey(type, column), k -> findSetter(k.type(), k.column()));
    }

    public static Optional<Method> getter(Class<?> type, String column) {
        return GETTERS.computeIfAbsent(new CacheKey(type, column), k -> findGetter(k.type(), k.column()));
    }

    /** DTO 에 이미 값이 있는지 (getter 가 없으면 false) */
    public static boolean hasValue(Object dto, String column) {
        if (dto == null) {
            return false;
        }
        Optional<Method> g = getter(dto.getClass(), column);
        if (g.isEmpty()) {
            return false;
        }
        try {
            Object v = g.get().invoke(dto);
            return v != null && !(v instanceof String s && s.isBlank());
        } catch (Exception e) {
            return false;
        }
    }

    /** 채번값 주입. setter 가 없거나 타입을 변환할 수 없으면 {@link SequenceException} */
    public static void set(Object dto, String column, long value) {
        if (dto == null) {
            return;
        }
        Method m = setter(dto.getClass(), column).orElseThrow(() -> new SequenceException(
                dto.getClass().getSimpleName() + " 에 채번 컬럼 " + column + " 의 setter(" + setterName(column) + ") 가 없습니다"));
        Class<?> target = m.getParameterTypes()[0];
        try {
            m.invoke(dto, convert(value, target));
        } catch (SequenceException e) {
            throw e;
        } catch (Exception e) {
            throw new SequenceException(dto.getClass().getSimpleName() + "." + m.getName() + " 호출 실패: " + e.getMessage(), e);
        }
    }

    /** setter 가 없는 컬럼 목록 (실행 전 점검·Step 시작 검증용) */
    public static List<String> missingSetters(Class<?> type, Collection<String> columns) {
        List<String> missing = new ArrayList<>();
        if (columns == null) {
            return missing;
        }
        for (String c : columns) {
            if (setter(type, c).isEmpty()) {
                missing.add(c);
            }
        }
        return missing;
    }

    static Object convert(long value, Class<?> target) {
        if (target == long.class || target == Long.class) {
            return value;
        }
        if (target == int.class || target == Integer.class) {
            if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
                throw new SequenceException("채번값 " + value + " 이(가) int 범위를 넘습니다");
            }
            return (int) value;
        }
        if (target == short.class || target == Short.class) {
            if (value > Short.MAX_VALUE || value < Short.MIN_VALUE) {
                throw new SequenceException("채번값 " + value + " 이(가) short 범위를 넘습니다");
            }
            return (short) value;
        }
        if (target == double.class || target == Double.class) {
            return (double) value;
        }
        if (target == BigDecimal.class) {
            return BigDecimal.valueOf(value);
        }
        if (target == BigInteger.class) {
            return BigInteger.valueOf(value);
        }
        if (target == String.class) {
            return Long.toString(value);
        }
        if (target == Object.class || target == Number.class) {
            return value;
        }
        throw new SequenceException("채번값을 넣을 수 없는 타입입니다: " + target.getName());
    }

    private static String setterName(String column) {
        String p = propertyName(column);
        return "set" + Character.toUpperCase(p.charAt(0)) + p.substring(1);
    }

    private static String getterName(String column) {
        String p = propertyName(column);
        return "get" + Character.toUpperCase(p.charAt(0)) + p.substring(1);
    }

    private static Optional<Method> findSetter(Class<?> type, String column) {
        String name = setterName(column);
        Method fallback = null;
        for (Method m : type.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != 1) {
                continue;
            }
            Class<?> p = m.getParameterTypes()[0];
            if (isSupported(p)) {
                return Optional.of(accessible(m));
            }
            fallback = m;
        }
        return Optional.ofNullable(fallback).map(SequenceValueInjector::accessible);
    }

    private static Optional<Method> findGetter(Class<?> type, String column) {
        try {
            return Optional.of(accessible(type.getMethod(getterName(column))));
        } catch (NoSuchMethodException e) {
            return Optional.empty();
        }
    }

    /** DTO 클래스가 public 이 아니어도(패키지 내부 클래스 등) 호출할 수 있게 한다 */
    private static Method accessible(Method m) {
        try {
            m.setAccessible(true);
        } catch (RuntimeException ignored) {
            // 모듈 제한 등으로 실패하면 public 접근으로만 시도
        }
        return m;
    }

    private static boolean isSupported(Class<?> p) {
        return p == long.class || p == Long.class || p == int.class || p == Integer.class
                || p == short.class || p == Short.class || p == double.class || p == Double.class
                || p == BigDecimal.class || p == BigInteger.class || p == String.class
                || p == Number.class || p == Object.class;
    }
}
