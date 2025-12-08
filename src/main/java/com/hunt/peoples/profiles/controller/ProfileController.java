package com.hunt.peoples.profiles.controller;

import com.hunt.peoples.browser.config.AppProperties;
import com.hunt.peoples.browser.service.BrowserContainerService;
import com.hunt.peoples.profiles.entity.Profile;
import com.hunt.peoples.profiles.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/profiles")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileRepository profileRepository;
    private final BrowserContainerService browserContainerService;
    private final AppProperties appProperties;

    @GetMapping
    public List<Profile> getAll() {
        return profileRepository.findAll();
    }

    @PostMapping
    public Profile create(@RequestBody Profile profile) {
        // На первом этапе минимальная логика
        profile.setId(null);                // на всякий случай
        profile.setStatus("FREE");
        profile.setLastUsedAt(Instant.now());
        return profileRepository.save(profile);
    }

    @PostMapping("/{id}")
    public void delete(@PathVariable Long id) {
        profileRepository.deleteById(id);
    }


    @PostMapping("/{id}/start")
    public Map<String, String> start(@PathVariable Long id) {
        Profile profile = profileRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Profile not found"));

        String vncUrl = browserContainerService.startBrowser(
                profile.getId(),
                profile.getUserDataPath(),
                profile.getProxyUrl(),
                appProperties.getHostBaseUrl()
        );

        profile.setStatus("BUSY");
        profile.setLastUsedAt(Instant.now());
        profileRepository.save(profile);

        return Map.of("vncUrl", vncUrl);
    }

    @PostMapping("/{id}/stop")
    public void stop(@PathVariable Long id) {
        browserContainerService.stopBrowser(id);

        profileRepository.findById(id).ifPresent(p -> {
            p.setStatus("FREE");
            p.setLockedByUserId(null);
            profileRepository.save(p);
        });
    }

}
