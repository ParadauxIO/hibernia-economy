package io.paradaux.chestshop.model;

import io.paradaux.chestshop.support.ServerTest;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the {@link DestroyedShop} carrier: the nullable destroyer and container, and the sign.
 */
class DestroyedShopTest extends ServerTest {

    private final Sign sign = Mockito.mock(Sign.class);
    private final Container container = Mockito.mock(Container.class);

    @Test
    void exposesDestroyerContainerAndSign() {
        Player destroyer = player("Steve");
        DestroyedShop shop = new DestroyedShop(destroyer, sign, container);

        assertThat(shop.getDestroyer()).isSameAs(destroyer);
        assertThat(shop.getContainer()).isSameAs(container);
        assertThat(shop.getSign()).isSameAs(sign);
    }

    @Test
    void destroyerAndContainer_mayBeNull() {
        DestroyedShop shop = new DestroyedShop(null, sign, null);

        assertThat(shop.getDestroyer()).isNull();
        assertThat(shop.getContainer()).isNull();
        assertThat(shop.getSign()).isSameAs(sign);
    }
}
