package com.hunt.peoples.browser.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchStartRequest {
    @NotEmpty(message = "profileIds cannot be empty")
    private List<Long> profileIds;
    private String proxyUrl;
}
