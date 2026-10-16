package io.paradaux.business.chat;

import net.draycia.carbon.api.CarbonChat;
import net.draycia.carbon.api.CarbonChatProvider;
import net.draycia.carbon.api.channels.ChatChannel;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holder for everything that touches {@code net.draycia.carbon.*} on behalf of
 * {@link FirmChatServiceImpl} (PAR-20). CarbonChat is only a soft dependency, and
 * Guice reflects over the service's declared fields, methods and constructors at
 * injector-build time — including the synthetic methods javac emits for lambda
 * bodies. A single Carbon type in any of those signatures makes that reflection
 * throw {@code NoClassDefFoundError} on a server without CarbonChat, failing the
 * whole plugin enable. So no Carbon-typed statement (or lambda) may live in the
 * service: they all live here, and this class is only linked from inside the
 * service's guarded {@code initialise()}.
 *
 * <p>This class itself has no Carbon supertype, so merely <em>loading</em> it (as
 * the type of the service's field) is safe without CarbonChat; only linking it
 * ({@link #register}) needs Carbon on the classpath.
 */
final class CarbonFirmChat {

    private static final Logger LOG = Logger.getLogger(CarbonFirmChat.class.getName());

    private final CarbonChat carbon;
    private final FirmChatChannel channel;

    private CarbonFirmChat(CarbonChat carbon, FirmChatChannel channel) {
        this.carbon = carbon;
        this.channel = channel;
    }

    /** Register the firm-chat channel with CarbonChat. Throws if CarbonChat is absent. */
    static CarbonFirmChat register(FirmChatService service) {
        CarbonChat cc = CarbonChatProvider.carbonChat();
        FirmChatChannel ch = new FirmChatChannel(service);
        cc.channelRegistry().register(ch);
        return new CarbonFirmChat(cc, ch);
    }

    /** Switch the player onto the firm-chat channel. */
    void selectFirmChannel(UUID uuid) {
        select(uuid, channel);
    }

    /** Switch the player back to Carbon's default channel. */
    void selectDefaultChannel(UUID uuid) {
        select(uuid, carbon.channelRegistry().defaultChannel());
    }

    private void select(UUID uuid, ChatChannel target) {
        // userManager().user(uuid) is async; selecting on completion is fine for a toggle,
        // but a failed lookup/select must be logged rather than dropped with the future.
        carbon.userManager().user(uuid)
                .thenAccept(cp -> cp.selectedChannel(target))
                .exceptionally(t -> {
                    LOG.log(Level.WARNING, "Could not switch " + uuid + "'s CarbonChat channel", t);
                    return null;
                });
    }
}
