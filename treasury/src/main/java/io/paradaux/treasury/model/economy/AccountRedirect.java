package io.paradaux.treasury.model.economy;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * One row of {@code account_redirects}: a legacy "player" UUID that Vault calls
 * should route onto the canonical {@code account_id}. Loaded in bulk by
 * {@link io.paradaux.treasury.services.cache.AccountRedirectCache}.
 */
@Getter
@Setter
public class AccountRedirect {
    private UUID redirectUuid;
    private int accountId;
}
