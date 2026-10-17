package net.democracycraft.inttests.support;

import io.paradaux.mockclient.BlockFace;
import io.paradaux.mockclient.BlockPos;
import io.paradaux.mockclient.MockPlayer;
import io.paradaux.mockclient.Position;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-shot fixture for an end-to-end ChestShop test: places a stocked chest
 * with a properly-formatted shop sign on top via RCON, then air-fills the
 * area on close.
 *
 * <p>Why RCON setblock instead of having a mock player place the blocks:
 * ChestShop reads sign text fresh from the block on every right-click
 * (PlayerInteract.java:88, 186-188 in the vendored fork), so a sign
 * created via {@code setblock} is indistinguishable from one created via
 * {@code SignChangeEvent} at trade time. That sidesteps the need to put a
 * sign in slot 0, sneak, place it on the chest top without opening the GUI,
 * and respond to the sign-editor packet.
 *
 * <p>Layout: chest at {@code (x, y, z)}, standing sign on top at
 * {@code (x, y+1, z)}. ChestShop's {@code findConnectedContainer} accepts a
 * sign with a chest on its DOWN face, so this is enough.
 *
 * <p>Coordinates auto-advance per fixture so back-to-back tests (or
 * concurrent ones) don't collide. Y is fixed at {@code Y} (well above any
 * default-world terrain); X advances by 4 blocks per fixture.
 *
 * <pre>{@code
 * try (ChestShopFixture shop = ChestShopFixture.create()) {
 *     shop.setupSellShop("Alice", 5, 100, "DIRT");
 *     buyer.useBlock(shop.signPos(), BlockFace.NORTH);
 *     ...
 * }
 * }</pre>
 */
public final class ChestShopFixture implements AutoCloseable {

    private static final int Y = 200;
    private static final int Z = 100;
    private static final int X_BASE = 100;
    private static final int X_STRIDE = 4;
    private static final AtomicInteger COUNTER = new AtomicInteger(0);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** When each player's last trade click was handled (test clock; see {@link #awaitInteractionInterval}). */
    private static final java.util.Map<String, Long> LAST_CLICK_HANDLED = new java.util.concurrent.ConcurrentHashMap<>();
    /** Slack for millisecond-clock granularity between the server's and our timestamps. */
    private static final long CLOCK_MARGIN_MS = 20;
    private static volatile Long interactionIntervalMs;

    private final BlockPos chestPos;
    private final BlockPos signPos;
    private final int chunkBlockX;
    private final int chunkBlockZ;

    private ChestShopFixture(BlockPos chestPos, BlockPos signPos) {
        this.chestPos = chestPos;
        this.signPos = signPos;
        this.chunkBlockX = chestPos.x();
        this.chunkBlockZ = chestPos.z();
    }

    public static ChestShopFixture create() {
        int x = X_BASE + COUNTER.getAndIncrement() * X_STRIDE;
        BlockPos chest = BlockPos.of(x, Y, Z);
        BlockPos sign = BlockPos.of(x, Y + 1, Z);
        ChestShopFixture fixture = new ChestShopFixture(chest, sign);
        // Forceload so subsequent setblock commands don't hit "position not
        // loaded" — the chunk has no players in it yet.
        RconClient.send("forceload add " + fixture.chunkBlockX + " " + fixture.chunkBlockZ);
        // Wipe any leftover blocks (e.g. from a crashed prior run).
        RconClient.send(String.format("fill %d %d %d %d %d %d minecraft:air",
                chest.x(), chest.y(), chest.z(), sign.x(), sign.y(), sign.z()));
        return fixture;
    }

