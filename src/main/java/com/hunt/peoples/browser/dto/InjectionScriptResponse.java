package com.hunt.peoples.browser.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InjectionScriptResponse {
    private Long profileId;
    private String externalKey;
    private String detectionLevel;
    private String script;
    private Integer scriptLength;
    private Instant generatedAt;
}
