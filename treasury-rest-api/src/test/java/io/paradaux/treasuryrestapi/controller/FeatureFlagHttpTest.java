package io.paradaux.treasuryrestapi.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.paradaux.treasuryrestapi.exception.GlobalExceptionHandler;
import io.paradaux.treasuryrestapi.service.FeatureFlagService;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

class FeatureFlagHttpTest {
    @Test
    void disabledFeatureUsesStableHttpErrorWithoutInitializingOrExecutingFeature() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        var flags = new FeatureFlagService(dataSource);
        var effects = new AtomicInteger();
        var mvc = MockMvcBuilders.standaloneSetup(new PreviewController(flags, effects))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        try {
            mvc.perform(post("/test-preview"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("FEATURE_DISABLED"));
            assertEquals(0, effects.get());
            verifyNoInteractions(dataSource);
        } finally {
            flags.close();
        }
    }

    @RestController
    static class PreviewController {
        private final FeatureFlagService flags;
        private final AtomicInteger effects;

        PreviewController(FeatureFlagService flags, AtomicInteger effects) {
            this.flags = flags;
            this.effects = effects;
        }

        @PostMapping("/test-preview")
        String execute() {
            flags.requireEnabled("business.preview");
            effects.incrementAndGet();
            return "executed";
        }
    }
}
