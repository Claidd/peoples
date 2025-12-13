package com.hunt.peoples.profiles.repository;

import com.hunt.peoples.profiles.entity.FingerprintCheck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface FingerprintCheckRepository extends JpaRepository<FingerprintCheck, Long> {

    List<FingerprintCheck> findByProfileId(Long profileId);

    List<FingerprintCheck> findByProfileIdOrderByCheckedAtDesc(Long profileId);

    @Query("SELECT fc FROM FingerprintCheck fc WHERE fc.profile.id = :profileId AND fc.passed = false")
    List<FingerprintCheck> findFailedChecksByProfileId(@Param("profileId") Long profileId);

    @Query("SELECT fc FROM FingerprintCheck fc WHERE fc.overallRisk >= :riskThreshold")
    List<FingerprintCheck> findHighRiskChecks(@Param("riskThreshold") Double riskThreshold);

    @Query("SELECT fc FROM FingerprintCheck fc WHERE fc.needsAction = true")
    List<FingerprintCheck> findChecksNeedingAction();

    @Query("SELECT fc FROM FingerprintCheck fc WHERE fc.checkedAt >= :startDate AND fc.checkedAt <= :endDate")
    List<FingerprintCheck> findChecksBetweenDates(@Param("startDate") Instant startDate,
                                                  @Param("endDate") Instant endDate);

    @Query("SELECT fc FROM FingerprintCheck fc WHERE fc.profile.id = :profileId " +
            "AND fc.checkedAt = (SELECT MAX(fc2.checkedAt) FROM FingerprintCheck fc2 WHERE fc2.profile.id = :profileId)")
    Optional<FingerprintCheck> findLatestCheckByProfileId(@Param("profileId") Long profileId);

    @Query("SELECT COUNT(fc) FROM FingerprintCheck fc WHERE fc.profile.id = :profileId")
    Long countByProfileId(@Param("profileId") Long profileId);

    @Query("SELECT AVG(fc.overallRisk) FROM FingerprintCheck fc WHERE fc.profile.id = :profileId")
    Double getAverageRiskByProfileId(@Param("profileId") Long profileId);

    @Modifying
    @Query("DELETE FROM FingerprintCheck fc WHERE fc.checkedAt < :date")
    void deleteOldChecks(@Param("date") Instant date);

    Page<FingerprintCheck> findByProfileId(Long profileId, Pageable pageable);
}
