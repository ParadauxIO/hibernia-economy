package io.paradaux.treasury.internal.utils;

import java.util.UUID;

/**
 * System identities. Configurable account names live on
 * {@link io.paradaux.treasury.internal.model.config.GovernmentConfiguration} —
 * read them from the configuration object, not from this class. The values
 * here are limited to identities that are intentionally not user-configurable.
 */
public final class TreasuryConstants {

    /** Virtual UUID used as the owner of all internal GOVERNMENT/SYSTEM accounts. */
    public static final UUID VIRTUAL_TREASURY_OWNER = new UUID(0L, 0L);

    /**
     * Virtual UUID used as the initiator for system-initiated transactions
     * (e.g. starting-balance seeding) and as the initiator when the sender is console.
     */
    public static final UUID VIRTUAL_TREASURY_INITIATOR = new UUID(0L, 1L);

    /** Plugin attribution recorded on system-initiated ledger entries. */
    public static final String TREASURY_PLUGIN_NAME = "Treasury-Core";

    /** Plugin attribution recorded on admin {@code /eco} ledger entries. */
    public static final String ECO_PLUGIN_SYSTEM = "Eco";

    /** Plugin attribution recorded on in-game {@code /pay} ledger entries (PAR-145). */
    public static final String PAY_PLUGIN_SYSTEM = "Treasury-Pay";

    /**
     * Display name of the GOVERNMENT account that backs admin {@code /eco}
     * give/take/set/reset operations. Not exposed to end users; not configurable.
     */
    public static final String ECO_ACCOUNT_NAME = "Eco";

    private TreasuryConstants() {}
}
