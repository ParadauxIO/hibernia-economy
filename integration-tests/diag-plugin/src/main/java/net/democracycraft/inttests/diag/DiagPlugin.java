package net.democracycraft.inttests.diag;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * Test-only diagnostic plugin for the integration-tests harness.
 *
 * <p>Two jobs:
 * <ol>
 *   <li>Log {@link PlayerInteractEvent} and {@link BlockBreakEvent} at MONITOR
 *       priority. MONITOR is invoked even when an upstream listener cancels
 *       the event — so this listener always fires when Bukkit dispatches the
 *       event at all. If a mock-client {@code useBlock} packet reaches Bukkit,
 *       this listener logs it; if not, the packet was dropped server-side.
 *       That's the bisect.</li>
 *   <li>Expose RCON commands that synthesise a {@link PlayerInteractEvent}
 *       directly via the Bukkit API:
 *       {@code /diag click <player> <world> <x> <y> <z> [face]}. This
 *       sidesteps every server-side packet validation and lets tests drive
 *       ChestShop's listener deterministically.</li>
 * </ol>
 *
 * <p>Logging is on by default; toggle with {@code /diag logon|logoff}.
 */
public final class DiagPlugin extends JavaPlugin implements Listener {

    private volatile boolean logging = true;

    /**
     * PlayerInteractEvents seen per player (lower-cased name), cancelled or not. Read with
     * {@code /diag interactions <player>}: a test that expects a click to change nothing
     * first proves the click reached Bukkit at all, so "nothing changed" can't pass because
     * the server silently dropped the packet.
     */
    private final java.util.Map<String, java.util.concurrent.atomic.AtomicLong> interactions =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Ticks ahead within which a pending one-shot task counts as in flight for
     * {@code /diag asyncidle}. Work a command hands to the scheduler runs on the next tick
     * or two; anything scheduled further out (TaxCycleTask parks its next cycle hours
     * ahead) is not part of the command and must not hold the barrier forever.
     */
    private static final long ASYNC_IDLE_HORIZON_TICKS = 40;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("DcDiag enabled — logging interactions at MONITOR priority.");
    }

    // ---------------------------------------------------------------- listeners

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        interactions.computeIfAbsent(e.getPlayer().getName().toLowerCase(Locale.ROOT),
                k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        if (!logging) return;
        Block b = e.getClickedBlock();
        getLogger().info(String.format(
                "[INTERACT] player=%s action=%s block=%s face=%s hand=%s cancelled=%s useInteractedBlock=%s useItemInHand=%s",
                e.getPlayer().getName(),
                e.getAction(),
                b == null ? "null" : (b.getType() + "@" + b.getWorld().getName()
                        + ":" + b.getX() + "," + b.getY() + "," + b.getZ()),
                e.getBlockFace(),
                e.getHand(),
                e.isCancelled(),
                e.useInteractedBlock(),
                e.useItemInHand()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent e) {
        if (!logging) return;
        Block b = e.getBlock();
        getLogger().info(String.format("[BREAK] player=%s block=%s@%s:%d,%d,%d cancelled=%s",
                e.getPlayer().getName(), b.getType(), b.getWorld().getName(),
                b.getX(), b.getY(), b.getZ(), e.isCancelled()));
    }

    private final java.util.Map<UUID, long[]> menuEvents = new java.util.HashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onMenuClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        long[] counts = menuEvents.computeIfAbsent(event.getWhoClicked().getUniqueId(), ignored -> new long[3]);
        counts[0]++;
        if (event.isCancelled()) counts[2]++;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onMenuDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        long[] counts = menuEvents.computeIfAbsent(event.getWhoClicked().getUniqueId(), ignored -> new long[3]);
        counts[1]++;
        if (event.isCancelled()) counts[2]++;
    }

    /** Test-only inventory observation and fixture setup. Clicks themselves always arrive over the wire. */
    private boolean doMenuDiagnostic(CommandSender sender, String[] args) {
        try {
            if (args[0].equals("menusetting")) {
                var cs = java.util.Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("ChestShop"));
                var file = new java.io.File(cs.getDataFolder(), "find.yml");
                var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
                if (!java.util.Set.of("shop-icon-left-click-command", "shop-icon-right-click-command").contains(args[1])) {
                    throw new IllegalArgumentException("Unsupported finder setting");
                }
                yaml.set(args[1], args.length < 3 ? "" : String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                yaml.save(file);
                var field = cs.getClass().getDeclaredField("injector");
                field.setAccessible(true);
                var loader = cs.getClass().getClassLoader();
                var type = loader.loadClass("io.paradaux.chestshop.services.FindPresentation");
                var service = loader.loadClass("io.paradaux.chestshop.Libs.guice.Injector")
                        .getMethod("getInstance", Class.class).invoke(field.get(cs), type);
                type.getMethod("reload").invoke(service);
                sender.sendMessage("updated");
                return true;
            }
            Player player = java.util.Objects.requireNonNull(Bukkit.getPlayerExact(args[1]), "player");
            if (args[0].equals("menucursor")) {
                player.setItemOnCursor(new ItemStack(Material.valueOf(args[2]), Integer.parseInt(args[3])));
                sender.sendMessage("updated");
            } else if (args[0].equals("menuplain")) {
                var inventory = Bukkit.createInventory(null, 27, net.kyori.adventure.text.Component.text("Unrelated"));
                inventory.setItem(0, new ItemStack(Material.DIAMOND, 7));
                player.openInventory(inventory);
                sender.sendMessage("opened");
            } else {
                var json = new com.google.gson.JsonObject();
                var view = player.getOpenInventory();
                if (args[0].equals("menuslot")) {
                    ItemStack item = view.getTopInventory().getItem(Integer.parseInt(args[2]));
                    json.addProperty("material", item == null ? "AIR" : item.getType().name());
                    if (item != null) {
                        json.addProperty("name", plain(item.effectiveName()));
                        json.addProperty("lore", item.lore() == null ? "" : item.lore().stream()
                                .map(DiagPlugin::plain).collect(java.util.stream.Collectors.joining(" | ")));
                    }
                } else {
                    long[] events = menuEvents.getOrDefault(player.getUniqueId(), new long[3]);
                    json.addProperty("clicks", events[0]);
                    json.addProperty("drags", events[1]);
                    json.addProperty("denied", events[2]);
                    json.addProperty("tick", Bukkit.getCurrentTick());
                    json.addProperty("title", plain(view.title()));
                    json.addProperty("size", view.getTopInventory().getSize());
                    json.addProperty("inventory", inventoryHash(player.getInventory().getContents()));
                    json.addProperty("cursor", inventoryHash(new ItemStack[]{player.getItemOnCursor()}));
                    json.addProperty("top", inventoryHash(view.getTopInventory().getContents()));
                    json.addProperty("drops", player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size());
                }
                sender.sendMessage(json.toString());
            }
        } catch (Exception e) {
            sender.sendMessage("menu diagnostic failed: " + e);
        }
        return true;
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static String inventoryHash(ItemStack[] items) throws java.security.NoSuchAlgorithmException {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) digest.update((byte) 0);
            else {
                byte[] bytes = item.serializeAsBytes();
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    // ---------------------------------------------------------------- commands

    private boolean doVault(CommandSender sender, String[] args) {
        if (args.length != 4 || !(args[1].equals("withdraw") || args[1].equals("deposit"))) {
            sender.sendMessage("usage: diag vault <withdraw|deposit> <player-uuid> <amount>");
            return true;
        }
        try {
            var vault = java.util.Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("Vault"));
            Class<?> economy = vault.getClass().getClassLoader().loadClass("net.milkbowl.vault.economy.Economy");
            Object provider = java.util.Objects.requireNonNull(Bukkit.getServicesManager().load(economy));
            Object response = economy.getMethod(args[1] + "Player", org.bukkit.OfflinePlayer.class, double.class)
                    .invoke(provider, Bukkit.getOfflinePlayer(UUID.fromString(args[2])), Double.parseDouble(args[3]));
            sender.sendMessage("provider=" + economy.getMethod("getName").invoke(provider)
                    + " success=" + response.getClass().getMethod("transactionSuccess").invoke(response)
                    + " balance=" + response.getClass().getField("balance").get(response)
                    + " error=" + response.getClass().getField("errorMessage").get(response));
        } catch (ReflectiveOperationException e) {
            sender.sendMessage("Vault diagnostic failed: " + e);
        }
        return true;
    }

    private boolean doRealNexo(CommandSender sender, String[] args) {
        if ((args[0].equals("nexostock") && args.length != 7)
                || (args[0].equals("nexocount") && args.length != 3)) {
            sender.sendMessage("usage: diag nexostock <world> <x> <y> <z> <id> <count> OR diag nexocount <player> <id>");
            return true;
        }
        try {
            var nexo = java.util.Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("Nexo"));
            Class<?> api = nexo.getClass().getClassLoader().loadClass("com.nexomc.nexo.api.NexoItems");
            if (args[0].equals("nexostock")) {
                var chest = (org.bukkit.block.Chest) Bukkit.getWorld(args[1]).getBlockAt(
                        Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4])).getState();
                Object builder = api.getMethod("itemFromId", String.class).invoke(null, args[5]);
                ItemStack item = (ItemStack) builder.getClass().getMethod("build").invoke(builder);
                item.setAmount(Integer.parseInt(args[6]));
                chest.getBlockInventory().setItem(0, item);
                sender.sendMessage("nexostock " + args[5]);
            } else {
                Player player = java.util.Objects.requireNonNull(Bukkit.getPlayerExact(args[1]));
                int count = 0;
                for (ItemStack item : player.getInventory().getContents()) {
                    if (item != null && args[2].equals(api.getMethod("idFromItem", ItemStack.class).invoke(null, item))) count += item.getAmount();
                }
                sender.sendMessage("nexocount " + args[2] + " = " + count);
            }
        } catch (Exception e) { sender.sendMessage("real Nexo diagnostic failed: " + e); }
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("dcsync")) {
            // Barrier sentinel (support/Barrier.java): answered synchronously on the main
            // thread, so the reply proves every command this player sent before it has
            // been dispatched. Echo the caller's nonce so replies can't be confused.
            sender.sendMessage("dcsync " + String.join(" ", args));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("usage: /diag <click|break|pos|logon|logoff> [args...]");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "nexostock":
            case "nexocount":
                return doRealNexo(sender, args);
            case "taxschedule":
                return doTaxSchedule(sender, args);
            case "taxscheduledstate":
                return doTaxScheduledState(sender, args);
            case "worlduuid":
                if (args.length != 2 || Bukkit.getWorld(args[1]) == null) {
                    sender.sendMessage("usage: /diag worlduuid <loaded-world>");
                    return true;
                }
                sender.sendMessage(Bukkit.getWorld(args[1]).getUID().toString());
                return true;
            case "itembytes":
                if (args.length != 2 || org.bukkit.Material.matchMaterial(args[1]) == null) {
                    sender.sendMessage("usage: /diag itembytes <material>");
                    return true;
                }
                sender.sendMessage(java.util.Base64.getEncoder().encodeToString(
                        new org.bukkit.inventory.ItemStack(org.bukkit.Material.matchMaterial(args[1])).serializeAsBytes()));
                return true;
            case "runtime":
                sender.sendMessage("minecraft=" + Bukkit.getMinecraftVersion()
                        + " online-mode=" + Bukkit.getOnlineMode());
                return true;
            case "plugin":
                if (args.length != 2) {
                    sender.sendMessage("usage: /diag plugin <name>");
                    return true;
                }
                org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin(args[1]);
                sender.sendMessage(plugin == null ? "missing" : "enabled=" + plugin.isEnabled()
                        + " version=" + plugin.getDescription().getVersion());
                return true;
            case "logon":
                logging = true;
                sender.sendMessage("DcDiag logging ON");
                return true;
            case "logoff":
                logging = false;
                sender.sendMessage("DcDiag logging OFF");
                return true;
            case "pos":
                return doPos(sender, args);
            case "click":
                return doInteract(sender, args, Action.RIGHT_CLICK_BLOCK);
            case "leftclick":
                return doInteract(sender, args, Action.LEFT_CLICK_BLOCK);
            case "break":
                return doBreak(sender, args);
            case "checksign":
                return doCheckSign(sender, args);
            case "warmname":
                return doWarmName(sender, args);
            case "chestcount":
                return doChestCount(sender, args);
            case "invcount":
                return doInvCount(sender, args);
            case "invgive":
                return doInvGive(sender, args);
            case "invclear":
                return doInvClear(sender, args);
            case "invfill":
                return doInvFill(sender, args);
            case "chestshoptax":
                return doChestShopTax(sender, args);
            case "config":
                return doConfig(sender, args);
            case "configget":
                return doConfigGet(sender, args);
            case "createshop":
                return doCreateShop(sender, args);
            case "wgregion":
                return doWgRegion(sender, args);
            case "vault":
                return doVault(sender, args);
            case "wgowners":
                if (args.length != 3) { sender.sendMessage("usage: diag wgowners <world> <region>"); return true; }
                var manager = com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer()
                        .get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(java.util.Objects.requireNonNull(Bukkit.getWorld(args[1]))));
                var region = java.util.Objects.requireNonNull(java.util.Objects.requireNonNull(manager).getRegion(args[2]));
                sender.sendMessage("owners=" + region.getOwners().getUniqueIds().stream().map(UUID::toString).sorted().toList());
                return true;
            case "gpclaim":
                return doGpClaim(sender, args);
            case "protclear":
                return doProtClear(sender, args);
            case "interactions":
                return doInteractions(sender, args);
            case "perm":
                return doPerm(sender, args);
            case "menustate":
            case "menucursor":
            case "menuslot":
            case "menuplain":
            case "menusetting":
                return doMenuDiagnostic(sender, args);
            case "asyncidle":
                return doAsyncIdle(sender, args);
            case "ingeststub":
                return doIngestStub(sender, args);
            default:
                sender.sendMessage("unknown subcommand: " + args[0]);
                return true;
        }
    }

    private boolean doPos(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("usage: /diag pos <player>");
            return true;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) {
            sender.sendMessage("player not found: " + args[1]);
            return true;
        }
        Location loc = p.getLocation();
        Location eye = p.getEyeLocation();
        sender.sendMessage(String.format(
                "%s: world=%s pos=(%.3f,%.3f,%.3f) eye=(%.3f,%.3f,%.3f) yaw=%.1f pitch=%.1f gamemode=%s onGround=%s",
                p.getName(), loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(),
                eye.getX(), eye.getY(), eye.getZ(),
                loc.getYaw(), loc.getPitch(),
                p.getGameMode(), p.isOnGround()));
        return true;
    }

    private boolean doInteract(CommandSender sender, String[] args, Action action) {
        // /diag click <player> <world> <x> <y> <z> [face]
        if (args.length < 6) {
            sender.sendMessage("usage: /diag " + args[0] + " <player> <world> <x> <y> <z> [face]");
            return true;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) {
            sender.sendMessage("player not found: " + args[1]);
            return true;
        }
        World w = Bukkit.getWorld(args[2]);
        if (w == null) {
            sender.sendMessage("world not found: " + args[2]);
            return true;
        }
        int x, y, z;
        try {
            x = Integer.parseInt(args[3]);
            y = Integer.parseInt(args[4]);
            z = Integer.parseInt(args[5]);
        } catch (NumberFormatException e) {
            sender.sendMessage("coords must be ints: " + e.getMessage());
            return true;
        }
        BlockFace face = BlockFace.UP;
        if (args.length >= 7) {
            try {
                face = BlockFace.valueOf(args[6].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                sender.sendMessage("invalid face: " + args[6]);
                return true;
            }
        }
        Block block = w.getBlockAt(x, y, z);
        ItemStack item = p.getInventory().getItemInMainHand();
        PlayerInteractEvent event = new PlayerInteractEvent(p, action, item, block, face, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        sender.sendMessage(String.format(
                "fired %s for %s on %s@%d,%d,%d face=%s — cancelled=%s useInteractedBlock=%s",
                action, p.getName(), block.getType(), x, y, z, face,
                event.isCancelled(), event.useInteractedBlock()));
        return true;
    }

    /**
     * Reads a sign block via the Bukkit {@code Sign} API and reflectively calls
     * ChestShop's {@code ChestShopSign.isValid(Sign)} so we can see exactly
     * what the plugin sees. Prints each line of the front side as Bukkit
     * resolves it, plus the validity verdict.
     */
    private boolean doCheckSign(CommandSender sender, String[] args) {
        if (args.length != 5) {
            sender.sendMessage("usage: /diag checksign <world> <x> <y> <z>");
            return true;
        }
        World w = Bukkit.getWorld(args[1]);
        if (w == null) { sender.sendMessage("world not found: " + args[1]); return true; }
        int x, y, z;
        try {
            x = Integer.parseInt(args[2]); y = Integer.parseInt(args[3]); z = Integer.parseInt(args[4]);
        } catch (NumberFormatException e) {
            sender.sendMessage("coords must be ints"); return true;
        }
        Block block = w.getBlockAt(x, y, z);
        sender.sendMessage("type=" + block.getType() + " loaded=" + block.getChunk().isLoaded());
        org.bukkit.block.BlockState state = block.getState();
        if (!(state instanceof org.bukkit.block.Sign)) {
            sender.sendMessage("not a sign at that pos");
            return true;
        }
        org.bukkit.block.Sign sign = (org.bukkit.block.Sign) state;
        @SuppressWarnings("deprecation")
        String[] lines = sign.getLines();
        for (int i = 0; i < lines.length; i++) {
            sender.sendMessage("  legacy line " + i + " = '" + lines[i] + "' (len=" + lines[i].length() + ")");
        }
        try {
            org.bukkit.block.sign.SignSide front = sign.getSide(org.bukkit.block.sign.Side.FRONT);
            for (int i = 0; i < 4; i++) {
                String s = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(front.line(i));
                sender.sendMessage("  front line " + i + " = '" + s + "'");
            }
        } catch (Throwable t) {
            sender.sendMessage("front side read failed: " + t);
        }
        try {
            Class<?> chestShopSign = Class.forName("com.Acrobot.ChestShop.Signs.ChestShopSign");
            java.lang.reflect.Method isValid = chestShopSign.getMethod("isValid", org.bukkit.block.Sign.class);
            Object result = isValid.invoke(null, sign);
            sender.sendMessage("ChestShopSign.isValid(sign) = " + result);

            java.lang.reflect.Method isValidLines = chestShopSign.getMethod("isValid", String[].class);
            Object resLines = isValidLines.invoke(null, (Object) lines);
            sender.sendMessage("ChestShopSign.isValid(lines) = " + resLines);
        } catch (Throwable t) {
            sender.sendMessage("reflection failed: " + t);
        }
        return true;
    }

    /**
     * Force NameManager to register a connected player so that ChestShop's
     * {@code AccountQueryEvent} can resolve their username. ChestShop's
     * {@code PlayerConnect} listener only updates accounts that already exist
     * — fresh players never get a row, and {@code AccountQueryEvent} from
     * {@code PlayerInteract} fires with {@code searchOfflinePlayers=false}.
     * This command short-circuits that by reflectively calling
     * {@code NameManager.storeUsername(PlayerDTO)}.
     */
    private boolean doWarmName(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("usage: /diag warmname <player>");
            return true;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) { sender.sendMessage("player not found: " + args[1]); return true; }
        org.bukkit.plugin.Plugin cs = Bukkit.getPluginManager().getPlugin("ChestShop");
        if (cs == null) { sender.sendMessage("ChestShop not loaded"); return true; }
        try {
            // io.paradaux fork: a static AccountService field 'accounts' on the main class,
            // and PlayerDTO under io.paradaux.chestshop.players. storeUsername creates-or-refreshes
            // the name↔UUID row. (Largely redundant now that PlayerConnect registers every join,
            // but kept so the harness doesn't depend on that.)
            java.lang.reflect.Field accField = cs.getClass().getDeclaredField("accounts");
            accField.setAccessible(true);
            Object accountService = accField.get(null); // static field
            Class<?> playerDtoCls = cs.getClass().getClassLoader()
                    .loadClass("io.paradaux.chestshop.players.PlayerDTO");
            java.lang.reflect.Constructor<?> dtoCtor = playerDtoCls.getConstructor(org.bukkit.entity.Player.class);
            Object dto = dtoCtor.newInstance(p);
            java.lang.reflect.Method store = accountService.getClass().getMethod("storeUsername", playerDtoCls);
            Object result = store.invoke(accountService, dto);
            sender.sendMessage("storeUsername(" + p.getName() + ") = " + (result == null ? "null" : result.toString()));
        } catch (Throwable t) {
            sender.sendMessage("warmname failed: " + t);
        }
        return true;
    }

    // ---------------------------------------------------------------- inventory / container helpers
    //
    // Each "count" command emits a single line "count=N" so RCON callers can
    // grep deterministically. Material strings are matched via Material.valueOf
    // — pass uppercase-with-underscores names ("OAK_LOG", "DIRT", etc).

    private boolean doChestCount(CommandSender sender, String[] args) {
        if (args.length != 6) {
            sender.sendMessage("usage: /diag chestcount <world> <x> <y> <z> <material>");
            return true;
        }
        World w = Bukkit.getWorld(args[1]);
        if (w == null) { sender.sendMessage("world not found: " + args[1]); return true; }
        int x, y, z;
        try {
            x = Integer.parseInt(args[2]); y = Integer.parseInt(args[3]); z = Integer.parseInt(args[4]);
        } catch (NumberFormatException e) { sender.sendMessage("coords must be ints"); return true; }
        Material mat;
        try {
            mat = Material.valueOf(args[5].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) { sender.sendMessage("unknown material: " + args[5]); return true; }

        Block block = w.getBlockAt(x, y, z);
        if (!(block.getState() instanceof Container container)) {
            sender.sendMessage("not a container at that pos: " + block.getType());
            return true;
        }
        sender.sendMessage("count=" + countMaterial(container.getInventory(), mat));
        return true;
    }

    private boolean doInvCount(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sender.sendMessage("usage: /diag invcount <player> <material>");
            return true;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) { sender.sendMessage("player not found: " + args[1]); return true; }
        Material mat;
        try {
            mat = Material.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) { sender.sendMessage("unknown material: " + args[2]); return true; }
        sender.sendMessage("count=" + countMaterial(p.getInventory(), mat));
        return true;
    }

    private boolean doInvGive(CommandSender sender, String[] args) {
        if (args.length != 4) {
            sender.sendMessage("usage: /diag invgive <player> <material> <count>");
            return true;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) { sender.sendMessage("player not found: " + args[1]); return true; }
        Material mat;
        int count;
        try {
            mat = Material.valueOf(args[2].toUpperCase(Locale.ROOT));
            count = Integer.parseInt(args[3]);
        } catch (Exception e) { sender.sendMessage("bad args"); return true; }
        p.getInventory().addItem(new ItemStack(mat, count));
        sender.sendMessage("gave " + count + " " + mat + " to " + p.getName());
        return true;
    }

    private boolean doInvClear(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("usage: /diag invclear <player>");
            return true;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) { sender.sendMessage("player not found: " + args[1]); return true; }
        p.getInventory().clear();
        sender.sendMessage("cleared inventory of " + p.getName());
        return true;
    }

    /**
     * Fill every slot of the player's main inventory (36 slots) with stacks of
     * cobblestone so a subsequent buy attempt fails with NOT_ENOUGH_SPACE.
     */
    private boolean doInvFill(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("usage: /diag invfill <player>");
            return true;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) { sender.sendMessage("player not found: " + args[1]); return true; }
        Inventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            inv.setItem(i, new ItemStack(Material.COBBLESTONE, 64));
        }
        sender.sendMessage("filled inventory of " + p.getName());
        return true;
    }

    private static int countMaterial(Inventory inv, Material mat) {
        int total = 0;
        for (ItemStack stack : inv.getContents()) {
            if (stack != null && stack.getType() == mat) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /**
     * Mutates ChestShop's static {@code Properties.TAX_AMOUNT} field at
     * runtime so tests can drive the new Treasury-routed sales-tax flow
     * without writing a config file + reloading the plugin. Loads
     * Properties via ChestShop's plugin classloader (DcDiag's loader
     * doesn't see Acrobot classes by default).
     */
    private boolean doChestShopTax(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("usage: /diag chestshoptax <percentRate>");
            return true;
        }
        double rate;
        try {
            rate = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage("rate must be numeric");
            return true;
        }
        org.bukkit.plugin.Plugin cs = Bukkit.getPluginManager().getPlugin("ChestShop");
        if (cs == null) {
            sender.sendMessage("ChestShop not loaded");
            return true;
        }
        try {
            // taxAmount/serverTaxAmount are a PERCENTAGE (10 == 10%, /100 in resolveTaxRate).
            Object cfg = chestShopConfig(cs);
            setDoubleField(cfg, "taxAmount", rate);
            setDoubleField(cfg, "serverTaxAmount", rate);
            sender.sendMessage("ChestShopConfiguration taxAmount/serverTaxAmount = " + rate);
        } catch (Throwable t) {
            sender.sendMessage("failed to set tax: " + t);
        }
        return true;
    }

    /**
     * Set any ChestShopConfiguration field live: {@code /diag config <fieldName> <value>}.
     * The field is the camelCase Java name (e.g. {@code allowFreeShops},
     * {@code forceUnlimitedAdminShop}, {@code allowPartialTransactions}), not the
     * UPPER_SNAKE config key. Type is inferred from the field (boolean/int/double/String).
     */
    private boolean doConfig(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sender.sendMessage("usage: /diag config <fieldName> <value>");
            return true;
        }
        org.bukkit.plugin.Plugin cs = Bukkit.getPluginManager().getPlugin("ChestShop");
        if (cs == null) { sender.sendMessage("ChestShop not loaded"); return true; }
        try {
            Object cfg = chestShopConfig(cs);
            java.lang.reflect.Field f = cfg.getClass().getDeclaredField(args[1]);
            f.setAccessible(true);
            Class<?> t = f.getType();
            String v = args[2];
            if (t == boolean.class || t == Boolean.class) f.setBoolean(cfg, Boolean.parseBoolean(v));
            else if (t == double.class || t == Double.class) f.setDouble(cfg, Double.parseDouble(v));
            else if (t == int.class || t == Integer.class) f.setInt(cfg, Integer.parseInt(v));
            else if (t == long.class || t == Long.class) f.setLong(cfg, Long.parseLong(v));
            else if (t == String.class) f.set(cfg, v);
            else { sender.sendMessage("unsupported field type: " + t); return true; }
            sender.sendMessage("ChestShopConfiguration." + args[1] + " = " + v);
        } catch (Throwable ex) {
            sender.sendMessage("config set failed: " + ex);
        }
        return true;
    }

    /** Read a ChestShopConfiguration field from the Guice-bound instance: {@code /diag configget <field>}. */
    private boolean doConfigGet(CommandSender sender, String[] args) {
        if (args.length != 2) { sender.sendMessage("usage: /diag configget <fieldName>"); return true; }
        org.bukkit.plugin.Plugin cs = Bukkit.getPluginManager().getPlugin("ChestShop");
        if (cs == null) { sender.sendMessage("ChestShop not loaded"); return true; }
        try {
            Object cfg = chestShopConfig(cs);
            java.lang.reflect.Field f = cfg.getClass().getDeclaredField(args[1]);
            f.setAccessible(true);
            sender.sendMessage("ChestShopConfiguration." + args[1] + " = " + f.get(cfg)
                    + " cfgHash=" + System.identityHashCode(cfg));
        } catch (Throwable ex) {
            sender.sendMessage("config get failed: " + ex);
        }
        return true;
    }

    private static void setDoubleField(Object target, String name, double value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.setDouble(target, value);
    }

    /**
     * The live, Guice-bound ChestShopConfiguration instance (what EconomyService and
     * the services hold). The framework rebuilds config into fresh instances on
     * reload and services keep a final injected ref, so mutate the Guice-bound one
     * — reached via ChestShop's injector — NOT getComponent(). getInstance is called
     * through the public relocated Injector interface (InjectorImpl isn't accessible
     * cross-plugin).
     */
    private Object chestShopConfig(org.bukkit.plugin.Plugin cs) throws Exception {
        java.lang.reflect.Field injField = cs.getClass().getDeclaredField("injector");
        injField.setAccessible(true);
        Object injector = injField.get(cs);
        ClassLoader csLoader = cs.getClass().getClassLoader();
        Class<?> cfgCls = csLoader.loadClass("io.paradaux.chestshop.model.config.ChestShopConfiguration");
        Class<?> injectorIface = csLoader.loadClass("io.paradaux.chestshop.Libs.guice.Injector");
        java.lang.reflect.Method getInstance = injectorIface.getMethod("getInstance", Class.class);
        getInstance.setAccessible(true);
        return getInstance.invoke(injector, cfgCls);
    }

    /** Test fixture: run the real weekly Bukkit task with an explicit due period. */
    private boolean doTaxSchedule(CommandSender sender, String[] args) {
        try {
            if (args.length != 2) throw new IllegalArgumentException("expected epoch seconds");
            var treasury = Bukkit.getPluginManager().getPlugin("Treasury");
            ClassLoader loader = treasury.getClass().getClassLoader();
            Object injector = treasury.getClass().getMethod("getInjector").invoke(treasury);
            var get = loader.loadClass("io.paradaux.libs.guice.Injector").getMethod("getInstance", Class.class);
            Class<?> api = loader.loadClass("io.paradaux.treasury.api.TaxApi");
            Class<?> registry = loader.loadClass("io.paradaux.treasury.services.TaxCycleRegistry");
            Class<?> webhook = loader.loadClass("io.paradaux.treasury.services.TaxWebhookService");
            Class<?> type = loader.loadClass("io.paradaux.treasury.tasks.TaxCycleTask");
            Object task = type.getMethod("weekly", org.bukkit.plugin.Plugin.class, api, registry, webhook, int.class, int.class)
                    .invoke(null, treasury, get.invoke(injector, api), get.invoke(injector, registry),
                            get.invoke(injector, webhook), 0, 1);
            var due = type.getDeclaredField("scheduledFire");
            due.setAccessible(true);
            due.set(task, java.time.Instant.ofEpochSecond(Long.parseLong(args[1])));
            org.bukkit.scheduler.BukkitTask scheduled = ((org.bukkit.scheduler.BukkitRunnable) task)
                    .runTaskLaterAsynchronously(treasury, 1);
            sender.sendMessage("task=" + scheduled.getTaskId());
        } catch (Exception error) {
            sender.sendMessage("taxschedule failed: " + error);
        }
        return true;
    }

    private boolean doTaxScheduledState(CommandSender sender, String[] args) {
        try {
            if (args.length != 2) throw new IllegalArgumentException("expected task id");
            int id = Integer.parseInt(args[1]);
            var treasury = Bukkit.getPluginManager().getPlugin("Treasury");
            ClassLoader loader = treasury.getClass().getClassLoader();
            Object injector = treasury.getClass().getMethod("getInjector").invoke(treasury);
            var get = loader.loadClass("io.paradaux.libs.guice.Injector").getMethod("getInstance", Class.class);
            Class<?> registry = loader.loadClass("io.paradaux.treasury.services.TaxCycleRegistry");
            Class<?> cycle = loader.loadClass("io.paradaux.treasury.model.tax.TaxCycleType");
            Object weekly = java.util.Arrays.stream(cycle.getEnumConstants()).filter(c -> c.toString().equals("WEEKLY")).findFirst().orElseThrow();
            var next = (java.util.Optional<?>) registry.getMethod("getNextFireTime", cycle)
                    .invoke(get.invoke(injector, registry), weekly);
            sender.sendMessage("queued=" + Bukkit.getScheduler().isQueued(id)
                    + " running=" + Bukkit.getScheduler().isCurrentlyRunning(id)
                    + " next=" + next.map(Object::toString).orElse("none"));
        } catch (Exception error) {
            sender.sendMessage("taxscheduledstate failed: " + error);
        }
        return true;
    }

    private boolean doBreak(CommandSender sender, String[] args) {
        // /diag break <player> <world> <x> <y> <z>
        if (args.length != 6) {
            sender.sendMessage("usage: /diag break <player> <world> <x> <y> <z>");
            return true;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) { sender.sendMessage("player not found: " + args[1]); return true; }
        World w = Bukkit.getWorld(args[2]);
        if (w == null) { sender.sendMessage("world not found: " + args[2]); return true; }
        int x, y, z;
        try {
            x = Integer.parseInt(args[3]); y = Integer.parseInt(args[4]); z = Integer.parseInt(args[5]);
        } catch (NumberFormatException e) {
            sender.sendMessage("coords must be ints"); return true;
        }
        Block block = w.getBlockAt(x, y, z);
        BlockBreakEvent event = new BlockBreakEvent(block, p);
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            block.setType(org.bukkit.Material.AIR);
        }
        sender.sendMessage(String.format("fired break for %s on %s@%d,%d,%d — cancelled=%s",
                p.getName(), block.getType(), x, y, z, event.isCancelled()));
        return true;
    }

    /**
     * Create a shop the real way: fire a SignChangeEvent through ChestShop's SignCreate
     * (the same thing the server does when a player finishes editing a sign), so the
     * full create flow runs — validation, WorldGuard/GriefPrevention canBuild, free-shop
     * rejection, creation fee, and shop recognition.
     * Usage: {@code /diag createshop <player> <world> <x> <y> <z> <l0>|<l1>|<l2>|<l3>}
     * (lines are pipe-joined so they can contain spaces, e.g. {@code Owner|5|100 B|DIRT}).
     */
    private boolean doCreateShop(CommandSender sender, String[] args) {
        if (args.length < 7) {
            sender.sendMessage("usage: /diag createshop <player> <world> <x> <y> <z> <l0>|<l1>|<l2>|<l3>");
            return true;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) { sender.sendMessage("player not found: " + args[1]); return true; }
        World w = Bukkit.getWorld(args[2]);
        if (w == null) { sender.sendMessage("world not found: " + args[2]); return true; }
        int x, y, z;
        try {
            x = Integer.parseInt(args[3]); y = Integer.parseInt(args[4]); z = Integer.parseInt(args[5]);
        } catch (NumberFormatException e) { sender.sendMessage("coords must be ints"); return true; }

        String joined = String.join(" ", Arrays.copyOfRange(args, 6, args.length));
        String[] lines = joined.split("\\|", -1);
        if (lines.length != 4) { sender.sendMessage("need exactly 4 pipe-joined lines, got " + lines.length); return true; }

        Block block = w.getBlockAt(x, y, z);
        org.bukkit.block.BlockState state = block.getState();
        if (!(state instanceof org.bukkit.block.Sign)) { sender.sendMessage("not a sign at that pos: " + block.getType()); return true; }

        // Seed the sign text so getLines() reflects the shop lines, then fire the create event.
        org.bukkit.block.Sign pre = (org.bukkit.block.Sign) state;
        for (int i = 0; i < 4; i++) { pre.setLine(i, lines[i]); }
        pre.update(true, false);

        SignChangeEvent event = new SignChangeEvent(block, p, lines.clone());
        Bukkit.getPluginManager().callEvent(event);
        boolean created = !event.isCancelled();
        if (created) {
            org.bukkit.block.BlockState post = block.getState();
            if (post instanceof org.bukkit.block.Sign) {
                org.bukkit.block.Sign s = (org.bukkit.block.Sign) post;
                for (int i = 0; i < 4; i++) { s.setLine(i, event.getLine(i)); }
                s.update(true, false);
            }
        }
        sender.sendMessage(String.format("createshop by=%s cancelled=%s resultType=%s",
                p.getName(), event.isCancelled(), block.getType()));
        return true;
    }

    /**
     * Create a WorldGuard cuboid region so ChestShop's WORLDGUARD_INTEGRATION creation
     * gate can be exercised (with WORLDGUARD_USE_FLAG off, creation is allowed only where
     * some region exists).
     * Usage: {@code /diag wgregion <world> <name> <x1> <y1> <z1> <x2> <y2> <z2>}
     */
    private boolean doWgRegion(CommandSender sender, String[] args) {
        if (args.length != 9) {
            sender.sendMessage("usage: /diag wgregion <world> <name> <x1> <y1> <z1> <x2> <y2> <z2>");
            return true;
        }
        World w = Bukkit.getWorld(args[1]);
        if (w == null) { sender.sendMessage("world not found: " + args[1]); return true; }
        try {
            int x1 = Integer.parseInt(args[3]), y1 = Integer.parseInt(args[4]), z1 = Integer.parseInt(args[5]);
            int x2 = Integer.parseInt(args[6]), y2 = Integer.parseInt(args[7]), z2 = Integer.parseInt(args[8]);
            com.sk89q.worldguard.protection.managers.RegionManager rm =
                    com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer()
                            .get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(w));
            if (rm == null) { sender.sendMessage("no WG region manager for world"); return true; }
            com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion region =
                    new com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion(args[2],
                            com.sk89q.worldedit.math.BlockVector3.at(x1, y1, z1),
                            com.sk89q.worldedit.math.BlockVector3.at(x2, y2, z2));
            // Let anyone build/access inside — otherwise the shop-creation chest-access
            // check (ShopService.checkChest → security.canAccess) denies a non-member creator
            // even though canBuild (region presence) passes.
            region.setFlag(com.sk89q.worldguard.protection.flags.Flags.BUILD,
                    com.sk89q.worldguard.protection.flags.StateFlag.State.ALLOW);
            region.setFlag(com.sk89q.worldguard.protection.flags.Flags.CHEST_ACCESS,
                    com.sk89q.worldguard.protection.flags.StateFlag.State.ALLOW);
            region.setFlag(com.sk89q.worldguard.protection.flags.Flags.USE,
                    com.sk89q.worldguard.protection.flags.StateFlag.State.ALLOW);
            rm.addRegion(region);
            sender.sendMessage("wgregion created " + args[2] + " (regions now " + rm.size() + ")");
        } catch (Throwable t) {
            sender.sendMessage("wgregion failed: " + t);
        }
        return true;
    }

    /**
     * Create a GriefPrevention claim so ChestShop's GRIEFPREVENTION_INTEGRATION creation
     * gate can be exercised (creation allowed only inside a claim).
     * Usage: {@code /diag gpclaim <world> <x1> <z1> <x2> <z2> <ownerName|ADMIN>}
     */
    private boolean doGpClaim(CommandSender sender, String[] args) {
        if (args.length != 7) {
            sender.sendMessage("usage: /diag gpclaim <world> <x1> <z1> <x2> <z2> <ownerName|ADMIN>");
            return true;
        }
        World w = Bukkit.getWorld(args[1]);
        if (w == null) { sender.sendMessage("world not found: " + args[1]); return true; }
        try {
            int x1 = Integer.parseInt(args[2]), z1 = Integer.parseInt(args[3]);
            int x2 = Integer.parseInt(args[4]), z2 = Integer.parseInt(args[5]);
            UUID owner = null; // ADMIN claim
            if (!"ADMIN".equalsIgnoreCase(args[6])) {
                Player o = Bukkit.getPlayerExact(args[6]);
                if (o == null) { sender.sendMessage("owner not online: " + args[6]); return true; }
                owner = o.getUniqueId();
            }
            me.ryanhamshire.GriefPrevention.CreateClaimResult r =
                    me.ryanhamshire.GriefPrevention.GriefPrevention.instance.dataStore.createClaim(
                            w, x1, x2, w.getMinHeight(), w.getMaxHeight(), z1, z2,
                            owner, null, null, null);
            sender.sendMessage("gpclaim succeeded=" + r.succeeded
                    + (r.claim != null ? " id=" + r.claim.getID() : ""));
        } catch (Throwable t) {
            sender.sendMessage("gpclaim failed: " + t);
        }
        return true;
    }

    /**
     * Remove every WorldGuard region and GriefPrevention claim in a world so the
     * protection-integration tests start from a clean slate (both persist to disk and
     * would otherwise accumulate/overlap across runs at the fixed fixture coordinates).
     * Usage: {@code /diag protclear <world>}
     */
    private boolean doProtClear(CommandSender sender, String[] args) {
        if (args.length != 2) { sender.sendMessage("usage: /diag protclear <world>"); return true; }
        World w = Bukkit.getWorld(args[1]);
        if (w == null) { sender.sendMessage("world not found: " + args[1]); return true; }
        int regions = 0, claims = 0;
        try {
            com.sk89q.worldguard.protection.managers.RegionManager rm =
                    com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer()
                            .get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(w));
            if (rm != null) {
                for (String id : new java.util.ArrayList<>(rm.getRegions().keySet())) {
                    if (!"__global__".equals(id)) { rm.removeRegion(id); regions++; }
                }
            }
        } catch (Throwable t) { sender.sendMessage("wg clear failed: " + t); }
        try {
            me.ryanhamshire.GriefPrevention.DataStore ds =
                    me.ryanhamshire.GriefPrevention.GriefPrevention.instance.dataStore;
            for (me.ryanhamshire.GriefPrevention.Claim c : new java.util.ArrayList<>(ds.getClaims())) {
                ds.deleteClaim(c);
                claims++;
            }
        } catch (Throwable t) { sender.sendMessage("gp clear failed: " + t); }
        sender.sendMessage("protclear removed regions=" + regions + " claims=" + claims);
        return true;
    }

    // ---------------------------------------------------------------- barrier helpers
    //
    // Support for the suite's positive barriers (tests/.../support/Barrier.java, Perms.java).
    // RCON runs these on the main thread, so each answer is a consistent server-side read.

    /** {@code /diag interactions <player>} → {@code interactions=N} (PlayerInteractEvents seen). */
    private boolean doInteractions(CommandSender sender, String[] args) {
        if (args.length != 2) { sender.sendMessage("usage: /diag interactions <player>"); return true; }
        java.util.concurrent.atomic.AtomicLong n = interactions.get(args[1].toLowerCase(Locale.ROOT));
        sender.sendMessage("interactions=" + (n == null ? 0 : n.get()));
        return true;
    }

    /**
     * {@code /diag perm <player> <node>} → {@code has=<bool> set=<bool>} — exactly what a
     * command's permission check reads ({@link Player#hasPermission}), plus whether the node
     * is explicitly set. LuckPerms applies {@code lp ... permission set} on its own thread;
     * polling this is how a test knows the grant is live. {@code offline} if not online.
     */
    private boolean doPerm(CommandSender sender, String[] args) {
        if (args.length != 3) { sender.sendMessage("usage: /diag perm <player> <node>"); return true; }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) { sender.sendMessage("offline"); return true; }
        sender.sendMessage("has=" + p.hasPermission(args[2]) + " set=" + p.isPermissionSet(args[2]));
        return true;
    }

    /**
     * {@code /diag asyncidle <plugin>...} → {@code idle}, or {@code busy <task>...} while any
     * named plugin has a one-shot scheduler task (sync or async) that is queued to run
     * within {@link #ASYNC_IDLE_HORIZON_TICKS} or is still running.
     *
     * <p>Why that observes "the plugin's command handlers have finished": Hibernia's
     * {@code @Async} routes call {@code BukkitScheduler.runTaskAsynchronously} from the main
     * thread. CraftScheduler keeps such a task visible to {@code getPendingTasks()} from the
     * moment it is submitted (the pending list, then the {@code runners} map) until
     * {@code CraftAsyncTask.run} removes it from {@code runners} <em>after</em> the body
     * returned. Repeating timers (period &gt; 0) are excluded — they are never "done".
     */
    private boolean doAsyncIdle(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("usage: /diag asyncidle <plugin>..."); return true; }
        java.util.Set<String> owners = new java.util.HashSet<>();
        for (int i = 1; i < args.length; i++) owners.add(args[i].toLowerCase(Locale.ROOT));
        try {
            Object scheduler = Bukkit.getScheduler();
            int syncTick = (int) field(scheduler, "currentTick");
            Object asyncScheduler = field(scheduler, "asyncScheduler");
            int asyncTick = asyncScheduler == null ? syncTick : (int) field(asyncScheduler, "currentTick");
            java.util.List<String> busy = new java.util.ArrayList<>();
            for (org.bukkit.scheduler.BukkitTask t : Bukkit.getScheduler().getPendingTasks()) {
                if (!owners.contains(t.getOwner().getName().toLowerCase(Locale.ROOT))) continue;
                long period = (long) call(t, "getPeriod");
                if (period != -1L) continue; // repeating (>0) or cancelled/finished (<-1)
                long ahead = (long) call(t, "getNextRun") - (t.isSync() ? syncTick : asyncTick);
                if (ahead > ASYNC_IDLE_HORIZON_TICKS) continue; // parked far ahead, not in flight
                busy.add(t.getOwner().getName() + "#" + t.getTaskId()
                        + (t.isSync() ? "(sync," : "(async,") + "in " + ahead + " ticks)");
            }
            sender.sendMessage(busy.isEmpty() ? "idle" : "busy " + String.join(" ", busy));
        } catch (Throwable t) {
            sender.sendMessage("asyncidle failed: " + t);
        }
        return true;
    }

    /** The stub provider, once registered — kept so a second call doesn't register twice. */
    private Object ingestStub;

    /**
     * {@code /diag ingeststub <source>} → registers (once) a Treasury {@code IngestApi}
     * provider for {@code source} that ingests nothing and reports {@code scanned=0}. It
     * stands in for the standalone TreasuryIngest plugin (not part of this repo or the
     * integration stack) so {@code /treasury admin ingest}'s dispatch-to-provider branch can
     * be exercised. Built as a {@link java.lang.reflect.Proxy} over the interface loaded from
     * Treasury's class loader, so DcDiag needs no compile-time Treasury dependency.
     */
    private boolean doIngestStub(CommandSender sender, String[] args) {
        if (args.length != 2) { sender.sendMessage("usage: /diag ingeststub <source>"); return true; }
        if (ingestStub != null) { sender.sendMessage("ingeststub already registered"); return true; }
        String source = args[1].toLowerCase(Locale.ROOT);
        try {
            org.bukkit.plugin.Plugin treasury = Bukkit.getPluginManager().getPlugin("Treasury");
            if (treasury == null) { sender.sendMessage("Treasury not loaded"); return true; }
            ClassLoader cl = treasury.getClass().getClassLoader();
            Class<?> apiType = cl.loadClass("io.paradaux.treasury.api.ingest.IngestApi");
            Class<?> reportType = cl.loadClass("io.paradaux.treasury.api.ingest.IngestReport");
            java.lang.reflect.Constructor<?> report = reportType.getConstructors()[0];
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[] {apiType}, (self, m, a) ->
                    switch (m.getName()) {
                        case "supportedSources" -> java.util.Set.of(source);
                        case "ingest" -> report.newInstance(a[0], 0, 0, 0, 0, java.math.BigDecimal.ZERO, 0L);
                        case "toString" -> "DcDiag ingest stub for " + source;
                        case "hashCode" -> System.identityHashCode(self);
                        case "equals" -> self == a[0];
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
            registerService(apiType, proxy);
            ingestStub = proxy;
            sender.sendMessage("ingeststub registered for " + source);
        } catch (Throwable t) {
            sender.sendMessage("ingeststub failed: " + t);
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerService(Class<?> type, Object provider) {
        Bukkit.getServicesManager().register((Class) type, provider, this, org.bukkit.plugin.ServicePriority.Lowest);
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(name + " on " + target.getClass().getName());
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchMethodException(name + " on " + target.getClass().getName());
    }
}
