package io.paradaux.treasuryapi.services;

import java.util.UUID;

/**
 * Thin Keycloak Admin REST client for the in-game linking flow: authenticates
 * with the plugin's service-account client and sets a user's
 * {@code minecraft_uuid} (+ {@code minecraft_name}) attribute.
 */
public interface KeycloakAdminClient {

    /** Whether the Keycloak integration is configured/enabled. */
    boolean isEnabled();

    /** Merges the minecraft attributes into the Keycloak user identified by {@code sub}. */
    void setMinecraftAttributes(String sub, UUID uuid, String name) throws Exception;
}
