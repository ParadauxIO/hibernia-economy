package io.paradaux.chestshop.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.paradaux.chestshop.mappers.ItemCodeMapper;
import io.paradaux.chestshop.model.Item;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.support.TestConfigs;
import io.paradaux.chestshop.utils.encoding.Base62;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real item-code serialisation on MockBukkit: {@link ItemCodeServiceImpl} over a real
 * {@link MaterialServiceImpl} and a fake in-memory {@link ItemCodeMapper}, exercising the
 * YAML/Base62 encode/decode, the find-or-create dedup, and the metadata/blob migrations.
 */
class ItemCodeServiceImplTest extends ServerTest {

    @TempDir
    Path dataDir;

    /** In-memory stand-in for the MyBatis mapper: an auto-increment id ↔ blob store. */
    private static final class FakeMapper implements ItemCodeMapper {
        final Map<Integer, String> rows = new HashMap<>();
        int seq = 0;

        @Override
        public void createTable() {}

        @Override
        public void createCodeIndex() {}

        @Override
        public Integer findIdByBlob(String blob) {
            for (Map.Entry<Integer, String> e : rows.entrySet()) {
                if (e.getValue().equals(blob)) {
                    return e.getKey();
                }
            }
            return null;
        }

        @Override
        public void insert(Item item) {
            int id = ++seq;
            rows.put(id, item.getBase64ItemCode());
            item.setId(id);
        }

        @Override
        public String findBlobById(int id) {
            return rows.get(id);
        }

        @Override
        public void updateBlob(int id, String blob) {
            rows.put(id, blob);
        }

        @Override
        public List<Integer> findAllIds() {
            return new ArrayList<>(rows.keySet());
        }

        @Override
        public long count() {
            return rows.size();
        }
    }

    private FakeMapper mapper;
    private ItemCodeServiceImpl service;
    private File dataFolder;

    @BeforeEach
    void wire() {
        mapper = new FakeMapper();
        ChestShopConfiguration config = TestConfigs.defaults();
        dataFolder = dataDir.toFile();
        service = new ItemCodeServiceImpl(mapper, new MaterialServiceImpl(config), dataFolder);
    }

    // ---- getItemCode / getFromCode ---------------------------------------------

    @Test
    void getItemCode_createsRow_thenDedupsOnSecondCall() {
        ItemStack diamond = item(Material.DIAMOND, 1);
        String code = service.getItemCode(diamond);
        assertThat(code).isNotNull();
        assertThat(mapper.rows).hasSize(1);

        String again = service.getItemCode(diamond);
        assertThat(again).isEqualTo(code);
        assertThat(mapper.rows).hasSize(1); // deduped, no new row
    }

    @Test
    void getItemCode_keepsDamageInTheStoredItem() {
        // The legacy "reset damage" only touches a throwaway meta copy, so damage stays part of
        // the stored item: a damaged and an undamaged sword get distinct codes (byte-identical
        // to codes on existing shops).
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sword.getItemMeta();
        ((Damageable) meta).setDamage(25);
        sword.setItemMeta(meta);

        String damagedCode = service.getItemCode(sword);
        String pristineCode = service.getItemCode(new ItemStack(Material.DIAMOND_SWORD));

        assertThat(damagedCode).isNotEqualTo(pristineCode);
        assertThat(((Damageable) service.getFromCode(damagedCode).getItemMeta()).getDamage())
                .isEqualTo(25);
    }

    @Test
    void getItemCode_returnsNull_whenMapperThrows() {
        ItemCodeMapper boom = mock(ItemCodeMapper.class);
        when(boom.findIdByBlob(anyString())).thenThrow(new RuntimeException("db down"));
        ItemCodeServiceImpl svc =
                new ItemCodeServiceImpl(boom, new MaterialServiceImpl(TestConfigs.defaults()), dataFolder);
        assertThat(svc.getItemCode(item(Material.DIAMOND, 1))).isNull();
    }

