package io.paradaux.chestshop.utils;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessAccountUtilTest {

    @Test
    void toBusinessUuid_encodesAccountIdInTheLowWord_withTheFixedHighWord() {
        UUID uuid = BusinessAccountUtil.toBusinessUuid(42);
        assertThat(uuid.getMostSignificantBits()).isEqualTo(BusinessAccountUtil.BUSINESS_UUID_MSB);
        assertThat(uuid.getLeastSignificantBits()).isEqualTo(42L);
    }

    @Test
    void isBusinessUuid_trueForAnEncodedBusinessUuid() {
        assertThat(BusinessAccountUtil.isBusinessUuid(BusinessAccountUtil.toBusinessUuid(7))).isTrue();
    }

    @Test
    void isBusinessUuid_falseForARealPlayerUuid() {
        assertThat(BusinessAccountUtil.isBusinessUuid(UUID.randomUUID())).isFalse();
    }

    @Test
    void systemUuid_isTheReservedConstant() {
        assertThat(BusinessAccountUtil.CHESTSHOP_SYSTEM_UUID)
                .isEqualTo(new UUID(0xC5B0FFFFFFFFFFFEL, 0xFFFFFFFFFFFFFFFEL));
    }

    @Test
    void isUtilityClass_privateConstructor() throws Exception {
        Constructor<BusinessAccountUtil> ctor = BusinessAccountUtil.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isNotNull();
    }
}
