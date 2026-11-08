package io.paradaux.treasuryapi.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.treasuryapi.TreasuryAPI;
import io.paradaux.treasuryapi.mappers.ExplorerGroupMapper;
import io.paradaux.treasuryapi.model.SyncedGroup;
import io.paradaux.treasuryapi.services.PlayerGroupSync;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;

/** Synchronizes effective LP roles and account relationships, including offline users.
 * Both entry points share a lock; failures never publish a partially resolved snapshot.
 * Stored LP grants expire after one hour if reconciliation cannot refresh them.
 */
@Singleton
public class GroupReconciliationTask implements Runnable, PlayerGroupSync {
    private final TreasuryAPI plugin;
    private final ExplorerGroupMapper mapper;
    private final LuckPerms luckPerms;
    private final Logger log;

    @Inject
    public GroupReconciliationTask(TreasuryAPI plugin, ExplorerGroupMapper mapper, LuckPerms luckPerms) {
        this.plugin = plugin;
        this.mapper = mapper;
        this.luckPerms = luckPerms;
        this.log = plugin.getLogger();
    }

    @Override
    public synchronized void run() {
        try {
            var groups = mapper.listSyncedGroups();
            var accountGroups = mapper.listAccountGroups();
            if (groups.isEmpty() && accountGroups.isEmpty()) return;
            luckPerms.getGroupManager().loadAllGroups().get(15, TimeUnit.SECONDS);
            luckPerms.getGroupManager().getLoadedGroups().forEach(g -> g.auditTemporaryNodes());
            Set<UUID> players =
                    new HashSet<>(luckPerms.getUserManager().getUniqueUsers().get(15, TimeUnit.SECONDS));
            // Include former users so removing their final stored node revokes web access.
            players.addAll(mapper.listSyncedPlayers());
            luckPerms.getUserManager().getLoadedUsers().forEach(u -> players.add(u.getUniqueId()));
            Map<Integer, Set<UUID>> desired = new HashMap<>();
            Map<String, Set<UUID>> accountDesired = new HashMap<>();
            groups.forEach(g -> desired.put(g.getGroupId(), new HashSet<>()));
            accountGroups.forEach(g -> accountDesired.put(g, new HashSet<>()));
            // Resolve first, publish only after every user loaded successfully.
            // Unlike searchAll(node), this honours negations, wildcards and inheritance.
            QueryOptions options = webOptions();
            Map<String, String> rules = normalizedRules(groups, accountGroups);
            for (UUID id : players) {
                User user = loadUser(id);
                try {
                    UserMembership membership = new UserMembership(user, options);
                    for (SyncedGroup group : groups) {
                        if (membership.matches(rules.get(group.getLuckpermsNode())))
                            desired.get(group.getGroupId()).add(id);
                    }
                    for (String group : accountGroups) {
                        if (membership.matches(rules.get("group." + group)))
                            accountDesired.get(group).add(id);
                    }
                } finally {
                    luckPerms.getUserManager().cleanupUser(user);
                }
            }
            for (SyncedGroup group : groups) {
                for (UUID id : desired.get(group.getGroupId())) {
                    mapper.refreshLuckpermsMember(group.getGroupId(), id, group.getLuckpermsNode());
                }
                for (UUID id : mapper.listLuckpermsMemberUuids(group.getGroupId())) {
                    if (!desired.get(group.getGroupId()).contains(id))
                        mapper.removeLuckpermsMember(group.getGroupId(), id);
                }
            }
            for (String group : accountGroups) {
                for (UUID id : accountDesired.get(group)) mapper.refreshAccountGroupMember(group, id);
                for (UUID id : mapper.listAccountGroupMembers(group)) {
                    if (!accountDesired.get(group).contains(id)) mapper.removeAccountGroupMember(group, id);
                }
            }
            mapper.removeUnusedAccountGroups();
        } catch (Exception e) {
            log.warning("Explorer LP reconciliation failed (unrefreshed grants expire): " + e.getMessage());
        }
    }

