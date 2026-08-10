package io.paradaux.business.model;

import java.util.Locale;

public enum RolePermission {

    ADMIN,
    FINANCIAL,
    CHESTSHOP,
    DEFAULT;

    public static RolePermission fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Permission value cannot be null or blank");
        }

        // Locale.ROOT: a locale-sensitive toUpperCase() (e.g. Turkish "i" → "İ")
        // would corrupt the comparison on some servers (ADT-35).
        String normalized = value.trim().toUpperCase(Locale.ROOT);

        // Alias handling for backward compatibility
        if ("CHEST_SHOP".equals(normalized)) {
            return CHESTSHOP;
        }
        if ("ADMINISTRATOR".equals(normalized)) {
            return ADMIN;
        }

        // Match enum names
        for (RolePermission permission : values()) {
            if (permission.name().equalsIgnoreCase(normalized)) {
                return permission;
            }
        }

        throw new IllegalArgumentException("No permission matched value: " + value);
    }
}