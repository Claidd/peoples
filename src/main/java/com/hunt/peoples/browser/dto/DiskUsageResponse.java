package com.hunt.peoples.browser.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DiskUsageResponse {
    private Long profileId;
    private String externalKey;
    private long sizeBytes;
    private String sizeHuman;
    private boolean directoryExists;
    private String error;
}