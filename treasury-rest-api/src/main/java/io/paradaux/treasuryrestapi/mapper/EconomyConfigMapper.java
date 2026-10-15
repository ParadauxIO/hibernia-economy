package io.paradaux.treasuryrestapi.mapper;

import io.paradaux.treasuryrestapi.model.EconomyConfigRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Read-only access to {@code economy_config}, the snapshot of plugin config the
 * Treasury plugin publishes on start and reload (PAR-80). A handful of rows; the
 * prefix match rides the primary key.
 */
@Mapper
public interface EconomyConfigMapper {

    @Select("""
            SELECT config_key, value_type, config_value, updated_at
              FROM economy_config
             WHERE config_key LIKE CONCAT(#{prefix}, '%')
            """)
    List<EconomyConfigRow> findByPrefix(@Param("prefix") String prefix);
}
