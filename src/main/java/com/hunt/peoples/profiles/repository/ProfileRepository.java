package com.hunt.peoples.profiles.repository;

import com.hunt.peoples.profiles.entity.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

// Добавлен JpaSpecificationExecutor
public interface ProfileRepository extends JpaRepository<Profile, Long>, JpaSpecificationExecutor<Profile> {

    Optional<Profile> findByExternalKey(String externalKey);
    List<Profile> findByStatus(String status);
    List<Profile> findByIsActive(Boolean isActive);
    List<Profile> findByDetectionLevel(String detectionLevel);

    @Query("SELECT p FROM Profile p WHERE p.detectionRisk > :riskThreshold")
    List<Profile> findHighRiskProfiles(@Param("riskThreshold") Double riskThreshold);

    @Query("SELECT p FROM Profile p WHERE p.fingerprintUpdatedAt < :date")
    List<Profile> findProfilesWithOldFingerprint(@Param("date") Instant date);

    @Query("SELECT p FROM Profile p WHERE p.externalKey IN :keys")
    List<Profile> findByExternalKeys(@Param("keys") List<String> keys);

    @Query(value = "SELECT DISTINCT platform FROM profiles", nativeQuery = true)
    List<String> findAllPlatforms();

    Long countByStatus(String status);

    @Modifying
    @Query("UPDATE Profile p SET p.status = :status WHERE p.id = :id")
    void updateStatus(@Param("id") Long id, @Param("status") String status);

    @Modifying
    @Query("UPDATE Profile p SET p.detectionRisk = :risk WHERE p.id = :id")
    void updateDetectionRisk(@Param("id") Long id, @Param("risk") Double risk);

    List<Profile> findByDetectionRiskGreaterThanEqual(Double risk);
}