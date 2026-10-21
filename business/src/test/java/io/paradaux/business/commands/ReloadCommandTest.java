package io.paradaux.business.commands;

import io.paradaux.business.Business;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.mockito.Mockito.*;

class ReloadCommandTest {
    @Test
    void reloadFailureDoesNotExposeExceptionDetails() {
        Business business = mock(Business.class);
        Message message = mock(Message.class);
        CommandSender sender = mock(CommandSender.class);
        when(business.getLogger()).thenReturn(mock(Logger.class));
        doThrow(new RuntimeException("private configuration details")).when(business).reloadConfig();
        var command = new ReloadCommand(business, message, null, null);

        command.reload(sender);

        verify(message).send(sender, "business.admin.reload.failed", "error", "Could not reload configuration");
        verifyNoMoreInteractions(message);
    }
}
