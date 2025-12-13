package com.hunt.peoples.profiles.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "fingerprint_checks")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class FingerprintCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id", nullable = false)
    private Profile profile;

    // Результаты проверок
    private Boolean canvasConsistent;
    private Boolean webglConsistent;
    private Boolean timezoneConsistent;
    private Boolean fontsConsistent;
    private Boolean webDriverDetected;
    private Boolean automationDetected;
    private Boolean proxyDetected;
    private Boolean headlessDetected;

    // Детали проверок
    @Column(columnDefinition = "TEXT")
    private String canvasDetails;

    @Column(columnDefinition = "TEXT")
    private String webglDetails;

    @Column(columnDefinition = "TEXT")
    private String fontsDetails;

    @Column(columnDefinition = "TEXT")
    private String automationDetails;

    // URL тестирования
    private String testUrl;
    private String referrerUrl;

    // Временные метки
    private Instant checkedAt;
    private Long responseTimeMs;

    // Риски и оценки
    private Double canvasRisk;
    private Double webglRisk;
    private Double fontsRisk;
    private Double automationRisk;
    private Double proxyRisk;

    // Итоговые оценки
    private Double overallRisk;
    private String riskLevel; // LOW, MEDIUM, HIGH, CRITICAL

    @Column(columnDefinition = "TEXT")
    private String recommendations;

    // Статус
    private Boolean passed;
    private Boolean needsAction;
    private String actionTaken;

    // Метаданные
    private String userAgentUsed;
    private String ipAddress;
    private String countryCode;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    // Вспомогательные методы
    @Transient
    public Map<String, Object> getCheckSummary() {
        Map<String, Object> summary = new HashMap<>();

        summary.put("canvasConsistent", canvasConsistent);
        summary.put("webglConsistent", webglConsistent);
        summary.put("timezoneConsistent", timezoneConsistent);
        summary.put("fontsConsistent", fontsConsistent);
        summary.put("webDriverDetected", webDriverDetected);
        summary.put("automationDetected", automationDetected);
        summary.put("overallRisk", overallRisk);
        summary.put("riskLevel", riskLevel);
        summary.put("passed", passed);

        return summary;
    }

    @Transient
    public boolean isHighRisk() {
        return overallRisk != null && overallRisk >= 0.7;
    }

    @Transient
    public boolean needsUrgentAction() {
        return Boolean.TRUE.equals(needsAction) &&
                (overallRisk != null && overallRisk >= 0.8);
    }
}
