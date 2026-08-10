package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.mappers.ItemCodeMapper;
import io.paradaux.chestshop.model.Item;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.CustomItemResolver;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.support.TestConfigs;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real end-to-end item-string resolution on MockBukkit: {@link ItemServiceImpl} over the real
 * {@link ItemCodeServiceImpl}/{@link MaterialServiceImpl}/{@link InventoryServiceImpl} graph
 * (only the MyBatis mapper is faked), exercising parse/queryString/getName and the
 * configurable item-alias map from {@code itemAliases.yml}.
 *
 * <p>Note: every MockBukkit {@link ItemStack} carries a (Damageable) meta, so
 * {@code MaterialUtil.hasCustomData} is always true and a vanilla item's canonical code always
 * ends with a {@code #<base62 id>} suffix (the first stored item is {@code #1}).
 */
class ItemServiceImplTest extends ServerTest {

    @TempDir
    Path dataDir;

    private static final class FakeMapper implements ItemCodeMapper {
        final Map<Integer, String> rows = new HashMap<>();
        int seq = 0;
        @Override public void createTable() {}
        @Override public void createCodeIndex() {}
        @Override public Integer findIdByBlob(String blob) {
            for (Map.Entry<Integer, String> e : rows.entrySet()) {
                if (e.getValue().equals(blob)) return e.getKey();
            }
            return null;
        }
        @Override public void insert(Item item) { int id = ++seq; rows.put(id, item.getBase64ItemCode()); item.setId(id); }
        @Override public String findBlobById(int id) { return rows.get(id); }
        @Override public void updateBlob(int id, String blob) { rows.put(id, blob); }
        @Override public List<Integer> findAllIds() { return new ArrayList<>(rows.keySet()); }
        @Override public long count() { return rows.size(); }
    }

    private File dataFolder;
    private ChestShopConfiguration config;

    @BeforeEach
    void wire() {
        dataFolder = dataDir.toFile();
        config = TestConfigs.defaults();
    }

    private ItemServiceImpl newService() {
        ItemCodeServiceImpl codes = new ItemCodeServiceImpl(new FakeMapper(), new MaterialServiceImpl(config), dataFolder);
        MaterialServiceImpl material = new MaterialServiceImpl(config);
        InventoryServiceImpl inventory = new InventoryServiceImpl(config, material);
        return new ItemServiceImpl(codes, material, inventory, dataFolder);
    }

    private void writeAliases(String yaml) {
        try {
            Files.writeString(new File(dataFolder, "itemAliases.yml").toPath(), yaml, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- loadAliases (constructor) ---------------------------------------------

    @Test
    void constructor_writesDefaultAliasesFile_whenAbsent() {
        newService();
        assertThat(new File(dataFolder, "itemAliases.yml")).exists();
    }

    @Test
    void constructor_loadsExistingAliasesFile_ignoringNonStringValues() {
        // A pre-existing file with a string alias (keyed on the full code) and a skipped section.
        writeAliases("Diamond#1: Gem\nnested:\n  a: b\n");
        ItemServiceImpl svc = newService();
        assertThat(svc.queryString(item(Material.DIAMOND, 1), 0)).isEqualTo("Gem");
    }

    // ---- parse ------------------------------------------------------------------

    @Test
    void parse_vanillaMaterial_whenNoResolverNoAlias() {
        ItemStack parsed = newService().parse("Diamond");
        assertThat(parsed).isNotNull();
        assertThat(parsed.getType()).isEqualTo(Material.DIAMOND);
    }

    @Test
    void parse_nullForUnknown() {
        assertThat(newService().parse("Not_A_Material")).isNull();
    }

    @Test
    void parse_usesCustomResolver_whenRegistered() {
        ItemServiceImpl svc = newService();
        CustomItemResolver resolver = mock(CustomItemResolver.class);
        when(resolver.parseItem("magic")).thenReturn(item(Material.EMERALD, 1));
        svc.registerCustomItemResolver(resolver);
        assertThat(svc.parse("magic").getType()).isEqualTo(Material.EMERALD);
    }

    @Test
    void parse_configuredAliasOverridesCustomResolver() {
        writeAliases("Diamond: Gem\n"); // inverse: "Gem" -> code "Diamond"
        ItemServiceImpl svc = newService();
        CustomItemResolver resolver = mock(CustomItemResolver.class);
        when(resolver.parseItem("Gem")).thenReturn(item(Material.EMERALD, 1));
        svc.registerCustomItemResolver(resolver);
        assertThat(svc.parse("Gem").getType()).isEqualTo(Material.DIAMOND);
    }

    // ---- resolveAlias fuzzy branches -------------------------------------------

    @Test
    void parse_resolvesAliasByExactInverse() {
        writeAliases("Diamond: Gem\n");
        assertThat(newService().parse("Gem").getType()).isEqualTo(Material.DIAMOND);
    }

    @Test
    void parse_resolvesAliasByPrefix() {
        // No exact inverse hit; the alias value "Gemstone" startsWith the query "Gem".
        writeAliases("Diamond: Gemstone\n");
        assertThat(newService().parse("Gem").getType()).isEqualTo(Material.DIAMOND);
    }

    @Test
    void parse_resolvesAliasByMultiWordPartMatch() {
        // lowercase "aa bb" prefixes each word of the alias value "aa bbcc" (prefix startsWith
        // fails, so the multi-word part-by-part match path runs and succeeds).
        writeAliases("Diamond: aaxx bbyy\n");
        assertThat(newService().parse("aa bb").getType()).isEqualTo(Material.DIAMOND);
    }

    @Test
    void parse_noAliasMatch_fallsThroughToVanilla() {
        writeAliases("Diamond: Zzz Alias Value\n");
        // "Gold_Ingot" matches no alias -> vanilla decode.
        assertThat(newService().parse("Gold_Ingot").getType()).isEqualTo(Material.GOLD_INGOT);
    }

    // ---- queryString / applyAlias ----------------------------------------------

    @Test
    void queryString_vanillaCode_whenNoResolver() {
        assertThat(newService().queryString(item(Material.DIAMOND, 1), 0)).isEqualTo("Diamond#1");
    }

    @Test
    void queryString_usesResolverResult_whenPresent() {
        ItemServiceImpl svc = newService();
        CustomItemResolver resolver = mock(CustomItemResolver.class);
        when(resolver.queryString(any(), anyInt())).thenReturn("CustomThing");
        svc.registerCustomItemResolver(resolver);
        assertThat(svc.queryString(item(Material.DIAMOND, 1), 0)).isEqualTo("CustomThing");
    }

    @Test
    void queryString_appliesAlias_directKeyOnFullCode() {
        writeAliases("Diamond#1: Gem\n");
        assertThat(newService().queryString(item(Material.DIAMOND, 1), 0)).isEqualTo("Gem");
    }

    @Test
    void queryString_appliesAlias_lowercaseHashCode() {
        // Code "Diamond#1" not a direct key, but "diamond#1" (lowercased material part) is.
        writeAliases("diamond#1: Gem\n");
        assertThat(newService().queryString(item(Material.DIAMOND, 1), 0)).isEqualTo("Gem");
    }

    @Test
    void queryString_appliesAlias_lowercaseNonHashCode_viaResolverString() {
        // applyAlias's no-'#' branch: feed a '#'-less string through the resolver.
        writeAliases("diamond: Gem\n");
        ItemServiceImpl svc = newService();
        CustomItemResolver resolver = mock(CustomItemResolver.class);
        when(resolver.queryString(any(), anyInt())).thenReturn("Diamond");
        svc.registerCustomItemResolver(resolver);
        assertThat(svc.queryString(item(Material.DIAMOND, 1), 0)).isEqualTo("Gem");
    }

    @Test
    void queryString_noAlias_returnsInput() {
        writeAliases("Iron_Ingot#5: Bar\n");
        assertThat(newService().queryString(item(Material.DIAMOND, 1), 0)).isEqualTo("Diamond#1");
    }

    @Test
    void queryString_aliasTooWide_isRejected() {
        // Wide enough max width that encode does not shorten, but the alias itself overflows it.
        String wide = "ThisAliasIsWayTooWideToEverFitOnASignAndExceedsTheGivenMaximumWidthByFar";
        writeAliases("Diamond#1: " + wide + "\n");
        assertThat(newService().queryString(item(Material.DIAMOND, 1), 70)).isEqualTo("Diamond#1");
    }

    // ---- parseMaterial ----------------------------------------------------------

    @Test
    void parseMaterial_resolvesVanilla() {
        assertThat(newService().parseMaterial("Diamond", (short) 0)).isEqualTo(Material.DIAMOND);
    }

    // ---- getItemList ------------------------------------------------------------

    @Test
    void getItemList_joinsCountsAndNames() {
        String list = newService().getItemList(new ItemStack[]{item(Material.DIAMOND, 3), item(Material.DIRT, 2)});
        assertThat(list).contains("3 Diamond").contains("2 Dirt");
    }

    // ---- getName / getSignName --------------------------------------------------

    @Test
    void getName_fullCode() {
        assertThat(newService().getName(item(Material.DIAMOND, 1))).isEqualTo("Diamond#1");
    }

    @Test
    void getSignName_constrainsToSignWidth() {
        assertThat(newService().getSignName(item(Material.DIAMOND, 1))).isEqualTo("Diamond#1");
    }

    @Test
    void getName_shortensPoundCode_whenItStillRoundTrips() {
        // A wide material name shortened by a small amount still resolves back to the same item.
        ItemServiceImpl svc = newService();
        ItemStack sword = item(Material.NETHERITE_SWORD, 1);
        String full = svc.getName(sword, 0);       // "Netherite_Sword#1"
        int width = io.paradaux.chestshop.utils.StringUtil.getMinecraftStringWidth(full);
        String shortened = svc.getName(sword, width - 4); // minor shortening, still reversible
        assertThat(shortened).contains("#1");
        assertThat(shortened.length()).isLessThanOrEqualTo(full.length());
    }

    @Test
    void getName_shortensColonCode_forDamagedItem() {
        ItemServiceImpl svc = newService();
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        var meta = sword.getItemMeta();
        ((org.bukkit.inventory.meta.Damageable) meta).setDamage(4);
        sword.setItemMeta(meta);
        String full = svc.getName(sword, 0); // Netherite_Sword:4#1  (durability then metadata)
        assertThat(full).contains(":4");
        int width = io.paradaux.chestshop.utils.StringUtil.getMinecraftStringWidth(full);
        String shortened = svc.getName(sword, width - 4);
        assertThat(shortened).contains(":4");
    }

    @Test
    void getName_throwsWhenShortenedCodeNoLongerRoundTrips() {
        // Netherite_Sword at width 20 shortens the material to "N", which resolves to NAME_TAG.
        ItemServiceImpl svc = newService();
        assertThatThrownBy(() -> svc.getName(item(Material.NETHERITE_SWORD, 1), 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- reloadAliases ----------------------------------------------------------

    @Test
    void getName_noShortening_whenCodeFitsLargeWidth() {
        // maxWidth large enough that the code already fits -> the shorten block is skipped.
        assertThat(newService().getName(item(Material.DIAMOND, 1), 1000)).isEqualTo("Diamond#1");
    }

    @Test
    void queryString_appliesNarrowAlias_withMaxWidth() {
        // Alias fits the (>0) max width -> the width-check's "fits" branch.
        writeAliases("Diamond#1: Gm\n");
        assertThat(newService().queryString(item(Material.DIAMOND, 1), 50)).isEqualTo("Gm");
    }

    @Test
    void parse_prefixLoop_skipsLongerAndNonMatchingEntries() {
        // Two entries: "Gemstone" matches "Gem" by prefix; the longer "Xylophone" is then skipped.
        writeAliases("Diamond: Gemstone\nGold_Ingot: Xylophone\n");
        assertThat(newService().parse("Gem").getType()).isEqualTo(Material.DIAMOND);
    }

    @Test
    void parse_multiWord_noMatchWhenAPartDiffers() {
        // parts [AA, ZZ] vs [AA, BBCC]: second part fails startsWith -> matched=false break.
        writeAliases("Diamond: aaxx bbyy\n");
        assertThat(newService().parse("aa zz")).isNull();
    }

    @Test
    void parse_multiWord_noMatchWhenPartCountDiffers() {
        // "aa bb cc" -> 3 parts vs alias value "aa bbcc" -> 2 parts: length mismatch, skipped.
        writeAliases("Diamond: aaxx bbyy\n");
        assertThat(newService().parse("aa bb cc")).isNull();
    }

    // ---- getName shortening block (driven via a resolver returning wide codes) ---

    /** getName shortens a resolver-supplied wide code; here it no longer round-trips -> throws. */
    private void assertShorteningThrows(String resolverCode, int maxWidth) {
        ItemServiceImpl svc = newService();
        CustomItemResolver resolver = mock(CustomItemResolver.class);
        when(resolver.queryString(any(), anyInt())).thenReturn(resolverCode);
        svc.registerCustomItemResolver(resolver);
        assertThatThrownBy(() -> svc.getName(item(Material.DIAMOND, 1), maxWidth))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getName_shortens_poundOnlyCode() {
        assertShorteningThrows("Netherite Sword Extra#1", 20); // pound, no colon
    }

    @Test
    void getName_shortens_colonOnlyCode() {
        assertShorteningThrows("Netherite Sword Extra:5", 20); // colon, no pound
    }

    @Test
    void getName_shortens_plainCode_noPoundNoColon() {
        assertShorteningThrows("Netherite Sword Extra Long", 20); // neither
    }

    @Test
    void getName_shortens_poundBeforeColon() {
        assertShorteningThrows("Netherite Sword#1:2", 20); // '#' before ':'
    }

    @Test
    void getName_shortens_colonBeforePound() {
        assertShorteningThrows("Netherite Sword:1#2", 20); // ':' before '#'
    }

    @Test
    void reloadAliases_reloadsFileAndResolver() {
        ItemServiceImpl svc = newService();
        CustomItemResolver resolver = mock(CustomItemResolver.class);
        svc.registerCustomItemResolver(resolver);
        svc.reloadAliases();
        verify(resolver).reload();
    }

    @Test
    void reloadAliases_withoutResolver_justReloadsFile() {
        newService().reloadAliases(); // must not throw with no resolver
    }

    // ---- residual branches ------------------------------------------------------

    @Test
    void getName_nullWhenQueryStringYieldsNull() {
        // itemCodes.encode returns null (no resolver, no alias) -> queryString returns null via
        // applyAlias(null) (242 true / 243), so getName's `code != null` guard is false (142 false).
        io.paradaux.chestshop.services.ItemCodeService codes =
                mock(io.paradaux.chestshop.services.ItemCodeService.class);
        when(codes.encode(any(), anyInt())).thenReturn(null);
        MaterialServiceImpl material = new MaterialServiceImpl(config);
        InventoryServiceImpl inventory = new InventoryServiceImpl(config, material);
        ItemServiceImpl svc = new ItemServiceImpl(codes, material, inventory, dataFolder);
        assertThat(svc.getName(item(Material.DIAMOND, 1), 0)).isNull();
    }

    @Test
    void getName_shortens_hashAtIndexZero() {
        // A resolver code whose '#' is at index 0: poundIndex==0 so the 152 guard (pound>0) is
        // false, and 155's (pound<0 || colon<pound) is also false -> material stays the whole code.
        assertShorteningThrows("#NetheriteSwordExtraLong:5", 20);
    }

    @Test
    void loadAliases_swallowsIoError_whenDefaultFileCannotBeSaved() throws Exception {
        // Point the data folder at a regular file so createParentDirs for itemAliases.yml fails
        // -> the default-file save throws IOException, which loadAliases logs and swallows (195/196).
        File blocker = new File(dataFolder, "blocker");
        Files.writeString(blocker.toPath(), "x", StandardCharsets.UTF_8);
        ItemCodeServiceImpl codes = new ItemCodeServiceImpl(new FakeMapper(), new MaterialServiceImpl(config), dataFolder);
        MaterialServiceImpl material = new MaterialServiceImpl(config);
        InventoryServiceImpl inventory = new InventoryServiceImpl(config, material);
        ItemServiceImpl svc = new ItemServiceImpl(codes, material, inventory, blocker);
        // Construction did not throw; the alias map is simply empty.
        assertThat(svc.parse("Diamond").getType()).isEqualTo(Material.DIAMOND);
    }
}
