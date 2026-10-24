package io.paradaux.business.services.impl;

import io.paradaux.business.services.RegionValidator;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FirmAreaShopServiceImplTest {

    private final RegionValidator validator = mock(RegionValidator.class);
    private final FirmAreaShopServiceImpl svc = new FirmAreaShopServiceImpl(validator);

    @Test
    void blankOrNullIsRejectedWithoutConsultingProvider() {
        assertThat(svc.isValidPlot(null)).isFalse();
        assertThat(svc.isValidPlot("")).isFalse();
        assertThat(svc.isValidPlot("   ")).isFalse();
        verifyNoInteractions(validator);
    }

    @Test
    void knownRegionIsValid() {
        when(validator.validate("plaza")).thenReturn(Optional.of(true));
        assertThat(svc.isValidPlot("plaza")).isTrue();
    }

    @Test
    void unknownRegionIsInvalidWhenProviderPresent() {
        when(validator.validate("ghost")).thenReturn(Optional.of(false));
        assertThat(svc.isValidPlot("ghost")).isFalse();
    }

    @Test
    void failsOpenWhenNoProviderAvailable() {
        // Realty not installed: accept so HQ-setting still works (ADT-37).
        when(validator.validate("anything")).thenReturn(Optional.empty());
        assertThat(svc.isValidPlot("anything")).isTrue();
    }
}
