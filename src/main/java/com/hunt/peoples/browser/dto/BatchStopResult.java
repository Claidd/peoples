package com.hunt.peoples.browser.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchStopResult {
    private Long profileId;
    private Boolean success;
    private String errorMessage;

    public static BatchStopResult success(Long profileId) {
        return BatchStopResult.builder()
                .profileId(profileId)
                .success(true)
                .build();
    }

    public static BatchStopResult failed(Long profileId, String error) {
        return BatchStopResult.builder()
                .profileId(profileId)
                .success(false)
                .errorMessage(error)
                .build();
    }
}
