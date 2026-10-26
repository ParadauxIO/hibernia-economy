package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.TaxConfigResponse;
import io.paradaux.treasuryrestapi.mapper.EconomyConfigMapper;
import io.paradaux.treasuryrestapi.model.EconomyConfigRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads the config snapshot the Treasury plugin publishes into
 * {@code economy_config} (PAR-80) and shapes it for the public API. The plugin
 * owns the encoding; see its {@code ConfigPublishService} for the key list.
 */
@Service
public class EconomyConfigService {

    private static final Logger log = LoggerFactory.getLogger(EconomyConfigService.class);

    static final String BALANCE_ENABLED = "tax.balance.enabled";
    static final String BALANCE_BRACKETS = "tax.balance.brackets";
    static final String INCOME_ENABLED = "tax.source-income-tax.enabled";
    static final String INCOME_DEFAULT_RATE = "tax.source-income-tax.default-rate";
    static final String INCOME_PLUGIN_RATES = "tax.source-income-tax.plugin-rates";

    private final EconomyConfigMapper mapper;
    private final ObjectMapper objectMapper;

    public EconomyConfigService(EconomyConfigMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public TaxConfigResponse taxConfig() {
        Map<String, EconomyConfigRow> rows = mapper.findByPrefix("tax.").stream()
                .collect(Collectors.toMap(EconomyConfigRow::getConfigKey, Function.identity()));
        return new TaxConfigResponse(balanceTax(rows), sourceIncomeTax(rows));
    }

    private TaxConfigResponse.BalanceTax balanceTax(Map<String, EconomyConfigRow> rows) {
        EconomyConfigRow enabled = rows.get(BALANCE_ENABLED);
        EconomyConfigRow brackets = rows.get(BALANCE_BRACKETS);
        if (enabled == null || brackets == null) return null;
        try {
            List<TaxConfigResponse.Bracket> list = new ArrayList<>();
            for (JsonNode b : objectMapper.readTree(brackets.getConfigValue())) {
                list.add(new TaxConfigResponse.Bracket(b.get("floor").asString(), b.get("rate").asString()));
            }
            return new TaxConfigResponse.BalanceTax(Boolean.parseBoolean(enabled.getConfigValue()), list,
                    latest(enabled, brackets));
        } catch (JacksonException | NullPointerException e) {
            log.warn("Unreadable {} in economy_config: {}", BALANCE_BRACKETS, e.toString());
            return null;
        }
    }

    private TaxConfigResponse.SourceIncomeTax sourceIncomeTax(Map<String, EconomyConfigRow> rows) {
        EconomyConfigRow enabled = rows.get(INCOME_ENABLED);
        EconomyConfigRow defaultRate = rows.get(INCOME_DEFAULT_RATE);
        EconomyConfigRow pluginRates = rows.get(INCOME_PLUGIN_RATES);
        if (enabled == null || defaultRate == null || pluginRates == null) return null;
        try {
            List<TaxConfigResponse.PluginRate> list = new ArrayList<>();
            objectMapper.readTree(pluginRates.getConfigValue()).properties()
                    .forEach(e -> list.add(new TaxConfigResponse.PluginRate(e.getKey(), e.getValue().asString())));
            return new TaxConfigResponse.SourceIncomeTax(Boolean.parseBoolean(enabled.getConfigValue()),
                    defaultRate.getConfigValue(), list, latest(enabled, defaultRate, pluginRates));
        } catch (JacksonException e) {
            log.warn("Unreadable {} in economy_config: {}", INCOME_PLUGIN_RATES, e.toString());
            return null;
        }
    }

    /** When the section last changed: the newest of its rows ({@code updated_at} is UTC). */
    private static Instant latest(EconomyConfigRow... rows) {
        return Stream.of(rows).map(EconomyConfigRow::getUpdatedAt).filter(Objects::nonNull)
                .max(LocalDateTime::compareTo).map(t -> t.toInstant(ZoneOffset.UTC)).orElse(null);
    }
}
