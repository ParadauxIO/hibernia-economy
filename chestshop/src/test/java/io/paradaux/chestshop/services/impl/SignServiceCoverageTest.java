package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.support.TestConfigs;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Full coverage for {@link SignService}: the business/admin token predicates, the structural
 * sign validation (owner-name pattern, the three configured line patterns, the single-colon
 * price rule), and the static line accessors — driven from real MockBukkit signs where the
 * {@code Sign}/{@code Block} overloads require live world state.
 */
class SignServiceCoverageTest extends ServerTest {

    private ChestShopConfiguration config;
    private SignService signs;
    private World world;
    private int nextX = 0;

    @BeforeEach
    void wire() {
        config = TestConfigs.defaults(); // ADMIN_SHOP_NAME "Admin Shop", VALID_PLAYERNAME_REGEXP ^\.?\w+$
        signs = new SignService(config);
        world = server.addSimpleWorld("signworld");
        for (int cx = -2; cx <= 40; cx++) {
            for (int cz = -2; cz <= 2; cz++) {
                world.loadChunk(cx, cz);
            }
        }
    }

    /** Place a real front-facing wall sign and stamp the four lines onto it. */
    private Sign placeSign(String owner, String qty, String price, String item) {
        Block block = world.getBlockAt(nextX += 2, 64, 0);
        block.setType(Material.OAK_WALL_SIGN);
        Sign sign = (Sign) block.getState();
        sign.setLine(SignService.NAME_LINE, owner);
        sign.setLine(SignService.QUANTITY_LINE, qty);
        sign.setLine(SignService.PRICE_LINE, price);
        sign.setLine(SignService.ITEM_LINE, item);
        sign.update();
        return (Sign) block.getState();
    }

    // ---- business token ---------------------------------------------------------

    @Test
    void businessAccount_detectionAndIdRoundTrip() {
        assertThat(SignService.isBusinessAccount("B:1A")).isTrue();
        assertThat(SignService.isBusinessAccount("PlayerName")).isFalse();
        assertThat(SignService.isBusinessAccount(new String[]{"B:Z9", "", "", ""})).isTrue();

        int id = SignService.getBusinessAccountId("B:Z9");
        assertThat(SignService.businessAccountSignName(id)).isEqualTo("B:Z9");
    }

    // ---- admin shop -------------------------------------------------------------

    @Test
    void adminShop_matchesConfiguredNameIgnoringSpacesAndCase() {
        assertThat(signs.isAdminShop("Admin Shop")).isTrue();
        assertThat(signs.isAdminShop("adminshop")).isTrue();
        assertThat(signs.isAdminShop("SomePlayer")).isFalse();
        assertThat(signs.isAdminShop(new String[]{"Admin Shop", "", "", ""})).isTrue();
    }

    @Test
    void adminShop_fromRealSign() {
        Sign sign = placeSign("Admin Shop", "64", "B 5", "Diamond");
        assertThat(signs.isAdminShop(sign)).isTrue();
    }

    // ---- validateSign / isValid -------------------------------------------------

    @Test
    void isValid_trueForWellFormedPlayerShop() {
        String[] lines = {"Notch", "64", "B 5", "Diamond"};
        assertThat(signs.isValid(lines)).isTrue();
    }

    @Test
    void isValid_falseWhenNoBuyOrSellFlag() {
        // Structurally valid lines but the price carries neither B nor S once normalised.
        String[] lines = {"Notch", "64", "5", "Diamond"};
        assertThat(signs.isValid(lines)).isFalse();
    }

    @Test
    void isValid_trueForSellOnlyShop() {
        // Exercises the `contains("S")` side of the buy-or-sell price check.
        assertThat(signs.isValid(new String[]{"Notch", "64", "S 5", "Diamond"})).isTrue();
    }

    @Test
    void isValid_block_falseForSignWithInvalidLines() {
        Sign invalid = placeSign("", "notaqty", "nope", "??bad");
        // The block is a sign, but its lines don't validate.
        assertThat(signs.isValid(invalid.getBlock())).isFalse();
    }

    @Test
    void isValid_falseWhenOwnerEmpty() {
        String[] lines = {"", "64", "B 5", "Diamond"};
        assertThat(signs.isValid(lines)).isFalse();
    }

    @Test
    void validateSign_adminOwnerSkipsNameValidation() {
        // Admin owner name isn't validated against the player-name regexp.
        assertThat(signs.validateSign(new String[]{"Admin Shop", "64", "B 5", "Diamond"})).isTrue();
    }

    @Test
    void validateSign_businessOwnerSkipsNameValidation() {
        assertThat(signs.validateSign(new String[]{"B:1A", "64", "B 5", "Diamond"})).isTrue();
    }

    @Test
    void validateSign_emptyOwnerSkipsNameValidation() {
        assertThat(signs.validateSign(new String[]{"", "64", "B 5", "Diamond"})).isTrue();
    }

