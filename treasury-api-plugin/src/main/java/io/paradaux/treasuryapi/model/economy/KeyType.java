package io.paradaux.treasuryapi.model.economy;

/**
 * The closed set of API-key scopes. Replaces the hand-typed {@code "PERSONAL"} /
 * {@code "BUSINESS"} / {@code "GOVERNMENT"} string literals that were scattered
 * across the service and command layers (treasury-api-plugin/structure/0003).
 *
 * <p>The enum constant names deliberately equal the {@code api_keys.key_type}
 * column values, so MyBatis' default enum handling round-trips each value through
 * {@link #name()} at the persistence boundary — no bespoke type handler needed.
 *
 * <p>{@code GOVERNMENT} is retained for forward-compat with the schema enum
 * (the migration baseline still permits it), even though no command path issues
 * a GOVERNMENT key today.
 *
 * <p>{@code SERVICE} (schema V15) is the trusted-backend scope verified by
 * treasury-rest-api. It is listed so a SERVICE row maps cleanly instead of
 * failing enum conversion on read, but this plugin never issues one and
 * {@code ApiKeyServiceImpl} refuses to let any player manage one.
 */
public enum KeyType {
    PERSONAL,
    BUSINESS,
    GOVERNMENT,
    SERVICE
}