    /**
     * Place a stocked chest with a "client-buys" shop sign on top — i.e. the
     * sign's price line is {@code "<price> B"}, so a buyer's right-click
     * triggers a buy. ChestShop's convention is sign-relative-to-client: the
     * "B" indicator means the clicker pays this much to take items, "S" means
     * the clicker receives this much for handing items over.
     *
     * @param ownerSignName line 0 — a player username (e.g. {@code "Alice"})
     *                      or {@code "B:<base36-account-id>"} for a firm.
     * @param qty           items per transaction (sign line 1)
     * @param buyPrice      price the buyer pays per transaction (sign line 2 →
     *                      {@code "<buyPrice> B"})
     * @param material      Minecraft material id without prefix (e.g.
     *                      {@code "dirt"}). Sign line 3 is the upper-cased form
     *                      ({@code "DIRT"}); the chest is stocked with the
     *                      lower-cased form.
     */
    public void setupSellShop(String ownerSignName, int qty, int buyPrice, String material) {
        String materialLower = material.toLowerCase();
        String materialUpper = material.toUpperCase();

        // Stone platform so the buyer (teleported one block south of the chest)
        // has ground to stand on — without it they fall and the server can drop
        // the subsequent useBlock packet on a sanity check.
        RconClient.send(String.format("setblock %d %d %d minecraft:stone",
                chestPos.x(), chestPos.y() - 1, chestPos.z() + 1));

        // Chest, then stock the chest. Stock = qty * 12 so a handful of trades
        // can run before the chest empties, capped at one stack.
        int stock = Math.min(qty * 12, 64);
        RconClient.send(String.format("setblock %d %d %d minecraft:chest",
                chestPos.x(), chestPos.y(), chestPos.z()));
        RconClient.send(String.format(
                "item replace block %d %d %d container.0 with minecraft:%s %d",
                chestPos.x(), chestPos.y(), chestPos.z(), materialLower, stock));

        // Sign on top with the 4 ChestShop lines populated. In 1.21+ each
        // message is a plain string tag — not a JSON-wrapped string — so the
        // SNBT is messages:['Owner','5','100 S','DIRT']. Wrapping in extra
        // quotes (like `'"Owner"'`) leaves the literal quotes in the line text
        // and ChestShop's name regex rejects the sign as invalid.
        String signNbt = String.format(
                "{front_text:{messages:['%s','%d','%d B','%s']}}",
                ownerSignName, qty, buyPrice, materialUpper);
        RconClient.send(String.format("setblock %d %d %d minecraft:oak_sign%s",
                signPos.x(), signPos.y(), signPos.z(), signNbt));
    }

    /**
     * Place an empty chest with a "client-sells" shop sign on top — sign price
     * line is {@code "<price> S"}, so a clicker's left-click triggers a sell
     * to the shop. The chest starts empty, leaving room for the items the
     * client hands over. The owner of the sign gets debited the price; the
     * chest gains the items. ChestShop's convention is sign-relative-to-client:
     * "S" means the clicker receives this much for handing items over.
     *
     * @param ownerSignName line 0 — a player username or
     *                      {@code "B:<base36-account-id>"} for a firm.
     * @param qty           items per transaction
     * @param sellPrice     price the clicker receives per transaction (sign
     *                      line 2 → {@code "<sellPrice> S"})
     * @param material      Minecraft material id without prefix (e.g. {@code "dirt"})
     */
    public void setupBuyShop(String ownerSignName, int qty, int sellPrice, String material) {
        String materialUpper = material.toUpperCase();

        // Stone platform under the buyer-side standing position.
        RconClient.send(String.format("setblock %d %d %d minecraft:stone",
                chestPos.x(), chestPos.y() - 1, chestPos.z() + 1));

        // Empty chest — the client's items will fill it as trades fire.
        RconClient.send(String.format("setblock %d %d %d minecraft:chest",
                chestPos.x(), chestPos.y(), chestPos.z()));

        String signNbt = String.format(
                "{front_text:{messages:['%s','%d','%d S','%s']}}",
                ownerSignName, qty, sellPrice, materialUpper);
        RconClient.send(String.format("setblock %d %d %d minecraft:oak_sign%s",
                signPos.x(), signPos.y(), signPos.z(), signNbt));
    }

