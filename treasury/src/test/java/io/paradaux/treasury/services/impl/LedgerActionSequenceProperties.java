package io.paradaux.treasury.services.impl;

import com.google.inject.Guice;
import com.google.inject.Injector;
import io.paradaux.hibernia.testsupport.db.LedgerInvariants;
import io.paradaux.hibernia.testsupport.db.TestDatabases;
import io.paradaux.hibernia.testsupport.properties.FixedSeedInCi;
import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.guice.DatabaseModule;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.model.tax.TaxCollection;
import io.paradaux.treasury.model.tax.TaxResult;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import io.paradaux.treasury.testsupport.TestServicesModule;
import io.paradaux.treasury.utils.Idempotency;
import io.paradaux.treasury.utils.TreasuryConstants;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stateful property over the real ledger on the real database: random sequences of
 * open-account / transfer / admin credit / admin debit / sweep / tax actions — with
 * random and frequently invalid amounts (zero, negative, sub-cent, more than the
 * balance), self-transfers, unknown accounts and re-used idempotency keys — run
 * against {@link LedgerService}/{@link TaxApi} and, in parallel, against a tiny
 * in-memory model of what each action should do.
 *
 * <p>After every action: the call succeeded exactly when the model said it should,
 * and {@code ops/money-invariants.sql} holds. After the sequence: every account's
 * balance equals the model's. On failure jqwik shrinks to the shortest action list
 * that still breaks it, and its failure database replays that first next run.
 *
 * <p>Cost is dominated by the per-try reset + Guice wiring (fresh singletons, so no
 * cached account ids leak between tries); tries are kept modest for CI, the seed is
 * fixed on CI by {@link FixedSeedInCi}.
 */
@Tag("integration")
@AddLifecycleHook(FixedSeedInCi.class)
class LedgerActionSequenceProperties {

    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000ad01");
    private static final int UNKNOWN_ACCOUNT = 987_654;

    private DataSource dataSource;
    private LedgerService ledger;
    private AccountService accounts;
    private TaxApi taxApi;

    /**
     * One injector for all tries: building it is most of a try's cost. Reusing its
     * singletons across DB resets is safe here because the only id caches the actions
     * touch are keyed by freshly random player UUIDs (never reused across tries), and
     * government accounts are looked up by name on every call.
     */
    private static Injector injector;

    @BeforeTry
    void freshLedger() {
        TestDatabases.reset(IntegrationTestBase.DATABASE);
        dataSource = TestDatabases.dataSource(IntegrationTestBase.DATABASE);
        if (injector == null) {
            injector = Guice.createInjector(new DatabaseModule(dataSource), new TestServicesModule());
        }
        ledger = injector.getInstance(LedgerService.class);
        accounts = injector.getInstance(AccountService.class);
        taxApi = injector.getInstance(TaxApi.class);
        ledger.bootstrapGovernmentAccounts();
    }

    @Property(tries = 40)
    void randomActionSequences_matchTheModel_andKeepTheLedgerConsistent(@ForAll("actions") List<Action> actions) {
        Model model = new Model();
        model.addFaucet(gov("starting-balances"));
        model.addFaucet(gov("DCGovernment"));
        model.addFaucet(gov("GovernmentFines"));

        int step = 0;
        for (Action action : actions) {
            step++;
            boolean expected = model.predict(action);
            boolean actual = execute(action, model);
            assertThat(actual).as("step %d %s: succeeded?", step, action).isEqualTo(expected);
            if (actual) model.apply(action);
            LedgerInvariants.assertHold(dataSource, "step " + step + " " + action);
        }

        for (Map.Entry<Integer, BigDecimal> e : model.balances.entrySet()) {
            assertThat(accounts.getBalanceReadOnly(e.getKey()))
                    .as("balance of account %d after %s", e.getKey(), actions)
                    .isEqualByComparingTo(e.getValue());
        }
    }

    // ---- execution against the real services ----

