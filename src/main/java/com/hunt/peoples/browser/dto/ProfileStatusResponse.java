package com.hunt.peoples.browser.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileStatusResponse {
    private Long profileId;
    private String externalKey;
    private String status;
    private Boolean isBrowserRunning;
    private Instant lastUsedAt;
    private Double detectionRisk;
    private String detectionLevel;

    // Container info (если запущен)
    private String containerId;
    private String vncUrl;
    private String devToolsUrl;
    private Long uptime; // в минутах

    // Directory info
    private Boolean directoryExists;
    private Long directorySize;
    private String directorySizeHuman;
}