    /**
     * Place a stocked chest with a dual-price sign — line 2 is {@code "<buy> B <sell> S"},
     * so a right-click buys (pay {@code buyPrice}) and a left-click sells (receive
     * {@code sellPrice}) against the same shop.
     */
    public void setupBothShop(String ownerSignName, int qty, int buyPrice, int sellPrice, String material) {
        String materialLower = material.toLowerCase();
        String materialUpper = material.toUpperCase();

        RconClient.send(String.format("setblock %d %d %d minecraft:stone",
                chestPos.x(), chestPos.y() - 1, chestPos.z() + 1));

        int stock = Math.min(qty * 12, 64);
        RconClient.send(String.format("setblock %d %d %d minecraft:chest",
                chestPos.x(), chestPos.y(), chestPos.z()));
        RconClient.send(String.format(
                "item replace block %d %d %d container.0 with minecraft:%s %d",
                chestPos.x(), chestPos.y(), chestPos.z(), materialLower, stock));

        // ChestShop splits the price line on ':' — a dual-price line is "<buy> B : <sell> S".
        String signNbt = String.format(
                "{front_text:{messages:['%s','%d','%d B : %d S','%s']}}",
                ownerSignName, qty, buyPrice, sellPrice, materialUpper);
        RconClient.send(String.format("setblock %d %d %d minecraft:oak_sign%s",
                signPos.x(), signPos.y(), signPos.z(), signNbt));
    }

    /**
     * Fill every storage slot of the chest with a full stack of {@code material}
     * so nothing else fits — used to drive the "chest full" sell rejection.
     */
    public void fillChestWith(String material) {
        for (int slot = 0; slot < 27; slot++) {
            RconClient.send(String.format(
                    "item replace block %d %d %d container.%d with minecraft:%s 64",
                    chestPos.x(), chestPos.y(), chestPos.z(), slot, material.toLowerCase()));
        }
    }

    /**
     * Create a "buy" shop the REAL way: place a stocked chest + a blank sign, then
     * fire ChestShop's SignCreate via {@code diag createshop} as {@code creator}. This
     * runs the full create flow — validation, WorldGuard/GriefPrevention canBuild,
     * free-shop rejection, creation fee — and registers a recognised shop (unlike the
     * {@code /setblock} setup, which hand-places a shop the create flow never saw).
     *
     * @return the diag response, which contains {@code cancelled=true} (creation denied,
     *         sign broken) or {@code cancelled=false} (created).
     */
    public String createSellShop(String creator, String signOwner, int qty, int price, String material) {
        String materialLower = material.toLowerCase();

        RconClient.send(String.format("setblock %d %d %d minecraft:stone",
                chestPos.x(), chestPos.y() - 1, chestPos.z() + 1));
        int stock = Math.min(qty * 12, 64);
        RconClient.send(String.format("setblock %d %d %d minecraft:chest",
                chestPos.x(), chestPos.y(), chestPos.z()));
        RconClient.send(String.format(
                "item replace block %d %d %d container.0 with minecraft:%s %d",
                chestPos.x(), chestPos.y(), chestPos.z(), materialLower, stock));
        // Blank sign — createshop seeds the lines and fires the create event.
        RconClient.send(String.format("setblock %d %d %d minecraft:oak_sign",
                signPos.x(), signPos.y(), signPos.z()));

        String lines = signOwner + "|" + qty + "|" + price + " B|" + material.toUpperCase();
        return RconClient.send("diag createshop " + creator + " world "
                + signPos.x() + " " + signPos.y() + " " + signPos.z() + " " + lines);
    }

