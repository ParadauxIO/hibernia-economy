package io.paradaux.chestshop.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers the two hand-written {@link Item} constructors (the Lombok accessors aside). */
class ItemTest {

    @Test
    void emptyConstructor_leavesFieldsUnset() {
        Item item = new Item();
        assertThat(item.getId()).isZero();
        assertThat(item.getBase64ItemCode()).isNull();
    }

    @Test
    void blobConstructor_storesBase64Code() {
        Item item = new Item("QUJD");
        assertThat(item.getBase64ItemCode()).isEqualTo("QUJD");
        assertThat(item.getId()).isZero();
    }

    @Test
    void setters_roundTrip() {
        Item item = new Item();
        item.setId(42);
        item.setBase64ItemCode("XYZ");

        assertThat(item.getId()).isEqualTo(42);
        assertThat(item.getBase64ItemCode()).isEqualTo("XYZ");
    }
}