    /** Runs the action; true if it took effect (or was an accepted idempotent replay). */
    private boolean execute(Action action, Model model) {
        try {
            switch (action) {
                case Open open -> {
                    UUID owner = UUID.randomUUID();
                    Account a = ledger.resolveOrCreatePersonal(owner);
                    model.pendingOpen = new Model.Personal(a.getAccountId(), owner);
                    return true;
                }
                case Transfer t -> {
                    ledger.transfer(new TransferRequest(model.id(t.from()), model.id(t.to()), t.amount(),
                            "prop transfer", ADMIN, null, "prop", key(t.dedupSlot())));
                    return true;
                }
                case Credit c -> {
                    Model.Personal p = model.personal(c.player());
                    if (p == null) return false;
                    ledger.adminGive(p.owner(), c.amount(), "prop credit", ADMIN);
                    model.eco = gov(TreasuryConstants.ECO_ACCOUNT_NAME);
                    return true;
                }
                case Debit d -> {
                    Model.Personal p = model.personal(d.player());
                    if (p == null) return false;
                    ledger.adminTake(p.owner(), d.amount(), "prop debit", ADMIN);
                    model.eco = gov(TreasuryConstants.ECO_ACCOUNT_NAME);
                    return true;
                }
                case Sweep s -> {
                    ledger.sweepAll(model.id(s.from()), model.id(s.to()), "prop sweep", ADMIN, "prop");
                    return true;
                }
                case Tax tax -> {
                    TaxResult r = taxApi.collectTax(TaxCollection.toAccount(model.id(tax.from()), model.taxIncome(),
                            tax.amount(), "prop", "prop tax", ADMIN, "prop", key(tax.dedupSlot())));
                    return r instanceof TaxResult.Collected;
                }
            }
        } catch (RuntimeException rejected) {
            return false;
        }
    }

    private int gov(String name) {
        Account a = accounts.getGovernmentAccountByName(name);
        return a == null ? -1 : a.getAccountId();
    }

    private static byte[] key(int slot) {
        return slot < 0 ? null : Idempotency.sha256("prop-dedup:" + slot);
    }

    // ---- the model ----

    /** Expected ledger state: balances by account id, plus spent idempotency keys. */
    private static final class Model {
        record Personal(int id, UUID owner) {}

        final Map<Integer, BigDecimal> balances = new LinkedHashMap<>();
        final List<Integer> faucets = new ArrayList<>();
        final List<Personal> personals = new ArrayList<>();
        final Set<Integer> spentKeys = new HashSet<>();
        Personal pendingOpen;
        int eco = -1;

        void addFaucet(int id) {
            faucets.add(id);
            balances.put(id, BigDecimal.ZERO);
        }

        int taxIncome() {
            return faucets.get(1);
        }

        /**
         * Account index → id. Indices address faucets first, then personal accounts,
         * wrapping around; index -1 is a deliberately unknown account.
         */
        int id(int index) {
            if (index < 0) return UNKNOWN_ACCOUNT;
            int size = faucets.size() + personals.size();
            int i = index % size;
            return i < faucets.size() ? faucets.get(i) : personals.get(i - faucets.size()).id();
        }

        Personal personal(int index) {
            return personals.isEmpty() ? null : personals.get(index % personals.size());
        }

        boolean isFaucet(int id) {
            return faucets.contains(id) || id == eco;
        }

        BigDecimal balance(int id) {
            return balances.getOrDefault(id, BigDecimal.ZERO);
        }

        /** Mirrors LedgerServiceImpl.transferInternal's checks, in its order. */
        boolean transferAllowed(int from, int to, BigDecimal amount, int dedupSlot) {
            if (!validAmount(amount)) return false;
            if (from == to) return false;
            if (dedupSlot >= 0 && spentKeys.contains(dedupSlot)) return true; // idempotent replay
            if (from == UNKNOWN_ACCOUNT || to == UNKNOWN_ACCOUNT) return false;
            return isFaucet(from) || balance(from).compareTo(amount) >= 0;
        }

        static boolean validAmount(BigDecimal amount) {
            return amount.signum() > 0
                    && amount.stripTrailingZeros().scale() <= 2
                    && amount.compareTo(new BigDecimal("0.01")) >= 0;
        }

