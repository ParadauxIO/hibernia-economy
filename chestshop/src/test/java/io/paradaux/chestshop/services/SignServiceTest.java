package io.paradaux.chestshop.services;

import static io.paradaux.chestshop.services.SignService.ITEM_LINE;
import static io.paradaux.chestshop.services.SignService.PRICE_LINE;
import static io.paradaux.chestshop.services.SignService.QUANTITY_LINE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.support.TestConfigs;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link SignService}: the per-line shop-sign patterns, the business/admin owner tokens, the
 * structural sign validation (owner-name rule, line patterns, single-colon price rule), shop
 * block resolution from inventory holders, and the line accessors — driven from real MockBukkit
 * signs where the {@code Sign}/{@code Block} overloads need live world state.
 */
class SignServiceTest extends ServerTest {

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

    private static boolean matchesLinePattern(int lineNumber, String line) {
        for (Pattern pattern : SignService.SHOP_SIGN_PATTERN[lineNumber - 1]) {
            if (pattern.matcher(line).matches()) {
                return true;
            }
        }
        return false;
    }

    // ---- per-line shop-sign patterns --------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"1", "17", "64", "3000"})
    void quantityLine_accepts(String line) {
        assertThat(matchesLinePattern(QUANTITY_LINE, line)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "0123", "four", "abc", "1 string", "string 3", "test 2 test", "1e10", "930271839"})
    void quantityLine_rejects(String line) {
        assertThat(matchesLinePattern(QUANTITY_LINE, line)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"B 1", "B FREE", "B 1K", "B 10M", "1K", "S 1K", "S 10M", "1K S"})
    void priceLine_accepts(String line) {
        assertThat(matchesLinePattern(PRICE_LINE, line)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"B 1 : S", "B : S 2", "B S 2", "B 1 2", "10KB", "1 B:S -1M", "S 1MK", "B -1K : S10K", "B1Z"})
    void priceLine_rejects(String line) {
        assertThat(matchesLinePattern(PRICE_LINE, line)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Stone",
                "Diamond_Sword",
                "Diamond-Sword",
                "Diamond Sword",
                "Diamond Sword:203",
                "Diamond#123",
                "Diamond#az3",
                "Diamond#BE2",
                "Stone Sword:123#123",
                "Diamond#223:123",
                "?"
            })
    void itemLine_accepts(String line) {
        assertThat(matchesLinePattern(ITEM_LINE, line)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Stone Sword:as", "Dirt:23:432", "©"})
    void itemLine_rejects(String line) {
        assertThat(matchesLinePattern(ITEM_LINE, line)).isFalse();
    }

    // ---- business token ---------------------------------------------------------

    @Test
    void businessAccount_detectsIdAndAliasTokensCaseInsensitively() {
        assertThat(SignService.isBusinessAccount("B:1A")).isTrue();
        assertThat(SignService.isBusinessAccount("b:z9")).isTrue();
        assertThat(SignService.isBusinessAccount("B:@ACME")).isTrue();
        // Malformed alias tokens are still reserved so creation rejects them.
        assertThat(SignService.isBusinessAccount("B:@")).isTrue();
        assertThat(SignService.isBusinessAccount("PlayerName")).isFalse();
        assertThat(SignService.isBusinessAccount("B:")).isFalse();
        assertThat(SignService.isBusinessAccount(new String[] {"B:Z9", "", "", ""}))
                .isTrue();
    }

    @Test
    void businessAccount_idIsBase36AndRoundTripsToUppercaseSignName() {
        assertThat(SignService.getBusinessAccountId("B:Z9")).isEqualTo(35 * 36 + 9);
        assertThat(SignService.businessAccountSignName(35 * 36 + 9)).isEqualTo("B:Z9");
        assertThat(SignService.getBusinessAccountId("b:z9")).isEqualTo(35 * 36 + 9);
    }

    // ---- admin shop -------------------------------------------------------------

    @Test
    void adminShop_matchesConfiguredNameIgnoringSpacesAndCase() {
        assertThat(signs.isAdminShop("Admin Shop")).isTrue();
        assertThat(signs.isAdminShop("adminshop")).isTrue();
        assertThat(signs.isAdminShop("SomePlayer")).isFalse();
        assertThat(signs.isAdminShop(new String[] {"Admin Shop", "", "", ""})).isTrue();
    }

    // ---- isValid ----------------------------------------------------------------

    @Test
    void isValid_trueForWellFormedPlayerShop() {
        assertThat(signs.isValid(new String[] {"Notch", "64", "B 5", "Diamond"}))
                .isTrue();
    }

    @Test
    void isValid_trueForSellOnlyShop() {
        assertThat(signs.isValid(new String[] {"Notch", "64", "S 5", "Diamond"}))
                .isTrue();
    }

    @Test
    void isValid_falseWhenNoBuyOrSellFlag() {
        // Structurally valid lines but the price carries neither B nor S.
        assertThat(signs.isValid(new String[] {"Notch", "64", "5", "Diamond"})).isFalse();
    }

    @Test
    void isValid_falseWhenOwnerEmpty() {
        // validateSign tolerates a blank owner (auto-fill), but a finished shop needs one.
        assertThat(signs.isValid(new String[] {"", "64", "B 5", "Diamond"})).isFalse();
    }

    @Test
    void isValid_block_trueForValidSignFalseForNonSign() {
        Sign sign = placeSign("Notch", "64", "B 5", "Diamond");
        assertThat(signs.isValid(sign.getBlock())).isTrue();

        Block stone = world.getBlockAt(500, 64, 0);
        stone.setType(Material.STONE);
        assertThat(signs.isValid(stone)).isFalse();
    }

    @Test
    void isValid_block_falseForSignWithInvalidLines() {
        Sign invalid = placeSign("", "notaqty", "nope", "??bad");
        assertThat(signs.isValid(invalid.getBlock())).isFalse();
    }

    // ---- validateSign -----------------------------------------------------------

    @Test
    void validateSign_adminOwnerSkipsNameValidation() {
        // "Admin Shop" contains a space, which the player-name regexp would reject.
        assertThat(signs.validateSign(new String[] {"Admin Shop", "64", "B 5", "Diamond"}))
                .isTrue();
    }

    @Test
    void validateSign_businessOwnerSkipsNameValidation() {
        // An alias token neither matches the player-name regexp nor the "name:id" suffix form.
        assertThat(signs.validateSign(new String[] {"B:@ACME", "64", "B 5", "Diamond"}))
                .isTrue();
    }

    @Test
    void validateSign_emptyOwnerSkipsNameValidation() {
        assertThat(signs.validateSign(new String[] {"", "64", "B 5", "Diamond"}))
                .isTrue();
    }

    @Test
    void validateSign_nameWithDisambiguationIdValidatesPrefix() {
        assertThat(signs.validateSign(new String[] {"Notch:ab12", "64", "B 5", "Diamond"}))
                .isTrue();
        assertThat(signs.validateSign(new String[] {"bad@name:ab12", "64", "B 5", "Diamond"}))
                .isFalse();
    }

    @Test
    void validateSign_falseForInvalidOwnerName() {
        // '@' isn't allowed by ^\.?\w+$.
        assertThat(signs.validateSign(new String[] {"bad@name", "64", "B 5", "Diamond"}))
                .isFalse();
    }

    @Test
    void validateSign_falseForInvalidQuantityLine() {
        assertThat(signs.validateSign(new String[] {"Notch", "notanumber", "B 5", "Diamond"}))
                .isFalse();
    }

    @Test
    void validateSign_falseForMoreThanOneColonInPriceLine() {
        assertThat(signs.validateSign(new String[] {"Notch", "64", "B 1 : S 2 : B 3", "Diamond"}))
                .isFalse();
    }

    @Test
    void validateSign_picksUpPlayerNameRegexpChangedOnReload() {
        String[] lines = {"bad@name", "64", "B 5", "Diamond"};
        assertThat(signs.validateSign(lines)).isFalse();

        TestConfigs.with(config, "validPlayernameRegexp", "^.+$");

        assertThat(signs.validateSign(lines)).isTrue();
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
        Block left = world.getBlockAt(12, 64, 12);
        left.setType(Material.CHEST);
        Block right = world.getBlockAt(13, 64, 12);
        right.setType(Material.CHEST);

        DoubleChest doubleChest = mock(DoubleChest.class);
        when(doubleChest.getLeftSide(false)).thenReturn((InventoryHolder) left.getState());
        when(doubleChest.getRightSide(false)).thenReturn((InventoryHolder) right.getState());
        assertThat(signs.getShopBlock(doubleChest)).isEqualTo(left);
    }

    @Test
    void getShopBlock_fromDoubleChest_fallsBackToRightSide() {
        Block chestBlock = world.getBlockAt(14, 64, 14);
        chestBlock.setType(Material.CHEST);

        DoubleChest doubleChest = mock(DoubleChest.class);
        when(doubleChest.getLeftSide(false)).thenReturn(null);
        when(doubleChest.getRightSide(false)).thenReturn((InventoryHolder) chestBlock.getState());
        assertThat(signs.getShopBlock(doubleChest)).isEqualTo(chestBlock);
    }

    @Test
    void getShopBlock_nullForOtherHolder() {
        assertThat(signs.getShopBlock(mock(InventoryHolder.class))).isNull();
    }

    // ---- line accessors ---------------------------------------------------------

    @Test
    void signOverloads_readTheSignsLines() {
        Sign sign = placeSign("Notch", "64", "B 5", "Diamond");
        assertThat(SignService.getOwner(sign)).isEqualTo("Notch");
        assertThat(SignService.getQuantityLine(sign)).isEqualTo("64");
        assertThat(SignService.getQuantity(sign)).isEqualTo(64);
        assertThat(SignService.getPrice(sign)).isEqualTo("B 5");
        assertThat(SignService.getItem(sign)).isEqualTo("Diamond");
        assertThat(signs.isValid(sign)).isTrue();
        assertThat(signs.isAdminShop(sign)).isFalse();
        assertThat(signs.isAdminShop(placeSign("Admin Shop", "64", "B 5", "Diamond")))
                .isTrue();
    }

    @Test
    void lineAccessors_readAndTrimTheLines() {
        String[] lines = {" Notch ", " 64 ", " B 5 ", " Diamond "};
        assertThat(SignService.getOwner(lines)).isEqualTo("Notch");
        assertThat(SignService.getQuantityLine(lines)).isEqualTo("64");
        assertThat(SignService.getQuantity(lines)).isEqualTo(64);
        assertThat(SignService.getPrice(lines)).isEqualTo("B 5");
        assertThat(SignService.getItem(lines)).isEqualTo("Diamond");
    }

    @Test
    void lineAccessors_emptyForTruncatedLineArrays() {
        assertThat(SignService.getQuantityLine(new String[] {"Notch"})).isEmpty();
        assertThat(SignService.getPrice(new String[] {"Notch", "64"})).isEmpty();
        assertThat(SignService.getItem(new String[] {"Notch", "64", "B 5"})).isEmpty();
    }
}
