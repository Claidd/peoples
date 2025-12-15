package com.hunt.peoples.profiles.controller;

import com.hunt.peoples.browser.config.AppProperties;
import com.hunt.peoples.browser.dto.BrowserStartResult;
import com.hunt.peoples.browser.service.BrowserContainerService;
import com.hunt.peoples.profiles.entity.Profile;
import com.hunt.peoples.profiles.repository.ProfileRepository;
import com.hunt.peoples.profiles.service.FingerprintMonitor;
import com.hunt.peoples.profiles.service.ProfilesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/integration/profiles")
@RequiredArgsConstructor
@Tag(name = "Integration Profiles", description = "API для внешней интеграции (2GIS и другие системы)")
public class IntegrationProfileController {

    private final ProfileRepository profileRepository;
    private final ProfilesService profilesService;
    private final BrowserContainerService browserContainerService;
    private final FingerprintMonitor fingerprintMonitor;
    private final AppProperties appProperties;

    @PostMapping("/connect")
    @Operation(summary = "Подключиться к профилю или создать новый и запустить браузер")
    public ResponseEntity<IntegrationConnectResponse> connect(
            @Valid @RequestBody IntegrationConnectRequest request) {

        String externalKey = request.externalKey();
        String proxyUrl = request.proxyUrl();
        String deviceType = request.deviceType();
        String detectionLevel = request.detectionLevel();
        Boolean forceNew = request.forceNewFingerprint();

        log.info("Integration connect request: externalKey={}, proxyUrl={}, deviceType={}, level={}, forceNew={}",
                externalKey, proxyUrl, deviceType, detectionLevel, forceNew);

        try {
            // 1. Находим или создаем профиль с рандомными мобильными параметрами
            Profile profile = profilesService.findOrCreateByExternalKey(
                    externalKey,
                    proxyUrl,
                    deviceType,  // Если null, будет выбран рандомный мобильный
                    detectionLevel != null ? detectionLevel : "ENHANCED",
                    Boolean.TRUE.equals(forceNew)
            );

            log.info("Profile {} found/created for externalKey: {}, userAgent: {}",
                    profile.getId(), externalKey, profile.getUserAgent());

            // 2. Проверяем, не запущен ли уже браузер
            if (browserContainerService.isBrowserRunning(profile.getId())) {
                log.warn("Browser already running for profile {}", profile.getId());
                return handleAlreadyRunningProfile(profile);
            }

            // 3. Запускаем браузер
            BrowserStartResult result = browserContainerService.startBrowser(profile, proxyUrl);

            // 4. Обновляем статус профиля
            profile.setStatus("BUSY");
            profile.setLastUsedAt(Instant.now());
            profileRepository.save(profile);

            // 5. Формируем ответ
            IntegrationConnectResponse response = buildSuccessResponse(profile, result);

            log.info("Integration connect successful. Profile: {}, VNC: {}",
                    profile.getId(), response.getVncUrl());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Integration connect failed for externalKey: {}", externalKey, e);
            return buildErrorResponse(externalKey, e);
        }
    }

