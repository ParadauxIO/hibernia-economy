package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.AdminBypassService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.support.TestConfigs;
import io.paradaux.chestshop.utils.BusinessAccountUtil;
import io.paradaux.chestshop.utils.Permissions;
import io.paradaux.chestshop.utils.SignText;
import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.model.tax.TaxResult;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static io.paradaux.chestshop.model.Transaction.TransactionType.BUY;
import static io.paradaux.chestshop.model.Transaction.TransactionType.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Full line + branch coverage for {@link EconomyServiceImpl} — ChestShop's single point of
 * contact with the Treasury ledger. Runs against a live MockBukkit server (real {@link Player}s
 * and real placed signs) with the {@link TreasuryApi}/{@link TaxApi} boundary and the MyBatis-backed
 * {@link AccountService} mocked; the real {@link TransferRequest}/{@link TaxResult} types are
 * constructed/matched, and the routing decisions (who pays whom, the SYSTEM hop, tax collected vs
 * skipped, memo/dedup, admin/no-tax bypass) are asserted for real.
 */
class EconomyServiceImplTest extends ServerTest {

    private static final int SYSTEM_ID = 999;
    private static final UUID SYSTEM_UUID = BusinessAccountUtil.CHESTSHOP_SYSTEM_UUID;

    private AccountService accounts;
    private ItemService items;
    private ChestShopConfiguration config;
    private AdminBypassService adminBypass;
    private TreasuryApi treasury;
    private TaxApi taxApi;
    private EconomyServiceImpl service;
    private World world;
    private int signX = 0;

    @BeforeEach
    void wire() {
        accounts = mock(AccountService.class);
        items = mock(ItemService.class);
        adminBypass = mock(AdminBypassService.class);
        treasury = mock(TreasuryApi.class);
        taxApi = mock(TaxApi.class);
        config = TestConfigs.defaults();
        world = server.addSimpleWorld("shopworld");
        service = new EconomyServiceImpl(accounts, items, config, adminBypass);
        service.bind(treasury, SYSTEM_ID, taxApi);
    }

    // ---- helpers ---------------------------------------------------------------

    private io.paradaux.treasury.model.economy.Account tAccount(int id) {
        io.paradaux.treasury.model.economy.Account a = new io.paradaux.treasury.model.economy.Account();
        a.setAccountId(id);
        return a;
    }

    private io.paradaux.treasury.model.economy.Account govAccount(int id) {
        io.paradaux.treasury.model.economy.Account a = tAccount(id);
        a.setAccountType(AccountType.GOVERNMENT);
        return a;
    }

    /** A real placed sign whose owner (NAME_LINE) and item (ITEM_LINE) lines are set. */
    private Sign placeSign(String owner, String item) {
        Block b = world.getBlockAt(signX += 2, 64, 0);
        b.setType(Material.OAK_SIGN);
        Sign s = (Sign) b.getState();
        SignText.setLine(s, SignService.NAME_LINE, owner);
        SignText.setLine(s, SignService.ITEM_LINE, item);
        s.update(true);
        return (Sign) b.getState();
    }

    private Transaction txn(Transaction.TransactionType type, ItemStack[] stock, String ownerName,
                            Player client, Sign sign, UUID tradeId) {
        Transaction t = mock(Transaction.class);
        when(t.getTransactionType()).thenReturn(type);
        when(t.getStock()).thenReturn(stock);
        when(t.getOwnerAccount()).thenReturn(new Account(ownerName, ownerName, UUID.randomUUID()));
        when(t.getClient()).thenReturn(client);
        when(t.getSign()).thenReturn(sign);
        when(t.getTradeId()).thenReturn(tradeId);
        return t;
    }

    /** A single-diamond stock whose display name resolves (via ItemService) to {@code name}. */
    private ItemStack[] stockNamed(String name) {
        ItemStack[] stock = {new ItemStack(Material.DIAMOND, 3)};
        when(items.getName(stock[0], 0)).thenReturn(name);
        return stock;
    }

    private TransferRequest captureTransfer() {
        ArgumentCaptor<TransferRequest> cap = ArgumentCaptor.forClass(TransferRequest.class);
        verify(treasury).transfer(cap.capture());
        return cap.getValue();
    }

