package io.paradaux.chestshop.utils;

import java.util.UUID;

/**
 * Pure, stateless codec for ChestShop's synthetic business/system account UUIDs. A Business
 * plugin firm account has no real player UUID, so ChestShop encodes its Treasury account id
 * into a UUID with a fixed high word ({@link #BUSINESS_UUID_MSB}); {@link #CHESTSHOP_SYSTEM_UUID}
 * is the reserved owner of ChestShop's Treasury SYSTEM intermediary account. Shared by
 * {@link io.paradaux.chestshop.services.EconomyService} (money routing) and
 * {@link io.paradaux.chestshop.services.BusinessAccountService} (resolution) so neither owns
 * the encoding (PAR-282).
 */
public final class BusinessAccountUtil {

    private BusinessAccountUtil() {
    }

    public static final long BUSINESS_UUID_MSB = 0xC5B0000000000000L;
    public static final UUID CHESTSHOP_SYSTEM_UUID = new UUID(0xC5B0FFFFFFFFFFFEL, 0xFFFFFFFFFFFFFFFEL);

    public static boolean isBusinessUuid(UUID uuid) {
        return uuid.getMostSignificantBits() == BUSINESS_UUID_MSB;
    }

    public static UUID toBusinessUuid(int accountId) {
        return new UUID(BUSINESS_UUID_MSB, (long) accountId);
    }
}