        boolean predict(Action action) {
            return switch (action) {
                case Open o -> true;
                case Transfer t -> transferAllowed(id(t.from()), id(t.to()), t.amount(), t.dedupSlot());
                case Credit c -> personal(c.player()) != null && validAmount(c.amount());
                case Debit d -> personal(d.player()) != null && validAmount(d.amount())
                        && balance(personal(d.player()).id()).compareTo(d.amount()) >= 0;
                // sweepAll: same-account and unknown-source are rejected; an empty (or
                // faucet-negative) source is a successful no-op before the destination
                // is ever touched; otherwise the postings need a real destination.
                case Sweep s -> id(s.from()) != id(s.to()) && id(s.from()) != UNKNOWN_ACCOUNT
                        && (balance(id(s.from())).signum() <= 0 || id(s.to()) != UNKNOWN_ACCOUNT);
                case Tax tax -> tax.amount().compareTo(new BigDecimal("0.01")) >= 0
                        && transferAllowed(id(tax.from()), taxIncome(), tax.amount(), tax.dedupSlot());
            };
        }

        void apply(Action action) {
            switch (action) {
                case Open o -> {
                    personals.add(pendingOpen);
                    move(faucets.get(0), pendingOpen.id(), new BigDecimal("10000.00")); // starting balance
                }
                case Transfer t -> transfer(id(t.from()), id(t.to()), t.amount(), t.dedupSlot());
                case Credit c -> move(eco, personal(c.player()).id(), c.amount());
                case Debit d -> move(personal(d.player()).id(), eco, d.amount());
                case Sweep s -> {
                    BigDecimal all = balance(id(s.from()));
                    if (all.signum() > 0) move(id(s.from()), id(s.to()), all);
                }
                case Tax tax -> transfer(id(tax.from()), taxIncome(), tax.amount(), tax.dedupSlot());
            }
        }

        private void transfer(int from, int to, BigDecimal amount, int dedupSlot) {
            if (dedupSlot >= 0 && !spentKeys.add(dedupSlot)) return; // replay: nothing moves
            move(from, to, amount);
        }

        private void move(int from, int to, BigDecimal amount) {
            balances.merge(from, amount.negate(), BigDecimal::add);
            balances.merge(to, amount, BigDecimal::add);
        }
    }

    // ---- actions + generators ----

    sealed interface Action permits Open, Transfer, Credit, Debit, Sweep, Tax {}
    record Open() implements Action {}
    record Transfer(int from, int to, BigDecimal amount, int dedupSlot) implements Action {}
    record Credit(int player, BigDecimal amount) implements Action {}
    record Debit(int player, BigDecimal amount) implements Action {}
    record Sweep(int from, int to) implements Action {}
    record Tax(int from, BigDecimal amount, int dedupSlot) implements Action {}

    @Provide
    Arbitrary<List<Action>> actions() {
        Arbitrary<Integer> account = Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(19, Arbitraries.integers().between(0, 12)),
                net.jqwik.api.Tuple.of(1, Arbitraries.just(-1)));
        Arbitrary<Integer> player = Arbitraries.integers().between(0, 8);
        Arbitrary<Integer> dedup = Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(3, Arbitraries.just(-1)),
                net.jqwik.api.Tuple.of(1, Arbitraries.integers().between(0, 3)));
        Arbitrary<BigDecimal> amount = amounts();

        Arbitrary<Action> action = Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(3, Arbitraries.just(new Open())),
                net.jqwik.api.Tuple.of(8, Combinators.combine(account, account, amount, dedup).as(Transfer::new)),
                net.jqwik.api.Tuple.of(3, Combinators.combine(player, amount).as(Credit::new)),
                net.jqwik.api.Tuple.of(3, Combinators.combine(player, amount).as(Debit::new)),
                net.jqwik.api.Tuple.of(2, Combinators.combine(account, account).as(Sweep::new)),
                net.jqwik.api.Tuple.of(3, Combinators.combine(account, amount.filter(a -> a.signum() > 0), dedup)
                        .as(Tax::new)));
        return action.list().ofMinSize(1).ofMaxSize(25);
    }

    /** Mostly valid cent amounts up to well past a starting balance, plus invalid ones. */
    private static Arbitrary<BigDecimal> amounts() {
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(12, Arbitraries.longs().between(1, 2_500_000L).map(c -> BigDecimal.valueOf(c, 2))),
                net.jqwik.api.Tuple.of(1, Arbitraries.of(
                        BigDecimal.ZERO, new BigDecimal("-5.00"), new BigDecimal("0.001"), new BigDecimal("0.004"),
                        new BigDecimal("1.005"), new BigDecimal("10000.00"), new BigDecimal("10000.01"))));
    }
}
