package io.paradaux.chestshop.services.impl;
import lombok.extern.slf4j.Slf4j;

import io.paradaux.chestshop.services.MaterialService;
import io.paradaux.chestshop.services.ItemCodeService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.model.Item;
import io.paradaux.chestshop.mappers.ItemCodeMapper;
import io.paradaux.chestshop.utils.MaterialUtil;
import io.paradaux.chestshop.utils.StringUtil;
import io.paradaux.chestshop.utils.encoding.Base62;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.file.YamlConstructor;
import org.bukkit.configuration.file.YamlRepresenter;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.File;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Owns the item-code <em>logic</em>: serialising an {@link ItemStack} to/from the
 * short Base62 code shops put on signs, the find-or-create that keeps the store
 * deduplicated, and the one-time re-serialisation when the server's item data
 * version changes. All persistence is delegated to the {@link ItemCodeMapper}.
 *
 * <p>This is the service half of the {@code ItemDatabase} split: the old class
 * mixed YAML/Base64 encoding, find-or-create business rules, and raw SQLite access in
 * one place; that DB access now lives in the MyBatis mapper, leaving this a pure,
 * testable service.
 */
@Singleton
@Slf4j
public class ItemCodeServiceImpl implements ItemCodeService {

    private final ItemCodeMapper items;
    private final MaterialService materialService;
    private final java.io.File dataFolder;
    private final Yaml yaml = new Yaml(new YamlBukkitConstructor(), new YamlRepresenter(), new DumperOptions());

    @Inject
    public ItemCodeServiceImpl(ItemCodeMapper items, MaterialService materialService, @com.google.inject.name.Named("dataFolder") java.io.File dataFolder) {
        this.items = items;
        this.materialService = materialService;
        this.dataFolder = dataFolder;
    }

    /** Runs the one-time metadata re-serialisation if the server's data version advanced. */
    @Override
    public void migrateIfNeeded() {
        File versionFile = new File(dataFolder, "version");
        YamlConfiguration versionConfig = YamlConfiguration.loadConfiguration(versionFile);

        // One-time: rewrite legacy Java-serialized item-code blobs to plain Base64
        // (PAR-290 / ADT-136) before anything reads or dedups them, so the find-or-create
        // key (the blob itself) is consistent across old and new rows. Gated to run once.
        if (versionConfig.getInt("blob-encoding", 0) < BLOB_ENCODING_PLAIN && migrateBlobEncoding()) {
            versionConfig.set("blob-encoding", BLOB_ENCODING_PLAIN);
            try {
                versionConfig.save(versionFile);
            } catch (IOException e) {
                log.error("Error while recording the item-code blob-encoding version", e);
            }
        }

        int previousVersion = versionConfig.getInt("metadata-version", -1);
        int newVersion = currentMetadataVersion();
        if (previousVersion >= newVersion) {
            return;
        }
        if (reserialiseAll(previousVersion, newVersion)) {
            versionConfig.set("metadata-version", newVersion);
            try {
                versionConfig.save(versionFile);
            } catch (IOException e) {
                log.error("Error while updating metadata-version from " + previousVersion + " to " + newVersion, e);
            }
        } else {
            log.warn("Error while updating Item Metadata database! While the plugin will still run it will work less efficiently.");
        }
    }

    /** The short Base62 code for an item, creating a store row for it if new. */
    @Override
    public String getItemCode(ItemStack item) {
        try {
            ItemStack clone = new ItemStack(item);
            clone.setAmount(1);

            // Preserved verbatim from the old ItemDatabase: this resets damage on a
            // throwaway meta copy and never re-applies it, so it is effectively a
            // no-op. Kept as-is so item codes are byte-identical to existing shops —
            // actually applying it would change the generated code for damaged items.
            ItemMeta meta = clone.getItemMeta();
            if (meta instanceof Damageable damageable && damageable.hasDamage()) {
                damageable.setDamage(0);
            }

            String dumped = yaml.dump(clone);
            ItemStack loadedItem = yaml.loadAs(dumped, ItemStack.class);
            if (!loadedItem.isSimilar(item)) {
                dumped = yaml.dump(loadedItem);
            }
            String blob = encodeBlob(dumped);

            Integer existing = items.findIdByBlob(blob);
            int id;
            if (existing != null) {
                id = existing;
            } else {
                Item row = new Item(blob);
                items.insert(row); // generated id set back onto the row
                id = row.getId();
            }
            return Base62.encode(id);
        } catch (RuntimeException e) {
            log.error("Unable to get code of item " + item, e);
            return null;
        }
    }