    @Test
    void getFromCode_roundTripsAStoredItem() {
        ItemStack named = new ItemStack(Material.DIAMOND);
        ItemMeta meta = named.getItemMeta();
        meta.setDisplayName("Shiny");
        named.setItemMeta(meta);

        String code = service.getItemCode(named);
        ItemStack loaded = service.getFromCode(code);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getType()).isEqualTo(Material.DIAMOND);
        assertThat(loaded.getItemMeta().getDisplayName()).isEqualTo("Shiny");
    }

    @Test
    void getFromCode_nullForUnknownId() {
        assertThat(service.getFromCode(Base62.encode(999))).isNull();
    }

    @Test
    void getFromCode_nullAndLogsForCorruptYaml() {
        mapper.rows.put(1, ItemCodeServiceImpl.encodeBlob("this: is: not: an item stack: [")); // invalid YAML
        assertThat(service.getFromCode(Base62.encode(1))).isNull();
    }

    // ---- encode / decode (canonical sign codes) --------------------------------

    @Test
    void encode_materialNameWithMetadataSuffix() {
        // Every MockBukkit item has (Damageable) meta, so the code always carries a #<id> suffix.
        assertThat(service.encode(item(Material.DIAMOND, 1), 0)).startsWith("Diamond#");
    }

    @Test
    void encode_includesDurabilitySuffix() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sword.getItemMeta();
        ((Damageable) meta).setDamage(3);
        sword.setItemMeta(meta);
        // A purely-damaged item's meta serialises to just {meta-type, Damage} (size 2), so
        // hasCustomData is false: no #<id> suffix, and the name is space-separated.
        assertThat(service.encode(sword, 0)).isEqualTo("Diamond Sword:3");
    }

    @Test
    void encode_shortensToMaxWidth() {
        // A wide code constrained to a smaller-but-safe width gets shortened.
        String full = service.encode(item(Material.NETHERITE_SWORD, 1), 0);
        int width = io.paradaux.chestshop.utils.StringUtil.getMinecraftStringWidth(full);
        String shortened = service.encode(item(Material.NETHERITE_SWORD, 1), width - 4);
        assertThat(shortened.length()).isLessThanOrEqualTo(full.length());
    }

    @Test
    void encode_includesMetadataSuffix_forCustomDataItem() {
        ItemStack named = new ItemStack(Material.DIAMOND);
        ItemMeta meta = named.getItemMeta();
        meta.setDisplayName("Custom");
        named.setItemMeta(meta);
        String code = service.encode(named, 0);
        assertThat(code).startsWith("Diamond#");
        assertThat(mapper.rows).isNotEmpty();
    }

    @Test
    void decode_plainMaterial() {
        ItemStack decoded = service.decode("Diamond");
        assertThat(decoded).isNotNull();
        assertThat(decoded.getType()).isEqualTo(Material.DIAMOND);
    }

    @Test
    void decode_nullForUnknownMaterial() {
        assertThat(service.decode("Nonsense_Material")).isNull();
    }

    @Test
    void decode_appliesDurability() {
        ItemStack decoded = service.decode("Diamond_Sword:5");
        assertThat(decoded).isNotNull();
        assertThat(((Damageable) decoded.getItemMeta()).getDamage()).isEqualTo(5);
    }

    @Test
    void decode_appliesMetadataFromStoredCode() {
        ItemStack named = new ItemStack(Material.DIAMOND);
        ItemMeta meta = named.getItemMeta();
        meta.setDisplayName("Custom");
        named.setItemMeta(meta);
        String code = service.encode(named, 0); // Diamond#<base62>

        ItemStack decoded = service.decode(code);
        assertThat(decoded).isNotNull();
        assertThat(decoded.getItemMeta().getDisplayName()).isEqualTo("Custom");
    }

    @Test
    void decode_durabilityWithMetadata_appliesBoth() {
        // Build a sword with custom data (name) then a durability suffix.
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.setDisplayName("Named");
        sword.setItemMeta(meta);
        String base = service.encode(sword, 0); // Diamond_Sword#<code>
        ItemStack decoded = service.decode(base + ":7");
        assertThat(decoded).isNotNull();
        assertThat(((Damageable) decoded.getItemMeta()).getDamage()).isEqualTo(7);
        assertThat(decoded.getItemMeta().getDisplayName()).isEqualTo("Named");
    }

    // ---- migrateIfNeeded --------------------------------------------------------

    @Test
    void migrateIfNeeded_runsBlobAndMetadataMigration_onFreshFolder() throws Exception {
        // Seed a legacy row so the blob migration has something to rewrite: an
        // ObjectOutputStream-serialized String in plain Base64 (the pre-PAR-290 layout).
        String yaml = "==: org.bukkit.inventory.ItemStack\nv: 1\ntype: STONE\n";
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bos)) {
            oos.writeObject(yaml);
        }
        String legacy = java.util.Base64.getEncoder().encodeToString(bos.toByteArray());
        mapper.rows.put(1, legacy);

        service.migrateIfNeeded();

        // The blob was rewritten to plain Base64 (no longer the legacy form).
        assertThat(mapper.rows.get(1)).isNotEqualTo(legacy);
        // The version file records the migration so a second run is a no-op.
        File versionFile = new File(dataFolder, "version");
        assertThat(versionFile).exists();

        String afterFirst = mapper.rows.get(1);
        service.migrateIfNeeded(); // idempotent second pass
        assertThat(mapper.rows.get(1)).isEqualTo(afterFirst);
    }

    @Test
    void migrateIfNeeded_runsMetadataReserialisation_whenVersionAdvanced() throws Exception {
        // Store a real item blob, then pre-set an older metadata-version so reserialiseAll runs.
        String code = service.getItemCode(item(Material.DIAMOND, 1));
        assertThat(code).isNotNull();
        String before = mapper.rows.get(1);

        org.bukkit.configuration.file.YamlConfiguration cfg = new org.bukkit.configuration.file.YamlConfiguration();
        cfg.set("blob-encoding", 1); // skip the blob migration
        cfg.set("metadata-version", -5); // below the current (-1) so the re-serialiser runs
        cfg.save(new File(dataFolder, "version"));

        service.migrateIfNeeded();

        // The row is re-serialised (still a valid, decodable blob) and the version advances.
        assertThat(service.getFromCode(code)).isNotNull();
        org.bukkit.configuration.file.YamlConfiguration after =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new File(dataFolder, "version"));
        assertThat(after.getInt("metadata-version")).isGreaterThan(-5);
        assertThat(before).isNotNull();
    }

    private org.bukkit.configuration.file.YamlConfiguration readVersion() {
        return org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new File(dataFolder, "version"));
    }

    private static String legacyBlobOf(java.io.Serializable payload) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bos)) {
            oos.writeObject(payload);
        }
        return java.util.Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    @Test
    void migrateIfNeeded_skipsCorruptRow_duringReserialisation_andStillAdvancesTheVersion() {
        String corrupt = ItemCodeServiceImpl.encodeBlob("not: [valid: yaml");
        mapper.rows.put(1, corrupt);
        writeVersion(1, -5);

        service.migrateIfNeeded();

        assertThat(mapper.rows.get(1)).isEqualTo(corrupt);
        assertThat(readVersion().getInt("metadata-version")).isGreaterThan(-5);
    }

    private void writeVersion(int blobEncoding, int metadataVersion) {
        try {
            org.bukkit.configuration.file.YamlConfiguration cfg = new org.bukkit.configuration.file.YamlConfiguration();
            cfg.set("blob-encoding", blobEncoding);
            cfg.set("metadata-version", metadataVersion);
            cfg.save(new File(dataFolder, "version"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void migrateBlobEncoding_skipsNullBlobRows_andCompletes() {
        mapper.rows.put(1, null);
        mapper.rows.put(2, ItemCodeServiceImpl.encodeBlob("type: STONE\n"));

        service.migrateIfNeeded();

        assertThat(mapper.rows.get(1)).isNull();
        assertThat(readVersion().getInt("blob-encoding")).isEqualTo(1);
    }

    @Test
    void migrateBlobEncoding_leavesAlreadyPlainRowsUntouched() {
        String plain = ItemCodeServiceImpl.encodeBlob("==: org.bukkit.inventory.ItemStack\ntype: STONE\n");
        mapper.rows.put(1, plain);
        service.migrateIfNeeded();
        assertThat(mapper.rows.get(1)).isEqualTo(plain); // unchanged (plain == blob)
    }

    @Test
    void migrateBlobEncoding_leavesAnUndecodableRow_butMigratesTheRest() throws Exception {
        // A legacy blob of a non-String object is rejected by the String-only filter.
        String rejected = legacyBlobOf(new java.util.ArrayList<>());
        String legacy = legacyBlobOf("type: STONE\n");
        mapper.rows.put(1, rejected);
        mapper.rows.put(2, legacy);

        service.migrateIfNeeded();

        assertThat(mapper.rows.get(1)).isEqualTo(rejected);
        assertThat(mapper.rows.get(2)).isEqualTo(ItemCodeServiceImpl.encodeBlob("type: STONE\n"));
        assertThat(readVersion().getInt("blob-encoding")).isEqualTo(1);
    }

    @Test
    void migrateBlobEncoding_returnsFalse_whenStoreScanThrows() {
        ItemCodeMapper boom = mock(ItemCodeMapper.class);
        when(boom.findAllIds()).thenThrow(new RuntimeException("db down"));
        ItemCodeServiceImpl svc =
                new ItemCodeServiceImpl(boom, new MaterialServiceImpl(TestConfigs.defaults()), dataFolder);
        svc.migrateIfNeeded(); // outer catch -> returns false; blob-encoding not recorded, no throw
        File versionFile = new File(dataFolder, "version");
        org.bukkit.configuration.file.YamlConfiguration cfg =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(versionFile);
        assertThat(cfg.getInt("blob-encoding", 0)).isZero();
    }

    @Test
    void reserialiseAll_returnsFalse_whenStoreScanThrows() {
        writeVersion(1, -5); // skip blob migration; trigger metadata re-serialisation
        ItemCodeMapper boom = mock(ItemCodeMapper.class);
        when(boom.findAllIds()).thenThrow(new RuntimeException("db down"));
        ItemCodeServiceImpl svc =
                new ItemCodeServiceImpl(boom, new MaterialServiceImpl(TestConfigs.defaults()), dataFolder);
        assertThatCode(svc::migrateIfNeeded).doesNotThrowAnyException();
        // The re-serialisation failed, so the metadata version is not advanced (retried next boot).
        assertThat(readVersion().getInt("metadata-version")).isEqualTo(-5);
    }

    @Test
    void reserialiseAll_skipsNullBlobRows_andAdvancesTheVersion() {
        writeVersion(1, -5);
        mapper.rows.put(1, null);

        service.migrateIfNeeded();

        assertThat(mapper.rows.get(1)).isNull();
        assertThat(readVersion().getInt("metadata-version")).isGreaterThan(-5);
    }

    @Test
    void reserialiseAll_leavesARowThatFailsToDecode_andAdvancesTheVersion() throws Exception {
        writeVersion(1, -5);
        String rejected = legacyBlobOf(new java.util.ArrayList<>());
        mapper.rows.put(1, rejected);

        service.migrateIfNeeded();

        assertThat(mapper.rows.get(1)).isEqualTo(rejected);
        assertThat(readVersion().getInt("metadata-version")).isGreaterThan(-5);
    }

    @Test
    void getFromCode_nullForLegacyNonStringPayload() throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bos)) {
            oos.writeObject(new java.util.ArrayList<>());
        }
        mapper.rows.put(1, java.util.Base64.getEncoder().encodeToString(bos.toByteArray()));
        assertThat(service.getFromCode(Base62.encode(1))).isNull(); // IOException catch -> null
    }

    @Test
    void migrateIfNeeded_skipsBlobMigration_whenAlreadyAtPlainEncoding() {
        // Pre-write a version file at the plain blob-encoding + current metadata version.
        int metaVersion = new ItemStack(Material.STONE).serialize().containsKey("v")
                ? (int) new ItemStack(Material.STONE).serialize().get("v")
                : -1;
        org.bukkit.configuration.file.YamlConfiguration cfg = new org.bukkit.configuration.file.YamlConfiguration();
        cfg.set("blob-encoding", 1);
        cfg.set("metadata-version", metaVersion);
        try {
            cfg.save(new File(dataFolder, "version"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ItemCodeMapper spy = mock(ItemCodeMapper.class);
        ItemCodeServiceImpl svc =
                new ItemCodeServiceImpl(spy, new MaterialServiceImpl(TestConfigs.defaults()), dataFolder);
        svc.migrateIfNeeded();
        // Already migrated + up to date: no blob scan, no re-serialise.
        verify(spy, never()).findAllIds();
    }

    // ---- version advance and failure paths (stubbed YAML / item construction) ----

    /** Replace the service's private {@code yaml} so we can force a StackOverflowError / non-round-trip. */
    private static void setYaml(ItemCodeServiceImpl svc, org.yaml.snakeyaml.Yaml y) throws Exception {
        java.lang.reflect.Field f = ItemCodeServiceImpl.class.getDeclaredField("yaml");
        f.setAccessible(true);
        f.set(svc, y);
    }

    @Test
    void migrateIfNeeded_reserialisesWhenDataVersionAdvances() {
        // MockBukkit's ItemStack.serialize() omits the data-version "v", pinning
        // currentMetadataVersion() to -1 so the re-serialiser never runs with a non-negative
        // previous version. Mock ItemStack construction so the synthetic STONE probe reports v=5:
        // a known older version (0) is upgraded and the new version recorded.
        writeVersion(1, 0); // blob migration done; metadata-version 0 (>-1) and below the mocked 5
        mapper.rows.put(1, ItemCodeServiceImpl.encodeBlob("not: [valid: yaml")); // no "\nv: 5\n"
        mapper.rows.put(2, ItemCodeServiceImpl.encodeBlob("x\nv: 5\n")); // already at v5
        try (org.mockito.MockedConstruction<ItemStack> mc =
                org.mockito.Mockito.mockConstruction(ItemStack.class, (m, ctx) -> {
                    when(m.serialize()).thenReturn(java.util.Map.of("v", 5));
                    when(m.getItemMeta()).thenReturn(null);
                })) {
            service.migrateIfNeeded(); // must not throw
        }
        org.bukkit.configuration.file.YamlConfiguration after =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new File(dataFolder, "version"));
        assertThat(after.getInt("metadata-version")).isEqualTo(5);
    }

    @Test
    void getItemCode_storesTheReloadedDump_whenTheYamlRoundTripDiffers() throws Exception {
        // If the YAML round-trip doesn't reproduce the item, the stored blob is the dump of the
        // reloaded item (so later loads are stable), not the first dump.
        ItemStack reloaded = item(Material.DIRT, 1);
        org.yaml.snakeyaml.Yaml y = mock(org.yaml.snakeyaml.Yaml.class);
        when(y.dump(any())).thenAnswer(inv -> inv.getArgument(0) == reloaded ? "reloaded" : "original");
        when(y.loadAs(anyString(), eq(ItemStack.class))).thenReturn(reloaded);
        setYaml(service, y);

        String code = service.getItemCode(item(Material.DIAMOND, 1));

        assertThat(mapper.rows.get(Base62.decode(code))).isEqualTo(ItemCodeServiceImpl.encodeBlob("reloaded"));
    }

    @Test
    void getFromCode_nullOnStackOverflowError() throws Exception {
        mapper.rows.put(1, ItemCodeServiceImpl.encodeBlob("type: STONE\n"));
        org.yaml.snakeyaml.Yaml y = mock(org.yaml.snakeyaml.Yaml.class);
        when(y.loadAs(anyString(), eq(ItemStack.class))).thenThrow(new StackOverflowError());
        setYaml(service, y);
        assertThat(service.getFromCode(Base62.encode(1))).isNull();
    }

    @Test
    void reserialiseAll_leavesAStackOverflowingRow_andAdvancesTheVersion() throws Exception {
        writeVersion(1, -5); // skip blob migration, trigger metadata re-serialisation
        String blob = ItemCodeServiceImpl.encodeBlob("type: STONE\n");
        mapper.rows.put(1, blob);
        org.yaml.snakeyaml.Yaml y = mock(org.yaml.snakeyaml.Yaml.class);
        when(y.loadAs(anyString(), eq(ItemStack.class))).thenThrow(new StackOverflowError());
        setYaml(service, y);

        assertThatCode(service::migrateIfNeeded).doesNotThrowAnyException();

        assertThat(mapper.rows.get(1)).isEqualTo(blob);
        assertThat(readVersion().getInt("metadata-version")).isGreaterThan(-5);
    }

    @Test
    void migrateIfNeeded_stillMigratesBlobs_whenTheVersionFileCannotBeSaved() throws Exception {
        // A directory at the 'version' path makes recording the blob-encoding version fail;
        // startup must carry on with the rows already rewritten.
        new File(dataFolder, "version").mkdirs();
        mapper.rows.put(1, legacyBlobOf("type: STONE\n"));

        assertThatCode(service::migrateIfNeeded).doesNotThrowAnyException();

        assertThat(mapper.rows.get(1)).isEqualTo(ItemCodeServiceImpl.encodeBlob("type: STONE\n"));
    }

    @Test
    void migrateIfNeeded_logsError_whenMetadataVersionSaveFails() {
        // metadata-version -5 < currentVersion so reserialiseAll runs; the read-only version file
        // then makes the metadata-version save throw IOException, which must not abort startup.
        writeVersion(1, -5);
        File versionFile = new File(dataFolder, "version");
        assertThat(versionFile.setWritable(false)).isTrue();
        try {
            assertThatCode(service::migrateIfNeeded).doesNotThrowAnyException();
        } finally {
            versionFile.setWritable(true);
        }
    }

    @Test
    void decodeBlob_treatsAcNonEdPrefixAsPlainText() {
        // Standard-Base64 bytes starting 0xAC but not 0xED aren't a Java-serialization header,
        // so they are read as (garbage) UTF-8 -> corrupt YAML -> null, not deserialized.
        byte[] weird = {(byte) 0xAC, 0x00, (byte) 0x99, 0x3A, 0x20, 0x5B};
        mapper.rows.put(1, java.util.Base64.getEncoder().encodeToString(weird));
        assertThat(service.getFromCode(Base62.encode(1))).isNull();
    }

    @Test
    void getFromCode_readsLegacyStringBlob_viaAllowedFilter() throws Exception {
        // A legacy Java-serialized String blob: the deserialisation filter permits String (the
        // ALLOWED arm of 345/346), returning the YAML which then decodes to the item. Re-use the
        // real ItemStack YAML the service produced so the round-trip yields a valid item.
        service.getItemCode(item(Material.STONE, 1)); // stores a plain-Base64 blob at id 1
        String realYaml = new String(
                java.util.Base64.getDecoder().decode(mapper.rows.get(1)), java.nio.charset.StandardCharsets.UTF_8);

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bos)) {
            oos.writeObject(realYaml);
        }
        mapper.rows.put(2, java.util.Base64.getEncoder().encodeToString(bos.toByteArray()));

        ItemStack loaded = service.getFromCode(Base62.encode(2));
        assertThat(loaded).isNotNull();
        assertThat(loaded.getType()).isEqualTo(Material.STONE);
    }

    @Test
    void getFromCode_legacyStringClassPayload_isAllowedByFilterThenFailsCast() throws Exception {
        // A legacy blob serialising String.class: the deserialisation filter is invoked with
        // String.class -> ALLOWED (the allow arm of 345/346). readObject then yields the Class,
        // which the (String) cast rejects with a ClassCastException.
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bos)) {
            oos.writeObject(String.class);
        }
        mapper.rows.put(1, java.util.Base64.getEncoder().encodeToString(bos.toByteArray()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getFromCode(Base62.encode(1)))
                .isInstanceOf(ClassCastException.class);
    }
}