    /**
     * Create a real shop for an arbitrary, verbatim item line (e.g. a {@code "nexo:<id>"}
     * custom item) — like {@link #createSellShop} but the item line is used as-is (not
     * upper-cased) and the chest is left EMPTY for the caller to stock (a custom item can't
     * be placed with {@code /item replace}). Returns the {@code diag createshop} response.
     */
    public String createSellShopRaw(String creator, String signOwner, int qty, int price, String itemLine) {
        RconClient.send(String.format("setblock %d %d %d minecraft:stone",
                chestPos.x(), chestPos.y() - 1, chestPos.z() + 1));
        RconClient.send(String.format("setblock %d %d %d minecraft:chest",
                chestPos.x(), chestPos.y(), chestPos.z()));
        RconClient.send(String.format("setblock %d %d %d minecraft:oak_sign",
                signPos.x(), signPos.y(), signPos.z()));
        String lines = signOwner + "|" + qty + "|" + price + " B|" + itemLine;
        return RconClient.send("diag createshop " + creator + " world "
                + signPos.x() + " " + signPos.y() + " " + signPos.z() + " " + lines);
    }

    /** Position to teleport players to so they're in interaction range of the sign. */
    public String teleportArgs() {
        // Stand 1 block south of the chest, facing north toward the sign.
        return (chestPos.x() + 0.5) + " " + chestPos.y() + " " + (chestPos.z() + 1.5) + " 180 0";
    }

    /**
     * Put a clicker in interaction range of the sign and settle. Switches them to
     * adventure mode first (ChestShop's CreativeModeIgnorer cancels trades in
     * creative).
     *
     * <p>Robust against teleport-sync flakiness under load. We <em>poll the
     * client's current known position</em> (updated by whatever position packets
     * it receives) rather than {@code awaitPositionNear}, which blocks for a
     * <em>new</em> packet and can miss the teleport-confirm if it lands between
     * the {@code tp} and the await registering. Each retry stages the player a few
     * blocks up first so the re-{@code tp} actually moves them (a re-tp to the same
     * spot emits no packet).
     *
     * <p>Returns once the server has handled the client's position update ({@link
     * MockPlayer#sync}), so a click sent next is judged from the shop position, and once
     * everything the server sent before that (gamemode/teleport chatter) has been drained.
     */
    public void positionClicker(MockPlayer client, String name) {
        RconClient.send("gamemode adventure " + name);
        Position target = new Position(
                chestPos.x() + 0.5, chestPos.y(), chestPos.z() + 1.5, 180f, 0f);
        Position staging = new Position(chestPos.x() + 0.5, chestPos.y() + 6, chestPos.z() + 0.5, 0f, 0f);
        boolean arrived = false;
        for (int attempt = 0; attempt < 6 && !arrived; attempt++) {
            if (attempt > 0) {
                RconClient.send(String.format("tp %s %.1f %d %.1f",
                        name, staging.x(), (int) staging.y(), staging.z()));
                reaches(client, staging, Duration.ofSeconds(3));
            }
            RconClient.send("tp " + name + " " + teleportArgs());
            arrived = reaches(client, target, Duration.ofSeconds(6));
        }
        if (!arrived) {
            throw new AssertionError("clicker " + name + " never reached the shop position " + target);
        }
        client.sendPosition(target);
        client.sync(TIMEOUT);
        client.drainMessages();
    }

    /** Whether the client's known position comes within 2 blocks of {@code target} in time. */
    private static boolean reaches(MockPlayer client, Position target, Duration timeout) {
        try {
            Await.untilTrue(client.getUsername() + " to reach " + target,
                    () -> client.getPosition().filter(p -> p.within(target, 2.0)).isPresent(), timeout);
            return true;
        } catch (AssertionError notYet) {
            return false;
        }
    }

    /**
     * Position the client, then right-click the sign — a buy on a {@code "B"} shop.
     *
     * <p>Returns once the click has been fully processed: the trade, if any, is complete
     * (see {@link #click}). Tests can assert balances and stock straight away.
     */
    public void clickBuy(MockPlayer client, String name) {
        positionClicker(client, name);
        click(client, name, () -> client.useBlock(signPos, BlockFace.SOUTH));
    }

    /** Position the client, then left-click the sign — a sell on an {@code "S"} shop. Same contract as {@link #clickBuy}. */
    public void clickSell(MockPlayer client, String name) {
        positionClicker(client, name);
        click(client, name, () -> client.leftClickBlock(signPos, BlockFace.SOUTH));
    }

