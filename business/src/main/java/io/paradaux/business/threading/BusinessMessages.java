package io.paradaux.business.threading;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.hibernia.framework.models.HiberniaPlayer;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.bukkit.command.CommandSender;

/** Render locales/placeholders and resolve recipients on the server thread. */
@Singleton
public class BusinessMessages {
    private final Message message;
    private final MainThreadAccess mainThread;

    @Inject
    public BusinessMessages(Message message, MainThreadAccess mainThread) {
        this.message = message;
        this.mainThread = mainThread;
    }

    public void send(CommandSender to, String key, Object... values) {
        Object[] snapshot = values.clone();
        mainThread.execute(() -> message.send(to, key, snapshot));
    }

    public void send(HiberniaPlayer to, String key, Object... values) {
        send(to.getUniqueId(), key, values);
    }

    public void send(UUID to, String key, Object... values) {
        Object[] snapshot = values.clone();
        mainThread.execute(() -> message.send(to, key, snapshot));
    }

    public void send(Collection<? extends CommandSender> to, String key, Object... values) {
        var recipients = List.copyOf(to);
        Object[] snapshot = values.clone();
        mainThread.execute(() -> message.send(recipients, key, snapshot));
    }

    public void broadcast(String key, Object... values) {
        Object[] snapshot = values.clone();
        mainThread.execute(() -> message.broadcast(key, snapshot));
    }

    /** Bundle reload is disk I/O and stays on the caller's worker thread. */
    public void reload() {
        message.reload();
    }
}
