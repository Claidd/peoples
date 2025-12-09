package com.hunt.peoples.profiles.service;

import com.hunt.peoples.browser.config.BrowserProperties;
import com.hunt.peoples.profiles.entity.Profile;
import com.hunt.peoples.profiles.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProfilesService {

    private final ProfileRepository profileRepository;
    private final BrowserProperties browserProperties;

    public Profile findOrCreateByExternalKey(String externalKey, String proxyUrl) {
        String safeKey = sanitizeExternalKey(externalKey);

        return profileRepository.findByExternalKey(safeKey)
                .orElseGet(() -> createProfileForExternalKey(safeKey, proxyUrl));
    }

    private String sanitizeExternalKey(String key) {
        if (key == null) return "";
        key = key.trim();
        // убираем любые пробельные символы (пробел, табы, \n и т.п.)
        key = key.replaceAll("\\s+", "");
        // на всякий случай заменим «левые» символы
        key = key.replaceAll("[^0-9A-Za-z_-]", "_");
        return key;
    }

    private Profile createProfileForExternalKey(String safeExternalKey, String proxyUrl) {
        String baseDir = browserProperties.getBaseDir();
        String userDataPath = baseDir + "/" + safeExternalKey + "/userDataDir";

        Profile p = new Profile();
        p.setName("2gis_" + safeExternalKey);
        p.setExternalKey(safeExternalKey);
        p.setUserDataPath(userDataPath);
        p.setProxyUrl(proxyUrl);
        p.setStatus("FREE");
        p.setLastUsedAt(Instant.now());

        Profile saved = profileRepository.save(p);

        log.info("Created new profile {} for externalKey={}, path={}",
                saved.getId(), safeExternalKey, userDataPath);

        return saved;
    }
}



