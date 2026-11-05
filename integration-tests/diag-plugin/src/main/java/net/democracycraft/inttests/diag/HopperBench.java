package net.democracycraft.inttests.diag;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * PAR-523 hopper benchmark: builds a hopper workload, then samples one window of it.
 *
 * <p>{@code /diag bench build <owner> <typical|overload>} places sorter chains, busy shops,
 * full destinations, hopper minecarts and counter probes in forceloaded chunks away from
 * spawn. {@code /diag bench detach <plugin>[,<plugin>...]} unregisters those plugins' move-event
 * handlers, as production's ListenerControl does. {@code /diag bench start <variant> <scenario> <seconds> <out.json>}
 * records every tick's duration, main-thread CPU and allocation, a JFR profile, ChestShop's
 * refresh-queue depth, per-tick worker drain time and how long probe counters take to match
 * the chest once its transfers finish. {@code /diag bench status} reports progress.
 *
 * <p>Everything except the timed worker drain is passive. Probes run their own small
 * hopper cycles in every variant so each variant carries the same probe cost.
 */
final class HopperBench implements Listener {
    private static final int BASE_Y = 80;
    // Fixtures sit beyond the owner's view/simulation distance from spawn (4 chunks).
    private static final int ORIGIN_CHUNK_X = 16;
    private static final int PROBE_ITEMS = 8;
    private static final long PROBE_TIMEOUT_NANOS = Duration.ofSeconds(30).toNanos();
    private static final Pattern COUNTER = Pattern.compile("C (\\d+)");
    private static final String REFRESH_SERVICE = "io.paradaux.chestshop.services.HopperStockRefreshService";

    record Scenario(int chains, int chainHoppers, int shops, int fullDestinations, int minecarts, int probes) {}

    private static final Map<String, Scenario> SCENARIOS = Map.of(
            // About 1,300 dirty positions per interval: below the 4,096-position queue cap.
            "typical", new Scenario(64, 8, 32, 8, 16, 8),
            // About 6,000: overflows the cap into per-chunk reconciliation of dense chunks.
            "overload", new Scenario(320, 8, 128, 32, 64, 8));

    private final JavaPlugin plugin;
    private final List<Container> sinks = new ArrayList<>();
    private final List<Probe> probes = new ArrayList<>();
    private final Set<Long> chunks = new LinkedHashSet<>();
    private World world;
    private String scenarioName;
    private Scenario scenario;
    private Map<String, Integer> built = Map.of();
    private Sample sample;
    private volatile String status = "idle";

