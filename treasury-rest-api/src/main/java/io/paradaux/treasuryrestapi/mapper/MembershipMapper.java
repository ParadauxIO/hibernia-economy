package io.paradaux.treasuryrestapi.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

@Mapper
public interface MembershipMapper {

    /**
     * Returns a count > 0 if the given UUID currently has API read access to the
     * given account. Used by the transaction history endpoint to allow
     * business-account members to view history beyond their own account.
     *
     * <p>Reads the {@code account_read_access_api} view (ADT-13) — the single
     * source of truth for the public-API access rule over the consolidated
     * {@code account_access} table (PAR-249): level MEMBER or AUTHORIZER, not
     * soft-deleted. A read-only VIEWER does <em>not</em> count for the API (the
     * web explorer is deliberately more permissive — see the view definition in
     * {@code V22__account_read_access_views.sql}).
     */
    @Select("SELECT COUNT(*) FROM account_read_access_api " +
            "WHERE account_id = #{accountId} " +
            "  AND subject_uuid_bin = #{memberUuid}")
    int isMember(@Param("accountId") long accountId, @Param("memberUuid") UUID memberUuid);
}
