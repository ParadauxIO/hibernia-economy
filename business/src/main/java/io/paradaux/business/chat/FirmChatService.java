package io.paradaux.business.chat;

import io.paradaux.business.model.Firm;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns firm-chat state and the single CarbonChat channel (PAR-20). Firm
 * membership and the per-player "active firm" pointer live here (a player can be
 * in several firms, unlike Factions), so {@link FirmChatChannel} can resolve
 * recipients at send time without inflating Carbon's per-message channel loop.
 *
 * <p>CarbonChat is a soft dependency: if it isn't present the feature simply
 * disables itself ({@link #available()} returns false) and the command reports
 * it as unavailable.
 */
public interface FirmChatService {

    /** Register the firm-chat channel with CarbonChat if present. Call on enable. */
    void initialise();

    /** Whether firm chat is usable (CarbonChat present + channel registered). */
    boolean available();

    /** The firm whose chat the player is currently in, if any. */
    Optional<Integer> activeFirm(UUID uuid);

    /**
     * Online recipients for a sender's active firm: its online staff who are also
     * currently tuned into THIS firm's chat. A multi-firm member who has another
     * firm (or no firm) selected is excluded, so messages don't bleed across the
     * firms a player belongs to.
     */
    List<Audience> recipients(UUID senderUuid);

    /** Toggle spying on ALL firm chat. Returns the new state (true = now spying). */
    boolean toggleGlobalSpy(UUID uuid);

    /** Toggle spying on a specific firm's chat. Returns the new state (true = now spying). */
    boolean toggleFirmSpy(UUID uuid, int firmId);

    /** Whether the player is spying on the given firm (globally or that firm specifically). */
    boolean isSpyingOn(UUID uuid, Integer firmId);

    /** Whether the player is spying on anything (used to gate hearing on the channel). */
    boolean isAnySpy(UUID uuid);

    /** Display name of a firm by id, for the spy-view prefix (null if unknown/disbanded). */
    String firmName(Integer firmId);

    /**
     * Enter firm chat: set the active firm (explicit firmId, or the player's sole
     * firm when null) and select the Carbon channel. Returns the firm entered, or
     * empty on failure (not a member / ambiguous / unavailable).
     */
    Optional<Firm> enterChat(Player player, Integer firmId);

    /** Leave firm chat: clear the active firm and switch back to Carbon's default channel. */
    void leaveChat(Player player);

    /** Drop a player's state (e.g. on quit). */
    void forget(UUID uuid);

    /** Whether the player belongs to more than one firm (so /firm chat needs an explicit firm). */
    boolean inMultipleFirms(UUID uuid);
}
