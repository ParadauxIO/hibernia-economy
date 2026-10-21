package io.paradaux.mockclient;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus;
import org.geysermc.mcprotocollib.protocol.data.game.setting.SkinPart;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundAwardStatsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundSignUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * A minimal Minecraft Java-edition client that connects in offline mode and
 * lets a test send commands, observe chat, place blocks, edit signs, and wait
 * on arbitrary clientbound packets.
 *
 * <p>Designed for end-to-end integration testing of Paper plugins. Not a full
 * client — no rendering, no chunk handling, no inventory tracking. Only the
 * protocol surface needed to drive plugin commands and capture their output.
 *
 * <p>Usage:
 * <pre>{@code
 * try (MockPlayer alice = MockPlayer.connectLocal("Alice")) {
 *     alice.chatCommand("/business firm create Acme");
 *     alice.awaitMessageContaining("created", Duration.ofSeconds(2));
 *     Position pos = alice.getPosition().orElseThrow();
 *     alice.useBlock(BlockPos.of((int) pos.x(), (int) pos.y() - 1, (int) pos.z()), BlockFace.UP);
 * }
 * }</pre>
 *
 * <p>Each connection runs on a Netty event-loop owned by MCProtocolLib;
 * {@link #close()} disconnects it cleanly.
 */
public final class MockPlayer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MockPlayer.class);
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Duration DEFAULT_LOGIN_TIMEOUT = Duration.ofSeconds(15);

    /**
     * Mojang's username constraint: 3–16 chars of [A-Za-z0-9_]. Paper enforces
     * this even in offline mode and silently disconnects mid-handshake on
     * violation, which makes for a confusing test failure — fail fast here.
     */
    private static final Pattern VALID_USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private final String username;
    private final ClientNetworkSession session;
    private final BlockingQueue<String> messages = new LinkedBlockingDeque<>();
    private final CountDownLatch playReady = new CountDownLatch(1);
    private final CountDownLatch positionReceived = new CountDownLatch(1);
    private final List<PendingAwait<?>> awaiting = new CopyOnWriteArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger(0);

    /**
     * {@link #sync} round-trip accounting, guarded by {@code syncLock}: requests sent and
     * responses received. The server answers stats requests one-for-one and in order, so
     * the n-th response answers the n-th request.
     */
    private final Object syncLock = new Object();
    private long syncRequested;
    private long syncAnswered;

    /** Every player between a successful {@link #connect} and {@link #close}/disconnect. */
    private static final Set<MockPlayer> OPEN = ConcurrentHashMap.newKeySet();

    private volatile Position currentPosition;
    private volatile boolean connected;
    private volatile String disconnectReason;
    private volatile boolean playerLoadedSent;
    private volatile java.util.concurrent.ScheduledFuture<?> heartbeat;
    private static final java.util.concurrent.ScheduledExecutorService HEARTBEAT_POOL =
            java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "mock-player-heartbeat");
                t.setDaemon(true);
                return t;
            });

    private MockPlayer(String username, ClientNetworkSession session) {
        this.username = username;
        this.session = session;
    }

    // ------------------------------------------------------------------ connect

    /**
     * Connect a mock player to the local dev server. Defaults to
     * {@code 127.0.0.1:25565}, overridable via the {@code mockclient.host} /
     * {@code mockclient.port} system properties so a run can target a server on a
     * non-default port without touching the tests.
     */
    public static MockPlayer connectLocal(String username) {
        String host = System.getProperty("mockclient.host", "127.0.0.1");
        int port = Integer.getInteger("mockclient.port", 25565);
        return connect(username, host, port, DEFAULT_LOGIN_TIMEOUT);
    }

    /**
     * Connect a mock player and wait up to {@code loginTimeout} for the server
     * to drop the client into the play state. Blocks until either the
     * play-state Login packet arrives (success) or the timeout elapses.
     *
     * @throws IllegalStateException if the connection fails or the play state
     *                               is not reached in time
     */
    public static MockPlayer connect(String username, String host, int port, Duration loginTimeout) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(loginTimeout, "loginTimeout");
        if (!VALID_USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException(
                    "Invalid username '" + username + "': must be 3-16 chars of [A-Za-z0-9_] "
                            + "(Mojang/Paper constraint, enforced even in offline mode)");
        }

        MinecraftProtocol protocol = new MinecraftProtocol(username);
        ClientNetworkSession session = (ClientNetworkSession) ClientNetworkSessionFactory.factory()
                .setRemoteSocketAddress(new InetSocketAddress(host, port))
                .setProtocol(protocol)
                .create();

        // Auto-respond to keepalive PINGs. The default ClientListener
        // (installed by MinecraftProtocol because useDefaultListeners=true)
        // already handles login + the configuration-phase acks
        // (SelectKnownPacks, FinishConfiguration). Don't shadow those —
        // duplicate acks confuse Paper's task state machine.
        session.setFlag(MinecraftConstants.AUTOMATIC_KEEP_ALIVE_MANAGEMENT, true);

        MockPlayer player = new MockPlayer(username, session);
        session.addListener(player.new SessionListener());

        session.connect(true);

        try {
            if (!player.playReady.await(loginTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                session.disconnect("login timeout");
                throw new IllegalStateException(
                        "Did not enter play state for " + username + " on " + host + ":" + port
                                + " within " + loginTimeout
                                + " (last seen connected=" + session.isConnected() + ")");
            }
            // Also wait for the first position packet (Paper sends it
            // immediately after Login) so currentPosition is populated by
            // the time connect() returns. Without this, tests racing
            // connect() can see a null position.
            if (!player.positionReceived.await(2_000, TimeUnit.MILLISECONDS)) {
                LOG.warn("[{}] no initial position packet within 2s — currentPosition may be null", username);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.disconnect("interrupted");
            throw new IllegalStateException("Interrupted during login", e);
        }

        // Disconnect also releases the latches. Their release alone is not a
        // successful login (e.g. whitelist rejection or a plugin login cooldown).
        if (player.disconnectReason != null || !session.isConnected() || player.currentPosition == null) {
            session.disconnect("login did not complete");
            throw new IllegalStateException("Login failed for " + username + ": "
                    + (player.disconnectReason == null ? "no initial player position" : player.disconnectReason));
        }
        player.connected = true;
        OPEN.add(player);
        // Periodic position heartbeat — vanilla clients send a movement packet
        // every tick (50 ms). 200 ms is plenty for tests and keeps the player
        // out of Paper's idle-kick path.
        player.heartbeat = HEARTBEAT_POOL.scheduleAtFixedRate(() -> {
            Position pos = player.currentPosition;
            if (pos != null && session.isConnected()) {
                try {
                    session.send(new ServerboundMovePlayerPosRotPacket(
                            true, false,
                            pos.x(), pos.y(), pos.z(),
                            pos.yaw(), pos.pitch()));
                } catch (Throwable ignored) {}
            }
        }, 200, 200, TimeUnit.MILLISECONDS);
        LOG.info("Mock player {} connected to {}:{} (in play state)", username, host, port);
        return player;
    }

    // ------------------------------------------------------------------ chat

    /**
     * Send a slash-command to the server. The leading slash is optional — both
     * {@code chatCommand("/eco balance")} and {@code chatCommand("eco balance")}
     * work.
     */
    public void chatCommand(String command) {
        Objects.requireNonNull(command, "command");
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        session.send(new ServerboundChatCommandPacket(cmd));
        LOG.debug("[{}] sent /{}", username, cmd);
    }

    /**
     * Send a regular (non-command) chat message. The signature is empty — this
     * relies on the server being in offline mode (or otherwise tolerating
     * unsigned chat).
     */
    public void chat(String message) {
        Objects.requireNonNull(message, "message");
        long now = System.currentTimeMillis();
        session.send(new ServerboundChatPacket(
                message,
                now,        // timeStamp
                0L,         // salt
                new byte[0],// signature
                0,          // offset
                new BitSet(),// acknowledgedMessages
                0           // checksum
        ));
        LOG.debug("[{}] said: {}", username, message);
    }

    /**
     * Block until a chat message matching {@code matcher} arrives, or
     * {@code timeout} elapses. Messages received before this call are still
     * eligible — call {@link #drainMessages()} first if you only want
     * post-call output.
     *
     * @return the matching message text (Adventure components flattened to
     *         plain text)
     * @throws AssertionError if no matching message arrives within the timeout
     */
    public String awaitMessage(Predicate<String> matcher, Duration timeout) {
        Objects.requireNonNull(matcher, "matcher");
        Objects.requireNonNull(timeout, "timeout");

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        for (String existing : messages) {
            if (matcher.test(existing)) {
                messages.remove(existing);
                return existing;
            }
        }
        // Preserve unrelated replies, including those arriving while blocked.
        // Async command responses may arrive in either order.
        java.util.List<String> rejected = new java.util.ArrayList<>();
        try {
            while (System.nanoTime() < deadlineNanos) {
                long remainingMs = Math.max(1, (deadlineNanos - System.nanoTime()) / 1_000_000);
                String msg = messages.poll(remainingMs, TimeUnit.MILLISECONDS);
                if (msg == null) break;
                if (matcher.test(msg)) return msg;
                rejected.add(msg);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for chat message", e);
        } finally {
            messages.addAll(rejected);
        }
        throw new AssertionError(
                "Timed out after " + timeout + " waiting for chat message matching predicate. "
                        + "Received but not matching: " + rejected);
    }

    /** Convenience: wait for a message containing the given substring. */
    public String awaitMessageContaining(String substring, Duration timeout) {
        return awaitMessage(m -> m.contains(substring), timeout);
    }

    /** Snapshot buffered replies; use a processing barrier before negative assertions. */
    public java.util.List<String> collectedMessages() {
        return java.util.List.copyOf(messages);
    }

    /** Discard any chat messages received so far. */
    public void drainMessages() {
        messages.clear();
    }

    /**
     * Run {@code trigger} (typically a {@link #chatCommand}) and wait for a chat line that
     * arrives after it and matches {@code matcher}. Unlike {@link #awaitMessage},
     * preexisting buffered replies are not eligible. Everything else stays buffered
     * for later awaits, and only the matched line is removed.
     *
     * @return the matched line
     * @throws AssertionError on timeout
     */
    public String awaitMessageAfter(Runnable trigger, Predicate<String> matcher, Duration timeout) {
        Objects.requireNonNull(matcher, "matcher");
        ClientboundSystemChatPacket pkt = awaitClientboundAfter(trigger, ClientboundSystemChatPacket.class,
                p -> p.getContent() != null && matcher.test(PLAIN.serialize(p.getContent())), timeout);
        String text = PLAIN.serialize(pkt.getContent());
        // The listener buffers a line before it completes awaits, so it is there to remove.
        messages.remove(text);
        return text;
    }

    // ------------------------------------------------------------------ position

    /**
     * The most recently received player position, or empty if no position has
     * been received yet (rare — Paper sends one immediately after the play-state
     * Login packet).
     */
    public Optional<Position> getPosition() {
        return Optional.ofNullable(currentPosition);
    }

    /**
     * Block until the player's position satisfies {@code matcher}, or
     * {@code timeout} elapses. Useful after issuing a server-side teleport
     * (e.g., via RCON {@code /tp}) to confirm the client has registered the
     * new location.
     *
     * @throws AssertionError on timeout
     */
    public Position awaitPositionMatching(Predicate<Position> matcher, Duration timeout) {
        Objects.requireNonNull(matcher, "matcher");
        Objects.requireNonNull(timeout, "timeout");

        Position now = currentPosition;
        if (now != null && matcher.test(now)) return now;

        ClientboundPlayerPositionPacket p = awaitClientbound(
                ClientboundPlayerPositionPacket.class,
                pkt -> {
                    Vector3d v = pkt.getPosition();
                    Position candidate = new Position(v.getX(), v.getY(), v.getZ(), pkt.getYRot(), pkt.getXRot());
                    return matcher.test(candidate);
                },
                timeout);
        Vector3d v = p.getPosition();
        return new Position(v.getX(), v.getY(), v.getZ(), p.getYRot(), p.getXRot());
    }

    /** Convenience: wait for the player to be within {@code radius} blocks of {@code target}. */
    public Position awaitPositionNear(Position target, double radius, Duration timeout) {
        return awaitPositionMatching(p -> p.within(target, radius), timeout);
    }

    // ------------------------------------------------------------------ blocks / signs

    /** Submit a custom dialog action using the identifier and payload supplied by the server. */
    public void clickDialogAction(String id, org.cloudburstmc.nbt.NbtMap payload) {
        session.send(new org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomClickActionPacket(
                net.kyori.adventure.key.Key.key(id), payload));
    }

    /** Select a hotbar slot (0–8) before placing or using the held item. */
    public void selectHotbarSlot(int slot) {
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("Hotbar slot must be 0–8");
        session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket(slot));
    }

    /** Send the shift input, e.g. to place a sign on a chest instead of opening it. */
    public void setSneaking(boolean sneaking) {
        session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket(
                false, false, false, false, false, sneaking, false));
    }

    /**
     * Send a position+rotation update so the server's view of the player
     * matches {@code pos}. After a server-issued teleport, modern Paper
     * (1.21+) won't process block interactions from the client until at
     * least one client-driven position update has settled — useBlock
     * packets get dropped silently otherwise.
     */
    public void sendPosition(Position pos) {
        Objects.requireNonNull(pos, "pos");
        session.send(new ServerboundMovePlayerPosRotPacket(
                true,                  // onGround — assume true; tests park players on a setblock'd platform
                false,                 // horizontalCollision
                pos.x(), pos.y(), pos.z(),
                pos.yaw(), pos.pitch()));
        currentPosition = pos;
        LOG.debug("[{}] sent position {}", username, pos);
    }

    /**
     * Right-click on a block face (e.g., to interact with a sign, lever, or
     * place a block from the held item).
     *
     * <p>Cursor coordinates default to the face centre (0.5, 0.5, 0.5); item
     * goes from the main hand. The client sequence number auto-increments —
     * required by 1.21+ for client-side prediction acknowledgement.
     */
    public void useBlock(BlockPos pos, BlockFace face) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(face, "face");
        session.send(new ServerboundUseItemOnPacket(
                pos.toVector3i(),
                face.direction(),
                Hand.MAIN_HAND,
                0.5f, 0.5f, 0.5f,   // cursor (face centre)
                false,              // insideBlock
                false,              // hitWorldBorder
                sequence.incrementAndGet()));
        LOG.debug("[{}] use-block at {} face={}", username, pos, face);
    }

    /**
     * Left-click on a block. Server fires {@code PlayerInteractEvent} with
     * {@code LEFT_CLICK_BLOCK} action (the same handler ChestShop's "sell to
     * shop" path keys off when {@code REVERSE_BUTTONS=false}). Sends only
     * {@code START_DESTROY_BLOCK} — no follow-up {@code STOP_DESTROY_BLOCK} —
     * so the dig is never finalised and the block isn't broken even if the
     * downstream listener doesn't cancel the event.
     */
    public void leftClickBlock(BlockPos pos, BlockFace face) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(face, "face");
        session.send(new ServerboundPlayerActionPacket(
                PlayerAction.START_DIGGING, pos.toVector3i(), face.direction(),
                sequence.incrementAndGet()));
        LOG.debug("[{}] left-click at {} face={}", username, pos, face);
    }

    /**
     * Send a START_DIGGING + FINISH_DIGGING pair against a block, simulating an
     * instabreak. The server will validate digging time against the block
     * hardness and the player's tool — this works reliably only for blocks
     * the player can break instantly (creative mode, or blocks with hardness 0
     * like grass / signs).
     */
    public void breakBlock(BlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        Vector3i v = pos.toVector3i();
        int seq = sequence.incrementAndGet();
        session.send(new ServerboundPlayerActionPacket(
                PlayerAction.START_DIGGING, v, BlockFace.UP.direction(), seq));
        session.send(new ServerboundPlayerActionPacket(
                PlayerAction.FINISH_DIGGING, v, BlockFace.UP.direction(), seq));
        LOG.debug("[{}] break-block at {}", username, pos);
    }

    /** Shift-click using the server's current container/state IDs and explicit client slot predictions. */
    public void quickMoveContainerSlot(int containerId, int stateId, int slot,
            java.util.Map<Integer, org.geysermc.mcprotocollib.protocol.data.game.item.HashedStack> changedSlots) {
        session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket(
                containerId, stateId, slot,
                org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType.SHIFT_CLICK_ITEM,
                org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction.LEFT_CLICK,
                null, changedSlots));
    }

    /** Close an actual open container, triggering the server's normal InventoryCloseEvent. */
    public void closeContainer(int containerId) {
        session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket(containerId));
    }

    /**
     * Write text to a sign. The sign block must already exist at {@code pos}
     * — typically because the client just placed one via {@link #useBlock}.
     *
     * @param lines exactly 4 lines (front of sign). Pass empty strings for
     *              blank rows.
     * @param frontText {@code true} for front side, {@code false} for back
     *                  (1.20+ hanging signs).
     */
    public void updateSign(BlockPos pos, String[] lines, boolean frontText) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(lines, "lines");
        if (lines.length != 4) {
            throw new IllegalArgumentException(
                    "sign update requires exactly 4 lines, got " + lines.length);
        }
        session.send(new ServerboundSignUpdatePacket(pos.toVector3i(), lines, frontText));
        LOG.debug("[{}] sign-update at {} frontText={} lines={}", username, pos, frontText, List.of(lines));
    }

    // ------------------------------------------------------------------ generic packet observation

    /**
     * Capture packets caused by synchronous gameplay through the packet barrier.
     * Registers before the action, so an empty result can prove that an action did
     * not open a screen. Like {@link #sync}, this does not cover async commands.
     */
    public <P extends Packet> List<P> collectClientboundDuring(Runnable action, Class<P> packetType, Duration timeout) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(packetType, "packetType");
        Objects.requireNonNull(timeout, "timeout");
        var captured = new java.util.concurrent.ConcurrentLinkedQueue<P>();
        PendingAwait<P> observer = new PendingAwait<>(packetType, packet -> {
            captured.add(packet);
            return false; // Keep observing until the gameplay barrier completes.
        });
        awaiting.add(observer);
        try {
            action.run();
            sync(timeout);
            return List.copyOf(captured);
        } finally {
            awaiting.remove(observer);
        }
    }

    /**
     * Block until a clientbound packet of the given type arrives that satisfies
     * {@code matcher}. Useful when the chat-text/position helpers don't cover
     * what your test wants to assert on.
     *
     * <p>This method does not see packets received before it was called — it
     * registers a listener for new arrivals only. If you need historical
     * inspection, drain to a list yourself in your own listener.
     *
     * @throws AssertionError on timeout
     */
    public <P extends Packet> P awaitClientbound(Class<P> packetType, Predicate<? super P> matcher, Duration timeout) {
        Objects.requireNonNull(packetType, "packetType");
        Objects.requireNonNull(matcher, "matcher");
        Objects.requireNonNull(timeout, "timeout");

        PendingAwait<P> pending = new PendingAwait<>(packetType, matcher);
        awaiting.add(pending);
        try {
            return pending.future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError(
                    "Timed out after " + timeout + " waiting for " + packetType.getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for " + packetType.getSimpleName(), e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } finally {
            awaiting.remove(pending);
        }
    }

    /**
     * Like {@link #awaitClientbound}, but registers the listener <em>before</em> running
     * {@code trigger}, so a response that arrives immediately cannot be missed. Use it to
     * send a request and wait for its answer.
     */
    public <P extends Packet> P awaitClientboundAfter(Runnable trigger, Class<P> packetType,
                                                      Predicate<? super P> matcher, Duration timeout) {
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(packetType, "packetType");
        Objects.requireNonNull(matcher, "matcher");
        Objects.requireNonNull(timeout, "timeout");

        PendingAwait<P> pending = new PendingAwait<>(packetType, matcher);
        awaiting.add(pending);
        try {
            trigger.run();
            return pending.future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError(
                    "Timed out after " + timeout + " waiting for " + packetType.getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for " + packetType.getSimpleName(), e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } finally {
            awaiting.remove(pending);
        }
    }

    // ------------------------------------------------------------------ barrier

    /**
     * Round-trip barrier: returns once the server's game thread has handled every packet
     * this client sent before the call, and the client has received every packet the
     * server queued for it before handling them.
     *
     * <p>Mechanism: a {@code ServerboundClientCommandPacket(STATS)} request, answered with
     * a {@code ClientboundAwardStatsPacket}. Why that is a sound barrier on Paper 1.21.11:
     * <ul>
     *   <li>Netty decodes one connection's packets in order, and game packets that touch the
     *       world — {@code UseItemOn}, {@code PlayerAction} (dig/left-click), movement, sign
     *       updates and this client command — are all handed to the main thread through
     *       {@code PacketUtils.ensureRunningOnSameThread}, i.e. appended to the server's
     *       single FIFO {@code PacketProcessor} queue. So the stats request is handled only
     *       after every earlier interaction packet has been handled to completion, including
     *       the synchronous Bukkit events (and their listeners) it fired.</li>
     *   <li>The stats reply is written to the connection after anything those handlers
     *       wrote, and TCP keeps the order, so by the time the reply arrives every earlier
     *       clientbound packet (chat, inventory, block updates) is already here.</li>
     *   <li>Vanilla sends {@code AwardStats} only in answer to this request, so replies can
     *       be counted against requests.</li>
     * </ul>
     *
     * <p>What it does <em>not</em> cover: slash-commands. {@code ServerboundChatCommand}
     * is executed via {@code MinecraftServer.execute}, a different queue from the packet
     * processor, so no ordering holds between a command and this barrier. Nor does it cover
     * work a handler pushes onto another thread (a plugin's async task) — the barrier only
     * proves the synchronous part ran.
     *
     * @throws AssertionError if the reply does not arrive within {@code timeout} or the
     *                        player disconnects first
     */
    public void sync(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        long ticket;
        synchronized (syncLock) {
            // Send under the lock so ticket order is wire order.
            ticket = ++syncRequested;
            session.send(new ServerboundClientCommandPacket(ClientCommand.STATS));
        }
        synchronized (syncLock) {
            while (syncAnswered < ticket) {
                if (!isConnected()) {
                    throw new AssertionError("[" + username + "] disconnected before the sync barrier completed");
                }
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000;
                if (remainingMs <= 0) {
                    throw new AssertionError("[" + username + "] sync barrier not answered within " + timeout
                            + " (requested " + ticket + ", answered " + syncAnswered + ")");
                }
                try {
                    syncLock.wait(remainingMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted during sync barrier", e);
                }
            }
        }
    }

    // ------------------------------------------------------------------ misc

    /** Snapshot of the players currently connected through this library. */
    public static List<MockPlayer> openPlayers() {
        return List.copyOf(OPEN);
    }

    public boolean isConnected() {
        return connected && session.isConnected();
    }

    public String getUsername() {
        return username;
    }

    /**
     * The offline-mode UUID Paper assigns to a player with this username.
     * Computed identically to Bukkit's offline UUID derivation:
     * UUID v3 over the bytes of {@code "OfflinePlayer:<name>"}.
     */
    public UUID getUuid() {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        if (heartbeat != null) {
            heartbeat.cancel(false);
            heartbeat = null;
        }
        OPEN.remove(this);
        if (session.isConnected()) {
            session.disconnect("MockPlayer closing");
        }
        connected = false;
    }

    // ------------------------------------------------------------------ internals

    private static final class PendingAwait<P extends Packet> {
        final Class<P> type;
        final Predicate<? super P> matcher;
        final CompletableFuture<P> future = new CompletableFuture<>();

        PendingAwait(Class<P> type, Predicate<? super P> matcher) {
            this.type = type;
            this.matcher = matcher;
        }

        @SuppressWarnings("unchecked")
        boolean tryComplete(Packet incoming) {
            if (!type.isInstance(incoming)) return false;
            P typed = (P) incoming;
            if (!matcher.test(typed)) return false;
            return future.complete(typed);
        }
    }

    private final class SessionListener extends SessionAdapter {
        @Override
        public void packetReceived(Session s, Packet p) {
            // Play-state ready signal. We also send ClientInformation here:
            // without it the server has no render distance for this client and
            // sends zero chunks, which means the player's server-side chunk
            // tracker is empty and Paper drops every useBlock packet against
            // an "untracked" chunk silently.
            if (p instanceof ClientboundLoginPacket) {
                session.send(new ServerboundClientInformationPacket(
                        "en_us",
                        8,                          // render distance (chunks)
                        ChatVisibility.FULL,
                        true,                       // useChatColors
                        java.util.List.of(SkinPart.values()),
                        HandPreference.RIGHT_HAND,
                        false,                      // textFilteringEnabled
                        true,                       // allowsListing
                        ParticleStatus.ALL));
                playReady.countDown();
            }

            // Position tracking — keep a fresh snapshot of the player's
            // current pos so tests can assert on teleports without timing
            // races. Also ack the teleport: 1.21+ servers stash
            // `awaitingTeleport=true` after every ClientboundPlayerPosition
            // and silently drop subsequent serverbound packets (useBlock,
            // breakBlock, etc.) until the matching
            // ServerboundAcceptTeleportationPacket arrives. MCProtocolLib's
            // default ClientListener does not auto-ack, so we do it here.
            if (p instanceof ClientboundPlayerPositionPacket pos) {
                session.send(new ServerboundAcceptTeleportationPacket(pos.getId()));
                Vector3d v = pos.getPosition();
                currentPosition = new Position(v.getX(), v.getY(), v.getZ(), pos.getYRot(), pos.getXRot());
                positionReceived.countDown();
                // Mark the client as fully loaded once we have an initial
                // position. 1.21+ added a server-side `hasClientLoaded()`
                // gate (ServerGamePacketListenerImpl#handleUseItemOn checks
                // it before doing anything); without ServerboundPlayerLoaded,
                // every block interaction the mock sends is silently dropped.
                if (!playerLoadedSent) {
                    playerLoadedSent = true;
                    session.send(ServerboundPlayerLoadedPacket.INSTANCE);
                }
            }

            // Chunk-batch flow control. After every chunk batch the server
            // sends ClientboundChunkBatchFinishedPacket and waits for our
            // ServerboundChunkBatchReceivedPacket before the next batch ships.
            // Without this ack the player's server-side chunk tracker can sit
            // partially populated, and Paper drops useBlock/breakBlock packets
            // that target blocks in not-yet-tracked chunks — silently, with
            // no error message. 16 chunks/tick matches what the vanilla
            // client requests; we don't need adaptive bandwidth.
            if (p instanceof ClientboundChunkBatchFinishedPacket) {
                session.send(new ServerboundChunkBatchReceivedPacket(16f));
            }

            // sync() barrier replies.
            if (p instanceof ClientboundAwardStatsPacket) {
                synchronized (syncLock) {
                    syncAnswered++;
                    syncLock.notifyAll();
                }
            }

            // Chat capture
            if (p instanceof ClientboundSystemChatPacket sys) {
                Component content = sys.getContent();
                if (content != null) {
                    String text = PLAIN.serialize(content);
                    messages.offer(text);
                    LOG.debug("[{}] chat ← {}", username, text);
                }
            }

            // Generic packet awaits — fire any registered listeners that match
            for (PendingAwait<?> pa : awaiting) {
                if (pa.tryComplete(p)) {
                    // Don't break — multiple listeners may want the same packet
                }
            }
        }

        @Override
        public void disconnected(DisconnectedEvent e) {
            disconnectReason = e.getReason() == null ? "unknown" : PLAIN.serialize(e.getReason());
            connected = false;
            OPEN.remove(MockPlayer.this);
            synchronized (syncLock) {
                syncLock.notifyAll();
            }
            // Release any latches / awaits so callers don't deadlock.
            playReady.countDown();
            positionReceived.countDown();
            for (PendingAwait<?> pa : awaiting) {
                pa.future.completeExceptionally(
                        new IllegalStateException("Disconnected before packet arrived"));
            }
            String reason = e.getReason() == null ? "unknown" : PLAIN.serialize(e.getReason());
            LOG.info("[{}] disconnected: {}", username, reason);
        }
    }
}