    HopperBench(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    boolean command(CommandSender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "build" -> {
                if (args.length != 4 || !SCENARIOS.containsKey(args[3])) {
                    sender.sendMessage("usage: /diag bench build <owner> <" + String.join("|", SCENARIOS.keySet()) + ">");
                } else {
                    sender.sendMessage(build(args[2], args[3]));
                }
            }
            case "detach" -> sender.sendMessage(args.length == 3 ? detach(Set.of(args[2].split(","))) : "usage: /diag bench detach <plugin>[,<plugin>...]");
            case "start" -> {
                if (args.length != 6) {
                    sender.sendMessage("usage: /diag bench start <variant> <scenario> <seconds> <out.json>");
                } else {
                    sender.sendMessage(start(args[2], args[3], Integer.parseInt(args[4]), Path.of(args[5])));
                }
            }
            case "status" -> sender.sendMessage("bench " + status);
            default -> sender.sendMessage("usage: /diag bench <build|detach|start|status>");
        }
        return true;
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private String build(String owner, String name) {
        if (world != null) return "bench already built";
        world = Bukkit.getWorlds().getFirst();
        scenarioName = name;
        scenario = SCENARIOS.get(name);
        int chainChunks = (scenario.chains() + 63) / 64;
        int shopChunks = (scenario.shops() + 15) / 16;
        int z = 0;
        for (int i = 0; i < scenario.chains(); i++) {
            int chunk = i / 64, slot = i % 64;
            chain(blockX(chunk) + 2 * (slot % 8), z + 2 * (slot / 8), scenario.chainHoppers());
        }
        z += 32;
        for (int i = 0; i < scenario.shops(); i++) {
            int chunk = i / 16, slot = i % 16;
            busyShop(owner, blockX(chunk) + 1 + 4 * (slot % 4), z + 1 + 4 * (slot / 4), i);
        }
        z += 32;
        for (int i = 0; i < scenario.fullDestinations(); i++) {
            fullDestination(owner, blockX(i / 16) + 1 + 4 * (i % 4), z + 1 + 4 * ((i % 16) / 4), i % 2 == 0);
        }
        z += 32;
        for (int i = 0; i < scenario.minecarts(); i++) {
            minecart(owner, blockX(i / 16) + 1 + 4 * (i % 4), z + 1 + 4 * ((i % 16) / 4), i % 2 == 0);
        }
        z += 32;
        for (int i = 0; i < scenario.probes(); i++) {
            probes.add(probe(owner, blockX(0) + 1 + 4 * (i % 4), z + 1 + 4 * (i / 4), i));
        }
        built = new LinkedHashMap<>();
        built.put("chains", scenario.chains());
        built.put("chainHoppers", scenario.chainHoppers());
        built.put("chainChunks", chainChunks);
        built.put("shops", scenario.shops());
        built.put("shopChunks", shopChunks);
        built.put("fullDestinations", scenario.fullDestinations());
        built.put("minecarts", scenario.minecarts());
        built.put("probes", scenario.probes());
        built.put("forcedChunks", chunks.size());
        built.put("tileEntities", chunks.stream()
                .mapToInt(key -> world.getChunkAt((int) (key >> 32), (int) (long) key).getTileEntities(false).length)
                .sum());
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickProbes, 1L, 1L);
        return "bench built " + built;
    }

    private static int blockX(int chunk) {
        return (ORIGIN_CHUNK_X + chunk) * 16;
    }

    /** Supply chest, then hopper/chest pairs down to a sink chest. Every container is a CHEST. */
    private void chain(int x, int z, int hoppers) {
        int top = BASE_Y + 2 * hoppers;
        fill(chest(x, top, z, "single"), 27);
        for (int y = top - 1; y > BASE_Y; y -= 2) {
            hopper(x, y, z);
            chest(x, y - 1, z, "single");
        }
        sinks.add(container(x, BASE_Y, z));
    }

    /** Supply → hopper → shop chest → hopper → sink. A quarter are double chests, a quarter carry two signs. */
    private void busyShop(String owner, int x, int z, int index) {
        boolean doubleChest = index % 4 == 1;
        boolean twoSigns = index % 4 == 2;
        fill(chest(x, BASE_Y + 4, z, "single"), 27);
        hopper(x, BASE_Y + 3, z);
        if (doubleChest) {
            chest(x, BASE_Y + 2, z, "left");
            chest(x + 1, BASE_Y + 2, z, "right");
        } else {
            chest(x, BASE_Y + 2, z, "single");
        }
        sign(owner, x, BASE_Y + 2, z - 1, "north");
        if (twoSigns) sign(owner, x, BASE_Y + 2, z + 1, "south");
        int out = doubleChest ? x + 1 : x;
        hopper(out, BASE_Y + 1, z);
        chest(out, BASE_Y, z, "single");
        sinks.add(container(out, BASE_Y, z));
    }

    /** A hopper that keeps trying to push into a full chest; half of those chests are shops. */
    private void fullDestination(String owner, int x, int z, boolean shop) {
        fill(chest(x, BASE_Y + 2, z, "single"), 27);
        hopper(x, BASE_Y + 1, z);
        fill(chest(x, BASE_Y, z, "single"), 27);
        if (shop) sign(owner, x, BASE_Y, z - 1, "north");
    }

