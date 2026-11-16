package com.georu.core.processor;

import com.georu.core.plugin.GeoruDataSources;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.SqlSessionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 다른 DataSource 의 값을 붙일 때 쓰는 참조 (N:1 결합). Processor 에서 {@link AbstractItemProcessor#lookup(SourceLookup)} 로 등록한다.
 * 항목마다 한 번씩 조회하는 방식은 없다 - 두 가지만 있다.
 *
 * <ul>
 *   <li>{@link #cached}: 작은 참조(코드표·등급표). Step 에서 처음 찾을 때 통째로 한 번 읽어 둔다</li>
 *   <li>{@link #byKeys}: 큰 참조. 청크에서 읽은 항목의 키를 모아 IN 조회 한 번으로 가져온다 ({@code #{keys}} 로 받는다).
 *       키가 {@link #inSize(int)}(기본 1,000)보다 많으면 나눠 조회한다</li>
 * </ul>
 *
 * <pre>
 * public class TbMemberPointProcessor extends AbstractItemProcessor&lt;TbMemberSourceDto, TbMemberPointTargetDto&gt; {
 *     private final SourceLookup&lt;String, Map&lt;String, Object&gt;&gt; branches = lookup(
 *             SourceLookup.cached("source-1", "georu.demo.BranchLookupMapper.selectAll", r -&gt; (String) r.get("BR_CD")));
 *     private final SourceLookup&lt;Long, Map&lt;String, Object&gt;&gt; points = lookup(
 *             SourceLookup.byKeys("source-1", "georu.demo.PointLookupMapper.selectByKeys",
 *                     (TbMemberSourceDto m) -&gt; m.getMemberId(), r -&gt; ((Number) r.get("MEMBER_ID")).longValue()));
 *
 *     protected TbMemberPointTargetDto processItem(TbMemberSourceDto m) {
 *         Map&lt;String, Object&gt; branch = branches.get(m.getBrCd());
 *         Map&lt;String, Object&gt; point = points.get(m.getMemberId());
 *         ...
 *     }
 * }
 * </pre>
 *
 * SQL 은 그 DataSource 에 실리는 매퍼 XML 에 둔다 (파일 이름 {@code *LookupMapper.xml} 은 소스·타겟 DataSource 에 실린다).
 * 키는 항목 쪽과 행 쪽이 같은 타입이어야 찾아진다 (Oracle NUMBER 는 BigDecimal 로 오므로 {@code longValue()} 등으로 맞춘다).
 *
 * @param <K> 키 타입
 * @param <V> 행 타입 (SQL 의 resultType)
 */
@Slf4j
public final class SourceLookup<K, V> {

    /** cached 로 읽은 행이 이보다 많으면 byKeys 를 쓰라고 경고 */
    static final int CACHED_WARN_ROWS = 100_000;

    private final boolean cached;
    private final String dataSource;
    private final String statement;
    private final Function<Object, K> itemKey;
    private final Function<V, K> rowKey;
    private final Map<String, Object> params = new LinkedHashMap<>();
    private int inSize = 1000;

    private SqlSessionTemplate session;
    private final Map<K, V> rows = new HashMap<>();
    /** 조회했지만 행이 없던 키 (다시 조회하지 않는다) */
    private final Set<K> absent = new HashSet<>();
    private final Set<K> pending = new LinkedHashSet<>();
    private boolean loaded;
    private long queries;
    private long fetched;

    private SourceLookup(boolean cached, String dataSource, String statement, Function<Object, K> itemKey, Function<V, K> rowKey) {
        if (dataSource == null || dataSource.isBlank() || statement == null || statement.isBlank() || rowKey == null) {
            throw new IllegalArgumentException("SourceLookup 에는 DataSource 이름, SQL id(namespace.id), 행 키가 필요합니다");
        }
        this.cached = cached;
        this.dataSource = dataSource;
        this.statement = statement;
        this.itemKey = itemKey;
        this.rowKey = rowKey;
    }

    /** 작은 참조: SQL 결과를 통째로 한 번 읽어 rowKey 로 찾는다 */
    public static <K, V> SourceLookup<K, V> cached(String dataSource, String statement, Function<V, K> rowKey) {
        return new SourceLookup<>(true, dataSource, statement, null, rowKey);
    }

    /**
     * 큰 참조: 청크에서 읽은 항목마다 itemKey 로 키를 모아, 처음 찾을 때 IN 조회 한 번으로 가져온다.
     * SQL 은 {@code <foreach collection="keys" ...>} 로 키 목록을 받는다
     */
    @SuppressWarnings("unchecked")
    public static <I, K, V> SourceLookup<K, V> byKeys(String dataSource, String statement, Function<I, K> itemKey, Function<V, K> rowKey) {
        if (itemKey == null) {
            throw new IllegalArgumentException("byKeys 에는 항목에서 키를 꺼내는 함수가 필요합니다");
        }
        return new SourceLookup<>(false, dataSource, statement, item -> itemKey.apply((I) item), rowKey);
    }

    /** SQL 에 고정 조건을 넘긴다 ({@code #{params.<키>}}) */
    public SourceLookup<K, V> param(String key, Object value) {
        params.put(key, value);
        return this;
    }

    /** byKeys: IN 목록 하나에 넣을 키 수 (기본 1,000. Oracle 은 1,000 이 한도) */
    public SourceLookup<K, V> inSize(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("inSize 는 1 이상");
        }
        this.inSize = size;
        return this;
    }

    /** 키의 행. 없으면 null */
    public V get(K key) {
        if (key == null) {
            return null;
        }
        if (session == null) {
            throw new IllegalStateException("SourceLookup " + statement + " 이 Step 에 연결되지 않았습니다. Processor 에서 lookup(...) 으로 등록하세요");
        }
        if (cached) {
            if (!loaded) {
                loadAll();
            }
            return rows.get(key);
        }
        if (!rows.containsKey(key) && !absent.contains(key)) {
            pending.add(key);
            loadPending();
        }
        return rows.get(key);
    }

    public Optional<V> find(K key) {
        return Optional.ofNullable(get(key));
    }

    public String dataSource() {
        return dataSource;
    }

    public String statement() {
        return statement;
    }

    public boolean isCached() {
        return cached;
    }

    /** 이번 Step 에서 실행한 조회 수 */
    public long queryCount() {
        return queries;
    }

    /** 이번 Step 에서 가져온 행 수 */
    public long fetchedRows() {
        return fetched;
    }

    // ------------------------------------------------------------------ Step·청크 (AbstractItemProcessor 가 부른다)

    void bind(GeoruDataSources dataSources) {
        org.apache.ibatis.session.SqlSessionFactory factory = dataSources.sqlSessionFactory(dataSource);
        if (!factory.getConfiguration().hasStatement(statement)) {
            throw new IllegalStateException("SourceLookup SQL " + statement + " 이 DataSource " + dataSource
                    + " 에 없습니다 (매퍼 XML 이 그 DataSource 에 실리는지 확인: *LookupMapper.xml, 또는 카탈로그가 배정한 namespace)");
        }
        session = new SqlSessionTemplate(factory);
        clear();
        queries = 0;
        fetched = 0;
    }

    void unbind() {
        session = null;
        clear();
    }

    /** 새 청크: byKeys 는 앞 청크의 행을 버린다 */
    void startChunk() {
        if (!cached) {
            clear();
        }
    }

    /** 청크에서 읽은 항목 (byKeys 는 키를 모은다) */
    void collect(Object item) {
        if (cached || item == null) {
            return;
        }
        K key = itemKey.apply(item);
        if (key != null && !rows.containsKey(key) && !absent.contains(key)) {
            pending.add(key);
        }
    }

    private void clear() {
        rows.clear();
        absent.clear();
        pending.clear();
        loaded = false;
    }

    private void loadAll() {
        List<V> list = session.selectList(statement, query(null));
        queries++;
        int duplicated = put(list);
        loaded = true;
        log.info("[SourceLookup] {}@{} 캐시 {}건", statement, dataSource, rows.size());
        if (duplicated > 0) {
            log.warn("[SourceLookup] {} 키가 겹치는 행 {}건은 처음 행을 씁니다", statement, duplicated);
        }
        if (rows.size() > CACHED_WARN_ROWS) {
            log.warn("[SourceLookup] {} 캐시가 {}건입니다 - 큰 참조는 SourceLookup.byKeys 를 쓰세요", statement, rows.size());
        }
    }

    private void loadPending() {
        List<K> keys = new ArrayList<>(pending);
        pending.clear();
        for (int i = 0; i < keys.size(); i += inSize) {
            List<K> part = keys.subList(i, Math.min(keys.size(), i + inSize));
            put(session.selectList(statement, query(part)));
            queries++;
            for (K k : part) {
                if (!rows.containsKey(k)) {
                    absent.add(k);
                }
            }
        }
    }

    private Map<String, Object> query(List<K> keys) {
        Map<String, Object> q = new HashMap<>();
        q.put("params", params);
        if (keys != null) {
            q.put("keys", keys);
        }
        return q;
    }

    private int put(List<V> list) {
        int duplicated = 0;
        for (V row : list) {
            K k = rowKey.apply(row);
            if (rows.putIfAbsent(k, row) != null) {
                duplicated++;
            }
        }
        fetched += list.size();
        return duplicated;
    }

    @Override
    public String toString() {
        return (cached ? "cached " : "byKeys ") + statement + "@" + dataSource;
    }
}