    @Test
    void validateSign_nameWithDisambiguationIdValidatesPrefix() {
        assertThat(signs.validateSign(new String[]{"Notch:ab12", "64", "B 5", "Diamond"})).isTrue();
    }

    @Test
    void validateSign_falseForInvalidOwnerName() {
        // '@' isn't allowed by ^\.?\w+$.
        assertThat(signs.validateSign(new String[]{"bad@name", "64", "B 5", "Diamond"})).isFalse();
    }

    @Test
    void validateSign_falseForInvalidQuantityLine() {
        assertThat(signs.validateSign(new String[]{"Notch", "notanumber", "B 5", "Diamond"})).isFalse();
    }

    @Test
    void validateSign_falseForTooManyColonsInPriceLine() {
        // Passes the per-line patterns via the item/quantity but the price has two colons.
        assertThat(signs.validateSign(new String[]{"Notch", "64", "B 1 : S 2 : B 3", "Diamond"})).isFalse();
    }

    @Test
    void validNamePattern_isCachedAcrossCalls() {
        String[] lines = {"Notch", "64", "B 5", "Diamond"};
        assertThat(signs.validateSign(lines)).isTrue(); // compiles the pattern (cache miss)
        assertThat(signs.validateSign(lines)).isTrue(); // reuses it (cache hit)
    }

    @Test
    void isValid_fromRealSign() {
        Sign valid = placeSign("Notch", "64", "B 5", "Diamond");
        assertThat(signs.isValid(valid)).isTrue();
    }

    @Test
    void isValid_block_trueForSignFalseForNonSign() {
        Sign sign = placeSign("Notch", "64", "B 5", "Diamond");
        assertThat(signs.isValid(sign.getBlock())).isTrue();

        Block stone = world.getBlockAt(500, 64, 0);
        stone.setType(Material.STONE);
        assertThat(signs.isValid(stone)).isFalse();
    }

    // ---- getShopBlock -----------------------------------------------------------

    @Test
    void getShopBlock_fromBlockStateHolder() {
        Block chestBlock = world.getBlockAt(10, 64, 10);
        chestBlock.setType(Material.CHEST);
        BlockState state = chestBlock.getState();
        assertThat(signs.getShopBlock((InventoryHolder) state)).isEqualTo(chestBlock);
    }

    @Test
    void getShopBlock_fromDoubleChest_prefersLeftSide() {
        Block chestBlock = world.getBlockAt(12, 64, 12);
        chestBlock.setType(Material.CHEST);
        BlockState leftState = chestBlock.getState();

        DoubleChest doubleChest = mock(DoubleChest.class);
        when(doubleChest.getLeftSide(false)).thenReturn((InventoryHolder) leftState);
        assertThat(signs.getShopBlock(doubleChest)).isEqualTo(chestBlock);
    }

    @Test
    void getShopBlock_fromDoubleChest_fallsBackToRightSide() {
        Block chestBlock = world.getBlockAt(14, 64, 14);
        chestBlock.setType(Material.CHEST);
        BlockState rightState = chestBlock.getState();

        DoubleChest doubleChest = mock(DoubleChest.class);
        when(doubleChest.getLeftSide(false)).thenReturn(null);      // left has no shop block
        when(doubleChest.getRightSide(false)).thenReturn((InventoryHolder) rightState);
        assertThat(signs.getShopBlock(doubleChest)).isEqualTo(chestBlock);
    }

    @Test
    void getShopBlock_nullForOtherHolder() {
        InventoryHolder holder = mock(InventoryHolder.class);
        assertThat(signs.getShopBlock(holder)).isNull();
    }

    // ---- static line accessors --------------------------------------------------

    @Test
    void staticAccessors_fromSign() {
        Sign sign = placeSign("Notch", "64", "B 5", "Diamond");
        assertThat(SignService.getOwner(sign)).isEqualTo("Notch");
        assertThat(SignService.getQuantityLine(sign)).isEqualTo("64");
        assertThat(SignService.getQuantity(sign)).isEqualTo(64);
        assertThat(SignService.getPrice(sign)).isEqualTo("B 5");
        assertThat(SignService.getItem(sign)).isEqualTo("Diamond");
    }

    @Test
    void staticAccessors_fromLines() {
        String[] lines = {"Notch", "64", "B 5", "Diamond"};
        assertThat(SignService.getOwner(lines)).isEqualTo("Notch");
        assertThat(SignService.getQuantityLine(lines)).isEqualTo("64");
        assertThat(SignService.getQuantity(lines)).isEqualTo(64);
        assertThat(SignService.getPrice(lines)).isEqualTo("B 5");
        assertThat(SignService.getItem(lines)).isEqualTo("Diamond");
    }

    @Test
    void staticAccessors_emptyForTruncatedLineArrays() {
        assertThat(SignService.getQuantityLine(new String[]{"Notch"})).isEmpty();
        assertThat(SignService.getPrice(new String[]{"Notch", "64"})).isEmpty();
        assertThat(SignService.getItem(new String[]{"Notch", "64", "B 5"})).isEmpty();
    }
}
