package com.hunt.peoples.browser.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class CleanupResponse {
    private int deletedCount;
    private int daysThreshold;
    private String message;
    private String error;
    private Instant cleanedAt;
}
