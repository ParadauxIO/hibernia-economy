package io.paradaux.chestshop.listeners;
import io.paradaux.chestshop.utils.SignText;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

/**
 * Removes garbage characters from the sign
 *
 * @author Andrzej Pomirski
 */
public class GarbageTextListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public static void filterGarbage(SignChangeEvent event) {
        for (int i = 0; i < 4; ++i) {
            String line = SignText.getLine(event, i);
            if (line != null) {
                StringBuilder output = new StringBuilder(line.length());

                for (char character : line.toCharArray()) {
                    if (character < 0xF700 || character > 0xF747) {
                        output.append(character);
                    }
                }

                SignText.setLine(event, i, output.toString());
            }
        }
    }
}
