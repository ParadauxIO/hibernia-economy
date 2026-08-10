package io.paradaux.chestshop.utils;

import io.paradaux.chestshop.services.impl.MaterialServiceImpl;
import io.paradaux.chestshop.services.MaterialService;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

import java.util.Collections;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link io.paradaux.chestshop.utils.MaterialUtil}
 *
 * @author Acrobot
 */
public class MaterialTest {

    private MaterialService materialService;

    @BeforeEach
    void setUp() {
        ChestShopConfiguration config = mock(ChestShopConfiguration.class);
        lenient().when(config.getCacheSize()).thenReturn(1000);
        lenient().when(config.getExcludedItemAttributes()).thenReturn(new LinkedHashSet<>());
        materialService = new MaterialServiceImpl(config);
    }

    @Test
    public void testCodes() {
        for (Material material : Material.values()) {
            if (material.name().startsWith("LEGACY_")) {
                continue;
            }
            String shortenedName = MaterialUtil.getShortenedName(material.toString(), MaterialUtil.MAXIMUM_SIGN_WIDTH);
            assertSame(material, materialService.getMaterial(shortenedName), shortenedName + " did not produce " + material);
        }
    }

    @Test
    public void testCodesWithMeta() {
        String metaData = "#";
        for (int i = 0; i < 3; i++) {
            metaData += "A";
            int maxWidth = MaterialUtil.MAXIMUM_SIGN_WIDTH - StringUtil.getMinecraftStringWidth(metaData);
            for (Material material : Material.values()) {
                if (material.name().startsWith("LEGACY_")) {
                    continue;
                }
                String shortenedName = MaterialUtil.getShortenedName(material.toString(), maxWidth);
                assertSame(material, materialService.getMaterial(shortenedName), shortenedName + " with " + metaData + " meta did not produce " + material);
            }
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "DiamonPicka",
            "Diamon Picka",
            "ExpBottle",
            "Exp Bottle"
    })
    public void testCodesWithAndWithoutSpace(String materialName) {
        assertNotNull(materialService.getMaterial(materialName));
    }

    @Test
    public void testDurabilityParsing() {
        assertNull(MaterialUtil.getDurability("Stone#a3"));
        assertEquals(Integer.valueOf(1561), MaterialUtil.getDurability("Diamond Sword:1561"));
        assertEquals(Integer.valueOf(1561), MaterialUtil.getDurability("Diamond Sword:1561#2d"));
        assertEquals(Integer.valueOf(1561), MaterialUtil.getDurability("Diamond Sword#2d:1561"));
        assertEquals(Integer.valueOf(250), MaterialUtil.getDurability("Shovel:250"));
    }

    @Test
    public void testMetadataParsing() {
        assertNull(MaterialUtil.parseMetadata("Stone"));
        assertNull(MaterialUtil.parseMetadata("Stone:123"));
        assertEquals("asd", MaterialUtil.parseMetadata("Diamond Sword#asd"));
        assertEquals("3r9s", MaterialUtil.parseMetadata("Shovel:250#3r9s"));
        assertEquals("3r9s", MaterialUtil.parseMetadata("Shovel#3r9s:250"));
    }
}
