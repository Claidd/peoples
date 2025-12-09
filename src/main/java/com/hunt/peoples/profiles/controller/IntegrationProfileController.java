package com.hunt.peoples.profiles.controller;

import com.hunt.peoples.browser.config.AppProperties;
import com.hunt.peoples.browser.config.BrowserProperties;
import com.hunt.peoples.browser.dto.BrowserStartResult;
import com.hunt.peoples.browser.service.BrowserContainerService;
import com.hunt.peoples.profiles.dto.BrowserOpenResponse;
import com.hunt.peoples.profiles.dto.ConnectRequest;
import com.hunt.peoples.profiles.entity.Profile;
import com.hunt.peoples.profiles.repository.ProfileRepository;
import com.hunt.peoples.profiles.service.ProfilesService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/integration/profiles")
@RequiredArgsConstructor
public class IntegrationProfileController {

    private final ProfileRepository profileRepository;
    private final ProfilesService profilesService;          // <-- добавили
    private final BrowserContainerService browserContainerService;
    private final BrowserProperties browserProperties;      // где baseDir

    @PostMapping("/connect")
    public BrowserOpenResponse connect(@RequestBody ConnectRequest request) {

        Profile profile = profilesService.findOrCreateByExternalKey(
                request.externalKey(),
                request.proxyUrl()
        );

        BrowserStartResult result = browserContainerService.startBrowser(profile, request.proxyUrl());

        profile.setStatus("BUSY");
        profile.setLastUsedAt(Instant.now());
        profileRepository.save(profile);

        return new BrowserOpenResponse(
                result.profileId(),
                result.vncUrl(),
                result.externalKey()
        );
    }

    @PostMapping("/{externalKey}/stop")
    public void stop(@PathVariable String externalKey) {
        profileRepository.findByExternalKey(externalKey).ifPresent(profile -> {
            browserContainerService.stopBrowser(profile.getId());
            profile.setStatus("FREE");
            profile.setLockedByUserId(null);
            profile.setLastUsedAt(Instant.now());
            profileRepository.save(profile);
        });
    }

    // если profilesService внутри себя не создаёт новый профиль — можешь
    // использовать этот метод из него
    private Profile createProfileForExternalKey(String externalKey, String proxyUrl) {
        String baseDir = browserProperties.getBaseDir();
        String userDataPath = baseDir + "/" + externalKey + "/userDataDir";

        Profile p = new Profile();
        p.setName("2gis_" + externalKey);
        p.setExternalKey(externalKey);
        p.setUserDataPath(userDataPath);
        p.setProxyUrl(proxyUrl);
        p.setStatus("FREE");
        p.setLastUsedAt(Instant.now());
        return profileRepository.save(p);
    }
}
