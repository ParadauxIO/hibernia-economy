package io.paradaux.treasuryapi.services;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.treasuryapi.mappers.DerivedGroupMapper;
import io.paradaux.treasuryapi.model.GroupSource;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.mybatis.guice.transactional.Transactional;

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
                reconcileBinding(binding);
            } catch (Exception | LinkageError e) {
                LOG.log(
                        Level.WARNING,
                        "Explorer reconciliation failed for " + binding
                                + "; unrefreshed memberships expire after one hour",
                        e);
            }
        }
        for (var binding : mapper.listUnconfigured()) removeUnconfigured(binding);
    }

    /** Use the same binding-before-members lock order as publication. */
    @Transactional
    public void removeUnconfigured(GroupSource binding) {
        if (mapper.lockSource(binding) == null) mapper.removeUnconfigured(binding);
    }

    /** Guice intercepts this call, including calls from reconcile(), on the enhanced instance.
     * Each binding commits independently; a failed write must not publish half a membership set.
     */
    @Transactional
    public void reconcileBinding(GroupSource binding) throws ReflectiveOperationException {
        if (!binding.equals(mapper.lockSource(binding))) return;
        var desired = switch (binding.source()) {
            case "business" -> business.resolve(binding.selector());
            case "realty" -> realty.resolve(binding.selector());
            default -> throw new IllegalArgumentException("Unknown source: " + binding.source());
        };
        for (var player : desired) mapper.refresh(binding, player);
        for (var player : mapper.listMembers(binding)) {
            if (!desired.contains(player)) mapper.remove(binding, player);
        }
    }
}
