package io.paradaux.business.commands;

import io.paradaux.business.model.Firm;
import io.paradaux.business.services.FirmService;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FirmReleaseNameCommandTest {

    @Test
    void releaseRequiresDedicatedOpOnlyPermission() throws Exception {
        var method = FirmCommands.class.getMethod("adminReleaseName", CommandSender.class, int.class);
        assertThat(method.getAnnotation(Permission.class).value()).isEqualTo("business.admin.releasename");
        try (var stream = getClass().getResourceAsStream("/plugin.yml");
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var plugin = YamlConfiguration.loadConfiguration(reader);
            assertThat(plugin.getString("permissions.business.admin.releasename.default")).isEqualTo("op");
        }
    }

    @Test
    void releaseUsesExactFirmIdAndReportsPreservedName() {
        FirmService firms = mock(FirmService.class);
        Message message = mock(Message.class);
        CommandSender sender = mock(CommandSender.class);
        Firm archived = new Firm();
        archived.setFirmId(42);
        archived.setDisplayName("Acme");
        when(firms.adminReleaseFirmName(42)).thenReturn(archived);
        var command = new FirmCommands(null, null, firms, null, null, null, message);
        command.adminReleaseName(sender, 42);
        verify(firms).adminReleaseFirmName(42);
        verifyNoMoreInteractions(firms);
        verify(message).send(sender, "business.firm.admin.releasename.success", "firm", "Acme", "id", 42);
    }
}