    /** The {@link ItemStack} a code refers to, or {@code null} if unknown/corrupt. */
    @Override
    public ItemStack getFromCode(String code) {
        int id = Base62.decode(code);
        String blob = items.findBlobById(id);
        if (blob == null) {
            return null;
        }
        try {
            return yaml.loadAs(decodeBlob(blob), ItemStack.class);
        } catch (YAMLException e) {
            log.error("YAML of the item with ID " + code + " (" + id + ") is corrupted: \n" + blob);
        } catch (IOException | ClassNotFoundException e) {
            log.error("Unable to load item with ID " + code + " (" + id + ")", e);
        } catch (StackOverflowError e) {
            log.error("Item with ID " + code + " (" + id + ") is corrupted. Sorry :(");
        }
        return null;
    }

    // ---- canonical item ↔ sign-code (MATERIAL:durability#NNN) ----
    // The vanilla (no custom-item/alias) conversion ChestShop puts on signs. The custom-data
    // suffix (#NNN) round-trips through the item-code store above (getItemCode/getFromCode →
    // ItemCodeMapper) — was the static MaterialUtil.getName/getItem reaching the DB via the
    // ChestShop.itemCodes() locator (PAR-282). MaterialUtil keeps only the pure helpers.

    /** The canonical sign string for {@code item} (material + durability + #code), within {@code maxWidth} (0 = unlimited). */
    @Override
    public String encode(ItemStack item, int maxWidth) {
        String itemName = item.getType().toString();

        String durability = "";
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable damageable && damageable.hasDamage()) {
            durability = ":" + damageable.getDamage();
        }

        String metaData = "";
        if (MaterialUtil.hasCustomData(item)) {
            metaData = "#" + getItemCode(item);
        }

        String code = StringUtil.capitalizeFirstLetter(itemName, '_');
        if (maxWidth > 0) {
            int codeWidth = StringUtil.getMinecraftStringWidth(code + durability + metaData);
            if (codeWidth > maxWidth) {
                int exceeding = codeWidth - maxWidth;
                code = MaterialUtil.getShortenedName(code, StringUtil.getMinecraftStringWidth(code) - exceeding);
            }
        }

