package com.hunt.peoples.profiles.controller;

import com.hunt.peoples.browser.config.AppProperties;
import com.hunt.peoples.browser.dto.*;
import com.hunt.peoples.browser.service.BrowserContainerService;
import com.hunt.peoples.profiles.dto.*;
import com.hunt.peoples.profiles.entity.Profile;
import com.hunt.peoples.profiles.repository.ProfileRepository;
import com.hunt.peoples.profiles.service.FingerprintGenerator;
import com.hunt.peoples.profiles.service.FingerprintMonitor;
import com.hunt.peoples.profiles.service.ProfilesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/profiles")
@RequiredArgsConstructor
@Tag(name = "Profiles", description = "Управление профилями браузеров")
public class ProfileController {

    private final ProfileRepository profileRepository;
    private final BrowserContainerService browserContainerService;
    private final ProfilesService profilesService;
    private final FingerprintMonitor fingerprintMonitor;
    private final FingerprintGenerator fingerprintGenerator;
    private final AppProperties appProperties;

    @GetMapping
    @Operation(summary = "Получить все профили с фильтрацией")
    public ResponseEntity<PaginatedResponse<ProfileDTO>> getAllProfiles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDirection,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String search) {

        Sort sort = sortDirection.equalsIgnoreCase("desc") ?
                Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);

        // Используем спецификации для фильтрации
        Specification<Profile> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (status != null && !status.trim().isEmpty()) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            if (platform != null && !platform.trim().isEmpty()) {
                predicates.add(cb.equal(root.get("platform"), platform));
            }

            if (search != null && !search.trim().isEmpty()) {
                String searchPattern = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), searchPattern),
                        cb.like(cb.lower(root.get("externalKey")), searchPattern),
                        cb.like(cb.lower(root.get("userAgent")), searchPattern)
                ));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        // Используем спецификацию в запросе
        Page<Profile> profilesPage = profileRepository.findAll(spec, pageable);
        Page<ProfileDTO> dtoPage = ProfileDTO.fromEntityPage(profilesPage);

        PaginatedResponse<ProfileDTO> response = PaginatedResponse.<ProfileDTO>builder()
                .content(dtoPage.getContent())
                .pageNumber(dtoPage.getNumber())
                .pageSize(dtoPage.getSize())
                .totalElements(dtoPage.getTotalElements())
                .totalPages(dtoPage.getTotalPages())
                .last(dtoPage.isLast())
                .first(dtoPage.isFirst())
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Получить профиль по ID")
    public ResponseEntity<ProfileDTO> getProfileById(@PathVariable Long id) {
        return profileRepository.findById(id)
                .map(ProfileDTO::fromEntity)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/external/{externalKey}")
    @Operation(summary = "Получить профиль по externalKey")
    public ResponseEntity<ProfileDTO> getProfileByExternalKey(@PathVariable String externalKey) {
        return profileRepository.findByExternalKey(externalKey)
                .map(ProfileDTO::fromEntity)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Operation(summary = "Создать новый профиль")
    public ResponseEntity<ProfileDTO> createProfile(@Valid @RequestBody CreateProfileRequest request) {
        log.info("Creating new profile with externalKey: {}, deviceType: {}",
                request.getExternalKey(), request.getDeviceType());

        try {
            Profile profile = profilesService.findOrCreateByExternalKey(
                    request.getExternalKey(),
                    request.getProxyUrl(),
                    request.getDeviceType(),
                    request.getDetectionLevel() != null ? request.getDetectionLevel() : "ENHANCED",
                    Boolean.TRUE.equals(request.getForceNew())
            );

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ProfileDTO.fromEntity(profile));

        } catch (IllegalArgumentException e) {
            log.error("Invalid arguments for profile creation: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .header("X-Error", e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("Failed to create profile: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("X-Error", e.getMessage())
                    .build();
        }
    }

    @PutMapping("/{id}")
    @Operation(summary = "Обновить профиль")
    public ResponseEntity<ProfileDTO> updateProfile(
            @PathVariable Long id,
            @Valid @RequestBody UpdateProfileRequest request) {

        return profileRepository.findById(id)
                .map(profile -> {
                    boolean changed = false;

                    if (request.getName() != null && !request.getName().equals(profile.getName())) {
                        profile.setName(request.getName());
                        changed = true;
                    }

                    if (request.getProxyUrl() != null && !request.getProxyUrl().equals(profile.getProxyUrl())) {
                        profile.setProxyUrl(request.getProxyUrl());
                        changed = true;
                    }

                    if (request.getStatus() != null && !request.getStatus().equals(profile.getStatus())) {
                        profile.setStatus(request.getStatus());
                        changed = true;
                    }

                    if (changed) {
                        profile.setUpdatedAt(Instant.now());
                        Profile updated = profileRepository.save(profile);
                        log.info("Updated profile: {}", id);
                        return ResponseEntity.ok(ProfileDTO.fromEntity(updated));
                    } else {
                        // Ничего не изменилось
                        return ResponseEntity.ok(ProfileDTO.fromEntity(profile));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить профиль")
    public ResponseEntity<Void> deleteProfile(@PathVariable Long id) {
        log.info("Deleting profile: {}", id);

        if (browserContainerService.isBrowserRunning(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .header("X-Error", "Cannot delete profile while browser is running")
                    .build();
        }

        try {
            // Используем новый метод, который удаляет и директорию
            profilesService.deleteProfileWithDirectory(id);
            return ResponseEntity.noContent().build();

        } catch (RuntimeException e) {
            log.error("Failed to delete profile {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .header("X-Error", e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("Failed to delete profile {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("X-Error", "Internal server error")
                    .build();
        }
    }

    @PostMapping("/{id}/start")
    @Operation(summary = "Запустить браузер для профиля")
    public ResponseEntity<BrowserOpenResponse> startBrowser(
            @PathVariable Long id,
            @RequestParam(required = false) String proxyOverride) {

        try {
            // Находим профиль через сервис
            Profile profile = profilesService.getProfileOrThrow(id);

            // Проверяем, не запущен ли уже браузер
            if (browserContainerService.isBrowserRunning(id)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(BrowserOpenResponse.builder()
                                .profileId(id)
                                .error("Browser already running for this profile")
                                .build());
            }

            // Проверяем, существует ли директория профиля
            if (!profilesService.profileDirectoryExists(id)) {
                log.warn("Profile directory not found for profile {}, restoring...", id);
                profile = profilesService.restoreProfileDirectory(id);
            }

            // Запускаем браузер
            BrowserStartResult result = browserContainerService.startBrowser(
                    profile, proxyOverride);

            // Обновляем статус профиля
            profilesService.updateProfileStatus(id, "BUSY");

            // Создаем ответ
            BrowserOpenResponse response = BrowserOpenResponse.builder()
                    .profileId(id)
                    .vncUrl(result.vncUrl())
                    .externalKey(profile.getExternalKey())
                    .userAgent(profile.getUserAgent())
                    .screenResolution(profile.getScreenWidth() + "x" + profile.getScreenHeight())
                    .build();

            log.info("Browser started for profile {}: {}", id, response.vncUrl());
            return ResponseEntity.ok(response);

        } catch (ProfileNotFoundException e) {
            log.error("Profile not found: {}", id);
            return ResponseEntity.notFound().build();
        } catch (RuntimeException e) {
            log.error("Failed to start browser for profile {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(BrowserOpenResponse.builder()
                            .profileId(id)
                            .error("Failed to start browser: " + e.getMessage())
                            .build());
        }
    }

    @PostMapping("/{id}/stop")
    @Operation(summary = "Остановить браузер для профиля")
    public ResponseEntity<Void> stopBrowser(@PathVariable Long id) {
        log.info("Stopping browser for profile: {}", id);

        try {
            browserContainerService.stopBrowser(id);

            // Обновляем статус профиля через сервис
            profilesService.updateProfileStatus(id, "FREE");

            // Снимаем блокировку пользователя
            profilesService.unlockProfile(id);

            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("Failed to stop browser for profile {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/{id}/status")
    @Operation(summary = "Получить статус профиля и контейнера")
    public ResponseEntity<ProfileStatusResponse> getProfileStatus(@PathVariable Long id) {
        return profileRepository.findById(id)
                .map(profile -> {
                    boolean isRunning = browserContainerService.isBrowserRunning(id);
                    var containerInfo = browserContainerService.getContainerInfo(id);

                    ProfileStatusResponse response = ProfileStatusResponse.builder()
                            .profileId(id)
                            .externalKey(profile.getExternalKey())
                            .status(profile.getStatus())
                            .isBrowserRunning(isRunning)
                            .lastUsedAt(profile.getLastUsedAt())
                            .detectionRisk(profile.getDetectionRisk())
                            .detectionLevel(profile.getDetectionLevel())
                            .directoryExists(profilesService.profileDirectoryExists(id))
                            .directorySize(profilesService.getProfileDirectorySize(id))
                            .directorySizeHuman(readableFileSize(profilesService.getProfileDirectorySize(id)))
                            .build();

                    if (containerInfo.isPresent()) {
                        var container = containerInfo.get();
                        response.setContainerId(container.getContainerId());
                        response.setVncUrl(buildVncUrl(appProperties.getHostBaseUrl(), container.getHostVncPort()));
                        response.setDevToolsUrl(buildDevToolsUrl(appProperties.getHostBaseUrl(), container.getHostDevToolsPort()));
                        response.setUptime(container.getUptime().toMinutes());
                    }

                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/test-fingerprint")
    @Operation(summary = "Протестировать fingerprint профиля")
    public ResponseEntity<FingerprintTestResponse> testProfileFingerprint(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean force) {

        log.info("Testing fingerprint for profile: {}", id);

        try {
            var check = fingerprintMonitor.testProfileFingerprint(id, null);
            var response = FingerprintTestResponse.fromCheck(check);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to test fingerprint for profile {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(FingerprintTestResponse.error(id, e.getMessage()));
        }
    }

    @PostMapping("/{id}/rotate-fingerprint")
    @Operation(summary = "Сменить fingerprint профиля")
    public ResponseEntity<ProfileDTO> rotateProfileFingerprint(
            @PathVariable Long id,
            @Valid @RequestBody RotateFingerprintRequest request) {

        log.info("Rotating fingerprint for profile: {}", id);

        try {
            Profile updated = profilesService.updateFingerprint(
                    id,
                    request.getDeviceType(),
                    request.getDetectionLevel()
            );

            if (browserContainerService.isBrowserRunning(id) &&
                    Boolean.TRUE.equals(request.getRestartIfRunning())) {

                log.info("Restarting browser after fingerprint rotation");
                String proxyUrl = updated.getProxyUrl();

                browserContainerService.stopBrowser(id);

                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }

                browserContainerService.startBrowser(updated, proxyUrl);
            }

            return ResponseEntity.ok(ProfileDTO.fromEntity(updated));

        } catch (RuntimeException e) {
            log.error("Failed to rotate fingerprint for profile {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .header("X-Error", e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("Failed to rotate fingerprint for profile {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("X-Error", "Internal server error")
                    .build();
        }
    }

    @GetMapping("/{id}/container-info")
    @Operation(summary = "Получить информацию о контейнере профиля")
    public ResponseEntity<ContainerInfo> getContainerInfo(@PathVariable Long id) {
        return browserContainerService.getContainerInfo(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/active")
    @Operation(summary = "Получить активные профили")
    public ResponseEntity<List<ProfileDTO>> getActiveProfiles() {
        List<Profile> activeProfiles = profilesService.getActiveProfiles();
        List<ProfileDTO> dtos = ProfileDTO.fromEntities(activeProfiles);

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/free")
    @Operation(summary = "Получить свободные профили")
    public ResponseEntity<List<ProfileDTO>> getFreeProfiles() {
        List<Profile> freeProfiles = profilesService.getFreeProfiles();
        List<ProfileDTO> dtos = ProfileDTO.fromEntities(freeProfiles);

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/statistics")
    @Operation(summary = "Получить статистику по профилям")
    public ResponseEntity<ProfileSummaryStatistics> getProfilesStatistics() {
        long total = profileRepository.count();
        long active = profilesService.countByStatus("BUSY");
        long free = profilesService.countByStatus("FREE");
        long highRisk = profileRepository.findByDetectionRiskGreaterThanEqual(0.7).size();

        Map<String, Long> platformDistribution = profileRepository.findAll().stream()
                .filter(p -> p.getPlatform() != null)
                .collect(Collectors.groupingBy(
                        Profile::getPlatform,
                        Collectors.counting()
                ));

        Map<String, Long> levelDistribution = profileRepository.findAll().stream()
                .filter(p -> p.getDetectionLevel() != null)
                .collect(Collectors.groupingBy(
                        Profile::getDetectionLevel,
                        Collectors.counting()
                ));

        ProfileSummaryStatistics statistics = ProfileSummaryStatistics.builder()
                .totalProfiles(total)
                .activeProfiles(active)
                .freeProfiles(free)
                .highRiskProfiles(highRisk)
                .platformDistribution(platformDistribution)
                .levelDistribution(levelDistribution)
                .generatedAt(Instant.now())
                .build();

        return ResponseEntity.ok(statistics);
    }

    @GetMapping("/{id}/disk-usage")
    @Operation(summary = "Получить размер профиля на диске")
    public ResponseEntity<DiskUsageResponse> getProfileDiskUsage(@PathVariable Long id) {
        return profileRepository.findById(id)
                .map(profile -> {
                    long sizeBytes = profilesService.getProfileDirectorySize(id);
                    boolean exists = profilesService.profileDirectoryExists(id);

                    DiskUsageResponse response = DiskUsageResponse.builder()
                            .profileId(id)
                            .externalKey(profile.getExternalKey())
                            .sizeBytes(sizeBytes)
                            .sizeHuman(readableFileSize(sizeBytes))
                            .directoryExists(exists)
                            .build();

                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/restore-directory")
    @Operation(summary = "Восстановить директорию профиля")
    public ResponseEntity<ProfileDTO> restoreProfileDirectory(@PathVariable Long id) {
        try {
            Profile restored = profilesService.restoreProfileDirectory(id);
            return ResponseEntity.ok(ProfileDTO.fromEntity(restored));
        } catch (RuntimeException e) {
            log.error("Failed to restore directory for profile {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .header("X-Error", e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("Failed to restore directory for profile {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("X-Error", "Internal server error")
                    .build();
        }
    }

    @PostMapping("/batch-start")
    @Operation(summary = "Массовый запуск профилей")
    public ResponseEntity<BatchStartResponse> batchStartProfiles(
            @Valid @RequestBody BatchStartRequest request) {

        log.info("Batch starting {} profiles", request.getProfileIds().size());

        List<BatchStartResult> results = new ArrayList<>();
        int successCount = 0;
        int failedCount = 0;

        for (Long profileId : request.getProfileIds()) {
            try {
                Profile profile = profileRepository.findById(profileId)
                        .orElseThrow(() -> new ProfileNotFoundException(profileId));

                if (browserContainerService.isBrowserRunning(profileId)) {
                    results.add(BatchStartResult.alreadyRunning(profileId));
                    continue;
                }

                // Проверяем и восстанавливаем директорию если нужно
                if (!profilesService.profileDirectoryExists(profileId)) {
                    profilesService.restoreProfileDirectory(profileId);
                }

                var result = browserContainerService.startBrowser(profile, request.getProxyUrl());
                profilesService.updateProfileStatus(profileId, "BUSY");

                results.add(BatchStartResult.success(profileId, result.vncUrl()));
                successCount++;

            } catch (Exception e) {
                log.error("Failed to start profile {} in batch: {}", profileId, e.getMessage());
                results.add(BatchStartResult.failed(profileId, e.getMessage()));
                failedCount++;
            }
        }

        BatchStartResponse response = BatchStartResponse.builder()
                .total(request.getProfileIds().size())
                .successCount(successCount)
                .failedCount(failedCount)
                .results(results)
                .completedAt(Instant.now())
                .build();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/batch-stop")
    @Operation(summary = "Массовая остановка профилей")
    public ResponseEntity<BatchStopResponse> batchStopProfiles(
            @RequestBody List<Long> profileIds) {

        log.info("Batch stopping {} profiles", profileIds.size());

        List<BatchStopResult> results = new ArrayList<>();
        int successCount = 0;
        int failedCount = 0;

        for (Long profileId : profileIds) {
            try {
                browserContainerService.stopBrowser(profileId);
                profilesService.updateProfileStatus(profileId, "FREE");
                profilesService.unlockProfile(profileId);

                results.add(BatchStopResult.success(profileId));
                successCount++;

            } catch (Exception e) {
                log.error("Failed to stop profile {} in batch: {}", profileId, e.getMessage());
                results.add(BatchStopResult.failed(profileId, e.getMessage()));
                failedCount++;
            }
        }

        BatchStopResponse response = BatchStopResponse.builder()
                .total(profileIds.size())
                .successCount(successCount)
                .failedCount(failedCount)
                .results(results)
                .completedAt(Instant.now())
                .build();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/cleanup-old")
    @Operation(summary = "Очистка старых профилей")
    public ResponseEntity<CleanupResponse> cleanupOldProfiles(
            @RequestParam(defaultValue = "30") int days) {

        log.info("Cleaning up profiles older than {} days", days);

        try {
            int deletedCount = profilesService.cleanupOldProfiles(days);

            CleanupResponse response = CleanupResponse.builder()
                    .deletedCount(deletedCount)
                    .daysThreshold(days)
                    .message("Successfully cleaned up " + deletedCount + " old profiles")
                    .cleanedAt(Instant.now())
                    .build();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to cleanup old profiles: {}", e.getMessage(), e);

            CleanupResponse response = CleanupResponse.builder()
                    .deletedCount(0)
                    .daysThreshold(days)
                    .error("Failed to cleanup: " + e.getMessage())
                    .cleanedAt(Instant.now())
                    .build();

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(response);
        }
    }

    @GetMapping("/available-devices")
    @Operation(summary = "Получить список доступных типов устройств")
    public ResponseEntity<List<DeviceTypeInfo>> getAvailableDeviceTypes() {
        List<DeviceTypeInfo> devices = fingerprintGenerator.getAvailableDeviceTypes().stream()
                .map(type -> {
                    var template = fingerprintGenerator.getDeviceProfileTemplate(type).orElse(null);
                    return DeviceTypeInfo.builder()
                            .id(type)
                            .name(getDeviceDisplayName(type))
                            .platform(template != null ? template.getPlatform() : "Unknown")
                            .screenSize(template != null ?
                                    template.getWidth() + "x" + template.getHeight() : "Unknown")
                            .pixelRatio(template != null ? template.getPixelRatio() : null)
                            .isMobile(template != null && template.isMobile())
                            .build();
                })
                .collect(Collectors.toList());

        devices.add(DeviceTypeInfo.builder()
                .id("random")
                .name("Random Device")
                .platform("Mixed")
                .screenSize("Variable")
                .isMobile(true)
                .build());

        return ResponseEntity.ok(devices);
    }

    @GetMapping("/all-external-keys")
    @Operation(summary = "Получить все external keys")
    public ResponseEntity<List<String>> getAllExternalKeys() {
        List<String> keys = profilesService.getAllExternalKeys();
        return ResponseEntity.ok(keys);
    }

    private String buildVncUrl(String hostBaseUrl, int port) {
        if (hostBaseUrl == null || hostBaseUrl.isEmpty()) {
            return "http://localhost:" + port + "/vnc.html";
        }
        String host = hostBaseUrl.replaceFirst("^https?://", "");
        return "http://" + host + ":" + port + "/vnc.html";
    }

    private String buildDevToolsUrl(String hostBaseUrl, int port) {
        if (hostBaseUrl == null || hostBaseUrl.isEmpty()) {
            return "http://localhost:" + port;
        }
        String host = hostBaseUrl.replaceFirst("^https?://", "");
        return "http://" + host + ":" + port;
    }

    private String getDeviceDisplayName(String deviceType) {
        return switch (deviceType) {
            case "iphone_14_pro" -> "iPhone 14 Pro";
            case "iphone_13" -> "iPhone 13";
            case "ipad_pro" -> "iPad Pro";
            case "samsung_galaxy_s23" -> "Samsung Galaxy S23";
            case "google_pixel_7" -> "Google Pixel 7";
            case "xiaomi_13" -> "Xiaomi 13";
            case "samsung_galaxy_tab_s8" -> "Samsung Galaxy Tab S8";
            case "macbook_pro" -> "MacBook Pro";
            case "windows_pc" -> "Windows PC";
            case "random" -> "Random Device";
            default -> deviceType;
        };
    }

    private String readableFileSize(long size) {
        if (size <= 0) return "0 B";
        String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new DecimalFormat("#,##0.#")
                .format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class ProfileNotFoundException extends RuntimeException {
        public ProfileNotFoundException(Long profileId) {
            super("Profile not found with id: " + profileId);
        }
    }

    @ExceptionHandler(ProfileNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProfileNotFound(ProfileNotFoundException e) {
        log.error("Profile not found: {}", e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.NOT_FOUND.value())
                .error("Not Found")
                .message(e.getMessage())
                .path("/api/profiles")
                .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException e) {
        log.error("Illegal argument: {}", e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message(e.getMessage())
                .path("/api/profiles")
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception e) {
        log.error("Internal server error: {}", e.getMessage(), e);

        ErrorResponse error = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error("Internal Server Error")
                .message("An unexpected error occurred: " + e.getMessage())
                .path("/api/profiles")
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    // Вспомогательные DTO классы




}
