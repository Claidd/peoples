package com.hunt.peoples.profiles.controller;

import com.hunt.peoples.profiles.entity.Profile;
import com.hunt.peoples.profiles.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/profiles")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileRepository profileRepository;

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

}