    private void collects(long txnId, BigDecimal charged) {
        when(taxApi.collectRateTax(anyInt(), any(), any(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new TaxResult.Collected(txnId, charged, 1));
    }

    // ---- settle: direct personal→personal + tax collected ----------------------

    @Test
    void settle_buy_directPersonalToPersonal_transfersAndCollectsTax() {
        config = TestConfigs.with(TestConfigs.defaults(), "taxAmount", 5.0d);
        service = new EconomyServiceImpl(accounts, items, config, adminBypass);
        service.bind(treasury, SYSTEM_ID, taxApi);

        Player buyer = player("Buyer");
        UUID seller = UUID.randomUUID();
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(10));
        when(treasury.resolveOrCreatePersonal(seller)).thenReturn(tAccount(20));
        when(treasury.transfer(any())).thenReturn(555L);
        collects(777L, new BigDecimal("0.25"));

        Transaction t = txn(BUY, stockNamed("Diamond"), "Seller", buyer, null, UUID.randomUUID());
        boolean ok = service.settle(new BigDecimal("5"), buyer, seller, t);

        assertThat(ok).isTrue();
        TransferRequest req = captureTransfer();
        assertThat(req.fromAccountId()).isEqualTo(10);
        assertThat(req.toAccountId()).isEqualTo(20);
        assertThat(req.amount()).isEqualByComparingTo("5");
        assertThat(req.initiator()).isEqualTo(buyer.getUniqueId());
        assertThat(req.pluginSystem()).isEqualTo("ChestShop");
        assertThat(req.message()).contains("Buyer bought x3 Diamond from Seller");
        verify(t).setSettlementTxnId(555L);
        verify(t).setSalesTax(new BigDecimal("0.25"));

        // Tax leg: personal receiver authorises itself, off the receiver's account.
        ArgumentCaptor<Integer> srcAcct = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<UUID> taxInit = ArgumentCaptor.forClass(UUID.class);
        verify(taxApi).collectRateTax(srcAcct.capture(), any(), any(), anyString(), anyString(),
                taxInit.capture(), anyString(), any());
        assertThat(srcAcct.getValue()).isEqualTo(20);
        assertThat(taxInit.getValue()).isEqualTo(seller);
    }

    // ---- settle: business seller sells → personal buyer (transferInitiator = SYSTEM) ----

    @Test
    void settle_sell_businessSellerToPersonalBuyer_authorisesThroughSystem() {
        config = TestConfigs.with(TestConfigs.defaults(), "taxAmount", 10.0d);
        service = new EconomyServiceImpl(accounts, items, config, adminBypass);
        service.bind(treasury, SYSTEM_ID, taxApi);

        Player buyer = player("BuyerB");
        UUID businessSeller = BusinessAccountUtil.toBusinessUuid(30);
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(40));
        when(treasury.transfer(any())).thenReturn(600L);
        collects(1L, new BigDecimal("0.50"));

        Transaction t = txn(SELL, stockNamed("Gold"), "Firm", buyer, null, UUID.randomUUID());
        boolean ok = service.settle(new BigDecimal("5"), buyer, businessSeller, t);

        assertThat(ok).isTrue();
        TransferRequest req = captureTransfer();
        assertThat(req.fromAccountId()).isEqualTo(30);   // business seller resolves to its ledger id
        assertThat(req.toAccountId()).isEqualTo(40);
        assertThat(req.initiator()).isEqualTo(SYSTEM_UUID); // business authorises via SYSTEM
        assertThat(req.message()).contains("BuyerB sold x3 Gold to Firm");
        verify(t).setSalesTax(new BigDecimal("0.50"));
    }

    // ---- settle: receiver is an admin shop with no server account → SYSTEM sink ----

    @Test
    void settle_buy_receiverAdminNoServerAccount_sinksToSystem() {
        Player buyer = player("BuyerS");
        UUID adminShop = UUID.randomUUID();
        when(accounts.isAdminShop(adminShop)).thenReturn(true);
        when(accounts.getServerEconomyAccount()).thenReturn(null);
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(50));
        when(treasury.transfer(any())).thenReturn(700L);

        Transaction t = txn(BUY, stockNamed("Iron"), "Admin", buyer, null, UUID.randomUUID());
        boolean ok = service.settle(new BigDecimal("5"), buyer, adminShop, t);

