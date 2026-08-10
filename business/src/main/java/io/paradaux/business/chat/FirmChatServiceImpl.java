package io.paradaux.business.chat;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.Business;
import io.paradaux.business.model.Firm;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import net.draycia.carbon.api.CarbonChat;
import net.draycia.carbon.api.CarbonChatProvider;
import net.draycia.carbon.api.channels.ChatChannel;
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
import java.util.logging.Logger;

/**
 * Default {@link FirmChatService}: owns firm-chat state and the single CarbonChat
 * channel (PAR-20). Firm membership and the per-player "active firm" pointer live
 * here (a player can be in several firms, unlike Factions), so {@link FirmChatChannel}
 * can resolve recipients at send time without inflating Carbon's per-message channel
 * loop.
 *
 * <p>CarbonChat is a soft dependency: if it isn't present the feature simply
 * disables itself ({@link #available()} returns false) and the command reports
 * it as unavailable.
 */
@Singleton
public class FirmChatServiceImpl implements FirmChatService {

    private final FirmService firms;
    private final FirmStaffService staff;
    private final Logger log;

    /** player → the firm whose chat they're currently in. In-memory (cleared on leave). */
    private final Map<UUID, Integer> activeFirm = new ConcurrentHashMap<>();

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

    // Typed as Object — NOT CarbonChat / FirmChatChannel — on purpose. Guice scans
    // this class's declared fields and methods at injector-build time; if any
    // declared member referenced a net.draycia.carbon.* type, that reflection would
    // throw NoClassDefFoundError when CarbonChat isn't installed, failing the whole
    // injector before initialise() ever gets to soft-disable the feature. The actual
    // CarbonChat / FirmChatChannel are stored here at runtime and only ever touched
    // (via casts) inside the guarded method bodies below, which run only when Carbon
    // is present. (Keeps the soft-dependency genuinely soft.)
    private Object carbon;    // CarbonChat when present, else null
    private Object channel;   // FirmChatChannel when present, else null

    @Inject
    public FirmChatServiceImpl(FirmService firms, FirmStaffService staff, Business plugin) {
        this.firms = firms;
        this.staff = staff;
        this.log = plugin.getLogger();
    }

    /** Register the firm-chat channel with CarbonChat if present. Call on enable. */
    @Override
    public void initialise() {
        try {
            CarbonChat cc = CarbonChatProvider.carbonChat();
            FirmChatChannel ch = new FirmChatChannel(this);
            cc.channelRegistry().register(ch);
            this.carbon = cc;
            this.channel = ch;
            log.info("Registered the firm chat channel with CarbonChat.");
        } catch (Throwable t) {
            this.carbon = null;
            this.channel = null;
            log.info("CarbonChat unavailable — firm chat disabled (" + t.getClass().getSimpleName() + ").");
        }
    }

    /** Whether firm chat is usable (CarbonChat present + channel registered). */
    @Override
    public boolean available() {
        return carbon != null && channel != null;
    }

    /** The firm whose chat the player is currently in, if any. */
    @Override
    public Optional<Integer> activeFirm(UUID uuid) {
        return Optional.ofNullable(activeFirm.get(uuid));
    }

    /**
     * Online recipients for a sender's active firm: its online staff who are also
     * currently tuned into THIS firm's chat. A multi-firm member who has another
     * firm (or no firm) selected is excluded, so messages don't bleed across the
     * firms a player belongs to.
     */
    @Override
    public List<Audience> recipients(UUID senderUuid) {
        Integer firmId = activeFirm.get(senderUuid);
        if (firmId == null) {
            return List.of();
        }
        // Keyed by UUID so a spy who is also a tuned-in member isn't added twice.
        Map<UUID, Audience> out = new LinkedHashMap<>();
        for (Player employee : staff.getOnlineEmployees(firmId)) {
            if (firmId.equals(activeFirm.get(employee.getUniqueId()))) {
                out.put(employee.getUniqueId(), employee);
            }
        }
        // Chat moderators spying on this firm — globally, or this firm specifically (PAR-20).
        for (UUID spy : globalSpies) {
            addOnline(out, spy);
        }
        for (Map.Entry<UUID, Set<Integer>> e : firmSpies.entrySet()) {
            if (e.getValue().contains(firmId)) {
                addOnline(out, e.getKey());
            }
        }
        return new ArrayList<>(out.values());
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
        if (globalSpies.contains(uuid)) {
            return true;
        }
        Set<Integer> watched = firmSpies.get(uuid);
        return firmId != null && watched != null && watched.contains(firmId);
    }

    /** Whether the player is spying on anything (used to gate hearing on the channel). */
    @Override
    public boolean isAnySpy(UUID uuid) {
        return globalSpies.contains(uuid) || firmSpies.containsKey(uuid);
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
        if (!available()) {
            return Optional.empty();
        }
        UUID uuid = player.getUniqueId();
        Firm firm = resolveMemberFirm(uuid, firmId);
        if (firm == null) {
            return Optional.empty();
        }
        activeFirm.put(uuid, firm.getFirmId());
        selectChannel(uuid, channel);
        return Optional.of(firm);
    }

    /** Leave firm chat: clear the active firm and switch back to Carbon's default channel. */
    @Override
    public void leaveChat(Player player) {
        UUID uuid = player.getUniqueId();
        activeFirm.remove(uuid);
        if (available()) {
            selectChannel(uuid, ((CarbonChat) carbon).channelRegistry().defaultChannel());
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
        return firms.listOwnedOrMemberFirms(uuid).size() > 1;
    }

    // `target` is an Object (a ChatChannel at runtime) so this method's signature
    // stays free of net.draycia.* — see the carbon/channel field note above.
    private void selectChannel(UUID uuid, Object target) {
        // userManager().user(uuid) is async; selecting on completion is fine for a toggle.
        ((CarbonChat) carbon).userManager().user(uuid)
                .thenAccept(cp -> cp.selectedChannel((ChatChannel) target));
    }

    /** The firm a player may chat in: an explicit firm they're a member of, or their sole firm. */
    private Firm resolveMemberFirm(UUID uuid, Integer firmId) {
        List<Firm> mine = firms.listOwnedOrMemberFirms(uuid);
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
}
