package io.paradaux.treasury.mappers;

import io.paradaux.treasury.model.economy.AccountRedirect;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * Lookups for {@code account_redirects} — the table that routes Vault
 * calls for legacy "player" UUIDs onto their canonical GOVERNMENT
 * accounts.
 *
 * <p>See the {@code V2__account_redirects.sql} migration for the
 * rationale: DemocracyCraft's previous economy stored government
 * ledgers (DCGovernment, GovReserve, etc.) as Essentials "players"
 * with their own UUIDs. Plugins called Vault with those UUIDs; with
 * Treasury those calls should land on actual GOVERNMENT accounts.
 *
 * <p>Reads on the hot path — a redirect lookup happens on every Vault
 * deposit/withdraw/balance call. Write side is only used by
 * TreasuryIngest during a one-shot migration.
 */
@Mapper
public interface AccountRedirectMapper {

    /**
     * Returns the redirected {@code account_id} for the given UUID, or
     * {@code null} if no redirect is configured. The hot path: read
     * once per Vault call before falling through to PERSONAL resolution.
     */
    @Select("SELECT account_id FROM account_redirects WHERE redirect_uuid_bin = #{uuid}")
    Integer findRedirectAccountId(@Param("uuid") UUID uuid);

    /**
     * Loads every redirect in one shot. Used by
     * {@link io.paradaux.treasury.services.cache.AccountRedirectCache} to warm an
     * in-memory mirror — the table is tiny and effectively static at runtime, so
     * a single bulk read replaces a per-Vault-call point lookup.
     */
    @Select("SELECT redirect_uuid_bin, account_id FROM account_redirects")
    @Results({
            @Result(column = "redirect_uuid_bin", property = "redirectUuid"),
            @Result(column = "account_id", property = "accountId")
    })
    List<AccountRedirect> findAllRedirects();

    /**
     * Inserts (or replaces) a redirect for {@code uuid}. Used by
     * TreasuryIngest to map legacy Essentials player UUIDs onto the
     * GOVERNMENT account they should henceforth feed.
     */
    @Insert("""
            INSERT INTO account_redirects(redirect_uuid_bin, account_id, note)
            VALUES(#{uuid}, #{accountId}, #{note})
            ON DUPLICATE KEY UPDATE
                account_id = VALUES(account_id),
                note       = VALUES(note)
            """)
    int upsertRedirect(@Param("uuid") UUID uuid,
                       @Param("accountId") int accountId,
                       @Param("note") String note);
}
