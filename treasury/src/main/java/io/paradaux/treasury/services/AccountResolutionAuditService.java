package io.paradaux.treasury.services;

import com.google.inject.Inject;
import io.paradaux.treasury.internal.model.economy.AccountNameCollision;
import io.paradaux.treasury.mappers.AccountMapper;
import java.util.List;
import org.mybatis.guice.transactional.Transactional;

/** Reads current collisions without changing names, redirects, or balances. */
public class AccountResolutionAuditService {
    private final AccountMapper accounts;

    @Inject
    public AccountResolutionAuditService(AccountMapper accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public List<AccountNameCollision> findCollisions() {
        return accounts.findGovernmentNameCollisions();
    }
}
