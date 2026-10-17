package io.paradaux.treasury.model.config;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationComponent;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import lombok.Getter;

@ConfigurationComponent
@Getter
public class DiscordWebhookConfiguration {

    // Framework reload writes these fields in place; publish each value to async readers.

    @ConfigurationValue(path = "tax.webhook.enabled", defaultValue = "false")
    private volatile boolean enabled;

    /** Discord webhook URL. Leave empty to disable even if enabled is true. */
    @ConfigurationValue(path = "tax.webhook.url", defaultValue = "")
    private volatile String url;
}
