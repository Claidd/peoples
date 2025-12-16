package com.hunt.peoples.browser.dto;

import lombok.Builder;
import java.time.Instant;

@Builder
public record BrowserStartResult(
        Long profileId,
        String vncUrl,
        String devToolsUrl,
        String externalKey,
        String containerId,
        Instant startedAt,
        Instant expiresAt
) {}