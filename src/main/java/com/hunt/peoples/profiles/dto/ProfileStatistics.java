package com.hunt.peoples.profiles.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileStatistics {
    private Long profileId;

    // Статистика проверок профиля
    private Long totalChecks;
    private Long passedChecks;
    private Long failedChecks;
    private Long highRiskChecks;
    private Double passRate;
    private Double averageRisk;
    private Double latestRisk;
    private Instant latestCheckTime;
    private Map<String, Long> riskLevelDistribution;
    private Map<String, Double> componentRisks;

    // Статистика самого профиля (если нужно объединить)
    private Long totalProfiles;
    private Long activeProfiles;
    private Long freeProfiles;
    private Long highRiskProfiles;
    private Map<String, Long> platformDistribution;
    private Map<String, Long> levelDistribution;
    private Instant generatedAt;

    public static ProfileStatistics empty(Long profileId) {
        return ProfileStatistics.builder()
                .profileId(profileId)
                .totalChecks(0L)
                .passedChecks(0L)
                .failedChecks(0L)
                .highRiskChecks(0L)
                .passRate(0.0)
                .averageRisk(0.0)
                .latestRisk(0.0)
                .riskLevelDistribution(new HashMap<>())
                .componentRisks(new HashMap<>())
                .build();
    }
}
