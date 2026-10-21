package io.paradaux.treasury.commands;

import static org.mockito.Mockito.*;

import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.api.ingest.IngestApi;
import io.paradaux.treasury.services.ConfigReloadService;
import io.paradaux.treasury.services.LedgerService;
import java.math.BigDecimal;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.exceptions.PersistenceException;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

class TreasuryCommandErrorTest {
    @Test
    void ingestFailureDoesNotExposeExceptionTypeOrSqlDetails() {
        Message message = mock(Message.class);
        CommandSender sender = mock(CommandSender.class);
        IngestApi ingest = mock(IngestApi.class);
        ServicesManager services = mock(ServicesManager.class);
        when(ingest.supportedSources()).thenReturn(Set.of("legacy"));
        when(services.getRegistrations(IngestApi.class))
                .thenReturn(List.of(
                        new RegisteredServiceProvider<>(IngestApi.class, ingest, ServicePriority.Normal, null)));
        when(ingest.ingest("legacy", sender)).thenThrow(new PersistenceException("private SQL details"));
        var command = new TreasuryCommand(null, message, null, null, null, null, null, null);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            command.adminIngest(sender, "legacy");
        }

        verify(message).send(sender, "treasury.admin.ingest.starting", "source", "legacy");
        verify(message)
                .send(
                        sender,
                        "treasury.admin.ingest.failed",
                        "error",
                        "Import could not be completed. Check the server logs before retrying.");
        verifyNoMoreInteractions(message);
    }

    @Test
    void transferDatabaseFailureDoesNotExposeSqlDetails() {
        Message message = mock(Message.class);
        CommandSender sender = mock(CommandSender.class);
        LedgerService ledger = mock(LedgerService.class);
        AccountResolver resolver = mock(AccountResolver.class);
        when(resolver.resolve(sender, "from", "account", "1", true))
                .thenReturn(new AccountResolver.Resolved(1, "Source"));
        when(resolver.resolve(sender, "to", "account", "2", true))
                .thenReturn(new AccountResolver.Resolved(2, "Destination"));
        doThrow(new PersistenceException(
                        "SQL INSERT INTO ledger_postings: fk_post_account",
                        new SQLIntegrityConstraintViolationException("foreign key violation")))
                .when(ledger)
                .adminTransfer(any());
        var command = new TreasuryCommand(null, message, null, ledger, null, resolver, null, null);

        command.adminTransfer(sender, "account", "1", "account", "2", BigDecimal.TEN);

        verify(message)
                .send(
                        sender,
                        "treasury.admin.transfer.failed",
                        "error",
                        "An unexpected error occurred. Check the server logs before retrying.");
        verifyNoMoreInteractions(message);
    }

    @Test
    void reloadFailureDoesNotExposeExceptionDetails() {
        Message message = mock(Message.class);
        CommandSender sender = mock(CommandSender.class);
        ConfigReloadService reload = mock(ConfigReloadService.class);
        doThrow(new RuntimeException("private configuration details"))
                .when(reload)
                .reloadAll();
        var command = new TreasuryCommand(null, message, null, null, null, null, reload, null);

        command.reload(sender);

        verify(message).send(sender, "treasury.admin.reload.failed", "error", "Could not reload configuration");
        verifyNoMoreInteractions(message);
    }
}