    @Override
    public synchronized void reconcilePlayer(UUID playerId) {
        User user = null;
        try {
            luckPerms.getGroupManager().loadAllGroups().get(15, TimeUnit.SECONDS);
            luckPerms.getGroupManager().getLoadedGroups().forEach(g -> g.auditTemporaryNodes());
            user = loadUser(playerId);
            QueryOptions options = webOptions();
            var groups = mapper.listSyncedGroups();
            var accountGroups = mapper.listAccountGroups();
            Map<String, String> rules = normalizedRules(groups, accountGroups);
            UserMembership membership = new UserMembership(user, options);
            Set<Integer> desired = new HashSet<>();
            Set<String> accountDesired = new HashSet<>();
            // Self-sync must also finish resolution before publishing any changes.
            for (SyncedGroup group : groups) {
                if (membership.matches(rules.get(group.getLuckpermsNode()))) desired.add(group.getGroupId());
            }
            for (String group : accountGroups) {
                if (membership.matches(rules.get("group." + group))) accountDesired.add(group);
            }
            for (SyncedGroup group : groups) {
                if (desired.contains(group.getGroupId())) {
                    mapper.refreshLuckpermsMember(group.getGroupId(), playerId, group.getLuckpermsNode());
                } else mapper.removeLuckpermsMember(group.getGroupId(), playerId);
            }
            for (String group : accountGroups) {
                if (accountDesired.contains(group)) mapper.refreshAccountGroupMember(group, playerId);
                else mapper.removeAccountGroupMember(group, playerId);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Explorer LP self-sync failed for " + playerId, e);
        } finally {
            if (user != null) luckPerms.getUserManager().cleanupUser(user);
        }
    }

    private User loadUser(UUID id) throws Exception {
        User user = luckPerms.getUserManager().loadUser(id).get(15, TimeUnit.SECONDS);
        if (user == null) throw new IllegalStateException("LP returned no user for " + id);
        user.auditTemporaryNodes();
        return user;
    }

    private QueryOptions webOptions() {
        // Stable server context for online AND offline users. Never merge grants
        // from other servers/worlds as nonContextual() would.
        return luckPerms.getContextManager().getStaticQueryOptions();
    }

    private static Map<String, String> normalizedRules(List<SyncedGroup> groups, List<String> accountGroups) {
        Map<String, String> rules = new HashMap<>();
        for (SyncedGroup group : groups) {
            rules.computeIfAbsent(group.getLuckpermsNode(), GroupReconciliationTask::nodeKey);
        }
        for (String group : accountGroups) {
            rules.computeIfAbsent("group." + group, GroupReconciliationTask::nodeKey);
        }
        return rules;
    }

    static boolean matches(User user, String node, QueryOptions options) {
        return new UserMembership(user, options).matches(nodeKey(node));
    }

    /** One user's resolution for one pass only: never retain grants across runs or users. */
    private static final class UserMembership {
        private final User user;
        private final QueryOptions options;
        private Set<String> inheritedGroups;
        private CachedPermissionData permissions;

        private UserMembership(User user, QueryOptions options) {
            this.user = user;
            this.options = options;
        }

        private boolean matches(String key) {
            if (key.startsWith("group.")) {
                if (inheritedGroups == null) {
                    inheritedGroups = new HashSet<>();
                    for (var group : user.getInheritedGroups(options)) {
                        inheritedGroups.add(group.getName().toLowerCase(Locale.ROOT));
                    }
                }
                return inheritedGroups.contains(key.substring(6));
            }
            // Group-only bindings must not trigger full permission-cache construction.
            if (permissions == null) permissions = user.getCachedData().getPermissionData(options);
            return permissions.checkPermission(key).asBoolean();
        }
    }

    static String nodeKey(String node) {
        String key = node.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) throw new IllegalArgumentException("Empty LP node");
        return key.indexOf('.') < 0 ? "group." + key : key;
    }

    public void schedule(long intervalSeconds) {
        // DB leases use real time. Bukkit's tick-based async timer slows down with TPS.
        long periodSeconds = Math.max(1L, Math.min(intervalSeconds, 1800L));
        plugin.getServer()
                .getAsyncScheduler()
                .runAtFixedRate(plugin, task -> run(), 1L, periodSeconds, TimeUnit.SECONDS);
    }
}