        assertThat(ok).isTrue();
        TransferRequest req = captureTransfer();
        assertThat(req.fromAccountId()).isEqualTo(50);
        assertThat(req.toAccountId()).isEqualTo(SYSTEM_ID);
        assertThat(req.initiator()).isEqualTo(buyer.getUniqueId());
        verify(t).setSettlementTxnId(700L);
        verify(taxApi, never()).collectRateTax(anyInt(), any(), any(), anyString(), anyString(), any(), anyString(), any());
    }

    // ---- settle: sender is an admin shop with no server account → SYSTEM source ----

    @Test
    void settle_sell_senderAdminNoServerAccount_sourcesFromSystem() {
        config = TestConfigs.with(TestConfigs.defaults(), "serverTaxAmount", 2.0d);
        service = new EconomyServiceImpl(accounts, items, config, adminBypass);
        service.bind(treasury, SYSTEM_ID, taxApi);

        Player buyer = player("BuyerR");
        UUID adminShop = UUID.randomUUID();
        when(accounts.isAdminShop(adminShop)).thenReturn(true);
        when(accounts.getServerEconomyAccount()).thenReturn(null);
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(60));
        when(treasury.transfer(any())).thenReturn(800L);
        collects(1L, new BigDecimal("0.10"));

        Transaction t = txn(SELL, stockNamed("Emerald"), "Admin", buyer, null, UUID.randomUUID());
        boolean ok = service.settle(new BigDecimal("5"), buyer, adminShop, t);

        assertThat(ok).isTrue();
        TransferRequest req = captureTransfer();
        assertThat(req.fromAccountId()).isEqualTo(SYSTEM_ID);
        assertThat(req.toAccountId()).isEqualTo(60);
        assertThat(req.initiator()).isEqualTo(SYSTEM_UUID);
        verify(t).setSettlementTxnId(800L);
        // Admin counterparty → server tax rate applied, and it was collected.
        verify(taxApi).collectRateTax(eq(60), any(), any(), anyString(), anyString(), eq(buyer.getUniqueId()), anyString(), any());
    }

    // ---- settle: both sides admin → nothing moves ------------------------------

    @Test
    void settle_bothAdmin_nothingMoves() {
        Player buyer = player("BuyerBoth");
        UUID adminShop = UUID.randomUUID();
        when(accounts.isAdminShop(adminShop)).thenReturn(true);
        when(accounts.isAdminShop(buyer.getUniqueId())).thenReturn(true);
        when(accounts.getServerEconomyAccount()).thenReturn(null);

        Transaction t = txn(BUY, stockNamed("Coal"), "Admin", buyer, null, UUID.randomUUID());
        boolean ok = service.settle(new BigDecimal("5"), buyer, adminShop, t);

        assertThat(ok).isTrue();
        verify(treasury, never()).transfer(any());
        verify(t, never()).setSettlementTxnId(any());
        verify(taxApi, never()).collectRateTax(anyInt(), any(), any(), anyString(), anyString(), any(), anyString(), any());
    }

    // ---- settle: treasury throws → rollback path returns false -----------------

    @Test
    void settle_treasuryThrows_returnsFalse() {
        Player buyer = player("BuyerX");
        UUID seller = UUID.randomUUID();
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(11));
        when(treasury.resolveOrCreatePersonal(seller)).thenReturn(tAccount(12));
        when(treasury.transfer(any())).thenThrow(new RuntimeException("ledger down"));

        Transaction t = txn(BUY, stockNamed("Diamond"), "Seller", buyer, null, UUID.randomUUID());
        boolean ok = service.settle(new BigDecimal("5"), buyer, seller, t);

        assertThat(ok).isFalse();
        verify(t, never()).setSettlementTxnId(any());
        verify(t, never()).setSalesTax(any());
    }

    // ---- settle: admin partner redirects to the configured server-economy account ----

    @Test
    void settle_adminPartnerRedirectsToServerAccount() {
        UUID serverUuid = UUID.randomUUID();
        Player buyer = player("BuyerRedir");
        UUID adminShop = UUID.randomUUID();
        when(accounts.isAdminShop(adminShop)).thenReturn(true);
        when(accounts.isServerEconomyAccount(adminShop)).thenReturn(false);
        when(accounts.getServerEconomyAccount()).thenReturn(new Account("Server", "Server", serverUuid));
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(70));
        when(treasury.resolveOrCreatePersonal(serverUuid)).thenReturn(tAccount(80));
        when(treasury.transfer(any())).thenReturn(900L);
        collects(1L, BigDecimal.ZERO);

        Transaction t = txn(BUY, stockNamed("Diamond"), "Server", buyer, null, UUID.randomUUID());
        boolean ok = service.settle(new BigDecimal("5"), buyer, adminShop, t);

        assertThat(ok).isTrue();
        TransferRequest req = captureTransfer();
        assertThat(req.fromAccountId()).isEqualTo(70);
        assertThat(req.toAccountId()).isEqualTo(80); // redirected to the server-economy account
        verify(t).setSettlementTxnId(900L);
    }

    // ---- settle: admin partner that IS the server-economy account → no redirect ----

    @Test
    void settle_adminPartnerIsServerAccount_noRedirect() {
        Player buyer = player("BuyerSrvEco");
        UUID adminServer = UUID.randomUUID();
        when(accounts.isAdminShop(adminServer)).thenReturn(true);
        when(accounts.isServerEconomyAccount(adminServer)).thenReturn(true);
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(55));
        when(treasury.transfer(any())).thenReturn(950L);

        Transaction t = txn(BUY, stockNamed("Diamond"), "Server", buyer, null, UUID.randomUUID());
        boolean ok = service.settle(new BigDecimal("5"), buyer, adminServer, t);

        assertThat(ok).isTrue();
        // No redirect (it already IS the server account) — receiver is admin → SYSTEM sink.
        TransferRequest req = captureTransfer();
        assertThat(req.fromAccountId()).isEqualTo(55);
        assertThat(req.toAccountId()).isEqualTo(SYSTEM_ID);
    }

    // ---- settle: tax variants --------------------------------------------------

    @Test
    void settle_taxRateZero_recordsZeroTax() {
        // Default config taxAmount is 0 → resolveTaxRate short-circuits to ZERO.
        Player buyer = player("BuyerZeroTax");
        UUID seller = UUID.randomUUID();
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(1));
        when(treasury.resolveOrCreatePersonal(seller)).thenReturn(tAccount(2));
        when(treasury.transfer(any())).thenReturn(1L);

        Transaction t = txn(BUY, stockNamed("Diamond"), "Seller", buyer, null, UUID.randomUUID());
        service.settle(new BigDecimal("5"), buyer, seller, t);

        verify(taxApi, never()).collectRateTax(anyInt(), any(), any(), anyString(), anyString(), any(), anyString(), any());
        verify(t).setSalesTax(BigDecimal.ZERO);
    }

    @Test
    void settle_taxBypassPermission_recordsZeroTax() {
        config = TestConfigs.with(TestConfigs.defaults(), "taxAmount", 5.0d);
        service = new EconomyServiceImpl(accounts, items, config, adminBypass);
        service.bind(treasury, SYSTEM_ID, taxApi);

        Player buyer = player("BuyerNoTax");
        UUID seller = UUID.randomUUID();
        when(adminBypass.has(buyer, Permissions.NO_BUY_TAX)).thenReturn(true);
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(1));
        when(treasury.resolveOrCreatePersonal(seller)).thenReturn(tAccount(2));
        when(treasury.transfer(any())).thenReturn(1L);

        Transaction t = txn(BUY, stockNamed("Diamond"), "Seller", buyer, null, UUID.randomUUID());
        service.settle(new BigDecimal("5"), buyer, seller, t);

        verify(taxApi, never()).collectRateTax(anyInt(), any(), any(), anyString(), anyString(), any(), anyString(), any());
        verify(t).setSalesTax(BigDecimal.ZERO);
    }

    @Test
    void settle_taxFailed_recordsZeroTax() {
        config = TestConfigs.with(TestConfigs.defaults(), "taxAmount", 5.0d);
        service = new EconomyServiceImpl(accounts, items, config, adminBypass);
        service.bind(treasury, SYSTEM_ID, taxApi);

        Player buyer = player("BuyerTaxFail");
        UUID seller = UUID.randomUUID();
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(1));
        when(treasury.resolveOrCreatePersonal(seller)).thenReturn(tAccount(2));
        when(treasury.transfer(any())).thenReturn(1L);
        when(taxApi.collectRateTax(anyInt(), any(), any(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new TaxResult.Failed("insufficient funds"));

        Transaction t = txn(BUY, stockNamed("Diamond"), "Seller", buyer, null, UUID.randomUUID());
        service.settle(new BigDecimal("5"), buyer, seller, t);

        verify(t).setSalesTax(BigDecimal.ZERO);
    }

    @Test
    void settle_taxSkipped_recordsZeroTax() {
        config = TestConfigs.with(TestConfigs.defaults(), "taxAmount", 5.0d);
        service = new EconomyServiceImpl(accounts, items, config, adminBypass);
        service.bind(treasury, SYSTEM_ID, taxApi);

        Player buyer = player("BuyerTaxSkip");
        UUID seller = UUID.randomUUID();
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(1));
        when(treasury.resolveOrCreatePersonal(seller)).thenReturn(tAccount(2));
        when(treasury.transfer(any())).thenReturn(1L);
        when(taxApi.collectRateTax(anyInt(), any(), any(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new TaxResult.Skipped("below minimum"));

        Transaction t = txn(BUY, stockNamed("Diamond"), "Seller", buyer, null, UUID.randomUUID());
        service.settle(new BigDecimal("5"), buyer, seller, t);

        verify(t).setSalesTax(BigDecimal.ZERO);
    }

    @Test
    void settle_taxThrows_recordsZeroTax() {
        config = TestConfigs.with(TestConfigs.defaults(), "taxAmount", 5.0d);
        service = new EconomyServiceImpl(accounts, items, config, adminBypass);
        service.bind(treasury, SYSTEM_ID, taxApi);

        Player buyer = player("BuyerTaxThrow");
        UUID seller = UUID.randomUUID();
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(1));
        when(treasury.resolveOrCreatePersonal(seller)).thenReturn(tAccount(2));
        when(treasury.transfer(any())).thenReturn(1L);
        when(taxApi.collectRateTax(anyInt(), any(), any(), anyString(), anyString(), any(), anyString(), any()))
                .thenThrow(new RuntimeException("tax subsystem down"));

        Transaction t = txn(BUY, stockNamed("Diamond"), "Seller", buyer, null, UUID.randomUUID());
        service.settle(new BigDecimal("5"), buyer, seller, t);

        verify(t).setSalesTax(BigDecimal.ZERO);
    }

    @Test
    void settle_receiverAccountIdZero_skipsTax() {
        config = TestConfigs.with(TestConfigs.defaults(), "taxAmount", 5.0d);
        service = new EconomyServiceImpl(accounts, items, config, adminBypass);
        service.bind(treasury, SYSTEM_ID, taxApi);

        Player buyer = player("BuyerZeroAcct");
        UUID businessSeller = BusinessAccountUtil.toBusinessUuid(0); // resolves to ledger id 0
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(90));
        when(treasury.transfer(any())).thenReturn(1000L);

        Transaction t = txn(BUY, stockNamed("Diamond"), "Firm", buyer, null, UUID.randomUUID());
        service.settle(new BigDecimal("5"), buyer, businessSeller, t);

        // receiverAccountId == 0 → tax leg is skipped even though a rate is configured.
        verify(taxApi, never()).collectRateTax(anyInt(), any(), any(), anyString(), anyString(), any(), anyString(), any());
        verify(t).setSettlementTxnId(1000L);
    }

    @Test
    void settle_receiverBusinessWithFunds_taxAuthorisedThroughSystem() {
        config = TestConfigs.with(TestConfigs.defaults(), "taxAmount", 5.0d);
        service = new EconomyServiceImpl(accounts, items, config, adminBypass);
        service.bind(treasury, SYSTEM_ID, taxApi);

        Player buyer = player("BuyerBizRcv");
        UUID businessSeller = BusinessAccountUtil.toBusinessUuid(15);
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(100));
        when(treasury.transfer(any())).thenReturn(1100L);
        collects(1L, new BigDecimal("0.25"));

        Transaction t = txn(BUY, stockNamed("Diamond"), "Firm", buyer, null, UUID.randomUUID());
        service.settle(new BigDecimal("5"), buyer, businessSeller, t);

        // Business receiver → the tax leg is authorised through the SYSTEM account, off account 15.
        verify(taxApi).collectRateTax(eq(15), any(), any(), anyString(), anyString(), eq(SYSTEM_UUID), anyString(), any());
        verify(t).setSalesTax(new BigDecimal("0.25"));
    }

    @Test
    void settle_taxApiNull_skipsTaxLeg() {
        config = TestConfigs.with(TestConfigs.defaults(), "taxAmount", 5.0d);
        service = new EconomyServiceImpl(accounts, items, config, adminBypass);
        service.bind(treasury, SYSTEM_ID, null); // no tax API bound

        Player buyer = player("BuyerNoTaxApi");
        UUID seller = UUID.randomUUID();
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(1));
        when(treasury.resolveOrCreatePersonal(seller)).thenReturn(tAccount(2));
        when(treasury.transfer(any())).thenReturn(1200L);

        Transaction t = txn(BUY, stockNamed("Diamond"), "Seller", buyer, null, UUID.randomUUID());
        boolean ok = service.settle(new BigDecimal("5"), buyer, seller, t);

        assertThat(ok).isTrue();
        verify(t, never()).setSalesTax(any());
    }

    // ---- settle: item-name resolution fallbacks (transferItemName) -------------

    @Test
    void settle_itemNameFromSign_whenCodeBlank() {
        Player buyer = player("BuyerBlank");
        UUID seller = UUID.randomUUID();
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(1));
        when(treasury.resolveOrCreatePersonal(seller)).thenReturn(tAccount(2));
        when(treasury.transfer(any())).thenReturn(1L);
        Sign sign = placeSign("Seller", "SignDiamond");
        ItemStack[] stock = {new ItemStack(Material.DIAMOND, 3)};
        when(items.getName(stock[0], 0)).thenReturn("   "); // blank code → fall back to sign

        Transaction t = txn(BUY, stock, "Seller", buyer, sign, UUID.randomUUID());
        service.settle(new BigDecimal("5"), buyer, seller, t);

        assertThat(captureTransfer().message()).contains("SignDiamond");
    }

    @Test
    void settle_itemNameFromSign_whenGetNameThrows() {
        Player buyer = player("BuyerThrowName");
        UUID seller = UUID.randomUUID();
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(1));
        when(treasury.resolveOrCreatePersonal(seller)).thenReturn(tAccount(2));
        when(treasury.transfer(any())).thenReturn(1L);
        Sign sign = placeSign("Seller", "SignEmerald");
        ItemStack[] stock = {new ItemStack(Material.DIAMOND, 3)};
        when(items.getName(stock[0], 0)).thenThrow(new RuntimeException("bad code"));

        Transaction t = txn(BUY, stock, "Seller", buyer, sign, UUID.randomUUID());
        service.settle(new BigDecimal("5"), buyer, seller, t);

        assertThat(captureTransfer().message()).contains("SignEmerald");
    }

    @Test
    void settle_itemNameFromSign_whenStockEmpty() {
        Player buyer = player("BuyerEmpty");
        UUID seller = UUID.randomUUID();
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(1));
        when(treasury.resolveOrCreatePersonal(seller)).thenReturn(tAccount(2));
        when(treasury.transfer(any())).thenReturn(1L);
        Sign sign = placeSign("Seller", "SignGold");

        Transaction t = txn(BUY, new ItemStack[0], "Seller", buyer, sign, UUID.randomUUID());
        service.settle(new BigDecimal("5"), buyer, seller, t);

        assertThat(captureTransfer().message()).contains("SignGold");
    }

    // ---- settle: memo truncation (buildTransferMessage) ------------------------

    @Test
    void settle_memoTruncatedToMaxLength_whenNamesOverflow() {
        Player buyer = player("BuyerLong");
        UUID seller = UUID.randomUUID();
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(1));
        when(treasury.resolveOrCreatePersonal(seller)).thenReturn(tAccount(2));
        when(treasury.transfer(any())).thenReturn(1L);
        String hugeOwner = "O".repeat(300);
        ItemStack[] stock = {new ItemStack(Material.DIAMOND, 3)};
        when(items.getName(stock[0], 0)).thenReturn("Diamond");

        Transaction t = txn(BUY, stock, hugeOwner, buyer, null, UUID.randomUUID());
        service.settle(new BigDecimal("5"), buyer, seller, t);

        assertThat(captureTransfer().message()).hasSize(250); // clamped to MAX_MESSAGE_LENGTH
    }

    @Test
    void settle_itemNameTruncated_whenLongerThanAvailable() {
        Player buyer = player("BuyerItemTrunc");
        UUID seller = UUID.randomUUID();
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(1));
        when(treasury.resolveOrCreatePersonal(seller)).thenReturn(tAccount(2));
        when(treasury.transfer(any())).thenReturn(1L);
        ItemStack[] stock = {new ItemStack(Material.DIAMOND, 3)};
        when(items.getName(stock[0], 0)).thenReturn("I".repeat(400)); // item name overflows budget

        Transaction t = txn(BUY, stock, "Seller", buyer, null, UUID.randomUUID());
        service.settle(new BigDecimal("5"), buyer, seller, t);

        assertThat(captureTransfer().message()).hasSize(250);
    }

    // ---- format ----------------------------------------------------------------

    @Test
    void format_beforeTreasuryBound_returnsPlainString() {
        EconomyServiceImpl unbound = new EconomyServiceImpl(accounts, items, config, adminBypass);
        assertThat(unbound.format(new BigDecimal("12.50"))).isEqualTo("12.50");
    }

    @Test
    void format_stripColorsFalse_returnsRawFormatting() {
        config = TestConfigs.with(TestConfigs.defaults(), "stripPriceColors", false);
        service = new EconomyServiceImpl(accounts, items, config, adminBypass);
        service.bind(treasury, SYSTEM_ID, taxApi);
        when(treasury.formatAmount(any())).thenReturn("§a$5.00");
        assertThat(service.format(new BigDecimal("5"))).isEqualTo("§a$5.00");
    }

    @Test
    void format_stripColorsTrue_stripsColourCodes() {
        config = TestConfigs.with(TestConfigs.defaults(), "stripPriceColors", true);
        service = new EconomyServiceImpl(accounts, items, config, adminBypass);
        service.bind(treasury, SYSTEM_ID, taxApi);
        when(treasury.formatAmount(any())).thenReturn("§a$5.00");
        assertThat(service.format(new BigDecimal("5"))).isEqualTo("$5.00");
    }

    @Test
    void format_treasuryThrows_fallsBackToPlainString() {
        when(treasury.formatAmount(any())).thenThrow(new RuntimeException("boom"));
        assertThat(service.format(new BigDecimal("5.5"))).isEqualTo("5.5");
    }

    // ---- isOwnerEconomicallyActive --------------------------------------------

    @Test
    void isOwnerEconomicallyActive_trueForLimitedOwner() {
        assertThat(service.isOwnerEconomicallyActive(false)).isTrue();
    }

    @Test
    void isOwnerEconomicallyActive_unlimitedOwner_dependsOnServerAccount() {
        when(accounts.getServerEconomyAccount()).thenReturn(null);
        assertThat(service.isOwnerEconomicallyActive(true)).isFalse();
        when(accounts.getServerEconomyAccount()).thenReturn(new Account("Server", "Server", UUID.randomUUID()));
        assertThat(service.isOwnerEconomicallyActive(true)).isTrue();
    }

    // ---- deposit ---------------------------------------------------------------

    @Test
    void deposit_adminShopNoServerAccount_isNoOp() {
        UUID adminShop = UUID.randomUUID();
        when(accounts.isAdminShop(adminShop)).thenReturn(true);
        when(accounts.getServerEconomyAccount()).thenReturn(null);
        service.deposit(adminShop, new BigDecimal("5"), world);
        verify(treasury, never()).transfer(any());
    }

    @Test
    void deposit_personalTarget_creditsFromSystem() {
        UUID target = UUID.randomUUID();
        when(treasury.resolveOrCreatePersonal(target)).thenReturn(tAccount(21));
        service.deposit(target, new BigDecimal("5"), world);
        TransferRequest req = captureTransfer();
        assertThat(req.fromAccountId()).isEqualTo(SYSTEM_ID);
        assertThat(req.toAccountId()).isEqualTo(21);
        assertThat(req.message()).isEqualTo("ChestShop deposit");
    }

    @Test
    void deposit_treasuryThrows_isSwallowed() {
        UUID target = UUID.randomUUID();
        when(treasury.resolveOrCreatePersonal(target)).thenReturn(tAccount(21));
        when(treasury.transfer(any())).thenThrow(new RuntimeException("down"));
        service.deposit(target, new BigDecimal("5"), world); // must not throw
    }

    // ---- withdraw --------------------------------------------------------------

    @Test
    void withdraw_adminShopNoServerAccount_returnsTrue() {
        UUID adminShop = UUID.randomUUID();
        when(accounts.isAdminShop(adminShop)).thenReturn(true);
        when(accounts.getServerEconomyAccount()).thenReturn(null);
        assertThat(service.withdraw(adminShop, new BigDecimal("5"), world)).isTrue();
        verify(treasury, never()).transfer(any());
    }

    @Test
    void withdraw_personalTarget_authorisesItself() {
        UUID target = UUID.randomUUID();
        when(treasury.resolveOrCreatePersonal(target)).thenReturn(tAccount(22));
        assertThat(service.withdraw(target, new BigDecimal("5"), world)).isTrue();
        TransferRequest req = captureTransfer();
        assertThat(req.fromAccountId()).isEqualTo(22);
        assertThat(req.toAccountId()).isEqualTo(SYSTEM_ID);
        assertThat(req.initiator()).isEqualTo(target);
        assertThat(req.message()).isEqualTo("ChestShop withdrawal");
    }

    @Test
    void withdraw_businessTarget_authorisesThroughSystem() {
        UUID business = BusinessAccountUtil.toBusinessUuid(33);
        assertThat(service.withdraw(business, new BigDecimal("5"), world)).isTrue();
        TransferRequest req = captureTransfer();
        assertThat(req.fromAccountId()).isEqualTo(33);
        assertThat(req.initiator()).isEqualTo(SYSTEM_UUID);
    }

    @Test
    void withdraw_treasuryThrows_returnsFalse() {
        UUID target = UUID.randomUUID();
        when(treasury.resolveOrCreatePersonal(target)).thenReturn(tAccount(22));
        when(treasury.transfer(any())).thenThrow(new RuntimeException("down"));
        assertThat(service.withdraw(target, new BigDecimal("5"), world)).isFalse();
    }

    // ---- hasFunds --------------------------------------------------------------

    @Test
    void hasFunds_adminShopNoServerAccount_isTrue() {
        UUID adminShop = UUID.randomUUID();
        when(accounts.isAdminShop(adminShop)).thenReturn(true);
        when(accounts.getServerEconomyAccount()).thenReturn(null);
        assertThat(service.hasFunds(adminShop, new BigDecimal("5"))).isTrue();
    }

    @Test
    void hasFunds_delegatesToTreasury() {
        UUID target = UUID.randomUUID();
        when(treasury.resolveOrCreatePersonal(target)).thenReturn(tAccount(23));
        when(treasury.hasFunds(23, new BigDecimal("5"))).thenReturn(true);
        assertThat(service.hasFunds(target, new BigDecimal("5"))).isTrue();
    }

    @Test
    void hasFunds_treasuryThrows_isFalse() {
        UUID target = UUID.randomUUID();
        when(treasury.resolveOrCreatePersonal(target)).thenReturn(tAccount(23));
        when(treasury.hasFunds(anyInt(), any())).thenThrow(new RuntimeException("down"));
        assertThat(service.hasFunds(target, new BigDecimal("5"))).isFalse();
    }

    // ---- getBalance ------------------------------------------------------------

    @Test
    void getBalance_adminShopNoServerAccount_isEffectivelyUnlimited() {
        UUID adminShop = UUID.randomUUID();
        when(accounts.isAdminShop(adminShop)).thenReturn(true);
        when(accounts.getServerEconomyAccount()).thenReturn(null);
        assertThat(service.getBalance(adminShop)).isEqualByComparingTo(BigDecimal.valueOf(Double.MAX_VALUE));
    }

    @Test
    void getBalance_businessAccount_readsByAccountId() {
        UUID business = BusinessAccountUtil.toBusinessUuid(44);
        when(treasury.getBalanceByAccountId(44)).thenReturn(new BigDecimal("12"));
        assertThat(service.getBalance(business)).isEqualByComparingTo("12");
    }

    @Test
    void getBalance_governmentAccount_readsByGovernmentId() {
        UUID owner = UUID.randomUUID();
        when(treasury.getAccountsByTypeAndOwner(AccountType.GOVERNMENT, owner)).thenReturn(List.of(govAccount(7)));
        when(treasury.getBalanceByAccountId(7)).thenReturn(new BigDecimal("99"));
        assertThat(service.getBalance(owner)).isEqualByComparingTo("99");
    }

    @Test
    void getBalance_personalNoGovernment_readsByOwnerUuid() {
        UUID owner = UUID.randomUUID();
        when(treasury.getAccountsByTypeAndOwner(AccountType.GOVERNMENT, owner)).thenReturn(List.of());
        when(treasury.getBalanceByOwnerUuid(owner)).thenReturn(new BigDecimal("13"));
        assertThat(service.getBalance(owner)).isEqualByComparingTo("13");
    }

    @Test
    void getBalance_governmentLookupThrows_fallsBackToOwnerUuid() {
        UUID owner = UUID.randomUUID();
        when(treasury.getAccountsByTypeAndOwner(AccountType.GOVERNMENT, owner)).thenThrow(new RuntimeException("db"));
        when(treasury.getBalanceByOwnerUuid(owner)).thenReturn(new BigDecimal("8"));
        assertThat(service.getBalance(owner)).isEqualByComparingTo("8");
    }

    @Test
    void getBalance_treasuryThrows_returnsZero() {
        UUID business = BusinessAccountUtil.toBusinessUuid(44);
        when(treasury.getBalanceByAccountId(44)).thenThrow(new RuntimeException("down"));
        assertThat(service.getBalance(business)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---- hasAccount ------------------------------------------------------------

    @Test
    void hasAccount_businessAccount_checksByAccountId() {
        UUID business = BusinessAccountUtil.toBusinessUuid(45);
        when(treasury.hasAccountByAccountId(45)).thenReturn(true);
        assertThat(service.hasAccount(business)).isTrue();
    }

    @Test
    void hasAccount_governmentAccount_checksByGovernmentId() {
        UUID owner = UUID.randomUUID();
        when(treasury.getAccountsByTypeAndOwner(AccountType.GOVERNMENT, owner)).thenReturn(List.of(govAccount(6)));
        when(treasury.hasAccountByAccountId(6)).thenReturn(true);
        assertThat(service.hasAccount(owner)).isTrue();
    }

    @Test
    void hasAccount_personalNoGovernment_checksByOwnerUuid() {
        UUID owner = UUID.randomUUID();
        when(treasury.getAccountsByTypeAndOwner(AccountType.GOVERNMENT, owner)).thenReturn(null);
        when(treasury.hasAccountByOwnerUuid(owner)).thenReturn(true);
        assertThat(service.hasAccount(owner)).isTrue();
    }

    @Test
    void hasAccount_treasuryThrows_isFalse() {
        UUID owner = UUID.randomUUID();
        when(treasury.getAccountsByTypeAndOwner(AccountType.GOVERNMENT, owner)).thenReturn(null);
        when(treasury.hasAccountByOwnerUuid(owner)).thenThrow(new RuntimeException("down"));
        assertThat(service.hasAccount(owner)).isFalse();
    }

    // ---- migrateLegacyBusinessSign ---------------------------------------------

    @Test
    void migrate_returnsEarly_whenSignNull() {
        Transaction t = mock(Transaction.class);
        when(t.getSign()).thenReturn(null);
        when(t.getOwnerAccount()).thenReturn(businessOwner("B1"));
        service.migrateLegacyBusinessSign(t); // no exception
    }

    @Test
    void migrate_returnsEarly_whenOwnerNull() {
        Transaction t = mock(Transaction.class);
        when(t.getSign()).thenReturn(placeSign("X", "Y"));
        when(t.getOwnerAccount()).thenReturn(null);
        service.migrateLegacyBusinessSign(t);
    }

    @Test
    void migrate_returnsEarly_whenOwnerUuidNull() {
        Account owner = new Account();
        owner.setUuid(null);
        Transaction t = mock(Transaction.class);
        when(t.getSign()).thenReturn(placeSign("X", "Y"));
        when(t.getOwnerAccount()).thenReturn(owner);
        service.migrateLegacyBusinessSign(t);
    }

    @Test
    void migrate_returnsEarly_whenOwnerNotBusiness() {
        Account owner = new Account("Notch", "Notch", UUID.randomUUID()); // personal uuid
        Transaction t = mock(Transaction.class);
        when(t.getSign()).thenReturn(placeSign("X", "Y"));
        when(t.getOwnerAccount()).thenReturn(owner);
        service.migrateLegacyBusinessSign(t);
    }

    @Test
    void migrate_returnsEarly_whenCanonicalNull() {
        Account owner = businessOwner(null);
        Transaction t = mock(Transaction.class);
        when(t.getSign()).thenReturn(placeSign("OLD", "Y"));
        when(t.getOwnerAccount()).thenReturn(owner);
        service.migrateLegacyBusinessSign(t);
    }

    @Test
    void migrate_returnsEarly_whenSignAlreadyCanonical() {
        Sign sign = placeSign("Firm", "Y");
        Account owner = businessOwner("Firm");
        Transaction t = mock(Transaction.class);
        when(t.getSign()).thenReturn(sign);
        when(t.getOwnerAccount()).thenReturn(owner);
        service.migrateLegacyBusinessSign(t);
        assertThat(SignService.getOwner((Sign) sign.getBlock().getState())).isEqualTo("Firm");
    }

    @Test
    void migrate_rewritesSign_whenOwnerNameChanged() {
        Sign sign = placeSign("OLD", "Y");
        Account owner = businessOwner("NewFirm");
        Transaction t = mock(Transaction.class);
        when(t.getSign()).thenReturn(sign);
        when(t.getOwnerAccount()).thenReturn(owner);

        service.migrateLegacyBusinessSign(t);

        assertThat(SignText.getLine((Sign) sign.getBlock().getState(), SignService.NAME_LINE)).isEqualTo("NewFirm");
    }

    private Account businessOwner(String shortName) {
        Account owner = new Account();
        owner.setUuid(BusinessAccountUtil.toBusinessUuid(1));
        owner.setShortName(shortName);
        return owner;
    }

    // ---- extra branch coverage: server-economy tax rate + item-name null fallback ----

    @Test
    void settle_serverEconomyPartner_appliesServerTaxRate() {
        config = TestConfigs.with(TestConfigs.defaults(), "serverTaxAmount", 3.0d);
        service = new EconomyServiceImpl(accounts, items, config, adminBypass);
        service.bind(treasury, SYSTEM_ID, taxApi);

        Player buyer = player("BuyerSrvTax");
        UUID srvEco = UUID.randomUUID();
        when(accounts.isServerEconomyAccount(srvEco)).thenReturn(true); // not an admin shop, but server-eco
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(1));
        when(treasury.resolveOrCreatePersonal(srvEco)).thenReturn(tAccount(2));
        when(treasury.transfer(any())).thenReturn(1L);
        collects(1L, new BigDecimal("0.15"));

        Transaction t = txn(BUY, stockNamed("Diamond"), "Server", buyer, null, UUID.randomUUID());
        service.settle(new BigDecimal("5"), buyer, srvEco, t);

        verify(taxApi).collectRateTax(anyInt(), any(), any(), anyString(), anyString(), any(), anyString(), any());
        verify(t).setSalesTax(new BigDecimal("0.15"));
    }

    @Test
    void settle_itemNameFromSign_whenCodeNull() {
        Player buyer = player("BuyerNullName");
        UUID seller = UUID.randomUUID();
        when(treasury.resolveOrCreatePersonal(buyer.getUniqueId())).thenReturn(tAccount(1));
        when(treasury.resolveOrCreatePersonal(seller)).thenReturn(tAccount(2));
        when(treasury.transfer(any())).thenReturn(1L);
        Sign sign = placeSign("Seller", "SignRuby");
        ItemStack[] stock = {new ItemStack(Material.DIAMOND, 3)};
        when(items.getName(stock[0], 0)).thenReturn(null); // no code → fall back to sign

        Transaction t = txn(BUY, stock, "Seller", buyer, sign, UUID.randomUUID());
        service.settle(new BigDecimal("5"), buyer, seller, t);

        assertThat(captureTransfer().message()).contains("SignRuby");
    }

    // ---- extra branch coverage: normaliseAdminTarget + government resolution ----

    @Test
    void deposit_adminServerEcoTarget_creditsTargetDirectly() {
        UUID target = UUID.randomUUID();
        when(accounts.isAdminShop(target)).thenReturn(true);
        when(accounts.isServerEconomyAccount(target)).thenReturn(true); // already the server account → no redirect
        when(treasury.resolveOrCreatePersonal(target)).thenReturn(tAccount(31));
        service.deposit(target, new BigDecimal("5"), world);
        assertThat(captureTransfer().toAccountId()).isEqualTo(31);
    }

    @Test
    void deposit_adminTargetRedirectsToServerAccount() {
        UUID target = UUID.randomUUID();
        UUID serverUuid = UUID.randomUUID();
        when(accounts.isAdminShop(target)).thenReturn(true);
        when(accounts.isServerEconomyAccount(target)).thenReturn(false);
        when(accounts.getServerEconomyAccount()).thenReturn(new Account("Server", "Server", serverUuid));
        when(treasury.resolveOrCreatePersonal(serverUuid)).thenReturn(tAccount(32));
        service.deposit(target, new BigDecimal("5"), world);
        assertThat(captureTransfer().toAccountId()).isEqualTo(32);
    }

    @Test
    void deposit_governmentTarget_creditsGovernmentAccount() {
        UUID target = UUID.randomUUID();
        when(treasury.getAccountsByTypeAndOwner(AccountType.GOVERNMENT, target)).thenReturn(List.of(govAccount(41)));
        service.deposit(target, new BigDecimal("5"), world);
        assertThat(captureTransfer().toAccountId()).isEqualTo(41);
    }

    // ---- defensive-guard coverage (private methods, unreachable via the public API but
    //      real code paths): exercised directly so the null-guards are covered for real. ----

    @Test
    void transferItemName_nullStock_fallsBackToSign() throws Exception {
        Sign sign = placeSign("Owner", "GuardStone");
        Transaction t = mock(Transaction.class);
        when(t.getStock()).thenReturn(null);
        when(t.getSign()).thenReturn(sign);
        var m = EconomyServiceImpl.class.getDeclaredMethod("transferItemName", Transaction.class);
        m.setAccessible(true);
        assertThat((String) m.invoke(service, t)).isEqualTo("GuardStone");
    }

    @Test
    void transferItemName_nullFirstElement_fallsBackToSign() throws Exception {
        Sign sign = placeSign("Owner", "GuardOak");
        Transaction t = mock(Transaction.class);
        when(t.getStock()).thenReturn(new ItemStack[]{null});
        when(t.getSign()).thenReturn(sign);
        var m = EconomyServiceImpl.class.getDeclaredMethod("transferItemName", Transaction.class);
        m.setAccessible(true);
        assertThat((String) m.invoke(service, t)).isEqualTo("GuardOak");
    }

    @Test
    void resolveTaxRate_nullPartner_usesBaseRate() throws Exception {
        // Default base taxAmount is 0 → a null partner short-circuits to ZERO.
        var m = EconomyServiceImpl.class.getDeclaredMethod("resolveTaxRate", UUID.class);
        m.setAccessible(true);
        assertThat((BigDecimal) m.invoke(service, (UUID) null)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void collectSalesTax_nullInitiator_skipsBypassCheck() throws Exception {
        config = TestConfigs.with(TestConfigs.defaults(), "taxAmount", 5.0d);
        service = new EconomyServiceImpl(accounts, items, config, adminBypass);
        service.bind(treasury, SYSTEM_ID, taxApi);
        UUID receiver = UUID.randomUUID();
        collects(1L, new BigDecimal("0.25"));

        var m = EconomyServiceImpl.class.getDeclaredMethod("collectSalesTax",
                int.class, UUID.class, BigDecimal.class, UUID.class, Player.class, String.class, UUID.class);
        m.setAccessible(true);
        BigDecimal charged = (BigDecimal) m.invoke(service, 7, receiver, new BigDecimal("5"),
                receiver, null, "memo", UUID.randomUUID());

        assertThat(charged).isEqualByComparingTo("0.25");
        verify(taxApi).collectRateTax(eq(7), any(), any(), anyString(), anyString(), eq(receiver), anyString(), any());
    }
}
