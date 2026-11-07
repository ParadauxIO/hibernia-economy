package io.paradaux.business.services;

import io.paradaux.business.internal.model.directdebit.DirectDebitKind;
import io.paradaux.business.internal.model.directdebit.DirectDebitMandate;
import io.paradaux.business.internal.model.directdebit.DirectDebitPeriod;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Business direct debits (PAR-557): a customer accepts a mandate letting a firm
 * debit their personal account into the firm's account, either a fixed amount each
 * period ({@link DirectDebitKind#RECURRING}) or ad hoc charges whose period total
 * stays within the cap ({@link DirectDebitKind#ON_DEMAND}).
 *
 * <p>Every entry point is gated on the {@value #FEATURE_FLAG} release flag and
 * rejects with {@code business.debit.disabled} while it is off. Firm-finance
 * permission for the firm-side operations is enforced by the caller, as for the
 * other firm payment operations; customer-side operations check the actor is the
 * mandate's payer.
 */
public interface DirectDebitService {

    String FEATURE_FLAG = "business.direct_debits";

    /** {@code plugin_system} recorded on every collection's ledger transaction. */
    String PLUGIN_SYSTEM = "business-direct-debit";

    boolean isEnabled();

    /**
     * Proposes a mandate payable into the firm's default account. For
     * {@link DirectDebitKind#RECURRING} {@code limit} is the amount collected each
     * period; for {@link DirectDebitKind#ON_DEMAND} it is the per-period cap.
     */
    DirectDebitMandate propose(
            int firmId,
            UUID actorId,
            UUID payerId,
            DirectDebitKind kind,
            DirectDebitPeriod period,
            BigDecimal limit,
            String reference);

    DirectDebitMandate accept(long mandateId, UUID payerId);

    DirectDebitMandate reject(long mandateId, UUID payerId);

    /** The customer cancels a pending, active or suspended mandate. */
    DirectDebitMandate revoke(long mandateId, UUID payerId);

    /** The customer resumes a mandate suspended after missed recurring collections. */
    DirectDebitMandate resume(long mandateId, UUID payerId);

    /** The firm cancels one of its mandates. */
    DirectDebitMandate cancelByFirm(int firmId, long mandateId, UUID actorId);

    /**
     * Charges an active on-demand mandate. Throws when the charge would exceed the
     * period cap or the ledger transfer fails; a failed transfer is still recorded.
     */
    Charge charge(int firmId, long mandateId, UUID actorId, BigDecimal amount, String memo);

    List<DirectDebitMandate> listForPayer(UUID payerId);

    List<DirectDebitMandate> listForFirm(int firmId);

    /**
     * One pass of the background job: expires lapsed proposals, settles stale
     * reservations, then collects due recurring mandates. A no-op while the flag is off.
     */
    JobResult runJob();

    /** A successful charge. */
    record Charge(long collectionId, long txnId, BigDecimal amount, BigDecimal remainingThisPeriod) {}

    /** Tally of one {@link #runJob()} pass. */
    record JobResult(int expired, int recovered, int collected, int failed) {
        public boolean isEmpty() {
            return expired == 0 && recovered == 0 && collected == 0 && failed == 0;
        }
    }
}
