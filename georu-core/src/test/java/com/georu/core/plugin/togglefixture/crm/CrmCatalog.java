package com.georu.core.plugin.togglefixture.crm;

import com.georu.core.plugin.GeoruPlugin;
import com.georu.core.plugin.TableCatalog;
import com.georu.core.plugin.TableDef;

import java.util.List;

@GeoruPlugin(name = "crm.customer")
public class CrmCatalog implements TableCatalog {

    @Override
    public List<TableDef> tables() {
        return List.of();
    }
}
