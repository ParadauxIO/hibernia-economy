package net.democracycraft.inttests.restapi;

import java.util.UUID;
import net.democracycraft.inttests.support.ApiTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** Authenticated market requests use an isolated key for each test's request budget. */
abstract class MarketApiTestSupport {
    private ApiTokens.IssuedKey marketKey;

    @BeforeEach
    void createMarketKey() {
        marketKey = ApiTokens.mintServiceKey(UUID.randomUUID());
    }

    @AfterEach
    void revokeMarketKey() {
        if (marketKey != null) ApiTokens.revokeInDb(marketKey.keyId());
    }

    protected String marketToken() {
        return marketKey.token();
    }
}
