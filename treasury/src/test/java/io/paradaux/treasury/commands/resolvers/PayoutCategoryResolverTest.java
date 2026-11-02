package io.paradaux.treasury.commands.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import io.paradaux.treasury.model.economy.PayoutCategory;
import org.junit.jupiter.api.Test;

class PayoutCategoryResolverTest {
    private final PayoutCategoryResolver resolver = new PayoutCategoryResolver();

    @Test
    void acceptsOnlyKnownCategoriesAndSuggestsThem() {
        assertThat(resolver.resolve("employee", null)).contains(PayoutCategory.EMPLOYEE);
        assertThat(resolver.resolve("AUCTION_PROCEEDS", null)).contains(PayoutCategory.AUCTION_PROCEEDS);
        assertThat(resolver.resolve("salary", null)).isEmpty();
        assertThat(resolver.resolve("", null)).isEmpty();
        assertThat(resolver.suggestions("", null)).containsExactly("EMPLOYEE", "AUCTION_PROCEEDS");
        assertThat(resolver.suggestions("au", null)).containsExactly("AUCTION_PROCEEDS");
    }
}
