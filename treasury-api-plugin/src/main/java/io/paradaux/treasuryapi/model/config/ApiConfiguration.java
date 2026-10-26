package io.paradaux.treasuryapi.model.config;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationComponent;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import lombok.Getter;

@ConfigurationComponent
@Getter
public class ApiConfiguration {

    @ConfigurationValue(path = "api.jwt-secret", defaultValue = "change-me-please-use-a-long-random-secret-key")
    private String jwtSecret;
}
