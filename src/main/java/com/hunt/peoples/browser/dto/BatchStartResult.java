package com.hunt.peoples.browser.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchStartResult {
    private Long profileId;
    private Boolean success;
    private String vncUrl;
    private String errorMessage;

    public static BatchStartResult success(Long profileId, String vncUrl) {
        return BatchStartResult.builder()
                .profileId(profileId)
                .success(true)
                .vncUrl(vncUrl)
                .build();
    }

    public static BatchStartResult failed(Long profileId, String error) {
        return BatchStartResult.builder()
                .profileId(profileId)
                .success(false)
                .errorMessage(error)
                .build();
    }

    public static BatchStartResult alreadyRunning(Long profileId) {
        return BatchStartResult.builder()
                .profileId(profileId)
                .success(false)
                .errorMessage("Browser already running")
                .build();
    }
}
