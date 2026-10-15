package io.paradaux.treasury.model.config;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationComponent;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import lombok.Getter;

@ConfigurationComponent
@Getter
public class BytebinConfiguration {

    // Framework reload writes these fields in place; publish each value to async readers.

    @ConfigurationValue(path = "bytebin.post-url", defaultValue = "https://pastes.paradaux.io/post")
    private volatile String postUrl;

    @ConfigurationValue(path = "bytebin.base-url", defaultValue = "https://pastes.paradaux.io/")
    private volatile String baseUrl;
}
