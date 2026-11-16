package com.georu.core.plugin.togglefixture.hr;

import com.georu.core.plugin.GeoruPlugin;
import com.georu.core.plugin.TableCatalog;
import com.georu.core.plugin.TableDef;

import java.util.List;

@GeoruPlugin(name = "hr.member")
public class HrCatalog implements TableCatalog {

    @Override
    public List<TableDef> tables() {
        return List.of();
    }
}
