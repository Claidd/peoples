package com.hunt.peoples.browser.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceTypeInfo {
    private String id;
    private String name;
    private String platform;
    private String screenSize;
    private Double pixelRatio;
    private String userAgent;
    private Boolean isMobile;
}
