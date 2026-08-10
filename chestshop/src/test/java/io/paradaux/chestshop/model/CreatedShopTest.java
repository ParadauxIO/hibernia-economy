package io.paradaux.chestshop.model;

import io.paradaux.chestshop.support.ServerTest;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the {@link CreatedShop} carrier: sign-line cloning/accessors, the nullable container
 * and owner account, and every branch of {@link CreatedShop#createdByOwner()}.
 */
class CreatedShopTest extends ServerTest {

    private final Sign sign = Mockito.mock(Sign.class);
    private final Container container = Mockito.mock(Container.class);

    private static final String[] LINES = {"a", "b", "c", "d"};

    @Test
    void constructor_clonesSignLines_andExposesAccessors() {
        String[] source = {"a", "b", "c", "d"};
        CreatedShop shop = new CreatedShop(player("Steve"), sign, container, source, null);
        source[0] = "mutated";

        assertThat(shop.getSignLine((short) 0)).isEqualTo("a");
        assertThat(shop.getSignLines()).containsExactly("a", "b", "c", "d");
    }

    @Test
    void exposesCreatorSignAndContainer() {
        Player creator = player("Steve");
        CreatedShop shop = new CreatedShop(creator, sign, container, LINES, null);

        assertThat(shop.getPlayer()).isSameAs(creator);
        assertThat(shop.getSign()).isSameAs(sign);
        assertThat(shop.getContainer()).isSameAs(container);
    }

    @Test
    void container_mayBeNull() {
        CreatedShop shop = new CreatedShop(player("Steve"), sign, null, LINES, null);
        assertThat(shop.getContainer()).isNull();
    }

    @Test
    void createdByOwner_nullOwnerAccount_isTrue() {
        CreatedShop shop = new CreatedShop(player("Steve"), sign, container, LINES, null);
        assertThat(shop.getOwnerAccount()).isNull();
        assertThat(shop.createdByOwner()).isTrue();
    }

    @Test
    void createdByOwner_ownerMatchesCreator_isTrue() {
        Player creator = player("Steve");
        Account owner = new Account("Steve", creator.getUniqueId());
        CreatedShop shop = new CreatedShop(creator, sign, container, LINES, owner);

        assertThat(shop.getOwnerAccount()).isSameAs(owner);
        assertThat(shop.createdByOwner()).isTrue();
    }

    @Test
    void createdByOwner_ownerDiffersFromCreator_isFalse() {
        Player creator = player("Steve");
        Account other = new Account("Alex", UUID.randomUUID());
        CreatedShop shop = new CreatedShop(creator, sign, container, LINES, other);

        assertThat(shop.createdByOwner()).isFalse();
    }
}
