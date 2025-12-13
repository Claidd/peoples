package com.hunt.peoples.profiles.dto;

import com.hunt.peoples.browser.dto.BrowserStartResult;
import com.hunt.peoples.profiles.entity.Profile;
import lombok.Builder;

@Builder
public record BrowserOpenResponse(
        Long profileId,
        String vncUrl,
        String externalKey,
        String userAgent,        // добавлено
        String screenResolution, // добавлено
        String error
) {
    public static BrowserOpenResponse fromResult(BrowserStartResult result, Profile profile) {
        return BrowserOpenResponse.builder()
                .profileId(profile.getId())
                .vncUrl(result.vncUrl())
                .externalKey(profile.getExternalKey())
                .userAgent(profile.getUserAgent())
                .screenResolution(profile.getScreenWidth() + "x" + profile.getScreenHeight())
                .build();
    }
}