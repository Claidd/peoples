package com.hunt.peoples.browser.dto;

import com.hunt.peoples.profiles.entity.FingerprintCheck;
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
public class FingerprintTestResponse {
    private Long profileId;
    private Boolean success;
    private Double overallRisk;
    private String riskLevel;
    private Boolean passed;
    private String testUrl;
    private Instant checkedAt;
    private Long responseTimeMs;
    private Map<String, Object> checkDetails;
    private String errorMessage;

    public static FingerprintTestResponse fromCheck(FingerprintCheck check) {
        return FingerprintTestResponse.builder()
                .profileId(check.getProfile().getId())
                .success(true)
                .overallRisk(check.getOverallRisk())
                .riskLevel(check.getRiskLevel())
                .passed(check.getPassed())
                .testUrl(check.getTestUrl())
                .checkedAt(check.getCheckedAt())
                .responseTimeMs(check.getResponseTimeMs())
                .checkDetails(check.getCheckSummary())
                .build();
    }

    public static FingerprintTestResponse error(Long profileId, String error) {
        return FingerprintTestResponse.builder()
                .profileId(profileId)
                .success(false)
                .errorMessage(error)
                .checkedAt(Instant.now())
                .build();
    }
}