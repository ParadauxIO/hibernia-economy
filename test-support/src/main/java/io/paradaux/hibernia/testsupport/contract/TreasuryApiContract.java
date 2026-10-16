package io.paradaux.hibernia.testsupport.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.model.economy.LedgerPosting;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.model.tax.TaxCollection;
import io.paradaux.treasury.model.tax.TaxResult;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Executable specification of the {@link TreasuryApi} behaviour its consumers rely
 * on — business, chestshop and realty drive money exclusively through this interface,
 * and business's ~500 unit tests stub it with Mockito, so the stubs encode
 * assumptions about it that nothing else checks. Each test here pins one of them:
 * insufficient funds, unknown accounts, invalid amounts, idempotency keys, sweeps,
 * tax-result shapes and the return values callers read.
 *
 * <p>Abstract: an implementation module extends it and supplies the API plus one
 * unlimited faucet account to fund fixtures from. Treasury runs it against the real
 * {@code TreasuryApiImpl} on MariaDB ({@code TreasuryApiImplContractIT}); a fake used
 * in consumer tests can extend it too, which is how a fake proves it is faithful.
 *
 * <p>Lives in test-support (not treasury-api test fixtures) because treasury-api is a
 * published artifact and test-support is already every consumer's test kit;
 * treasury-api and AssertJ are compileOnly here — the implementing module provides
 * both.
 */
public abstract class TreasuryApiContract {

    private static final UUID INITIATOR = new UUID(0L, 42L);

    /** The implementation under test. */
    protected abstract TreasuryApi api();

    /** An existing account that may go arbitrarily negative (a GOVERNMENT/SYSTEM faucet). */
    protected abstract int faucetAccountId();

    /** An account id guaranteed not to exist. */
    protected int unknownAccountId() {
        return 987_654_321;
    }

    // ---- fixtures ----

    /** A fresh non-overdraft BUSINESS account holding exactly {@code balance}. */
    protected int accountWith(String balance) {
        Account account = api().createAccount(AccountType.BUSINESS, UUID.randomUUID(), "contract-" + UUID.randomUUID());
        BigDecimal amount = new BigDecimal(balance);
        if (amount.signum() > 0) {
            api().transfer(request(faucetAccountId(), account.getAccountId(), amount, null));
        }
        return account.getAccountId();
    }

    protected static TransferRequest request(int from, int to, BigDecimal amount, byte @Nullable [] dedupKey) {
        return new TransferRequest(from, to, amount, "contract", INITIATOR, null, "contract", dedupKey);
    }

    private BigDecimal balance(int accountId) {
        return api().getBalanceByAccountId(accountId);
    }

    // ---- accounts ----

    @Test
    void createAccount_returnsAPersistedZeroBalanceAccount() {
        UUID owner = UUID.randomUUID();
        Account created = api().createAccount(AccountType.BUSINESS, owner, "Contract Co Account");

        assertThat(created.getAccountId()).isPositive();
        assertThat(api().hasAccountByAccountId(created.getAccountId())).isTrue();
        Account read = api().getAccountById(created.getAccountId());
        assertThat(read).isNotNull();
        assertThat(read.getAccountType()).isEqualTo(AccountType.BUSINESS);
        assertThat(read.getOwnerUuid()).isEqualTo(owner);
        assertThat(balance(created.getAccountId())).isEqualByComparingTo("0");
    }

    @Test
    void unknownAccount_lookupsAreEmptyNotExceptional() {
        int unknown = unknownAccountId();
        assertThat(api().getAccountById(unknown)).isNull();
        assertThat(api().hasAccountByAccountId(unknown)).isFalse();
        assertThat(api().hasFunds(unknown, new BigDecimal("0.01"))).isFalse();
        assertThat(api().getBalancesByIds(List.of(unknown))).doesNotContainKey(unknown);
    }

    @Test
    void resolveOrCreatePersonal_isIdempotentPerPlayer() {
        UUID player = UUID.randomUUID();
        Account first = api().resolveOrCreatePersonal(player);
        Account second = api().resolveOrCreatePersonal(player);

        assertThat(first.getAccountType()).isEqualTo(AccountType.PERSONAL);
        assertThat(second.getAccountId()).isEqualTo(first.getAccountId());
        assertThat(api().getAccountByUUID(player).getAccountId()).isEqualTo(first.getAccountId());
    }

