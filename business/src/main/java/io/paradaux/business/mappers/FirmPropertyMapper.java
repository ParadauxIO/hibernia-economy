package io.paradaux.business.mappers;

import io.paradaux.business.internal.model.FirmProperty;
import org.apache.ibatis.annotations.*;

/**
 * Firm key-value metadata.
 *
 * <p><b>Soft-delete semantics.</b> {@code deleteProperty} sets
 * {@code deleted_at}; reads filter {@code IS NULL}; {@code setProperty}
 * clears {@code deleted_at} when overwriting (a re-set "undeletes" the
 * key).
 */
@Mapper
public interface FirmPropertyMapper {

    @Select("""
        SELECT firm_id AS firmId,
               `key`,
               value,
               type
        FROM firm_properties
        WHERE firm_id = #{firmId}
          AND `key` = #{key}
          AND deleted_at IS NULL
        """)
    FirmProperty getProperty(@Param("firmId") int firmId, @Param("key") String key);

    @Insert("""
        INSERT INTO firm_properties (firm_id, `key`, value, type)
        VALUES (#{firmId}, #{key}, #{value}, #{type})
        ON DUPLICATE KEY UPDATE
            value = #{value},
            type = #{type},
            deleted_at = NULL
        """)
    void setProperty(
            @Param("firmId") int firmId,
            @Param("key") String key,
            @Param("value") String value,
            @Param("type") String type);

    /** Soft-deletes the property by stamping {@code deleted_at}. */
    @Update("""
        UPDATE firm_properties
           SET deleted_at = CURRENT_TIMESTAMP
         WHERE firm_id = #{firmId}
           AND `key` = #{key}
           AND deleted_at IS NULL
        """)
    void deleteProperty(@Param("firmId") int firmId, @Param("key") String key);
}