        return code + durability + metaData;
    }

    /** The {@link ItemStack} for a canonical sign string (material + durability + #code), or {@code null}. */
    @Override
    public ItemStack decode(String itemName) {
        String[] split = itemName.split("[:#]");
        for (int i = 0; i < split.length; i++) {
            split[i] = split[i].trim();
        }

        Integer durability = MaterialUtil.getDurability(itemName);
        Material material = materialService.getMaterial(split[0]);
        if (material == null) {
            return null;
        }

        ItemStack itemStack = new ItemStack(material);

        ItemMeta meta = metadataFromCode(itemName);

        if (durability != null) {
            if (meta == null) {
                meta = itemStack.getItemMeta();
            }
            if (meta instanceof Damageable) {
                ((Damageable) meta).setDamage(durability);
            }
        }

        if (meta != null) {
            itemStack.setItemMeta(meta);
        }

        return itemStack;
    }

    /** The {@link ItemMeta} encoded by the #code suffix of {@code itemName}, or {@code null} if none. */
    private ItemMeta metadataFromCode(String itemName) {
        String group = MaterialUtil.parseMetadata(itemName);
        if (group == null) {
            return null;
        }
        ItemStack item = getFromCode(group);
        return item == null ? null : item.getItemMeta();
    }

    private int currentMetadataVersion() {
        ItemStack item = new ItemStack(Material.STONE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("GetCurrentMetadataVersion");
            item.setItemMeta(meta);
        }
        Map<String, Object> serialized = item.serialize();
        return (int) serialized.getOrDefault("v", -1);
    }

    private boolean reserialiseAll(int previousVersion, int newVersion) {
        if (previousVersion > -1) {
            log.info("Data version change detected! Previous version was " + previousVersion);
        }
        log.info("Updating Item Metadata database to data version " + newVersion + "...");

        int seen = 0;
        int updated = 0;
        long start = System.currentTimeMillis();
        try {
            // Stream the ids, then fetch + re-serialise each blob by id, so the whole
            // store never has to be held in memory for the one-time migration.
            for (int id : items.findAllIds()) {
                seen++;
                String blob = items.findBlobById(id);
                if (blob == null) {
                    continue;
                }
                try {
                    String serialized = decodeBlob(blob);
                    // Cheap version sniff: re-serialise only items not already at newVersion.
                    if (previousVersion < 0 || !serialized.contains("\nv: " + newVersion + "\n")) {
                        try {
                            ItemStack itemStack = yaml.loadAs(serialized, ItemStack.class);
                            items.updateBlob(id, encodeBlob(yaml.dump(itemStack)));
                            updated++;
                        } catch (RuntimeException e) {
                            log.error("YAML of the item with ID "
                                    + Base62.encode(id) + " (" + id + ") is corrupted: \n"
                                    + serialized + "\n" + e.getMessage());
                        }
                    }
                } catch (IOException | ClassNotFoundException e) {
                    log.error("Unable to convert item with ID "
                            + Base62.encode(id) + " (" + id + ")", e);
                } catch (StackOverflowError e) {
                    log.error("Item with ID "
                            + Base62.encode(id) + " (" + id + ") is corrupted. Sorry :(");
                }
                if (seen % 1000 == 0) {
                    log.info("Checked " + seen + " items. Updated " + updated + "...");
                }
            }
        } catch (RuntimeException e) {
            log.error("Unable to update metadata version of all items from " + previousVersion + " to " + newVersion, e);
            return false;
        }

        log.info("Finished updating database in " + (System.currentTimeMillis() - start) / 1000.0
                + "s. " + updated + " items out of " + seen + " were updated!");
        return true;
    }

    // ---- item-code blob codec (PAR-290 / ADT-136) ----
    // New rows store the YAML string as plain Base64 of its UTF-8 bytes
    // (java.util.Base64). Legacy rows are a Java-serialized String produced by the
    // vendored Base64#encodeObject — the native-deserialization sink ADT-136 flagged, now
    // gross overkill for a plain string. decodeBlob reads both (plain first, falling back
    // to the legacy object decoder), and migrateBlobEncoding() rewrites every legacy row
    // to plain, so the legacy path is transitional; readObject is String-filtered regardless.

    private static final int BLOB_ENCODING_PLAIN = 1;

    /** Encode a YAML string as plain Base64 of its UTF-8 bytes (the PAR-290 format). */
    static String encodeBlob(String yamlString) {
        return java.util.Base64.getEncoder().encodeToString(yamlString.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decode a stored item-code blob back to its YAML string, reading either the plain
     * Base64 format or a legacy Java-serialized {@code String}. A legacy blob's bytes
     * begin with the {@code ObjectOutputStream} stream header (0xAC 0xED), which plain
     * UTF-8 YAML never does; blobs the strict {@link java.util.Base64} decoder rejects
     * (e.g. line-broken legacy output) can only be legacy too.
     */
    static String decodeBlob(String blob) throws IOException, ClassNotFoundException {
        byte[] raw;
        try {
            raw = java.util.Base64.getDecoder().decode(blob);
        } catch (IllegalArgumentException notStandardBase64) {
            // Line-broken legacy Base64 the strict decoder rejects: the MIME decoder ignores
            // the wrapping newlines (byte-identical to the old vendored decoder for this data),
            // then a String-only filter guards readObject.
            return deserializeLegacyString(java.util.Base64.getMimeDecoder().decode(blob));
        }
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xAC && (raw[1] & 0xFF) == 0xED) {
            return deserializeLegacyString(raw);
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    /**
     * Deserialize a legacy Java-serialized {@code String} blob through an {@link ObjectInputFilter}
     * that permits only {@code String} — closing the native-deserialization RCE sink ADT-136 flagged
     * while still reading pre-PAR-290 rows (the migration rewrites them to plain Base64 so this path
     * is transitional). Any other class in the stream is rejected before it can be constructed.
     */
    private static String deserializeLegacyString(byte[] serialized) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
            ois.setObjectInputFilter(info -> {
                Class<?> c = info.serialClass();
                return (c == null || c == String.class)
                        ? ObjectInputFilter.Status.ALLOWED
                        : ObjectInputFilter.Status.REJECTED;
            });
            return (String) ois.readObject();
        }
    }

    /** Rewrite every stored blob into the plain Base64 format. Idempotent; returns false on a fatal error. */
    private boolean migrateBlobEncoding() {
        log.info("Migrating item-code blobs to plain Base64 (PAR-290)...");
        int seen = 0;
        int updated = 0;
        long start = System.currentTimeMillis();
        try {
            for (int id : items.findAllIds()) {
                seen++;
                String blob = items.findBlobById(id);
                if (blob == null) {
                    continue;
                }
                try {
                    String plain = encodeBlob(decodeBlob(blob));
                    if (!plain.equals(blob)) {
                        items.updateBlob(id, plain);
                        updated++;
                    }
                } catch (IOException | ClassNotFoundException | RuntimeException e) {
                    log.error("Unable to re-encode item-code blob " + Base62.encode(id) + " (" + id + ")", e);
                }
                if (seen % 1000 == 0) {
                    log.info("Re-encoded " + seen + " blobs (" + updated + " rewritten)...");
                }
            }
        } catch (RuntimeException e) {
            log.error("Item-code blob encoding migration failed", e);
            return false;
        }
        log.info("Finished item-code blob encoding migration in "
                + (System.currentTimeMillis() - start) / 1000.0 + "s. " + updated + " of " + seen + " rewritten.");
        return true;
    }

    private static final class YamlBukkitConstructor extends YamlConstructor {
        YamlBukkitConstructor() {
            this.yamlConstructors.put(new Tag(Tag.PREFIX + "org.bukkit.inventory.ItemStack"), yamlConstructors.get(Tag.MAP));
        }
    }
}
