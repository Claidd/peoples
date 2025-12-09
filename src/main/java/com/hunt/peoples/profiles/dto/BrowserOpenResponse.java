package com.hunt.peoples.profiles.dto;

public record BrowserOpenResponse(
        Long profileId,
        String vncUrl,
        String externalKey
) {}
