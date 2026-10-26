package io.paradaux.chestshop.services;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LegacyPreferenceImportTest {
    @Test
    void acceptsCanonicalPlayerUuidAndBooleanOnly() {
        var row = LegacyPreferenceImportService.Preference.parse(
                "{\"player_uuid\":\"00000000-0000-0000-0000-000000000123\",\"visible\":false}");
        assertThat(row.visible()).isFalse();
        for (String invalid : new String[] {
            "{}",
            "{\"player_uuid\":\"0-0-0-0-1\",\"visible\":true}",
            "{\"player_uuid\":\"00000000-0000-0000-0000-000000000123\",\"visible\":\"false\"}"
        }) {
            assertThatThrownBy(() -> LegacyPreferenceImportService.Preference.parse(invalid))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