    @PostMapping("/{externalKey}/stop")
    @Operation(summary = "Остановить браузер для профиля по externalKey")
    public ResponseEntity<IntegrationStopResponse> stop(@PathVariable String externalKey) {
        log.info("Integration stop request for externalKey: {}", externalKey);

        try {
            return profileRepository.findByExternalKey(externalKey)
                    .map(profile -> {
                        // Останавливаем браузер
                        browserContainerService.stopBrowser(profile.getId());

                        // Обновляем статус профиля
                        profile.setStatus("FREE");
                        profile.setLockedByUserId(null);
                        profile.setLastUsedAt(Instant.now());
                        profileRepository.save(profile);

                        log.info("Browser stopped for profile {} (externalKey: {})",
                                profile.getId(), externalKey);

                        return ResponseEntity.ok(IntegrationStopResponse.success(externalKey));
                    })
                    .orElseGet(() -> {
                        log.warn("Profile not found for externalKey: {}", externalKey);
                        return ResponseEntity.ok(IntegrationStopResponse.notFound(externalKey));
                    });

        } catch (Exception e) {
            log.error("Failed to stop browser for externalKey: {}", externalKey, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(IntegrationStopResponse.error(externalKey, e.getMessage()));
        }
    }

    private String tuneNoVncUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return rawUrl;

        // Важно: не затираем существующие параметры, только добавляем/переписываем нужные
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(rawUrl);

        b.replaceQueryParam("autoconnect", "1");
        b.replaceQueryParam("reconnect", "1");

        // ключевые для твоей проблемы:
        // - resize=none => noVNC не пытается “fit/scale” в iframe
        // - clip=true  => появятся скроллы (и горизонтальный тоже), можно “панорамировать”
        b.replaceQueryParam("resize", "none");
        b.replaceQueryParam("clip", "true");

        // опционально:
        // b.replaceQueryParam("show_dot_cursor", "1");
        // b.replaceQueryParam("view_only", "0");

        return b.build(true).toUriString();
    }

