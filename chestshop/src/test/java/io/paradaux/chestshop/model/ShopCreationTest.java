package io.paradaux.chestshop.model;

import io.paradaux.chestshop.model.ShopCreation.CreationOutcome;
import io.paradaux.chestshop.support.ServerTest;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the {@link ShopCreation} carrier: defensive cloning of sign lines, the cancel/outcome
 * state machine, owner-account resolution, and the {@link CreationOutcome} break-sign flag
 * (both enum constructors).
 */
class ShopCreationTest extends ServerTest {

    private final Sign sign = Mockito.mock(Sign.class);

    private ShopCreation creation(String... lines) {
        return new ShopCreation(player("Steve"), sign, lines);
    }

    @Test
    void constructor_clonesSignLines() {
        String[] source = {"a", "b", "c", "d"};
        ShopCreation creation = new ShopCreation(player("Steve"), sign, source);

        source[0] = "mutated";

        assertThat(creation.getSignLine((byte) 0)).isEqualTo("a");
        assertThat(creation.getSignLines()).containsExactly("a", "b", "c", "d");
    }

    @Test
    void newCreation_isNotCancelled() {
        ShopCreation creation = creation("a", "b", "c", "d");
        assertThat(creation.isCancelled()).isFalse();
        assertThat(creation.getOutcome()).isEqualTo(CreationOutcome.SHOP_CREATED_SUCCESSFULLY);
    }

    @Test
    void setCancelledTrue_setsOtherOutcome() {
        ShopCreation creation = creation("a", "b", "c", "d");
        creation.setCancelled(true);
        assertThat(creation.isCancelled()).isTrue();
        assertThat(creation.getOutcome()).isEqualTo(CreationOutcome.OTHER);
    }

    @Test
    void setCancelledFalse_restoresSuccess() {
        ShopCreation creation = creation("a", "b", "c", "d");
        creation.setOutcome(CreationOutcome.NO_CHEST);
        creation.setCancelled(false);
        assertThat(creation.isCancelled()).isFalse();
        assertThat(creation.getOutcome()).isEqualTo(CreationOutcome.SHOP_CREATED_SUCCESSFULLY);
    }

    @Test
    void setOutcome_nonSuccess_isCancelled() {
        ShopCreation creation = creation("a", "b", "c", "d");
        creation.setOutcome(CreationOutcome.INVALID_PRICE);
        assertThat(creation.isCancelled()).isTrue();
    }

    @Test
    void setSignLines_replacesAndClones() {
        ShopCreation creation = creation("a", "b", "c", "d");
        String[] replacement = {"w", "x", "y", "z"};
        creation.setSignLines(replacement);
        replacement[1] = "mutated";

        assertThat(creation.getSignLines()).containsExactly("w", "x", "y", "z");
    }

    @Test
    void setSignLine_replacesSingleLine() {
        ShopCreation creation = creation("a", "b", "c", "d");
        creation.setSignLine((byte) 2, "new");
        assertThat(creation.getSignLine((byte) 2)).isEqualTo("new");
    }

    @Test
    void exposesPlayerAndSign() {
        Player creator = player("Steve");
        ShopCreation creation = new ShopCreation(creator, sign, new String[]{"a", "b", "c", "d"});
        assertThat(creation.getPlayer()).isSameAs(creator);
        assertThat(creation.getSign()).isSameAs(sign);
    }

    @Test
    void ownerAccount_defaultsNull_andIsSettable() {
        ShopCreation creation = creation("a", "b", "c", "d");
        assertThat(creation.getOwnerAccount()).isNull();

        Account owner = new Account("Owner", UUID.randomUUID());
        creation.setOwnerAccount(owner);
        assertThat(creation.getOwnerAccount()).isSameAs(owner);
    }

    @Test
    void creationOutcome_breakSignFlag_matchesDeclaration() {
        Set<CreationOutcome> noBreak = EnumSet.of(
                CreationOutcome.ITEM_AUTOFILL, CreationOutcome.OTHER, CreationOutcome.SHOP_CREATED_SUCCESSFULLY);

        for (CreationOutcome outcome : CreationOutcome.values()) {
            assertThat(outcome.shouldBreakSign()).isEqualTo(!noBreak.contains(outcome));
        }
    }
}
