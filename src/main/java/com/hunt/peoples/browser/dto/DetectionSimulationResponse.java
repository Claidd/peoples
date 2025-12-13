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
public class DetectionSimulationResponse {
    private Long profileId;
    private String externalKey;
    private String detectionType;
    private Map<String, SimulationResult> simulations;
    private Double overallVulnerability;
    private String recommendations;
    private Instant simulatedAt;
}
