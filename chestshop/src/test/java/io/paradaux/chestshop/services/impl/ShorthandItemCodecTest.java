package io.paradaux.chestshop.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.support.TestConfigs;
import io.paradaux.chestshop.utils.MaterialUtil;
import io.paradaux.chestshop.utils.StringUtil;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.Test;

/**
 * Real-item coverage for {@link ShorthandItemCodec} on MockBukkit: every vanilla potion and
 * single-enchant book gets a unique sign-sized name that parses back to the same item, and
 * anything else is left to the {@code #code} fallback.
 */
class ShorthandItemCodecTest extends ServerTest {

    /** The item line a shop sign accepts (see {@code SignService}), without a durability/#code suffix. */
    private static final Pattern SIGN_ITEM_LINE = Pattern.compile("^[\\p{L}\\d_ \\-]+$");

    private static final List<Material> POTION_FORMS =
            List.of(Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION, Material.TIPPED_ARROW);

    private ShorthandItemCodec codec() {
        return codec(TestConfigs.defaults());
    }

    private ShorthandItemCodec codec(ChestShopConfiguration config) {
        return new ShorthandItemCodec(new MaterialServiceImpl(config));
    }

    private static ItemStack potion(Material form, PotionType type) {
        ItemStack item = new ItemStack(form);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.setBasePotionType(type);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack book(Enchantment enchantment, int level) {
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
        meta.addStoredEnchant(enchantment, level, true);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack withRepairCost(ItemStack item, int cost) {
        ItemMeta meta = item.getItemMeta();
        ((Repairable) meta).setRepairCost(cost);
        item.setItemMeta(meta);
        return item;
    }

    private void assertSignSizedRoundTrip(ShorthandItemCodec codec, ItemStack item, Set<String> seen) {
        String code = codec.encode(item);
        assertThat(code).as("shorthand for %s", item).isNotNull();
        assertThat(StringUtil.getMinecraftStringWidth(code))
                .as("width of '%s'", code)
                .isLessThanOrEqualTo(MaterialUtil.MAXIMUM_SIGN_WIDTH);
        assertThat(code).matches(SIGN_ITEM_LINE);
        assertThat(seen.add(code.toLowerCase(Locale.ROOT)))
                .as("'%s' is unique", code)
                .isTrue();
        assertThat(codec.decode(code).isSimilar(item))
                .as("'%s' parses back to %s", code, item)
                .isTrue();
    }

    // ---- the whole vanilla catalogue --------------------------------------------

    @Test
    void everyPotionTypeInEveryFormHasAUniqueSignSizedNameThatRoundTrips() {
        ShorthandItemCodec codec = codec();
        Set<String> seen = new HashSet<>();
        for (Material form : POTION_FORMS) {
            for (PotionType type : PotionType.values()) {
                assertSignSizedRoundTrip(codec, potion(form, type), seen);
            }
        }
        assertThat(seen).hasSize(POTION_FORMS.size() * PotionType.values().length);
    }

    @Test
    void everyEnchantmentAtEveryLevelHasAUniqueSignSizedNameThatRoundTrips() {
        ShorthandItemCodec codec = codec();
        Set<String> seen = new HashSet<>();
        int expected = 0;
        for (Enchantment enchantment : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)) {
            for (int level = 1; level <= enchantment.getMaxLevel(); level++) {
                assertSignSizedRoundTrip(codec, book(enchantment, level), seen);
                expected++;
            }
        }
        assertThat(expected).isGreaterThan(100);
        assertThat(seen).hasSize(expected);
    }

    // ---- the agreed names -------------------------------------------------------

    @Test
    void potionsAreNamedByFormEffectAndTier() {
        ShorthandItemCodec codec = codec();
        assertThat(codec.encode(potion(Material.POTION, PotionType.WATER))).isEqualTo("Pot Water");
        assertThat(codec.encode(potion(Material.SPLASH_POTION, PotionType.STRONG_HEALING)))
                .isEqualTo("Spl Healing II");
        assertThat(codec.encode(potion(Material.LINGERING_POTION, PotionType.LONG_NIGHT_VISION)))
                .isEqualTo("Lin NightVis Ext");
        assertThat(codec.encode(potion(Material.TIPPED_ARROW, PotionType.LONG_SWIFTNESS)))
                .isEqualTo("Arr Swiftness Ext");
    }

    @Test
    void booksAreNamedByEnchantAndLevel_withNoNumeralForSingleLevelEnchants() {
        ShorthandItemCodec codec = codec();
        assertThat(codec.encode(book(Enchantment.SHARPNESS, 5))).isEqualTo("Bk Sharpness V");
        assertThat(codec.encode(book(Enchantment.SHARPNESS, 1))).isEqualTo("Bk Sharpness I");
        assertThat(codec.encode(book(Enchantment.PROJECTILE_PROTECTION, 4))).isEqualTo("Bk ProjProt IV");
        assertThat(codec.encode(book(Enchantment.MENDING, 1))).isEqualTo("Bk Mending");
    }

    // ---- decode -----------------------------------------------------------------

    @Test
    void decodeIgnoresCase_soAHandTypedSignStillResolves() {
        assertThat(codec().decode("bk sharpness v").isSimilar(book(Enchantment.SHARPNESS, 5)))
                .isTrue();
    }

    @Test
    void decodeReturnsNullForAnythingThatIsNotAShorthand() {
        ShorthandItemCodec codec = codec();
        assertThat(codec.decode("Diamond")).isNull();
        assertThat(codec.decode("Bk Sharpness VI")).isNull();
        assertThat(codec.decode("Bk Mending I")).isNull();
        assertThat(codec.decode(null)).isNull();
    }

    @Test
    void decodeHandsOutACopy_soCallersCannotCorruptTheCatalogue() {
        ShorthandItemCodec codec = codec();
        codec.decode("Bk Mending").setAmount(64);
        assertThat(codec.decode("Bk Mending").getAmount()).isEqualTo(1);
    }

    // ---- items that keep their #code --------------------------------------------

    @Test
    void ordinaryItemsHaveNoShorthand() {
        ShorthandItemCodec codec = codec();
        assertThat(codec.encode(new ItemStack(Material.DIAMOND))).isNull();
        assertThat(codec.encode(new ItemStack(Material.POTION))).isNull();
        assertThat(codec.encode(new ItemStack(Material.ENCHANTED_BOOK))).isNull();
        assertThat(codec.encode(null)).isNull();
    }

    @Test
    void multiEnchantBooksHaveNoShorthand() {
        ItemStack item = book(Enchantment.SHARPNESS, 5);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
        meta.addStoredEnchant(Enchantment.UNBREAKING, 3, true);
        item.setItemMeta(meta);
        assertThat(codec().encode(item)).isNull();
    }

    @Test
    void booksAboveTheVanillaMaximumLevelHaveNoShorthand() {
        assertThat(codec().encode(book(Enchantment.SHARPNESS, 6))).isNull();
    }

    @Test
    void renamedPotionsHaveNoShorthand() {
        ItemStack item = potion(Material.POTION, PotionType.SWIFTNESS);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("Speedy");
        item.setItemMeta(meta);
        assertThat(codec().encode(item)).isNull();
    }

    @Test
    void potionsWithCustomEffectsHaveNoShorthand() {
        ItemStack item = potion(Material.POTION, PotionType.SWIFTNESS);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.addCustomEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 200, 1), true);
        item.setItemMeta(meta);
        assertThat(codec().encode(item)).isNull();
    }

    @Test
    void booksBelowLevelOneHaveNoShorthand() {
        assertThat(codec().encode(book(Enchantment.SHARPNESS, 0))).isNull();
    }

    @Test
    void dataPackEnchantmentsHaveNoShorthand_evenWhenTheyReuseAVanillaName() {
        assertThat(codec().encode(book(enchantment(new NamespacedKey("custom", "sharpness"), 5), 5)))
                .isNull();
        assertThat(codec().encode(book(enchantment(new NamespacedKey("custom", "lifesteal"), 3), 1)))
                .isNull();
    }

    @Test
    void levelsBeyondTheRomanNumeralsHaveNoShorthand_whenADataPackRaisesTheMaximum() {
        assertThat(codec().encode(book(enchantment(NamespacedKey.minecraft("sharpness"), 10), 6)))
                .isNull();
    }

    private static Enchantment enchantment(NamespacedKey key, int maxLevel) {
        Enchantment enchantment = mock(Enchantment.class);
        when(enchantment.getKey()).thenReturn(key);
        when(enchantment.getMaxLevel()).thenReturn(maxLevel);
        return enchantment;
    }

    // ---- repair cost (anvil-merged books) ---------------------------------------

    @Test
    void mergedBooksKeepTheirCodeByDefault() {
        assertThat(codec().encode(withRepairCost(book(Enchantment.SHARPNESS, 5), 1)))
                .isNull();
    }

    @Test
    void mergedBooksShareTheCleanNameWhenRepairCostIsAnExcludedAttribute() {
        ChestShopConfiguration config =
                TestConfigs.with(TestConfigs.defaults(), "excludedItemAttributesRaw", List.of("repair-cost"));
        assertThat(codec(config).encode(withRepairCost(book(Enchantment.SHARPNESS, 5), 1)))
                .isEqualTo("Bk Sharpness V");
    }
}
