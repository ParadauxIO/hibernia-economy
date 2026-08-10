package io.paradaux.treasuryapi.tasks;

import com.google.inject.Inject;
import io.paradaux.treasuryapi.TreasuryAPI;
import io.paradaux.treasuryapi.mappers.ExplorerGroupMapper;
import io.paradaux.treasuryapi.model.ReconciliationDiff;
import io.paradaux.treasuryapi.model.SyncedGroup;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.group.GroupManager;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.matcher.NodeMatcher;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Periodically reconciles each explorer group's LuckPerms-sourced membership to
 * match the group's {@code luckperms_node}. Runs on the async scheduler (it does
 * DB + LuckPerms storage lookups). Manual memberships are never touched — only
 * {@code source = 'luckperms'} rows are added/removed.
 */
public class GroupReconciliationTask extends BukkitRunnable {

    private final TreasuryAPI plugin;
    private final ExplorerGroupMapper mapper;
    private final LuckPerms luckPerms;
    private final Logger log;
    // Async timer ticks aren't guaranteed to be non-overlapping; skip a tick if the
    // previous reconcile is still running rather than interleaving DB writes.
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Inject
    public GroupReconciliationTask(TreasuryAPI plugin, ExplorerGroupMapper mapper, LuckPerms luckPerms) {
        this.plugin = plugin;
        this.mapper = mapper;
        this.luckPerms = luckPerms;
        this.log = plugin.getLogger();
    }

    @Override
    public void run() {
        if (!running.compareAndSet(false, true)) {
            log.fine("Skipping reconciliation tick: a previous run is still in progress.");
            return;
        }
        try {
            for (SyncedGroup group : mapper.listSyncedGroups()) {
                try {
                    Set<UUID> desired = resolveMembers(group.getLuckpermsNode());
                    if (desired.isEmpty()) {
                        // Fail loudly instead of silently syncing nothing — the usual cause of
                        // "the cron isn't working" is a node that resolves to no one.
                        log.warning("Explorer group " + group.getGroupId() + " (node '"
                                + group.getLuckpermsNode() + "') resolved 0 LuckPerms members. Verify the node "
                                + "is a real LuckPerms group (e.g. 'group.<rank>') or a granted permission.");
                    }
                    apply(group.getGroupId(), desired);
                } catch (Exception e) {
                    log.warning("Reconciliation of group " + group.getGroupId()
                            + " (node '" + group.getLuckpermsNode() + "') failed: " + e.getMessage());
                }
            }
        } finally {
            running.set(false);
        }
    }

    /** Bring the group's 'luckperms' membership in line with {@code desired}. */
    private void apply(int groupId, Set<UUID> desired) {
        Set<UUID> current = new HashSet<>(mapper.listLuckpermsMemberUuids(groupId));
        // Guard against a degraded/misconfigured LuckPerms resolving to zero members,
        // which would otherwise mass-revoke a whole group's synced access in one tick.
        // A node legitimately emptying is rare; refusing to prune on an empty result is
        // the safe failure mode (briefly over-grant, never mass-revoke).
        if (desired.isEmpty() && !current.isEmpty()) {
            log.warning("Reconciliation of explorer group " + groupId
                    + " skipped removals: node resolved to zero members while "
                    + current.size() + " are currently synced (treating as a transient/empty result).");
            return;
        }
        ReconciliationDiff.Result d = ReconciliationDiff.of(desired, current);
        for (UUID u : d.toAdd()) mapper.addLuckpermsMember(groupId, u);
        for (UUID u : d.toRemove()) mapper.removeLuckpermsMember(groupId, u);
        if (!d.toAdd().isEmpty() || !d.toRemove().isEmpty()) {
            log.info("Reconciled explorer group " + groupId + ": +" + d.toAdd().size()
                    + " -" + d.toRemove().size() + " members");
        }
    }

