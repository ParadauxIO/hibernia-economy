package io.paradaux.treasuryapi.model.config;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationComponent;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import lombok.Getter;

/**
 * Settings for the LuckPerms → explorer-group reconciliation cron. Enabled by
 * default; the task is only scheduled when enabled and LuckPerms is present.
 */
@ConfigurationComponent
@Getter
public class ReconciliationConfiguration {

    @ConfigurationValue(path = "reconciliation.enabled", defaultValue = "true")
    private boolean enabled;

    @ConfigurationValue(path = "reconciliation.interval-seconds", defaultValue = "1800")
    private long intervalSeconds;
}
