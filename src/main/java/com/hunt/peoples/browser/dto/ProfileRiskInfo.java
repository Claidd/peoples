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
public class ProfileRiskInfo {
    private Long profileId;
    private String externalKey;
    private String userAgent;
    private String platform;
    private String status;
    private Double latestRisk;
    private String riskLevel;
    private Instant lastCheckTime;
    private Integer checkCount;
    private String recommendations;
}