package com.hunt.peoples.browser.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchTestResult {
    private Long profileId;
    private Boolean success;
    private String riskLevel;
    private Double overallRisk;
    private Boolean passed;
    private String message;
    private String errorMessage;
}