    @GetMapping("/{externalKey}/status")
    @Operation(summary = "Получить статус профиля по externalKey")
    public ResponseEntity<IntegrationStatusResponse> getStatus(@PathVariable String externalKey) {
        log.debug("Integration status request for externalKey: {}", externalKey);

        try {
            return profileRepository.findByExternalKey(externalKey)
                    .map(profile -> {
                        boolean isRunning = browserContainerService.isBrowserRunning(profile.getId());
                        var containerInfo = browserContainerService.getContainerInfo(profile.getId());

                        IntegrationStatusResponse response = IntegrationStatusResponse.builder()
                                .externalKey(externalKey)
                                .profileId(profile.getId())
                                .status(profile.getStatus())
                                .isBrowserRunning(isRunning)
                                .userAgent(profile.getUserAgent())
                                .platform(profile.getPlatform())
                                .screenResolution(getScreenResolution(profile))
                                .detectionLevel(profile.getDetectionLevel())
                                .detectionRisk(profile.getDetectionRisk())
                                .lastUsedAt(profile.getLastUsedAt())
                                .build();

                        if (isRunning && containerInfo.isPresent()) {
                            var container = containerInfo.get();
                            response.setVncUrl(buildVncUrl(container.getHostVncPort()));
                            response.setUptimeMinutes(container.getUptime().toMinutes());
                            response.setContainerId(container.getContainerId());
                        }

                        return ResponseEntity.ok(response);
                    })
                    .orElseGet(() -> ResponseEntity.ok(IntegrationStatusResponse.notFound(externalKey)));

        } catch (Exception e) {
            log.error("Failed to get status for externalKey: {}", externalKey, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(IntegrationStatusResponse.error(externalKey, e.getMessage()));
        }
    }

    @GetMapping("/available-mobile-devices")
    @Operation(summary = "Получить список доступных мобильных устройств для интеграции")
    public ResponseEntity<IntegrationDeviceListResponse> getAvailableMobileDevices() {
        log.debug("Available mobile devices request");

        try {
            List<String> mobileDeviceTypes = profilesService.getAvailableMobileDeviceTypes();

            List<IntegrationDeviceInfo> devices = mobileDeviceTypes.stream()
                    .map(type -> IntegrationDeviceInfo.builder()
                            .id(type)
                            .name(getDeviceDisplayName(type))
                            .platform(type.contains("iphone") || type.contains("ipad") ? "iOS" : "Android")
                            .screenSize(getDefaultScreenSize(type))
                            .isMobile(true)
                            .description("Mobile device: " + type)
                            .build())
                    .collect(Collectors.toList());

            // Добавляем опцию "random" для случайного выбора
            devices.add(IntegrationDeviceInfo.builder()
                    .id("random")
                    .name("Random Mobile Device")
                    .platform("Mixed")
                    .screenSize("Variable")
                    .isMobile(true)
                    .description("Random selection from available mobile devices")
                    .build());

            IntegrationDeviceListResponse response = IntegrationDeviceListResponse.builder()
                    .success(true)
                    .devices(devices)
                    .totalDevices(devices.size())
                    .timestamp(Instant.now())
                    .build();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to get available devices", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(IntegrationDeviceListResponse.error(e.getMessage()));
        }
    }

    // ================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ==================

    private ResponseEntity<IntegrationConnectResponse> handleAlreadyRunningProfile(Profile profile) {
        var containerInfo = browserContainerService.getContainerInfo(profile.getId());

        if (containerInfo.isPresent()) {
            var container = containerInfo.get();
            String vncUrl = buildVncUrl(container.getHostVncPort());

            IntegrationConnectResponse response = IntegrationConnectResponse.builder()
                    .externalKey(profile.getExternalKey())
                    .profileId(profile.getId())
                    .success(true)
                    .alreadyRunning(true)
                    .vncUrl(tuneNoVncUrl(vncUrl))
                    .userAgent(profile.getUserAgent())
                    .screenResolution(getScreenResolution(profile))
                    .platform(profile.getPlatform())
                    .detectionLevel(profile.getDetectionLevel())
                    .message("Browser already running for this profile")
                    .connectedAt(Instant.now())
                    .build();

            return ResponseEntity.ok(response);
        }

        log.warn("Container not found but browser marked as running for profile {}", profile.getId());
        browserContainerService.stopBrowser(profile.getId());

        BrowserStartResult result = browserContainerService.startBrowser(profile, profile.getProxyUrl());

        IntegrationConnectResponse response = buildSuccessResponse(profile, result);
        response.setMessage("Browser was restarted due to state inconsistency");

        return ResponseEntity.ok(response);
    }

    private IntegrationConnectResponse buildSuccessResponse(Profile profile, BrowserStartResult result) {
        return IntegrationConnectResponse.builder()
                .externalKey(profile.getExternalKey())
                .profileId(profile.getId())
                .success(true)
                .alreadyRunning(false)
                .vncUrl(tuneNoVncUrl(result.vncUrl()))
                .userAgent(profile.getUserAgent())
                .screenResolution(getScreenResolution(profile))
                .platform(profile.getPlatform())
                .detectionLevel(profile.getDetectionLevel())
                .fingerprintHash(profile.getFingerprintHash())
                .proxyUrl(profile.getProxyUrl())
                .message("Browser started successfully")
                .connectedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
    }

    private ResponseEntity<IntegrationConnectResponse> buildErrorResponse(String externalKey, Exception e) {
        IntegrationConnectResponse errorResponse = IntegrationConnectResponse.builder()
                .externalKey(externalKey)
                .success(false)
                .errorMessage("Failed to connect: " + e.getMessage())
                .connectedAt(Instant.now())
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }

    private String buildVncUrl(int port) {
        String hostBaseUrl = appProperties.getHostBaseUrl();
        if (hostBaseUrl == null || hostBaseUrl.isEmpty()) {
            return "http://localhost:" + port + "/vnc.html";
        }
        String host = hostBaseUrl.replaceFirst("^https?://", "");
        return "http://" + host + ":" + port + "/vnc.html";
    }

    private String getScreenResolution(Profile profile) {
        if (profile.getScreenWidth() != null && profile.getScreenHeight() != null) {
            return profile.getScreenWidth() + "x" + profile.getScreenHeight();
        }
        return "Unknown";
    }

    private String getDeviceDisplayName(String deviceType) {
        Map<String, String> displayNames = new HashMap<>();
        displayNames.put("iphone_14_pro", "iPhone 14 Pro");
        displayNames.put("iphone_13", "iPhone 13");
        displayNames.put("samsung_galaxy_s23", "Samsung Galaxy S23");
        displayNames.put("google_pixel_7", "Google Pixel 7");
        displayNames.put("xiaomi_13", "Xiaomi 13");
        displayNames.put("samsung_galaxy_tab_s8", "Samsung Galaxy Tab S8");
        displayNames.put("ipad_pro", "iPad Pro");
        displayNames.put("random", "Random Mobile Device");

        return displayNames.getOrDefault(deviceType, deviceType);
    }

    private String getDefaultScreenSize(String deviceType) {
        Map<String, String> screenSizes = new HashMap<>();
        screenSizes.put("iphone_14_pro", "393x852");
        screenSizes.put("iphone_13", "390x844");
        screenSizes.put("samsung_galaxy_s23", "412x915");
        screenSizes.put("google_pixel_7", "412x915");
        screenSizes.put("xiaomi_13", "412x915");
        screenSizes.put("samsung_galaxy_tab_s8", "800x1280");
        screenSizes.put("ipad_pro", "1024x1366");
        screenSizes.put("random", "Variable");

        return screenSizes.getOrDefault(deviceType, "Unknown");
    }

    // ================== DTO КЛАССЫ ==================

    public record IntegrationConnectRequest(
            String externalKey,
            String proxyUrl,
            String deviceType,
            String detectionLevel,
            Boolean forceNewFingerprint
    ) {}


    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntegrationConnectResponse {
        private String externalKey;
        private Long profileId;
        private Boolean success;
        private Boolean alreadyRunning;
        private String vncUrl;
        private String userAgent;
        private String screenResolution;
        private String platform;
        private String detectionLevel;
        private String fingerprintHash;
        private String proxyUrl;
        private String message;
        private String errorMessage;
        private Instant connectedAt;
        private Instant expiresAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntegrationStopResponse {
        private String externalKey;
        private Boolean success;
        private String message;
        private String errorMessage;
        private Instant stoppedAt;

        public static IntegrationStopResponse success(String externalKey) {
            return IntegrationStopResponse.builder()
                    .externalKey(externalKey)
                    .success(true)
                    .message("Browser stopped successfully")
                    .stoppedAt(Instant.now())
                    .build();
        }

        public static IntegrationStopResponse notFound(String externalKey) {
            return IntegrationStopResponse.builder()
                    .externalKey(externalKey)
                    .success(false)
                    .message("Profile not found")
                    .stoppedAt(Instant.now())
                    .build();
        }

        public static IntegrationStopResponse error(String externalKey, String error) {
            return IntegrationStopResponse.builder()
                    .externalKey(externalKey)
                    .success(false)
                    .errorMessage(error)
                    .stoppedAt(Instant.now())
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntegrationStatusResponse {
        private String externalKey;
        private Long profileId;
        private Boolean success;
        private String status;
        private Boolean isBrowserRunning;
        private String userAgent;
        private String platform;
        private String screenResolution;
        private String detectionLevel;
        private Double detectionRisk;
        private String vncUrl;
        private Long uptimeMinutes;
        private String containerId;
        private Instant lastUsedAt;
        private String errorMessage;

        public static IntegrationStatusResponse notFound(String externalKey) {
            return IntegrationStatusResponse.builder()
                    .externalKey(externalKey)
                    .success(false)
                    .errorMessage("Profile not found")
                    .build();
        }

        public static IntegrationStatusResponse error(String externalKey, String error) {
            return IntegrationStatusResponse.builder()
                    .externalKey(externalKey)
                    .success(false)
                    .errorMessage(error)
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntegrationDeviceListResponse {
        private Boolean success;
        private List<IntegrationDeviceInfo> devices;
        private Integer totalDevices;
        private Instant timestamp;
        private String errorMessage;

        public static IntegrationDeviceListResponse error(String error) {
            return IntegrationDeviceListResponse.builder()
                    .success(false)
                    .errorMessage(error)
                    .timestamp(Instant.now())
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntegrationDeviceInfo {
        private String id;
        private String name;
        private String platform;
        private String screenSize;
        private Boolean isMobile;
        private String description;
    }
}