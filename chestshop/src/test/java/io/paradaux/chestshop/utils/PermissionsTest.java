package io.paradaux.chestshop.utils;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionsTest {

    @Test
    void hasNode_trueOnAnExactMatch() {
        CommandSender s = mock(CommandSender.class);
        when(s.hasPermission("ChestShop.admin")).thenReturn(true);
        assertThat(Permissions.hasNode(s, "ChestShop.admin")).isTrue();
    }

    @Test
    void hasNode_trueOnALowercaseMatchOnly() {
        CommandSender s = mock(CommandSender.class);
        when(s.hasPermission("ChestShop.Admin")).thenReturn(false);
        when(s.hasPermission("chestshop.admin")).thenReturn(true);
        assertThat(Permissions.hasNode(s, "ChestShop.Admin")).isTrue();
    }

    @Test
    void hasNode_falseWhenNeitherCaseMatches() {
        CommandSender s = mock(CommandSender.class);
        when(s.hasPermission("ChestShop.admin")).thenReturn(false);
        when(s.hasPermission("chestshop.admin")).thenReturn(false);
        assertThat(Permissions.hasNode(s, "ChestShop.admin")).isFalse();
    }

    @Test
    void isGated_trueForEachElevatedStaffPrefix() {
        assertThat(Permissions.isGated("ChestShop.admin")).isTrue();
        assertThat(Permissions.isGated("ChestShop.adminshop")).isTrue();
        assertThat(Permissions.isGated("ChestShop.mod")).isTrue();
        assertThat(Permissions.isGated("ChestShop.name")).isTrue();
        assertThat(Permissions.isGated("ChestShop.othername.create")).isTrue();
        assertThat(Permissions.isGated("ChestShop.nofee")).isTrue();
        assertThat(Permissions.isGated("ChestShop.notax.buy")).isTrue();
        assertThat(Permissions.isGated("ChestShop.nolimit")).isTrue();
        assertThat(Permissions.isGated("ChestShop.group.vip")).isTrue();
    }

    @Test
    void isGated_falseForBasicPlayerNodes() {
        assertThat(Permissions.isGated("ChestShop.shop.buy")).isFalse();
        assertThat(Permissions.isGated("ChestShop.iteminfo")).isFalse();
    }

    @Test
    void hasPermissionSetFalse_trueWhenExplicitlyDeniedOnTheExactNode() {
        CommandSender s = mock(CommandSender.class);
        when(s.isPermissionSet("ChestShop.nofee")).thenReturn(true);
        when(s.hasPermission("ChestShop.nofee")).thenReturn(false);
        assertThat(Permissions.hasPermissionSetFalse(s, "ChestShop.nofee")).isTrue();
    }

    @Test
    void hasPermissionSetFalse_trueWhenExplicitlyDeniedOnTheLowercaseNodeOnly() {
        CommandSender s = mock(CommandSender.class);
        when(s.isPermissionSet("ChestShop.NoFee")).thenReturn(false);
        lenient().when(s.hasPermission("ChestShop.NoFee")).thenReturn(false);
        when(s.isPermissionSet("chestshop.nofee")).thenReturn(true);
        when(s.hasPermission("chestshop.nofee")).thenReturn(false);
        assertThat(Permissions.hasPermissionSetFalse(s, "ChestShop.NoFee")).isTrue();
    }

    @Test
    void hasPermissionSetFalse_falseWhenSetButGranted_exactNode() {
        CommandSender s = mock(CommandSender.class);
        when(s.isPermissionSet("ChestShop.nofee")).thenReturn(true);
        when(s.hasPermission("ChestShop.nofee")).thenReturn(true); // set but granted -> first clause false
        when(s.isPermissionSet("chestshop.nofee")).thenReturn(false);
        assertThat(Permissions.hasPermissionSetFalse(s, "ChestShop.nofee")).isFalse();
    }

    @Test
    void hasPermissionSetFalse_falseWhenSetButGranted_lowercaseNode() {
        CommandSender s = mock(CommandSender.class);
        when(s.isPermissionSet("chestshop.nofee")).thenReturn(true);
        when(s.hasPermission("chestshop.nofee")).thenReturn(true); // set but granted -> second clause false
        assertThat(Permissions.hasPermissionSetFalse(s, "chestshop.nofee")).isFalse();
    }

    @Test
    void hasPermissionSetFalse_falseWhenNotSet() {
        CommandSender s = mock(CommandSender.class);
        when(s.isPermissionSet("ChestShop.nofee")).thenReturn(false);
        when(s.isPermissionSet("chestshop.nofee")).thenReturn(false);
        assertThat(Permissions.hasPermissionSetFalse(s, "ChestShop.nofee")).isFalse();
    }

    @Test
    void isUtilityClass_privateConstructor() throws Exception {
        Constructor<Permissions> ctor = Permissions.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isNotNull();
    }
}
