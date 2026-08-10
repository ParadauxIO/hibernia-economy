package io.paradaux.business.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.mappers.FirmPropertyMapper;
import io.paradaux.business.model.FirmProperty;
import io.paradaux.business.services.FirmPropertyService;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

@Singleton
public class FirmPropertyServiceImpl implements FirmPropertyService {

    private static final Logger LOG = Logger.getLogger("Business");

    private final FirmPropertyMapper mapper;

    @Inject
    public FirmPropertyServiceImpl(FirmPropertyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<String> getString(int firmId, String key) {
        FirmProperty prop = mapper.getProperty(firmId, key);
        if (prop == null || !"STRING".equals(prop.getType())) return Optional.empty();
        return Optional.of(prop.getValue());
    }

    @Override
    public Optional<Integer> getInteger(int firmId, String key) {
        FirmProperty prop = mapper.getProperty(firmId, key);
        if (prop == null || !"INTEGER".equals(prop.getType())) return Optional.empty();
        try {
            return Optional.of(Integer.parseInt(prop.getValue()));
        } catch (NumberFormatException e) {
            // Corrupt value, not merely absent — surface it instead of silently
            // reading as not-set (ADT-56).
            LOG.log(Level.WARNING, "Corrupt INTEGER property firm=" + firmId + " key=" + key
                    + " value=" + prop.getValue());
            return Optional.empty();
        }
    }

    @Override
    public Optional<BigDecimal> getBigDecimal(int firmId, String key) {
        FirmProperty prop = mapper.getProperty(firmId, key);
        if (prop == null || !"BIGDECIMAL".equals(prop.getType())) return Optional.empty();
        try {
            return Optional.of(new BigDecimal(prop.getValue()));
        } catch (NumberFormatException e) {
            // Corrupt value, not merely absent — surface it instead of silently
            // reading as not-set (ADT-56).
            LOG.log(Level.WARNING, "Corrupt BIGDECIMAL property firm=" + firmId + " key=" + key
                    + " value=" + prop.getValue());
            return Optional.empty();
        }
    }

    @Override
    public Optional<Boolean> getBoolean(int firmId, String key) {
        FirmProperty prop = mapper.getProperty(firmId, key);
        if (prop == null || !"BOOLEAN".equals(prop.getType())) return Optional.empty();
        return Optional.of(Boolean.parseBoolean(prop.getValue()));
    }

    @Override
    public void setString(int firmId, String key, String value) {
        mapper.setProperty(firmId, key, value, "STRING");
    }

    @Override
    public void setInteger(int firmId, String key, int value) {
        mapper.setProperty(firmId, key, String.valueOf(value), "INTEGER");
    }

    @Override
    public void setBigDecimal(int firmId, String key, BigDecimal value) {
        mapper.setProperty(firmId, key, value.toPlainString(), "BIGDECIMAL");
    }

    @Override
    public void setBoolean(int firmId, String key, boolean value) {
        mapper.setProperty(firmId, key, String.valueOf(value), "BOOLEAN");
    }

    @Override
    public void delete(int firmId, String key) {
        mapper.deleteProperty(firmId, key);
    }
}
