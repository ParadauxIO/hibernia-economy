package io.paradaux.treasuryapi.commands;

import static org.mockito.Mockito.*;

import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.api.FeatureFlagApi;
import io.paradaux.treasuryapi.services.ApiKeyService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class GovernmentKeyHandlerTest {
    @Test
    void disabledCommandsDoNotCallKeyService() {
        var keys = mock(ApiKeyService.class);
        var message = mock(Message.class);
        var player = mock(Player.class);
        var handler = new GovernmentKeyHandler(keys, message, FeatureFlagApi.DISABLED);
        handler.doIssue(player, 42);
        handler.doReissue(player, 7);
        verifyNoInteractions(keys);
        verify(message, times(2)).send(player, "treasuryapi.government.disabled");
    }
}