    /**
     * Reconcile a single player's {@code source='luckperms'} memberships across every
     * synced group — used by the on-demand self-sync command so a player's freshly
     * granted in-game ranks reflect in the explorer immediately instead of waiting for
     * the cron tick. Touches only this player's luckperms rows; manual grants and other
     * players are untouched.
     *
     * <p>Loads the one player's LuckPerms user a single time and tests each group's
     * node against their resolved permission data (ADT-39) — rather than running a
     * full storage {@code searchAll} per group to materialise every member and then
     * checking {@code contains(playerId)}. A failed user load is treated as transient
     * and never prunes the player.
     */
    public void reconcilePlayer(UUID playerId) {
        User user;
        try {
            user = luckPerms.getUserManager().loadUser(playerId).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warning("Self-sync: failed to load LuckPerms user " + playerId
                    + " — skipping (treated as transient): " + e.getMessage());
            return;
        }
        if (user == null) return;
        var permData = user.getCachedData().getPermissionData(QueryOptions.nonContextual());

        for (SyncedGroup group : mapper.listSyncedGroups()) {
            try {
                // checkPermission on the canonical node key resolves both direct and
                // inherited (group) grants — equivalent to membership of resolveMembers().
                boolean shouldBeMember = permData.checkPermission(nodeKey(group.getLuckpermsNode())).asBoolean();
                boolean isMember = mapper.listLuckpermsMemberUuids(group.getGroupId()).contains(playerId);
                if (shouldBeMember && !isMember) {
                    mapper.addLuckpermsMember(group.getGroupId(), playerId);
                } else if (!shouldBeMember && isMember) {
                    mapper.removeLuckpermsMember(group.getGroupId(), playerId);
                }
            } catch (Exception e) {
                log.warning("Self-sync of group " + group.getGroupId() + " for " + playerId
                        + " failed: " + e.getMessage());
            }
        }
    }

    /**
     * All players (offline included) who effectively carry the configured node —
     * whether they hold it directly OR inherit it from a LuckPerms group.
     *
     * <p>{@code searchAll} only matches <em>directly-stored</em> nodes, so a node
     * granted to a rank/group (and inherited by its members) would resolve to zero
     * — the exact silent failure this guards against. We therefore union the direct
     * holders with the members of every group that <em>effectively</em> grants the
     * node (its members carry that group's {@code group.<name>} node directly).
     */
    private Set<UUID> resolveMembers(String node) throws Exception {
        Set<UUID> result = new HashSet<>(searchAll(matcherFor(node)));

        GroupManager groups = luckPerms.getGroupManager();
        // Bounded so a stalled backend can't pin this async worker indefinitely.
        groups.loadAllGroups().get(15, TimeUnit.SECONDS);
        String key = nodeKey(node);
        QueryOptions opts = QueryOptions.nonContextual();
        for (Group g : groups.getLoadedGroups()) {
            // Skip the group whose own membership IS the node — its members are
            // already the direct holders above.
            if (key.equals("group." + g.getName().toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (g.getCachedData().getPermissionData(opts).checkPermission(key).asBoolean()) {
                result.addAll(searchAll(matcherFor("group." + g.getName())));
            }
        }
        return result;
    }

    /** Bounded storage search for the UUIDs of users directly carrying a matched node. */
    private Set<UUID> searchAll(NodeMatcher<? extends Node> matcher) throws Exception {
        return luckPerms.getUserManager().searchAll(matcher).get(15, TimeUnit.SECONDS).keySet();
    }

    /**
     * The canonical LuckPerms permission-key for a configured node, matching how
     * {@link #matcherFor} interprets it: a bare name or {@code group.<name>} keys to
     * {@code group.<name>} (group membership); a dotted node is the permission itself.
     * Used to ask each group whether it effectively grants the node.
     */
    static String nodeKey(String node) {
        String n = node.toLowerCase(Locale.ROOT);
        if (n.startsWith("group.")) return n;
        if (n.indexOf('.') < 0) return "group." + n;
        return n;
    }

    /**
     * Interpret the configured node: a bare name or {@code group.<name>} is a
     * LuckPerms group (inheritance node); anything else containing a dot is a
     * raw permission node.
     */
    static NodeMatcher<? extends Node> matcherFor(String node) {
        if (node.startsWith("group.")) {
            return NodeMatcher.key(InheritanceNode.builder(node.substring("group.".length())).build());
        }
        if (node.indexOf('.') < 0) {
            return NodeMatcher.key(InheritanceNode.builder(node).build());
        }
        return NodeMatcher.key(PermissionNode.builder(node).build());
    }

    /** Schedule the recurring async reconciliation. */
    public void schedule(long intervalSeconds) {
        long periodTicks = Math.max(1L, intervalSeconds * 20L);
        runTaskTimerAsynchronously(plugin, periodTicks, periodTicks);
    }
}
