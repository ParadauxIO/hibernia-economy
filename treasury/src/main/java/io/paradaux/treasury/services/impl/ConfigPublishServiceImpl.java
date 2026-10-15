package io.paradaux.treasury.services.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import io.paradaux.treasury.mappers.EconomyConfigMapper;
import io.paradaux.treasury.model.config.BalanceTaxConfiguration;
import io.paradaux.treasury.model.config.SourceIncomeTaxConfiguration;
import io.paradaux.treasury.services.ConfigPublishService;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.guice.transactional.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

@Slf4j
public class ConfigPublishServiceImpl implements ConfigPublishService {

    static final String OWNER = "treasury";

    static final String BALANCE_ENABLED = "tax.balance.enabled";
    static final String BALANCE_BRACKETS = "tax.balance.brackets";
    static final String INCOME_ENABLED = "tax.source-income-tax.enabled";
    static final String INCOME_DEFAULT_RATE = "tax.source-income-tax.default-rate";
    static final String INCOME_PLUGIN_RATES = "tax.source-income-tax.plugin-rates";

    private final EconomyConfigMapper mapper;
    private final BalanceTaxConfiguration balanceTax;
    private final SourceIncomeTaxConfiguration incomeTax;

    @Inject
    public ConfigPublishServiceImpl(EconomyConfigMapper mapper,
                                    BalanceTaxConfiguration balanceTax,
                                    SourceIncomeTaxConfiguration incomeTax) {
        this.mapper = mapper;
        this.balanceTax = balanceTax;
        this.incomeTax = incomeTax;
    }

    @Override
    @Transactional
    public void publish() {
        Map<String, Published> values = snapshot();
        values.forEach((key, v) -> mapper.upsert(key, OWNER, v.type(), v.value()));
        mapper.deleteOwnedExcept(OWNER, values.keySet());
        log.info("Published {} config value(s) to economy_config.", values.size());
    }

    /** The values to publish, keyed by config key, read from the live config objects. */
    Map<String, Published> snapshot() {
        Map<String, Published> out = new LinkedHashMap<>();

        out.put(BALANCE_ENABLED, Published.bool(balanceTax.isEnabled()));
        JsonArray brackets = new JsonArray();
        balanceTax.getBrackets().asMap().forEach((floor, rate) -> {
            JsonObject b = new JsonObject();
            b.addProperty("floor", floor.toPlainString());
            b.addProperty("rate", rate.toPlainString());
            brackets.add(b);
        });
        out.put(BALANCE_BRACKETS, Published.json(brackets.toString()));

        out.put(INCOME_ENABLED, Published.bool(incomeTax.isEnabled()));
        out.put(INCOME_DEFAULT_RATE, Published.decimal(incomeTax.getDefaultRate()));
        JsonObject pluginRates = new JsonObject();
        new TreeMap<>(incomeTax.getPluginRates())
                .forEach((plugin, rate) -> pluginRates.addProperty(plugin, rate.toPlainString()));
        out.put(INCOME_PLUGIN_RATES, Published.json(pluginRates.toString()));

        return out;
    }

    /** One row's {@code value_type} and {@code config_value}. */
    record Published(String type, String value) {
        static Published bool(boolean b) { return new Published("BOOLEAN", Boolean.toString(b)); }
        static Published decimal(BigDecimal d) { return new Published("DECIMAL", d.toPlainString()); }
        static Published json(String json) { return new Published("JSON", json); }
    }
}
