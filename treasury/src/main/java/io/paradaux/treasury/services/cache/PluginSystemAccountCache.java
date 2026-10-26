package io.paradaux.treasury.services.cache;

import com.google.inject.Inject;
import io.paradaux.treasury.services.AccountService;
import java.util.UUID;

public class PluginSystemAccountCache {
    private final AccountService accountService;

    @Inject
    public PluginSystemAccountCache(AccountService accountService) {
        this.accountService = accountService;
    }

    public int getOrCreate(String pluginName, UUID treasuryOwner) {
        // Resolution participates in the caller's transaction. A newly created
        // account can roll back with a rejected withdrawal, so publishing its ID
        // to a process-wide cache would poison later settlements. Resolve through
        // the database until we have transaction-commit-aware cache publication.
        // AccountService handles concurrent creation using the unique plugin key.
        return accountService.getOrCreateSystemAccountId(pluginName, treasuryOwner);
    }
}