    /**
     * Send a click and return once the server has processed it, having proved it was
     * processed as an interaction at all.
     *
     * <ul>
     *   <li>{@link MockPlayer#sync} after the click: the click and the barrier share
     *       Paper's FIFO packet queue, and ChestShop settles a trade synchronously inside
     *       its {@code PlayerInteractEvent} handler (goods and the Treasury transfer, see
     *       its {@code TransactionService}), so after the barrier the outcome is final.</li>
     *   <li>DcDiag's per-player {@code PlayerInteractEvent} counter (MONITOR, sees cancelled
     *       events too) must have moved. Without that, a "rejected trade changes nothing"
     *       test would pass if Paper silently dropped the packet (range, rate limit).</li>
     * </ul>
     *
     * A dropped click fired no event, so no plugin saw it and re-sending is safe; it is
     * retried a couple of times (re-positioning first) before failing.
     */
    private void click(MockPlayer client, String name, Runnable send) {
        for (int attempt = 1; ; attempt++) {
            awaitInteractionInterval(name);
            long before = interactions(name);
            send.run();
            client.sync(TIMEOUT);
            // The server handled the click no later than now (test and server share a clock).
            LAST_CLICK_HANDLED.put(name, System.currentTimeMillis());
            if (interactions(name) > before) return;
            if (attempt == 3) {
                throw new AssertionError("click by " + name + " on the sign at " + signPos
                        + " never reached Bukkit as a PlayerInteractEvent (" + attempt
                        + " attempts) — the server dropped it");
            }
            System.err.println("[ChestShopFixture] click by " + name + " was dropped server-side; "
                    + "re-positioning and retrying (attempt " + (attempt + 1) + ")");
            positionClicker(client, name);
        }
    }

    /**
     * Honour ChestShop's per-player trade throttle: a trade click less than
     * {@code SHOP_INTERACTION_INTERVAL} ms after that player's previous one is dropped
     * silently (PlayerInteractListener, ADT-44) — the event still fires, so the dropped-click
     * check can't see it. Wait out whatever is left of the interval since this player's last
     * click was handled. This is a product rule measured on the shared wall clock, not a
     * guess at server latency; back-to-back trades by one player pay at most the interval.
     */
    private static void awaitInteractionInterval(String name) {
        Long last = LAST_CLICK_HANDLED.get(name);
        if (last == null) return;
        long remaining = last + interactionIntervalMs() + CLOCK_MARGIN_MS - System.currentTimeMillis();
        if (remaining <= 0) return;
        try {
            Thread.sleep(remaining);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    /** ChestShop's live {@code SHOP_INTERACTION_INTERVAL}, read once from the server. */
    private static long interactionIntervalMs() {
        Long cached = interactionIntervalMs;
        if (cached != null) return cached;
        String reply = RconClient.send("diag configget shopInteractionInterval");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("= (\\d+)").matcher(reply);
        if (!m.find()) {
            throw new IllegalStateException("could not read ChestShop's shopInteractionInterval: " + reply);
        }
        interactionIntervalMs = Long.parseLong(m.group(1));
        return interactionIntervalMs;
    }

    /** PlayerInteractEvents DcDiag has seen for {@code name}. */
    private static long interactions(String name) {
        String reply = RconClient.send("diag interactions " + name).trim();
        if (!reply.startsWith("interactions=")) {
            throw new IllegalStateException("unexpected /diag interactions reply: " + reply);
        }
        return Long.parseLong(reply.substring("interactions=".length()));
    }

    public BlockPos chestPos() { return chestPos; }
    public BlockPos signPos() { return signPos; }

    @Override
    public void close() {
        // Air-fill placed blocks (stone platform too); release the forceload.
        RconClient.send(String.format("fill %d %d %d %d %d %d minecraft:air",
                chestPos.x(), chestPos.y() - 1, chestPos.z(), signPos.x(), signPos.y(), signPos.z() + 1));
        RconClient.send("forceload remove " + chunkBlockX + " " + chunkBlockZ);
    }
}
