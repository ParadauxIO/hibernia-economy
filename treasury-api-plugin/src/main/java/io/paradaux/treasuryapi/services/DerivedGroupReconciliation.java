package io.paradaux.treasuryapi.services;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.treasuryapi.mappers.DerivedGroupMapper;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Each binding resolves completely before publishing, and fails independently. */
@Singleton
public class DerivedGroupReconciliation {
    private static final Logger LOG = Logger.getLogger(DerivedGroupReconciliation.class.getName());
    private final DerivedGroupMapper mapper;
    private final BusinessGroupMembers business;
    private final RealtyGroupMembers realty;

    @Inject
    public DerivedGroupReconciliation(
            DerivedGroupMapper mapper, BusinessGroupMembers business, RealtyGroupMembers realty) {
        this.mapper = mapper;
        this.business = business;
        this.realty = realty;
    }

    public synchronized void reconcile() {
        for (var binding : mapper.listSources()) {
            try {
                var desired = switch (binding.source()) {
                    case "business" -> business.resolve(binding.selector());
                    case "realty" -> realty.resolve(binding.selector());
                    default -> throw new IllegalArgumentException("Unknown source: " + binding.source());
                };
                for (var player : desired) mapper.refresh(binding, player);
                for (var player : mapper.listMembers(binding)) {
                    if (!desired.contains(player)) mapper.remove(binding, player);
                }
            } catch (Exception | LinkageError e) {
                LOG.log(
                        Level.WARNING,
                        "Explorer reconciliation failed for " + binding
                                + "; unrefreshed memberships expire after one hour",
                        e);
            }
        }
        mapper.removeUnconfigured();
    }
}
