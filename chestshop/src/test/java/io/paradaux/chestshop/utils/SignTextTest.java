package io.paradaux.chestshop.utils;

import io.paradaux.chestshop.support.ServerTest;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.block.SignChangeEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bukkit.block.sign.Side.FRONT;

class SignTextTest extends ServerTest {

    private Sign placeSign() {
        World world = server.addSimpleWorld("w");
        Block block = world.getBlockAt(new Location(world, 0, 64, 0));
        block.setType(Material.OAK_SIGN);
        return (Sign) block.getState();
    }

    @Test
    void sign_roundTripsAColourCodedLine() {
        Sign sign = placeSign();
        SignText.setLine(sign, 1, "§cPrice");
        sign.update();
        assertThat(SignText.getLine(sign, 1)).isEqualTo("§cPrice");
    }

    @Test
    void sign_setNullTextWritesAnEmptyLine() {
        Sign sign = placeSign();
        SignText.setLine(sign, 0, null);
        sign.update();
        assertThat(SignText.getLine(sign, 0)).isEmpty();
    }

    @Test
    void event_roundTripsAColourCodedLine() {
        SignChangeEvent event = signChangeEvent();
        SignText.setLine(event, 2, "§aStock");
        assertThat(SignText.getLine(event, 2)).isEqualTo("§aStock");
    }

    @Test
    void event_setNullTextWritesAnEmptyLine() {
        SignChangeEvent event = signChangeEvent();
        SignText.setLine(event, 3, null);
        assertThat(SignText.getLine(event, 3)).isEmpty();
    }

    @Test
    void event_getLine_returnsEmptyForANullComponentLine() {
        // a genuinely null line (not an empty component) serialises to ""
        SignChangeEvent event = org.mockito.Mockito.mock(SignChangeEvent.class);
        org.mockito.Mockito.when(event.line(0)).thenReturn(null);
        assertThat(SignText.getLine(event, 0)).isEmpty();
    }

    private SignChangeEvent signChangeEvent() {
        World world = server.addSimpleWorld("we");
        Block block = world.getBlockAt(new Location(world, 1, 64, 0));
        block.setType(Material.OAK_SIGN);
        Player player = player("editor");
        List<Component> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            lines.add(Component.empty());
        }
        return new SignChangeEvent(block, player, lines, FRONT);
    }

    @Test
    void isUtilityClass_privateConstructor() throws Exception {
        Constructor<SignText> ctor = SignText.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isNotNull();
    }
}
