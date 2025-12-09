package com.hunt.peoples.browser.dto;

// multi-browser
public record BrowserStartResult(
        Long profileId,
        String vncUrl,
        String externalKey
) {}
