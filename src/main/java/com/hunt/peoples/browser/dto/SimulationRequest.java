package com.hunt.peoples.browser.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationRequest {
    @NotBlank
    @Pattern(regexp = "canvas|webgl|fonts|webdriver|timezone|proxy|all")
    private String detectionType;
}