    /**
     * A hopper minecart under a chest, drained by a hopper under its rail. Under a shop chest
     * the pull must be cancelled immediately; under an ordinary chest items flow continuously.
     */
    private void minecart(String owner, int x, int z, boolean shop) {
        fill(chest(x, BASE_Y + 3, z, "single"), 27);
        if (shop) sign(owner, x, BASE_Y + 3, z - 1, "north");
        set(x, BASE_Y + 2, z, "minecraft:rail");
        hopper(x, BASE_Y + 1, z);
        chest(x, BASE_Y, z, "single");
        sinks.add(container(x, BASE_Y, z));
        world.spawn(new Location(world, x + 0.5, BASE_Y + 2, z + 0.5), HopperMinecart.class);
    }

    /** An isolated shop with an input hopper above and a redstone-locked output hopper below. */
    private Probe probe(String owner, int x, int z, int index) {
        hopper(x, BASE_Y + 3, z);
        chest(x, BASE_Y + 2, z, "single");
        sign(owner, x, BASE_Y + 2, z - 1, "north");
        hopper(x, BASE_Y + 1, z);
        chest(x, BASE_Y, z, "single");
        // With a block update, so the output hopper starts locked.
        world.getBlockAt(x + 1, BASE_Y + 1, z).setType(Material.REDSTONE_BLOCK);
        return new Probe(x, z, index);
    }

    private Container chest(int x, int y, int z, String type) {
        set(x, y, z, "minecraft:chest[facing=north,type=" + type + "]");
        return container(x, y, z);
    }

    private void hopper(int x, int y, int z) {
        set(x, y, z, "minecraft:hopper[facing=down]");
    }

    private void sign(String owner, int x, int y, int z, String facing) {
        set(x, y, z, "minecraft:oak_wall_sign[facing=" + facing + "]");
        Sign sign = (Sign) world.getBlockAt(x, y, z).getState();
        String[] lines = {owner, "1", "1 B", "COBBLESTONE"};
        for (int i = 0; i < lines.length; i++) sign.getSide(Side.FRONT).line(i, Component.text(lines[i]));
        sign.update(true, false);
    }