    // ---- balances ----

    @Test
    void hasFunds_isTrueUpToAndIncludingTheBalance() {
        int account = accountWith("25.00");
        assertThat(api().hasFunds(account, new BigDecimal("25.00"))).isTrue();
        assertThat(api().hasFunds(account, new BigDecimal("25.01"))).isFalse();
    }

    @Test
    void getBalancesByIds_returnsEveryKnownAccount() {
        int a = accountWith("1.50");
        int b = accountWith("0");
        Map<Integer, BigDecimal> balances = api().getBalancesByIds(List.of(a, b));
        assertThat(balances).containsOnlyKeys(a, b);
        assertThat(balances.get(a)).isEqualByComparingTo("1.50");
        assertThat(balances.get(b)).isEqualByComparingTo("0");
        assertThat(api().getBalancesByIds(List.of())).isEmpty();
    }

    // ---- transfers ----

    @Test
    void transfer_movesExactlyTheAmount_andReturnsABalancedTxn() {
        int from = accountWith("100.00");
        int to = accountWith("0");

        long txnId = api().transfer(request(from, to, new BigDecimal("37.45"), null));

        assertThat(txnId).isPositive();
        assertThat(balance(from)).isEqualByComparingTo("62.55");
        assertThat(balance(to)).isEqualByComparingTo("37.45");
        assertThat(api().getTransaction(txnId)).isNotNull();
        List<LedgerPosting> postings = api().getPostingsForTransaction(txnId);
        assertThat(postings).extracting(LedgerPosting::accountId).containsExactlyInAnyOrder(from, to);
        assertThat(postings.stream().map(LedgerPosting::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("0");
    }

    @Test
    void transfer_ofTheWholeBalance_isAllowed() {
        int from = accountWith("10.00");
        int to = accountWith("0");
        api().transfer(request(from, to, new BigDecimal("10.00"), null));
        assertThat(balance(from)).isEqualByComparingTo("0");
    }

    @Test
    void transfer_insufficientFunds_throwsIllegalState_andMovesNothing() {
        int from = accountWith("10.00");
        int to = accountWith("0");

        assertThatThrownBy(() -> api().transfer(request(from, to, new BigDecimal("10.01"), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient funds");
        assertThat(balance(from)).isEqualByComparingTo("10.00");
        assertThat(balance(to)).isEqualByComparingTo("0");
    }

    @Test
    void transfer_unknownAccount_throwsIllegalArgument() {
        int known = accountWith("5.00");
        assertThatThrownBy(() -> api().transfer(request(known, unknownAccountId(), BigDecimal.ONE, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api().transfer(request(unknownAccountId(), known, BigDecimal.ONE, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(balance(known)).isEqualByComparingTo("5.00");
    }

    @Test
    void transfer_zeroNegativeOrSubCentAmounts_throwIllegalArgument_andMoveNothing() {
        int from = accountWith("5.00");
        int to = accountWith("0");
        for (String bad : List.of("0", "0.00", "-1.00", "0.001", "1.005")) {
            assertThatThrownBy(() -> api().transfer(request(from, to, new BigDecimal(bad), null)))
                    .as("amount %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(balance(from)).isEqualByComparingTo("5.00");
        assertThat(balance(to)).isEqualByComparingTo("0");
    }

    @Test
    void transfer_toTheSameAccount_throwsIllegalArgument() {
        int account = accountWith("5.00");
        assertThatThrownBy(() -> api().transfer(request(account, account, BigDecimal.ONE, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transfer_repeatedDedupKey_returnsTheOriginalTxn_andMovesMoneyOnce() {
        int from = accountWith("50.00");
        int to = accountWith("0");
        byte[] key = sha256("contract-dedup-" + UUID.randomUUID());

        long first = api().transfer(request(from, to, new BigDecimal("20.00"), key));
        long replay = api().transfer(request(from, to, new BigDecimal("20.00"), key));

        assertThat(replay).isEqualTo(first);
        assertThat(balance(from)).isEqualByComparingTo("30.00");
        assertThat(balance(to)).isEqualByComparingTo("20.00");
    }

    @Test
    void transfer_failedAttempt_doesNotConsumeItsDedupKey() {
        int from = accountWith("5.00");
        int to = accountWith("0");
        byte[] key = sha256("contract-dedup-retry-" + UUID.randomUUID());

        assertThatThrownBy(() -> api().transfer(request(from, to, new BigDecimal("8.00"), key)))
                .isInstanceOf(IllegalStateException.class);
        api().transfer(request(faucetAccountId(), from, new BigDecimal("5.00"), null));
        long retried = api().transfer(request(from, to, new BigDecimal("8.00"), key));

        assertThat(retried).isPositive();
        assertThat(balance(to)).isEqualByComparingTo("8.00");
    }

    // ---- sweep ----

    @Test
    void sweepAll_movesTheWholeBalance_andIsEmptyWhenThereIsNothingToMove() {
        int from = accountWith("12.34");
        int to = accountWith("1.00");

        OptionalLong swept = api().sweepAll(from, to, "contract sweep", INITIATOR, "contract");
        assertThat(swept).isPresent();
        assertThat(balance(from)).isEqualByComparingTo("0");
        assertThat(balance(to)).isEqualByComparingTo("13.34");

        assertThat(api().sweepAll(from, to, "contract sweep", INITIATOR, "contract"))
                .isEmpty();
        assertThatThrownBy(() -> api().sweepAll(to, to, "self", INITIATOR, "contract"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- tax ----

    @Test
    void collectTax_reportsCollectedSkippedOrFailed_neverThrows() {
        int payer = accountWith("10.00");
        int government = faucetAccountId();

        TaxResult collected = api().getTaxApi()
                .collectTax(TaxCollection.toAccount(
                        payer, government, new BigDecimal("2.50"), "contract", "tax", INITIATOR, "contract"));
        assertThat(collected).isInstanceOfSatisfying(TaxResult.Collected.class, c -> {
            assertThat(c.amountCharged()).isEqualByComparingTo("2.50");
            assertThat(c.destinationAccountId()).isEqualTo(government);
            assertThat(c.txnId()).isPositive();
        });
        assertThat(balance(payer)).isEqualByComparingTo("7.50");

        TaxResult belowMinimum = api().getTaxApi()
                .collectTax(TaxCollection.toAccount(
                        payer, government, new BigDecimal("0.004"), "contract", "tax", INITIATOR, "contract"));
        assertThat(belowMinimum).isInstanceOf(TaxResult.Skipped.class);

        TaxResult unaffordable = api().getTaxApi()
                .collectTax(TaxCollection.toAccount(
                        payer, government, new BigDecimal("7.51"), "contract", "tax", INITIATOR, "contract"));
        assertThat(unaffordable).isInstanceOf(TaxResult.Failed.class);
        assertThat(balance(payer)).isEqualByComparingTo("7.50");
    }

    @Test
    void collectBatch_returnsOneResultPerCollection_inOrder() {
        int rich = accountWith("10.00");
        int poor = accountWith("0.50");
        int government = faucetAccountId();

        List<TaxResult> results = api().getTaxApi()
                .collectBatch(List.of(
                        TaxCollection.toAccount(
                                rich, government, new BigDecimal("1.00"), "contract", "tax", INITIATOR, "contract"),
                        TaxCollection.toAccount(
                                poor, government, new BigDecimal("1.00"), "contract", "tax", INITIATOR, "contract"),
                        TaxCollection.toAccount(
                                rich, government, new BigDecimal("2.00"), "contract", "tax", INITIATOR, "contract")));

        assertThat(results).hasSize(3);
        assertThat(results.get(0)).isInstanceOf(TaxResult.Collected.class);
        assertThat(results.get(1)).isInstanceOf(TaxResult.Failed.class);
        assertThat(results.get(2)).isInstanceOf(TaxResult.Collected.class);
        assertThat(balance(rich)).isEqualByComparingTo("7.00");
        assertThat(balance(poor)).isEqualByComparingTo("0.50");
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
