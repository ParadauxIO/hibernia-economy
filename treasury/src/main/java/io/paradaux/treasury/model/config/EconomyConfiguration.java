package io.paradaux.treasury.model.config;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationComponent;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import lombok.Getter;

import java.math.BigDecimal;

@ConfigurationComponent
@Getter
public class EconomyConfiguration {

    @ConfigurationValue(path = "economy.format", defaultValue = "$ ")
    private String economyFormat;

    @ConfigurationValue(path = "economy.currency-name.singular", defaultValue = "dollar")
    private String currencyNameSingular;

    @ConfigurationValue(path = "economy.currency-name.plural", defaultValue = "dollars")
    private String currencyNamePlural;

    @ConfigurationValue(path = "economy.starting-balance", defaultValue = "10000")
    private BigDecimal startingBalance;
}
