package com.hunt.peoples.browser.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FingerprintDashboard {
    private Long totalProfiles;
    private Long activeProfiles;
    private Long highRiskProfiles;
    private Long totalChecks;
    private Double averageRisk;
    private Map<String, Long> checksByDay;
    private Map<String, Long> riskDistribution;
    private Map<String, Long> deviceDistribution;
    private Instant generatedAt;
    private Integer timeRangeDays;
}
