package com.hunt.peoples.browser.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchStopResponse {
    private Integer total;
    private Integer successCount;
    private Integer failedCount;
    private List<BatchStopResult> results;
    private Instant completedAt;
}
