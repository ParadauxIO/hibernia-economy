package io.paradaux.business.model.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.paradaux.business.Business;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FirmConfigurationTest {

    @Test
    void blockedTermsDefaultToEmptyAndReloadFromConfig() {
        FirmConfiguration config = new FirmConfiguration(plugin);
        assertThat(config.getBlockedNameTerms()).isEmpty();
        when(cfg.getStringList("firm.name-filter.blocked-terms")).thenReturn(java.util.List.of("blocked phrase"));
        config.reload();
        assertThat(config.getBlockedNameTerms()).containsExactly("blocked phrase");
        when(cfg.getStringList("firm.name-filter.blocked-terms")).thenReturn(java.util.List.of());
        config.reload();
        assertThat(config.getBlockedNameTerms()).isEmpty();
    }

    @Mock
    Business plugin;

    @Mock
    FileConfiguration cfg;

    @BeforeEach
    void setUp() {
        // The constructor reads via plugin.getLogger() and plugin.getConfig().
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("FirmConfigurationTest"));
        lenient().when(plugin.getConfig()).thenReturn(cfg);
    }

    @Test
    void defaultsToThreeWhenUnset() {
        when(cfg.getInt("firm.owned-limit", 3)).thenReturn(3);
        FirmConfiguration config = new FirmConfiguration(plugin);
        assertThat(config.getOwnedFirmLimit()).isEqualTo(3);
        assertThat(config.hasOwnedFirmLimit()).isTrue();
    }

    @Test
    void honoursAConfiguredLimit() {
        when(cfg.getInt("firm.owned-limit", 3)).thenReturn(10);
        FirmConfiguration config = new FirmConfiguration(plugin);
        assertThat(config.getOwnedFirmLimit()).isEqualTo(10);
        assertThat(config.hasOwnedFirmLimit()).isTrue();
    }

    @Test
    void zeroOrBelowMeansUnlimited() {
        when(cfg.getInt("firm.owned-limit", 3)).thenReturn(0);
        FirmConfiguration zero = new FirmConfiguration(plugin);
        assertThat(zero.hasOwnedFirmLimit()).isFalse();
        assertThat(zero.getOwnedFirmLimit()).isEqualTo(0);

        when(cfg.getInt("firm.owned-limit", 3)).thenReturn(-1);
        FirmConfiguration negative = new FirmConfiguration(plugin);
        assertThat(negative.hasOwnedFirmLimit()).isFalse();
    }
}
