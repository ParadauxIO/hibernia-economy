package io.paradaux.treasury.mappers;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;

/**
 * Writes the published config snapshot in {@code economy_config} (economy-flyway
 * V32), which the explorer docs and the REST API read to show live tunables.
 * Treasury only writes it; nothing in the plugin reads it back.
 */
@Mapper
public interface EconomyConfigMapper {

    /**
     * Inserts or overwrites one published value. Rewriting an unchanged value
     * leaves {@code updated_at} alone (its ON UPDATE only fires when another
     * column actually changes), so repeated restarts are no-ops.
     */
    @Insert("""
            INSERT INTO economy_config (config_key, owner, value_type, config_value)
            VALUES (#{key}, #{owner}, #{type}, #{value})
            ON DUPLICATE KEY UPDATE
                owner        = VALUES(owner),
                value_type   = VALUES(value_type),
                config_value = VALUES(config_value)
            """)
    int upsert(@Param("key") String key,
               @Param("owner") String owner,
               @Param("type") String type,
               @Param("value") String value);

    /** Removes {@code owner}'s rows whose key is not in {@code keep} (settings it stopped publishing). */
    @Delete("""
            <script>
            DELETE FROM economy_config
             WHERE owner = #{owner}
               AND config_key NOT IN
             <foreach item='k' collection='keep' open='(' separator=',' close=')'>#{k}</foreach>
            </script>
            """)
    int deleteOwnedExcept(@Param("owner") String owner, @Param("keep") Collection<String> keep);
}
