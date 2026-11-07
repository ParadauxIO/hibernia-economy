package io.paradaux.business.internal.model;

import lombok.Data;

@Data
public class FirmProperty {
    private int firmId;
    private String key;
    private String value;
    private String type;
}
