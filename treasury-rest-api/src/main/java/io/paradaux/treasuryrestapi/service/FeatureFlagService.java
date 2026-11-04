package io.paradaux.treasuryrestapi.service;

import io.paradaux.common.flags.FeatureFlagRuntime;
import io.paradaux.treasuryrestapi.exception.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Server-side release guard; authorization must still run independently. */
@Service
public class FeatureFlagService {
    private final FeatureFlagRuntime runtime;

    public FeatureFlagService(DataSource dataSource) {
        runtime = new FeatureFlagRuntime(
                dataSource,
                message -> LoggerFactory.getLogger(FeatureFlagService.class).warn(message));
    }

    @PostConstruct
    public void start() {
        runtime.start();
    }

    @PreDestroy
    public void close() {
        runtime.close();
    }

    public boolean isEnabled(String key) {
        return runtime.isEnabled(key);
    }

    public void requireEnabled(String key) {
        if (!isEnabled(key))
            throw new ApiException(HttpStatus.NOT_FOUND, "FEATURE_DISABLED", "This feature is unavailable.");
    }
}
