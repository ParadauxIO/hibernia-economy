package io.paradaux.treasury.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.paradaux.treasury.api.market.*;
import io.paradaux.treasury.model.economy.*;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImmutableApiModelsTest {
    @Test
    void publishedSnapshotsHaveNoMutationSurface() {
        for (Class<?> type : List.of(
                Account.class,
                AccountBalance.class,
                AccountMember.class,
                AccountTypeTotal.class,
                BalanceEntry.class,
                EconomySummary.class,
                GovernmentFine.class,
                LedgerTxn.class,
                TransactionEntry.class,
                SaleRow.class,
                SalesSummary.class,
                TopItem.class,
                TopCustomer.class)) {
            assertThat(Modifier.isFinal(type.getModifiers()))
                    .as("%s is final", type)
                    .isTrue();
            assertThat(type.getDeclaredFields())
                    .filteredOn(f -> !Modifier.isStatic(f.getModifiers()))
                    .allSatisfy(f -> assertThat(Modifier.isFinal(f.getModifiers()))
                            .as("%s", f)
                            .isTrue());
            assertThat(type.getMethods()).noneMatch(m -> m.getName().startsWith("set"));
            assertThat(type.getConstructors()).noneMatch(c -> c.getParameterCount() == 0);
        }
    }

    @Test
    void updatingAnAccountCreatesAnIndependentSnapshot() {
        Account original = new Account(
                17, AccountType.BUSINESS, UUID.randomUUID(), "Original", true, false, false, BigDecimal.ZERO);
        Account changed =
                original.toBuilder().displayName("Changed").allowOverdraft(true).build();
        assertThat(original.getDisplayName()).isEqualTo("Original");
        assertThat(original.isAllowOverdraft()).isFalse();
        assertThat(changed.getDisplayName()).isEqualTo("Changed");
        assertThat(changed.isAllowOverdraft()).isTrue();
        assertThat(changed.getAccountId()).isEqualTo(17);
        assertThat(original.withArchived(true).isArchived()).isTrue();
        assertThat(original.isArchived()).isFalse();
    }

    @Test
    void ledgerKeyIsCopiedByEveryConstructionPathAndGetter() {
        byte[] key = {1, 2, 3};
        LedgerTxn direct =
                new LedgerTxn(19, Instant.EPOCH, Instant.EPOCH, "message", UUID.randomUUID(), null, null, key);
        LedgerTxn built = LedgerTxn.builder().clientDedupKey(key).build();
        LedgerTxn with = direct.withClientDedupKey(key);
        LedgerTxn rebuilt = direct.toBuilder().clientDedupKey(key).build();
        key[0] = 9;
        for (LedgerTxn txn : List.of(direct, built, with, rebuilt)) {
            assertThat(txn.getClientDedupKey()).containsExactly(1, 2, 3);
            txn.getClientDedupKey()[1] = 9;
            assertThat(txn.getClientDedupKey()).containsExactly(1, 2, 3);
        }
        assertThat(direct.withClientDedupKey(null).getClientDedupKey()).isNull();
    }

    @Test
    void summaryCopiesLeaderboardsIncludingBuilderAndWithPaths() {
        TopItem item = new TopItem("stone", "Stone", 4, new BigDecimal("12.00"), 2);
        TopCustomer customer = new TopCustomer(UUID.randomUUID(), null, 2, new BigDecimal("12.00"));
        List<TopItem> items = new ArrayList<>(List.of(item));
        List<TopCustomer> customers = new ArrayList<>(List.of(customer));
        SalesSummary direct = new SalesSummary(
                2,
                4,
                new BigDecimal("12.00"),
                BigDecimal.ONE,
                2,
                new BigDecimal("12.00"),
                0,
                BigDecimal.ZERO,
                items,
                customers);
        SalesSummary built =
                direct.toBuilder().topItems(items).topCustomers(customers).build();
        SalesSummary with = direct.withTopItems(items).withTopCustomers(customers);
        items.clear();
        customers.clear();
        for (SalesSummary summary : List.of(direct, built, with)) {
            assertThat(summary.getTopItems()).containsExactly(item);
            assertThat(summary.getTopCustomers()).containsExactly(customer);
            assertThatThrownBy(() -> summary.getTopItems().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> summary.getTopCustomers().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(direct.withTopItems(null).getTopItems()).isEmpty();
        assertThat(direct.withTopCustomers(null).getTopCustomers()).isEmpty();
    }
}
