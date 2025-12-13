package com.hunt.peoples.browser.dto;

import lombok.Builder;
import java.time.Instant;

@Builder
public record BrowserStartResult(
        Long profileId,
        String vncUrl,       // поле должно называться vncUrl
        String externalKey,
        String devToolsUrl,
        Instant expiresAt,
        Instant startedAt,
        String containerId
) {
    // Метод доступа уже есть автоматически в record
    // public String vncUrl() { return this.vncUrl; }
}