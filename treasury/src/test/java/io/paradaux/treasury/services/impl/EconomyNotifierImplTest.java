package io.paradaux.treasury.services.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.services.AccountService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EconomyNotifierImplTest {

    @Mock
    AccountService accountService;

    @Mock
    Message message;

    private EconomyNotifierImpl notifier() {
        lenient().when(accountService.formatAmount(any())).thenAnswer(inv -> "$" + inv.getArgument(0));
        return new EconomyNotifierImpl(accountService, message);
    }

    private Account personal(UUID owner) {
        Account a = Account.builder()
                .accountId(42)
                .accountType(AccountType.PERSONAL)
                .ownerUuid(owner)
                .build();
        return a;
    }

    // ---- notifyTaxCollected ----

    @Test
    void tax_messagesPersonalAccountOwner() {
        UUID owner = UUID.randomUUID();
        when(accountService.getAccountById(42)).thenReturn(personal(owner));

        notifier().notifyTaxCollected(42, "personal-balance-tax", new BigDecimal("12.34"));

        // "personal-balance-tax" gets the friendly label "balance tax".
        verify(message)
                .send(
                        eq(owner),
                        eq("treasury.tax.collected"),
                        eq("amount"),
                        eq("$12.34"),
                        eq("tax"),
                        eq("balance tax"));
    }

    @Test
    void tax_friendlyLabelDefaultsToDehyphenatedType() {
        UUID owner = UUID.randomUUID();
        when(accountService.getAccountById(42)).thenReturn(personal(owner));

        notifier().notifyTaxCollected(42, "property-tax", new BigDecimal("5.00"));

        verify(message)
                .send(
                        eq(owner),
                        eq("treasury.tax.collected"),
                        eq("amount"),
                        eq("$5.00"),
                        eq("tax"),
                        eq("property tax"));
    }

    @Test
    void tax_silentTypeIsNotAnnounced() {
        notifier().notifyTaxCollected(42, "source-income-tax", new BigDecimal("1.00"));
        verify(accountService, never()).getAccountById(org.mockito.ArgumentMatchers.anyInt());
        verifyNoInteractions(message);
    }

    @Test
    void tax_skipsWhenAccountMissing() {
        when(accountService.getAccountById(42)).thenReturn(null);
        notifier().notifyTaxCollected(42, "property-tax", new BigDecimal("1.00"));
        verifyNoInteractions(message);
    }

    @Test
    void tax_skipsNonPersonalAccount() {
        Account gov = Account.builder()
                .accountId(42)
                .accountType(AccountType.GOVERNMENT)
                .ownerUuid(UUID.randomUUID())
                .build();
        when(accountService.getAccountById(42)).thenReturn(gov);

        notifier().notifyTaxCollected(42, "property-tax", new BigDecimal("1.00"));
        verifyNoInteractions(message);
    }

    @Test
    void tax_skipsPersonalAccountWithNullOwner() {
        Account a = Account.builder()
                .accountId(42)
                .accountType(AccountType.PERSONAL)
                .ownerUuid(null)
                .build();
        when(accountService.getAccountById(42)).thenReturn(a);

        notifier().notifyTaxCollected(42, "property-tax", new BigDecimal("1.00"));
        verifyNoInteractions(message);
    }

    @Test
    void tax_nullTypeStillNotifiesWithGenericLabel() {
        UUID owner = UUID.randomUUID();
        when(accountService.getAccountById(42)).thenReturn(personal(owner));

        notifier().notifyTaxCollected(42, null, new BigDecimal("2.00"));

        verify(message).send(eq(owner), eq("treasury.tax.collected"), eq("amount"), eq("$2.00"), eq("tax"), eq("tax"));
    }

    // ---- notifySalaryPaid ----

    @Test
    void salary_messagesRecipient() {
        UUID player = UUID.randomUUID();
        notifier().notifySalaryPaid(player, new BigDecimal("75.00"));
        verify(message).send(eq(player), eq("treasury.salary.received"), eq("amount"), eq("$75.00"));
    }
}
