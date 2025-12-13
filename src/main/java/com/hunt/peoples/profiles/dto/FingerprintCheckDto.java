package com.hunt.peoples.profiles.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.hunt.peoples.profiles.entity.FingerprintCheck;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FingerprintCheckDto {

    private Long id;
    private Long profileId;
    private String externalKey;

    // Результаты проверок
    private Boolean canvasConsistent;
    private Boolean webglConsistent;
    private Boolean timezoneConsistent;
    private Boolean fontsConsistent;
    private Boolean webDriverDetected;
    private Boolean automationDetected;
    private Boolean proxyDetected;
    private Boolean headlessDetected;

    // Детали
    private String canvasDetails;
    private String webglDetails;
    private String fontsDetails;
    private String automationDetails;

    // URL и реферер
    private String testUrl;
    private String referrerUrl;

    // Временные метки
    private Instant checkedAt;
    private Long responseTimeMs;

    // Риски
    private Double canvasRisk;
    private Double webglRisk;
    private Double fontsRisk;
    private Double automationRisk;
    private Double proxyRisk;
    private Double overallRisk;
    private String riskLevel;

    // Статус
    private Boolean passed;
    private Boolean needsAction;
    private String actionTaken;
    private String recommendations;

    // Метаданные
    private String userAgentUsed;
    private String ipAddress;
    private String countryCode;

    // Временные метки
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Конвертирует Entity в DTO
     */
    public static FingerprintCheckDto fromEntity(FingerprintCheck check) {
        if (check == null) return null;

        return FingerprintCheckDto.builder()
                .id(check.getId())
                .profileId(check.getProfile().getId())
                .externalKey(check.getProfile().getExternalKey())
                .canvasConsistent(check.getCanvasConsistent())
                .webglConsistent(check.getWebglConsistent())
                .timezoneConsistent(check.getTimezoneConsistent())
                .fontsConsistent(check.getFontsConsistent())
                .webDriverDetected(check.getWebDriverDetected())
                .automationDetected(check.getAutomationDetected())
                .proxyDetected(check.getProxyDetected())
                .headlessDetected(check.getHeadlessDetected())
                .canvasDetails(check.getCanvasDetails())
                .webglDetails(check.getWebglDetails())
                .fontsDetails(check.getFontsDetails())
                .automationDetails(check.getAutomationDetails())
                .testUrl(check.getTestUrl())
                .referrerUrl(check.getReferrerUrl())
                .checkedAt(check.getCheckedAt())
                .responseTimeMs(check.getResponseTimeMs())
                .canvasRisk(check.getCanvasRisk())
                .webglRisk(check.getWebglRisk())
                .fontsRisk(check.getFontsRisk())
                .automationRisk(check.getAutomationRisk())
                .proxyRisk(check.getProxyRisk())
                .overallRisk(check.getOverallRisk())
                .riskLevel(check.getRiskLevel())
                .passed(check.getPassed())
                .needsAction(check.getNeedsAction())
                .actionTaken(check.getActionTaken())
                .recommendations(check.getRecommendations())
                .userAgentUsed(check.getUserAgentUsed())
                .ipAddress(check.getIpAddress())
                .countryCode(check.getCountryCode())
                .createdAt(check.getCreatedAt())
                .updatedAt(check.getUpdatedAt())
                .build();
    }

    /**
     * Конвертирует список Entity в список DTO
     */
    public static List<FingerprintCheckDto> fromEntities(List<FingerprintCheck> checks) {
        return checks.stream()
                .map(FingerprintCheckDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Проверяет, является ли проверка высокорисковой
     */
    @JsonIgnore
    public boolean isHighRisk() {
        return overallRisk != null && overallRisk >= 0.7;
    }

    /**
     * Получает цвет для отображения риска
     */
    @JsonIgnore
    public String getRiskColor() {
        if (overallRisk == null) return "gray";

        if (overallRisk >= 0.8) return "red";
        if (overallRisk >= 0.7) return "orange";
        if (overallRisk >= 0.4) return "yellow";
        return "green";
    }

    /**
     * Получает иконку для статуса
     */
    @JsonIgnore
    public String getStatusIcon() {
        if (Boolean.TRUE.equals(passed)) {
            return "✅";
        } else if (isHighRisk()) {
            return "⚠️";
        } else {
            return "❌";
        }
    }
}
