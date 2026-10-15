package io.paradaux.treasury.model.config;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationComponent;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import lombok.Getter;

@ConfigurationComponent
@Getter
public class GovernmentConfiguration {

    // Framework reload writes these fields in place; publish each value to async readers.

    @ConfigurationValue(path = "government.starting-balances-account", defaultValue = "starting-balances")
    private volatile String startingBalancesAccount;

    @ConfigurationValue(path = "government.tax-income-account", defaultValue = "DCGovernment")
    private volatile String taxIncomeAccount;

    @ConfigurationValue(path = "government.fines-account", defaultValue = "GovernmentFines")
    private volatile String finesAccount;
}
