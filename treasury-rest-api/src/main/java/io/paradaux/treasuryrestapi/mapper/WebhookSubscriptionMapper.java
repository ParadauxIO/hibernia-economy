package io.paradaux.treasuryrestapi.mapper;

import io.paradaux.treasuryrestapi.model.SubscriptionMatch;
import io.paradaux.treasuryrestapi.model.WebhookSubscription;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

import java.util.List;

@Mapper
public interface WebhookSubscriptionMapper {

    String COLS = "subscription_id, api_key_id AS apiKeyId, owner_uuid_bin AS ownerUuid, key_type AS keyType, "
            + "account_id AS accountId, firm_id AS firmId, target_url AS targetUrl, secret, active, "
            + "consecutive_failures AS consecutiveFailures, disabled_at AS disabledAt, "
            + "created_at AS createdAt, updated_at AS updatedAt";

    @Options(useGeneratedKeys = true, keyProperty = "subscriptionId", keyColumn = "subscription_id")
    @Insert("INSERT INTO webhook_subscription "
            + "(api_key_id, owner_uuid_bin, key_type, account_id, firm_id, target_url, secret) "
            + "VALUES (#{apiKeyId}, #{ownerUuid}, #{keyType}, #{accountId}, #{firmId}, #{targetUrl}, #{secret})")
    void insert(WebhookSubscription sub);

    @Select("SELECT " + COLS + " FROM webhook_subscription WHERE subscription_id = #{id}")
    WebhookSubscription findById(@Param("id") long id);

    @Select("SELECT " + COLS + " FROM webhook_subscription WHERE api_key_id = #{apiKeyId} ORDER BY subscription_id")
    List<WebhookSubscription> listByApiKey(@Param("apiKeyId") long apiKeyId);

    @Select("SELECT COUNT(*) FROM webhook_subscription WHERE api_key_id = #{apiKeyId}")
    int countByApiKey(@Param("apiKeyId") long apiKeyId);

    /**
     * Updates the target URL and active flag. Re-activating ({@code active=1})
     * clears the failure counter and {@code disabled_at} so a fixed endpoint
     * resumes cleanly.
     */
    @Update("UPDATE webhook_subscription "
            + "SET target_url = #{targetUrl}, active = #{active}, "
            + "    consecutive_failures = IF(#{active}, 0, consecutive_failures), "
            + "    disabled_at = IF(#{active}, NULL, disabled_at) "
            + "WHERE subscription_id = #{id}")
    int update(@Param("id") long id, @Param("targetUrl") String targetUrl, @Param("active") boolean active);

    @Delete("DELETE FROM webhook_subscription WHERE subscription_id = #{id}")
    int delete(@Param("id") long id);

    // ── ADT-14: owner-optional admin mutators (replace the explorer's direct
    //    owner-scoped writes). When ownerUuid is non-null the row must also match
    //    the owner (self-service); when null it's a fleet-wide admin op. ──

    @Update("<script>UPDATE webhook_subscription "
            + "SET active = #{active}, "
            + "    consecutive_failures = IF(#{active}, 0, consecutive_failures), "
            + "    disabled_at = IF(#{active}, NULL, disabled_at) "
            + "WHERE subscription_id = #{id}"
            + "<if test='ownerUuid != null'> AND owner_uuid_bin = #{ownerUuid}</if></script>")
    int setActiveScoped(@Param("id") long id, @Param("ownerUuid") UUID ownerUuid, @Param("active") boolean active);

    @Update("<script>UPDATE webhook_subscription SET target_url = #{targetUrl} "
            + "WHERE subscription_id = #{id}"
            + "<if test='ownerUuid != null'> AND owner_uuid_bin = #{ownerUuid}</if></script>")
    int setUrlScoped(@Param("id") long id, @Param("ownerUuid") UUID ownerUuid, @Param("targetUrl") String targetUrl);

    @Update("<script>UPDATE webhook_subscription SET secret = #{secret} "
            + "WHERE subscription_id = #{id}"
            + "<if test='ownerUuid != null'> AND owner_uuid_bin = #{ownerUuid}</if></script>")
    int setSecretScoped(@Param("id") long id, @Param("ownerUuid") UUID ownerUuid, @Param("secret") String secret);

    @Delete("<script>DELETE FROM webhook_subscription WHERE subscription_id = #{id}"
            + "<if test='ownerUuid != null'> AND owner_uuid_bin = #{ownerUuid}</if></script>")
    int deleteScoped(@Param("id") long id, @Param("ownerUuid") UUID ownerUuid);

    // ── Dispatcher: match new transactions' accounts to active subscriptions ──

    /** Active subscriptions directly scoped (PERSONAL/GOVERNMENT) to any of the given accounts. */
    @Select("<script>"
            + "SELECT subscription_id AS subscriptionId, account_id AS accountId "
            + "FROM webhook_subscription "
            + "WHERE active = 1 AND account_id IN "
            + "<foreach item='id' collection='accountIds' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<SubscriptionMatch> findAccountMatches(@Param("accountIds") List<Long> accountIds);

    /** Active BUSINESS subscriptions whose firm currently owns any of the given accounts. */
    @Select("<script>"
            + "SELECT s.subscription_id AS subscriptionId, fa.account_id AS accountId "
            + "FROM webhook_subscription s "
            + "JOIN firm_accounts fa ON fa.firm_id = s.firm_id AND fa.removed_at IS NULL "
            + "WHERE s.active = 1 AND s.firm_id IS NOT NULL AND fa.account_id IN "
            + "<foreach item='id' collection='accountIds' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<SubscriptionMatch> findFirmMatches(@Param("accountIds") List<Long> accountIds);

    // ── Dispatcher: delivery health ──────────────────────────────────────────

    @Update("UPDATE webhook_subscription SET consecutive_failures = consecutive_failures + 1 "
            + "WHERE subscription_id = #{id}")
    int incrementFailures(@Param("id") long id);

    @Update("UPDATE webhook_subscription SET consecutive_failures = 0 "
            + "WHERE subscription_id = #{id} AND consecutive_failures <> 0")
    int resetFailures(@Param("id") long id);

    /** Auto-disables a subscription once it has failed too many times in a row. */
    @Update("UPDATE webhook_subscription SET active = 0, disabled_at = NOW() "
            + "WHERE subscription_id = #{id} AND active = 1 AND consecutive_failures >= #{threshold}")
    int disableIfOverThreshold(@Param("id") long id, @Param("threshold") long threshold);
}
