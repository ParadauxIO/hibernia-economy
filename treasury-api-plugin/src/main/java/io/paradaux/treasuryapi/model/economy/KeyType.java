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
 */
public enum KeyType {
    PERSONAL,
    BUSINESS,
    GOVERNMENT
}
