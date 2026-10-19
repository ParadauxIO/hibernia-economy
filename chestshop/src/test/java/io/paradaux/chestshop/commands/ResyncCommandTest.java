package io.paradaux.chestshop.commands;

import static org.mockito.Mockito.*;

import io.paradaux.chestshop.services.MarketResyncService;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.Test;

class ResyncCommandTest {
    @Test
    void consoleCanStartAResync() {
        var service = mock(MarketResyncService.class);
        var console = mock(ConsoleCommandSender.class);
        new ResyncCommand(service).resync(console, 4);
        verify(service).resync(console, 4);
    }
}
