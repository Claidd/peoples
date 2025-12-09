package com.hunt.peoples.profiles.repository;

import com.hunt.peoples.profiles.entity.Profile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProfileRepository extends JpaRepository<Profile, Long> {

    List<Profile> findByStatus(String status);

    Optional<Profile> findByExternalKey(String externalKey);

}