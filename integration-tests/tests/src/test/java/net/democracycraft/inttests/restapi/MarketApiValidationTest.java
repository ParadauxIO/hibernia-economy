package net.democracycraft.inttests.restapi;

import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class MarketApiValidationTest {
    @ParameterizedTest
    @ValueSource(strings = {"shops", "items"})
    void publicDirectoriesRejectInvalidPagesLimitsAndSearchWithoutOverflow(String route) {
        long checkpoint = Ledger.transactionCheckpoint();
        String base = "/chestshop/" + route;
        for (String query : new String[] {"page=0", "page=-1", "page=2147483647&limit=100",
                "limit=0", "limit=-1", "limit=101", "search=x", "search=" + "x".repeat(101)}) {
            invalid(base + "?" + query);
        }
        for (String query : new String[] {"limit=1", "limit=100", "search=zz", "search=" + "z".repeat(100), "search=%20%20"}) {
            var r = RestApi.call("GET", base + "?" + query, null, null);
            assertThat(r.status()).as("%s: %s", query, r.body()).isEqualTo(200);
            assertThat(r.json().path("items").isArray()).isTrue();
            assertThat(r.json().path("page").asInt()).isEqualTo(1);
        }
        // This extreme page is representable at limit=1 and must be a normal empty page.
        var far = RestApi.call("GET", base + "?page=2147483647&limit=1", null, null);
        assertThat(far.status()).as("%s", far.body()).isEqualTo(200);
        assertThat(far.json().path("page").asInt()).isEqualTo(Integer.MAX_VALUE);
        assertThat(far.json().path("items").isEmpty()).isTrue();
        assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
    }

    @Test
    void itemAndMaterialLengthAndDetailWindowBoundariesReturnDomainErrors() {
        long checkpoint = Ledger.transactionCheckpoint();
        invalid("/chestshop/shops?material=" + "x".repeat(65));
        invalid("/chestshop/shops?itemKey=" + "x".repeat(191));
        for (String query : new String[] {"material=" + "x".repeat(64), "itemKey=" + "x".repeat(190)}) {
            var r = RestApi.call("GET", "/chestshop/shops?" + query, null, null);
            assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
            assertThat(r.json().path("totalItems").asInt()).isZero();
            assertThat(r.json().path("items").isEmpty()).isTrue();
        }
        String unknown = "par499-never-traded-" + Usernames.fresh("Mkt");
        for (int days : new int[] {0, -1, 366, Integer.MAX_VALUE}) {
            invalid("/chestshop/items/" + unknown + "?days=" + days);
        }
        invalid("/chestshop/items/" + "x".repeat(191));
        for (String path : new String[] {"/chestshop/items/" + unknown + "?days=1",
                "/chestshop/items/" + unknown + "?days=365", "/chestshop/items/" + "x".repeat(190)}) {
            var r = RestApi.call("GET", path, null, null);
            assertThat(r.status()).as("%s", r.body()).isEqualTo(404);
            assertThat(r.error()).isEqualTo("ITEM_NOT_FOUND");
        }
        assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
    }
    private static void invalid(String path) {
        var r = RestApi.call("GET", path, null, null);
        assertThat(r.status()).as("%s: %s", path, r.body()).isEqualTo(400);
        assertThat(r.error()).isEqualTo("INVALID_PARAM");
    }
}
