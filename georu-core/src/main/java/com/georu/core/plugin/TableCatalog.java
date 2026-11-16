package com.georu.core.plugin;

import java.util.List;

/**
 * 플러그인이 이관하는 테이블 목록. {@link GeoruPlugin} 과 함께 쓴다.
 * 목록 순서가 권장 실행 순서(부모 → 자식)다.
 */
public interface TableCatalog {

    List<TableDef> tables();
}
