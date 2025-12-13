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
public class ProfileSummaryStatistics {
    private Long totalProfiles;
    private Long activeProfiles;
    private Long freeProfiles;
    private Long highRiskProfiles;
    private Map<String, Long> platformDistribution;
    private Map<String, Long> levelDistribution;
    private Instant generatedAt;
}
