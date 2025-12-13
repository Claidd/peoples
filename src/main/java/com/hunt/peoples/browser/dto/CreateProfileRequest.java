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
public class CreateProfileRequest {
    @NotBlank(message = "externalKey is required")
    private String externalKey;

    private String name;
    private String proxyUrl;

    @Pattern(regexp = "iphone_14_pro|samsung_galaxy_s23|google_pixel_7|xiaomi_13|ipad_pro|macbook_pro|windows_pc|random",
            message = "Invalid device type")
    private String deviceType;

    @Pattern(regexp = "BASIC|ENHANCED|AGGRESSIVE", message = "Invalid detection level")
    private String detectionLevel;

    private Boolean forceNew;
}