    private void set(int x, int y, int z, String data) {
        int cx = x >> 4, cz = z >> 4;
        if (chunks.add(((long) cx << 32) | (cz & 0xffffffffL))) world.setChunkForceLoaded(cx, cz, true);
        world.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData(data), false);
    }

    private Container container(int x, int y, int z) {
        return (Container) world.getBlockAt(x, y, z).getState(false);
    }

    private static Container fill(Container container, int stacks) {
        Inventory inventory = container.getSnapshotInventory();
        for (int i = 0; i < stacks; i++) inventory.setItem(i, new ItemStack(Material.COBBLESTONE, 64));
        container.update(true, false);
        return container;
    }

    private String detach(Set<String> plugins) {
        List<String> removed = new ArrayList<>();
        for (RegisteredListener listener : InventoryMoveItemEvent.getHandlerList().getRegisteredListeners()) {
            String owner = listener.getPlugin().getName();
            if (plugins.contains(owner)) {
                InventoryMoveItemEvent.getHandlerList().unregister(listener);
                removed.add(owner + "/" + listener.getPriority());
            }
        }
        return "bench detached " + removed + " remaining=" + handlers();
    }

    private static List<String> handlers() {
        return Arrays.stream(InventoryMoveItemEvent.getHandlerList().getRegisteredListeners())
                .map(l -> l.getPlugin().getName() + "/" + l.getPriority())
                .toList();
    }

    // ── Probes ───────────────────────────────────────────────────────────────

    /** Fill-then-drain cycles; latency runs from the chest reaching its target to the sign showing it. */
    private final class Probe {
        final int x, z;
        int target = -1;
        long phaseStart, reached = -1, idleUntilTick;

        Probe(int x, int z, int index) {
            this.x = x;
            this.z = z;
            idleUntilTick = 100 + 10L * index; // stagger cycles
        }

        void tick(long tick, long now) {
            if (target < 0) {
                if (tick >= idleUntilTick) begin(PROBE_ITEMS, now);
                return;
            }
            int stock = count(container(x, BASE_Y + 2, z).getInventory());
            if (reached < 0 && stock == target && count(container(x, BASE_Y + 3, z).getInventory()) == 0) reached = now;
            Integer shown = counter();
            if (reached >= 0 && shown != null && shown == target) {
                if (sample != null) sample.latency(target > 0, (now - reached) / 1e6);
                next(tick, now);
            } else if (now - phaseStart > PROBE_TIMEOUT_NANOS) {
                if (sample != null) sample.miss(target > 0);
                next(tick, now);
            }
        }

        private void next(long tick, long now) {
            if (target > 0) {
                begin(0, now);
            } else {
                target = -1;
                idleUntilTick = tick + 20 + (x + z) % 40;
            }
        }

        private void begin(int items, long now) {
            target = items;
            phaseStart = now;
            reached = -1;
            Block lock = world.getBlockAt(x + 1, BASE_Y + 1, z);
            if (items > 0) {
                container(x, BASE_Y, z).getInventory().clear();
                lock.setType(Material.REDSTONE_BLOCK);
                container(x, BASE_Y + 3, z).getInventory().setItem(0, new ItemStack(Material.COBBLESTONE, items));
            } else {
                lock.setType(Material.AIR);
            }
        }

        private Integer counter() {
            Sign sign = (Sign) world.getBlockAt(x, BASE_Y + 2, z - 1).getState(false);
            Matcher m = COUNTER.matcher(PlainTextComponentSerializer.plainText().serialize(sign.getSide(Side.FRONT).line(1)));
            return m.find() ? Integer.valueOf(m.group(1)) : null;
        }
    }

    private long probeTick;

    private void tickProbes() {
        long now = System.nanoTime();
        probeTick++;
        for (Probe probe : probes) probe.tick(probeTick, now);
    }

    private static int count(Inventory inventory) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == Material.COBBLESTONE) total += item.getAmount();
        }
        return total;
    }

    // ── Sampling ─────────────────────────────────────────────────────────────

    private String start(String variant, String expectedScenario, int seconds, Path out) {
        if (world == null) return "bench not built";
        if (!expectedScenario.equals(scenarioName)) return "bench built for " + scenarioName;
        if (sample != null) return "bench already sampling";
        try {
            sample = new Sample(variant, seconds, out);
        } catch (ReflectiveOperationException e) {
            return "bench start failed: " + e;
        }
        status = "sampling";
        return "bench started " + variant + " for " + seconds + "s handlers=" + handlers();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        if (sample != null) sample.tick(event.getTickDuration());
    }

    private final class Sample {
        final String variant;
        final Path out;
        final long ticks;
        final Thread main = Thread.currentThread();
        final com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long cpuStart, allocStart, wallStart;
        final long sinkStart;
        final Recording jfr = new Recording();
        final Path jfrFile;
        final Queue queue;
        final DoubleList tickMs = new DoubleList();
        final DoubleList drainMs = new DoubleList();
        final DoubleList inLatency = new DoubleList(), outLatency = new DoubleList();
        int inMisses, outMisses;
        final List<int[]> backlog = new ArrayList<>();
        long elapsed;

        Sample(String variant, int seconds, Path out) throws ReflectiveOperationException {
            this.variant = variant;
            this.out = out;
            this.ticks = seconds * 20L;
            this.queue = Queue.attach(drainMs);
            jfrFile = out.resolveSibling(out.getFileName() + ".jfr");
            jfr.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(2));
            jfr.enable("jdk.ObjectAllocationSample").with("throttle", "300/s");
            jfr.enable("jdk.GarbageCollection");
            jfr.setToDisk(true);
            sinkStart = sinkItems();
            cpuStart = threads.getThreadCpuTime(main.threadId());
            allocStart = threads.getThreadAllocatedBytes(main.threadId());
            jfr.start();
            wallStart = System.nanoTime();
        }

        void latency(boolean in, double ms) {
            (in ? inLatency : outLatency).add(ms);
        }

        void miss(boolean in) {
            if (in) inMisses++;
            else outMisses++;
        }

        void tick(double ms) {
            tickMs.add(ms);
            if (tickMs.size() % 20 == 0 && queue != null) backlog.add(queue.depth());
            if (tickMs.size() >= ticks) finish();
        }

        private void finish() {
            elapsed = System.nanoTime() - wallStart;
            long cpu = threads.getThreadCpuTime(main.threadId()) - cpuStart;
            long alloc = threads.getThreadAllocatedBytes(main.threadId()) - allocStart;
            jfr.stop();
            long sinkEnd = sinkItems();
            sample = null;
            status = "analyzing";
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    jfr.dump(jfrFile);
                    jfr.close();
                    Files.writeString(out, json(cpu, alloc, sinkEnd - sinkStart, Profile.read(jfrFile, main.getName())),
                            StandardCharsets.UTF_8);
                    status = "done " + out;
                } catch (IOException | RuntimeException e) {
                    plugin.getLogger().severe("bench analysis failed: " + e);
                    status = "failed " + e;
                }
            });
        }

        private String json(long cpu, long alloc, long moved, Profile profile) {
            double seconds = elapsed / 1e9;
            Json j = new Json();
            j.put("variant", variant).put("scenario", scenarioName).put("fixture", built);
            j.put("handlers", handlers());
            j.put("seconds", seconds).put("ticks", tickMs.size()).put("tps", tickMs.size() / seconds);
            j.put("tickMs", tickMs.summary());
            j.put("ticksOver50ms", tickMs.countAbove(50));
            j.put("mainCpuMsPerTick", cpu / 1e6 / tickMs.size());
            j.put("mainAllocMBPerSec", alloc / 1048576.0 / seconds);
            j.put("itemsDeliveredPerSec", moved / seconds);
            j.put("profile", profile.toMap());
            j.put("workerDrainMs", queue == null ? null : drainMs.summary());
            j.put("workerTicksOver2ms", queue == null ? null : drainMs.countAbove(2));
            j.put("probeInMs", inLatency.summary()).put("probeInMisses", inMisses);
            j.put("probeOutMs", outLatency.summary()).put("probeOutMisses", outMisses);
            j.put("backlog", backlogSummary());
            j.put("javaVersion", System.getProperty("java.version"));
            j.put("serverVersion", Bukkit.getVersion());
            j.put("hopperSettings", world.getName() + ":" + hopperSettings());
            return j.toString();
        }

        private Map<String, Object> backlogSummary() {
            Map<String, Object> m = new LinkedHashMap<>();
            String[] names = {"pending", "reconciliation", "recovery", "deferredPositions", "deferredChunks"};
            for (int k = 0; k < names.length; k++) {
                int index = k;
                DoubleList series = new DoubleList();
                backlog.forEach(row -> series.add(row[index]));
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("max", series.max());
                // Growth check: mean of the final third against the middle third.
                s.put("middleThirdMean", series.mean(series.size() / 3, 2 * series.size() / 3));
                s.put("finalThirdMean", series.mean(2 * series.size() / 3, series.size()));
                m.put(names[k], s);
            }
            return m;
        }
    }

    private long sinkItems() {
        long total = 0;
        for (Container sink : sinks) total += count(container(sink.getX(), sink.getY(), sink.getZ()).getInventory());
        return total;
    }

    private static String hopperSettings() {
        try {
            return Files.readString(Path.of("config/paper-world-defaults.yml")).lines()
                    .dropWhile(line -> !line.startsWith("hopper:")).limit(4).toList().toString();
        } catch (IOException e) {
            return "unreadable: " + e;
        }
    }

    /**
     * Reflective view of ChestShop's refresh worker (benchmark only). Replaces its scheduler
     * task with an identical per-tick call to {@code drain()} that is timed on the same thread.
     * The replacement stays after the sample: the benchmark server is discarded afterwards.
     */
    private record Queue(Object service, Field pending, Field reconciliation, Field recovery,
            Field deferredPositions, Field deferredChunks) {

        static Queue attach(DoubleList drainMs) throws ReflectiveOperationException {
            Plugin chestShop = Bukkit.getPluginManager().getPlugin("ChestShop");
            if (chestShop == null) return null;
            Field injectorField = chestShop.getClass().getDeclaredField("injector");
            injectorField.setAccessible(true);
            Object injector = injectorField.get(chestShop);
            Class<?> type = Class.forName(REFRESH_SERVICE, true, chestShop.getClass().getClassLoader());
            Object service = instance(injector, type);
            Field task = field(type, "task");
            BukkitTask original = (BukkitTask) task.get(service);
            if (original == null) return null; // service not started: nothing to time
            Method drain = type.getDeclaredMethod("drain");
            drain.setAccessible(true);
            original.cancel();
            BukkitTask timed = Bukkit.getScheduler().runTaskTimer(chestShop, () -> {
                long begin = System.nanoTime();
                try {
                    drain.invoke(service);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
                drainMs.add((System.nanoTime() - begin) / 1e6);
            }, 1L, 1L);
            task.set(service, timed);
            return new Queue(service, field(type, "pending"), field(type, "reconciliation"), field(type, "recovery"),
                    field(type, "deferredPositions"), field(type, "deferredChunks"));
        }

        /** Through the public Injector interface: Guice's implementation class is not public. */
        private static Object instance(Object injector, Class<?> type) throws ReflectiveOperationException {
            for (Class<?> api : injector.getClass().getInterfaces()) {
                if (api.getName().endsWith(".Injector")) return api.getMethod("getInstance", Class.class).invoke(injector, type);
            }
            throw new NoSuchMethodException("Injector.getInstance");
        }

        private static Field field(Class<?> type, String name) throws NoSuchFieldException {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }

        int[] depth() {
            try {
                return new int[] {
                    ((Map<?, ?>) pending.get(service)).size(),
                    ((Map<?, ?>) reconciliation.get(service)).size(),
                    ((Collection<?>) recovery.get(service)).size(),
                    deferredPositions.getInt(service),
                    ((Collection<?>) deferredChunks.get(service)).size(),
                };
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Main-thread JFR samples attributed to ChestShop's hopper paths and to hopper ticking overall. */
    private record Profile(long samples, long worker, long listener, long chestShopOther, long hopperTicking,
            long moveEventConstruction, double allocBytes, double workerAllocBytes, double listenerAllocBytes,
            double gcPauseMs, int gcCount) {

        static Profile read(Path file, String mainThread) throws IOException {
            long samples = 0, worker = 0, listener = 0, other = 0, hoppers = 0, construct = 0;
            double alloc = 0, workerAlloc = 0, listenerAlloc = 0, gcPause = 0;
            int gcs = 0;
            try (RecordingFile recording = new RecordingFile(file)) {
                while (recording.hasMoreEvents()) {
                    RecordedEvent event = recording.readEvent();
                    String type = event.getEventType().getName();
                    if (type.equals("jdk.GarbageCollection")) {
                        gcs++;
                        gcPause += event.getDuration("sumOfPauses").toNanos() / 1e6;
                        continue;
                    }
                    boolean execution = type.equals("jdk.ExecutionSample");
                    RecordedThread thread = event.getThread(execution ? "sampledThread" : "eventThread");
                    if (thread == null || !mainThread.equals(thread.getJavaName())) continue;
                    Site site = classify(event.getStackTrace());
                    if (execution) {
                        samples++;
                        switch (site) {
                            case WORKER -> worker++;
                            case LISTENER -> listener++;
                            case CHESTSHOP -> other++;
                            default -> {}
                        }
                        if (contains(event.getStackTrace(), "HopperBlockEntity")) hoppers++;
                        if (contains(event.getStackTrace(), "InventoryMoveItemEvent.<init>")) construct++;
                    } else if (type.equals("jdk.ObjectAllocationSample")) {
                        double weight = event.getLong("weight");
                        alloc += weight;
                        if (site == Site.WORKER) workerAlloc += weight;
                        if (site == Site.LISTENER) listenerAlloc += weight;
                    }
                }
            }
            return new Profile(samples, worker, listener, other, hoppers, construct, alloc, workerAlloc, listenerAlloc,
                    gcPause, gcs);
        }

        enum Site { WORKER, LISTENER, CHESTSHOP, OTHER }

        private static Site classify(RecordedStackTrace stack) {
            if (stack == null) return Site.OTHER;
            boolean chestShop = false;
            for (RecordedFrame frame : stack.getFrames()) {
                String owner = frame.getMethod().getType().getName();
                if (owner.equals(REFRESH_SERVICE)) return Site.WORKER;
                if (owner.endsWith(".ItemMoveListener")) return Site.LISTENER;
                chestShop |= owner.startsWith("io.paradaux.chestshop.");
            }
            return chestShop ? Site.CHESTSHOP : Site.OTHER;
        }

        private static boolean contains(RecordedStackTrace stack, String needle) {
            if (stack == null) return false;
            for (RecordedFrame frame : stack.getFrames()) {
                String name = frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
                if (name.contains(needle)) return true;
            }
            return false;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mainSamples", samples);
            m.put("workerSamples", worker);
            m.put("listenerSamples", listener);
            m.put("otherChestShopSamples", chestShopOther);
            m.put("hopperTickingSamples", hopperTicking);
            m.put("moveEventConstructionSamples", moveEventConstruction);
            m.put("mainAllocSampledBytes", allocBytes);
            m.put("workerAllocSampledBytes", workerAllocBytes);
            m.put("listenerAllocSampledBytes", listenerAllocBytes);
            m.put("gcPauseMs", gcPauseMs);
            m.put("gcCount", gcCount);
            return m;
        }
    }

    // ── Small helpers (DcDiag has no JSON or stats dependency) ───────────────

    private static final class DoubleList {
        private double[] values = new double[1024];
        private int size;

        void add(double v) {
            if (size == values.length) values = Arrays.copyOf(values, size * 2);
            values[size++] = v;
        }

        int size() {
            return size;
        }

        long countAbove(double limit) {
            long n = 0;
            for (int i = 0; i < size; i++) if (values[i] > limit) n++;
            return n;
        }

        double max() {
            double max = 0;
            for (int i = 0; i < size; i++) max = Math.max(max, values[i]);
            return max;
        }

        double mean(int from, int to) {
            if (to <= from) return 0;
            double sum = 0;
            for (int i = from; i < to; i++) sum += values[i];
            return sum / (to - from);
        }

        Map<String, Object> summary() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", size);
            if (size == 0) return m;
            double[] sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);
            m.put("mean", mean(0, size));
            m.put("p50", quantile(sorted, 0.50));
            m.put("p95", quantile(sorted, 0.95));
            m.put("p99", quantile(sorted, 0.99));
            m.put("p999", quantile(sorted, 0.999));
            m.put("max", sorted[size - 1]);
            return m;
        }

        private static double quantile(double[] sorted, double q) {
            return sorted[Math.min(sorted.length - 1, (int) Math.ceil(q * sorted.length) - 1)];
        }
    }

    private static final class Json {
        private final StringBuilder out = new StringBuilder("{");

        Json put(String key, Object value) {
            if (out.length() > 1) out.append(',');
            out.append(quote(key)).append(':');
            write(value);
            return this;
        }

        private void write(Object value) {
            switch (value) {
                case null -> out.append("null");
                case Number n when n instanceof Double d && !Double.isFinite(d) -> out.append("null");
                case Number n -> out.append(n);
                case Boolean b -> out.append(b);
                case Map<?, ?> map -> {
                    out.append('{');
                    boolean first = true;
                    for (var e : map.entrySet()) {
                        if (!first) out.append(',');
                        first = false;
                        out.append(quote(String.valueOf(e.getKey()))).append(':');
                        write(e.getValue());
                    }
                    out.append('}');
                }
                case Collection<?> list -> {
                    out.append('[');
                    boolean first = true;
                    for (Object item : list) {
                        if (!first) out.append(',');
                        first = false;
                        write(item);
                    }
                    out.append(']');
                }
                default -> out.append(quote(String.valueOf(value)));
            }
        }

        private static String quote(String s) {
            StringBuilder b = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    default -> {
                        if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                    }
                }
            }
            return b.append('"').toString();
        }

        @Override
        public String toString() {
            return out + "}";
        }
    }
}
