package io.paradaux.treasury.model.config;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationComponent;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import lombok.Getter;

@ConfigurationComponent
@Getter
public class FineWebhookConfiguration {

    // Framework reload writes these fields in place; publish each value to async readers.

    @ConfigurationValue(path = "fines.webhook.enabled", defaultValue = "false")
    private volatile boolean enabled;

    /** Discord webhook URL. Leave empty to disable even if enabled is true. */
    @ConfigurationValue(path = "fines.webhook.url", defaultValue = "")
    private volatile String url;
}
