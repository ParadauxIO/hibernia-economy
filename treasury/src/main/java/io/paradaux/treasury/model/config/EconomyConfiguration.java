package io.paradaux.treasury.model.config;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationComponent;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import lombok.Getter;

import java.math.BigDecimal;

@ConfigurationComponent
@Getter
public class EconomyConfiguration {

    // Framework reload writes these fields in place; publish each value to async readers.

    @ConfigurationValue(path = "economy.format", defaultValue = "$ ")
    private volatile String economyFormat;

    @ConfigurationValue(path = "economy.currency-name.singular", defaultValue = "dollar")
    private volatile String currencyNameSingular;

    @ConfigurationValue(path = "economy.currency-name.plural", defaultValue = "dollars")
    private volatile String currencyNamePlural;

    @ConfigurationValue(path = "economy.starting-balance", defaultValue = "10000")
    private volatile BigDecimal startingBalance;
}
