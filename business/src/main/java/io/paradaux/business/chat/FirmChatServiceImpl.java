package io.paradaux.business.chat;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.Business;
import io.paradaux.business.model.Firm;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default {@link FirmChatService}: owns firm-chat state and the single CarbonChat
 * channel (PAR-20). Firm membership and the per-player "active firm" pointer live
 * here (a player can be in several firms, unlike Factions), so {@link FirmChatChannel}
 * can resolve recipients at send time without inflating Carbon's per-message channel
 * loop.
 *
 * <p>The "active firm" pointer and the spy registries are caches of an
 * entitlement that can be withdrawn (an employee is fired, a moderator loses the
 * spy permission), so neither is trusted blindly: membership is re-checked
 * against the database at most once per {@link #MEMBERSHIP_TTL_MS} per player —
 * Carbon evaluates the channel permissions per message, so an uncached check
 * would put queries on the chat path — and immediately after
 * {@link #membershipChanged(int)}; the spy permission is an in-memory check and
 * is re-evaluated on every use.
 *
 * <p>CarbonChat is a soft dependency: if it isn't present the feature simply
 * disables itself ({@link #available()} returns false) and the command reports
 * it as unavailable.
 */
@Singleton
public class FirmChatServiceImpl implements FirmChatService {

    /** How long a positive membership check is trusted before it is re-run. */
    static final long MEMBERSHIP_TTL_MS = 10_000L;

    /** The node that gates {@code /firm admin chatspy}; re-checked whenever a spy would hear. */
    static final String SPY_PERMISSION = "business.admin.chatspy";

    /** {@link Selection#validatedAt} marker forcing a re-check on the next use. */
    private static final long STALE = Long.MIN_VALUE;

    private final FirmService firms;
    private final FirmStaffService staff;
    private final Logger log;
    private final LongSupplier clock;
    private final Predicate<UUID> spyPermitted;

    /** player → the firm whose chat they're currently in. In-memory (cleared on leave). */
    private final Map<UUID, Selection> activeFirm = new ConcurrentHashMap<>();

    /**
     * Chat moderators spying on <em>all</em> firm chat (PAR-20). A spy receives a
     * firm's chat without being an employee/proprietor and without an active firm of
     * their own — a {@code /socialspy}-style moderation view. In-memory, per session.
     */
    private final Set<UUID> globalSpies = ConcurrentHashMap.newKeySet();

    /**
     * Chat moderators spying on <em>specific</em> firms: player → the firm ids they
     * watch. Independent of {@link #globalSpies}. In-memory, per session.
     */
    private final Map<UUID, Set<Integer>> firmSpies = new ConcurrentHashMap<>();

    // No member of THIS class — field, method signature, or lambda body (javac lifts
    // each lambda into a synthetic method on the enclosing class) — may name a
    // net.draycia.carbon.* type. Guice reflects over the declared fields, methods and
    // constructors at injector-build time, and that reflection throws
    // NoClassDefFoundError when CarbonChat isn't installed, failing the whole plugin
    // enable before initialise() ever gets to soft-disable the feature. Every Carbon
    // call therefore lives in CarbonFirmChat, which is only linked inside the guarded
    // initialise(). (Keeps the soft-dependency genuinely soft; the testWithoutCarbon
    // Gradle task builds the real injector without carbonchat-api to enforce it.)
    private volatile CarbonFirmChat carbon;   // null when CarbonChat is absent

    @Inject
    public FirmChatServiceImpl(FirmService firms, FirmStaffService staff, Business plugin) {
        this(firms, staff, plugin.getLogger(), System::currentTimeMillis, FirmChatServiceImpl::holdsSpyPermission);
    }

    FirmChatServiceImpl(FirmService firms, FirmStaffService staff, Logger log, LongSupplier clock,
                        Predicate<UUID> spyPermitted) {
        this.firms = firms;
        this.staff = staff;
        this.log = log;
        this.clock = clock;
        this.spyPermitted = spyPermitted;
    }

    /**
     * Whether the player still holds the spy permission. An offline player can't be
     * asked (and can't hear anyway), so their toggle is left alone until they're back.
     */
    private static boolean holdsSpyPermission(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player == null || player.hasPermission(SPY_PERMISSION);
    }

    /** Register the firm-chat channel with CarbonChat if present. Call on enable. */
    @Override
    public void initialise() {
        try {
            this.carbon = CarbonFirmChat.register(this);
            log.info("Registered the firm chat channel with CarbonChat.");
        } catch (Throwable t) {
            this.carbon = null;
            log.info("CarbonChat unavailable — firm chat disabled (" + t.getClass().getSimpleName() + ").");
        }
    }

    /** Whether firm chat is usable (CarbonChat present + channel registered). */
    @Override
    public boolean available() {
        return carbon != null;
    }

    /**
     * The firm whose chat the player is currently in, if any. The selection is only
     * honoured while the player is still a member of that (still active) firm: a
     * selection whose last check is older than {@link #MEMBERSHIP_TTL_MS}, or that
     * {@link #membershipChanged(int)} flagged, is re-checked here, and one that no
     * longer holds is dropped and the player put back on Carbon's default channel —
     * so a fired or resigned employee can't keep posting to the firm.
     */
    @Override
    public Optional<Integer> activeFirm(UUID uuid) {
        Selection sel = activeFirm.get(uuid);
        if (sel == null) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        // STALE is MIN_VALUE; a plain (now - validatedAt) would overflow, so test it explicitly.
        if (sel.validatedAt() != STALE && now - sel.validatedAt() <= MEMBERSHIP_TTL_MS) {
            return Optional.of(sel.firmId());
        }
        boolean member;
        try {
            member = isCurrentMember(uuid, sel.firmId());
        } catch (RuntimeException ex) {
            // Can't confirm membership right now: fail closed for this message, but keep
            // the selection so a transient DB error doesn't kick the player out of chat.
            log.log(Level.WARNING, "Could not re-check firm chat membership for " + uuid, ex);
            return Optional.empty();
        }
        if (member) {
            // replace(), not put(): don't clobber a concurrent switch to another firm.
            activeFirm.replace(uuid, sel, new Selection(sel.firmId(), now));
            return Optional.of(sel.firmId());
        }
        CarbonFirmChat cc = carbon;
        if (activeFirm.remove(uuid, sel) && cc != null) {
            cc.selectDefaultChannel(uuid);
        }
        return Optional.empty();
    }

    /** Proprietor or current employee of a firm that hasn't been disbanded. */
    private boolean isCurrentMember(UUID uuid, int firmId) {
        Firm firm = firms.getFirmById(firmId); // active-only: a disbanded firm has no chat
        if (firm == null) {
            return false;
        }
        return uuid.toString().equalsIgnoreCase(firm.getProprietorUuid()) || staff.isEmployedBy(firmId, uuid);
    }

    /**
     * Flag every player tuned into the firm's chat for an immediate membership
     * re-check on their next message, instead of waiting out the TTL.
     */
    @Override
    public void membershipChanged(int firmId) {
        activeFirm.replaceAll((uuid, sel) -> sel.firmId() == firmId ? new Selection(firmId, STALE) : sel);
    }

    /**
     * Online recipients for a sender's active firm: its online staff who are also
     * currently tuned into THIS firm's chat. A multi-firm member who has another
     * firm (or no firm) selected is excluded, so messages don't bleed across the
     * firms a player belongs to.
     */
    @Override
    public List<Audience> recipients(UUID senderUuid) {
        // Re-validated: a sender who is no longer a member reaches nobody.
        Integer firmId = activeFirm(senderUuid).orElse(null);
        if (firmId == null) {
            return List.of();
        }
        // Keyed by UUID so a spy who is also a tuned-in member isn't added twice.
        Map<UUID, Audience> out = new LinkedHashMap<>();
        // The roster is read fresh from the DB, so a former employee is excluded here
        // even while their own (not yet re-checked) selection still points at the firm.
        for (Player employee : staff.getOnlineEmployees(firmId)) {
            Selection sel = activeFirm.get(employee.getUniqueId());
            if (sel != null && sel.firmId() == firmId) {
                out.put(employee.getUniqueId(), employee);
            }
        }
        // Chat moderators spying on this firm — globally, or this firm specifically (PAR-20).
        for (UUID spy : globalSpies) {
            if (stillSpy(spy)) {
                addOnline(out, spy);
            }
        }
        for (Map.Entry<UUID, Set<Integer>> e : firmSpies.entrySet()) {
            if (e.getValue().contains(firmId) && stillSpy(e.getKey())) {
                addOnline(out, e.getKey());
            }
        }
        return new ArrayList<>(out.values());
    }

    /**
     * Re-check a registered spy's permission; a spy who has lost it is dropped from
     * both registries (they must be re-granted and toggle again) and hears nothing.
     */
    private boolean stillSpy(UUID uuid) {
        if (spyPermitted.test(uuid)) {
            return true;
        }
        globalSpies.remove(uuid);
        firmSpies.remove(uuid);
        return false;
    }

    /** Add an online player to the recipient map (no-op if offline or already present). */
    private void addOnline(Map<UUID, Audience> out, UUID uuid) {
        if (out.containsKey(uuid)) {
            return;
        }
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            out.put(uuid, p);
        }
    }

    /** Toggle spying on ALL firm chat. Returns the new state (true = now spying). */
    @Override
    public boolean toggleGlobalSpy(UUID uuid) {
        if (globalSpies.add(uuid)) {
            return true;
        }
        globalSpies.remove(uuid);
        return false;
    }

    /** Toggle spying on a specific firm's chat. Returns the new state (true = now spying). */
    @Override
    public boolean toggleFirmSpy(UUID uuid, int firmId) {
        Set<Integer> watched = firmSpies.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        boolean nowOn = watched.add(firmId);
        if (!nowOn) {
            watched.remove(firmId);
        }
        if (watched.isEmpty()) {
            firmSpies.remove(uuid, watched);
        }
        return nowOn;
    }

    /** Whether the player is spying on the given firm (globally or that firm specifically). */
    @Override
    public boolean isSpyingOn(UUID uuid, Integer firmId) {
        Set<Integer> watched = firmSpies.get(uuid);
        boolean spying = globalSpies.contains(uuid)
                || (firmId != null && watched != null && watched.contains(firmId));
        return spying && stillSpy(uuid);
    }

    /** Whether the player is spying on anything (used to gate hearing on the channel). */
    @Override
    public boolean isAnySpy(UUID uuid) {
        return (globalSpies.contains(uuid) || firmSpies.containsKey(uuid)) && stillSpy(uuid);
    }

    /** Display name of a firm by id, for the spy-view prefix (null if unknown/disbanded). */
    @Override
    public String firmName(Integer firmId) {
        if (firmId == null) {
            return null;
        }
        Firm firm = firms.getFirmById(firmId);
        return firm == null ? null : firm.getDisplayName();
    }

    /**
     * Enter firm chat: set the active firm (explicit firmId, or the player's sole
     * firm when null) and select the Carbon channel. Returns the firm entered, or
     * empty on failure (not a member / ambiguous / unavailable).
     */
    @Override
    public Optional<Firm> enterChat(Player player, Integer firmId) {
        CarbonFirmChat cc = carbon;
        if (cc == null) {
            return Optional.empty();
        }
        UUID uuid = player.getUniqueId();
        Firm firm = resolveMemberFirm(uuid, firmId);
        if (firm == null) {
            return Optional.empty();
        }
        activeFirm.put(uuid, new Selection(firm.getFirmId(), clock.getAsLong()));
        cc.selectFirmChannel(uuid);
        return Optional.of(firm);
    }

    /** Leave firm chat: clear the active firm and switch back to Carbon's default channel. */
    @Override
    public void leaveChat(Player player) {
        UUID uuid = player.getUniqueId();
        activeFirm.remove(uuid);
        CarbonFirmChat cc = carbon;
        if (cc != null) {
            cc.selectDefaultChannel(uuid);
        }
    }

    /** Drop a player's state (e.g. on quit). */
    @Override
    public void forget(UUID uuid) {
        activeFirm.remove(uuid);
        globalSpies.remove(uuid);
        firmSpies.remove(uuid);
    }

    /** Whether the player belongs to more than one firm (so /firm chat needs an explicit firm). */
    @Override
    public boolean inMultipleFirms(UUID uuid) {
        return activeMemberFirms(uuid).size() > 1;
    }

    /**
     * The player's firms that still exist. listOwnedOrMemberFirms is archived-inclusive
     * (a disband neither clears the proprietor nor ends employments), and a disbanded
     * firm has no chat — counting one would make a single-firm player's bare
     * {@code /firm chat} ambiguous, or drop them into a defunct firm's channel.
     */
    private List<Firm> activeMemberFirms(UUID uuid) {
        return firms.listOwnedOrMemberFirms(uuid).stream()
                .filter(f -> !Boolean.TRUE.equals(f.getArchived()))
                .toList();
    }

    /** The firm a player may chat in: an explicit firm they're a member of, or their sole firm. */
    private Firm resolveMemberFirm(UUID uuid, Integer firmId) {
        List<Firm> mine = activeMemberFirms(uuid);
        if (mine.isEmpty()) {
            return null;
        }
        if (firmId != null) {
            // ADT-100: value equality, not Integer reference identity — boxed
            // Integers above the JVM cache (>127) are not == even when equal, which
            // silently broke explicit /firm chat selection for firm ids over 127.
            return mine.stream().filter(f -> firmId.equals(f.getFirmId())).findFirst().orElse(null);
        }
        return mine.size() == 1 ? mine.get(0) : null; // ambiguous when in multiple firms
    }

    /** A player's active-firm pointer and when their membership of it was last confirmed. */
    private record Selection(int firmId, long validatedAt) {}
}
