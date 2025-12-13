package com.hunt.peoples.browser.dto;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationResult {
    private Boolean detected;
    private Double confidence;
    private String description;

    // Вспомогательные методы
    public boolean isDetected() {
        return Boolean.TRUE.equals(detected);
    }

    public double getConfidence() {
        return confidence != null ? confidence : 0.0;
    }

    public boolean isHighConfidence() {
        return getConfidence() > 0.7;
    }

    public boolean isMediumConfidence() {
        double conf = getConfidence();
        return conf > 0.4 && conf <= 0.7;
    }

    public boolean isLowConfidence() {
        return getConfidence() <= 0.4;
    }

    // Для удобства дебаггинга
    @Override
    public String toString() {
        return String.format("SimulationResult{detected=%s, confidence=%.2f, description='%s'}",
                detected, getConfidence(), description != null ? description : "");
    }
}
