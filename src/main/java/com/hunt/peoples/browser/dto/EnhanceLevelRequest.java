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
public class EnhanceLevelRequest {
    @NotBlank
    @Pattern(regexp = "BASIC|ENHANCED|AGGRESSIVE")
    private String targetLevel;

    private Boolean updateScripts;
}
