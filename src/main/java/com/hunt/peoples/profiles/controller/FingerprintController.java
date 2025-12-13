package com.hunt.peoples.profiles.controller;

import com.hunt.peoples.browser.dto.*;
import com.hunt.peoples.browser.service.BrowserScriptInjector;
import com.hunt.peoples.profiles.dto.FingerprintCheckDto;
import com.hunt.peoples.profiles.dto.ProfileDTO;
import com.hunt.peoples.profiles.dto.ProfileStatistics;
import com.hunt.peoples.profiles.entity.FingerprintCheck;
import com.hunt.peoples.profiles.entity.Profile;
import com.hunt.peoples.profiles.repository.FingerprintCheckRepository;
import com.hunt.peoples.profiles.repository.ProfileRepository;
import com.hunt.peoples.profiles.service.FingerprintGenerator;
import com.hunt.peoples.profiles.service.FingerprintMonitor;
import com.hunt.peoples.profiles.service.ProfilesService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/fingerprint")
@RequiredArgsConstructor
@Slf4j
@Validated
public class FingerprintController {

    private final FingerprintMonitor fingerprintMonitor;
    private final ProfilesService profilesService;
    private final ProfileRepository profileRepository;
    private final FingerprintCheckRepository checkRepository;
    private final FingerprintGenerator fingerprintGenerator;
    private final BrowserScriptInjector scriptInjector;

    @PostMapping("/{profileId}/test")
    @Operation(summary = "Протестировать fingerprint профиля")
    public ResponseEntity<FingerprintTestResponse> testFingerprint(
            @PathVariable Long profileId,
            @RequestParam(required = false) String testUrl,
            @RequestParam(defaultValue = "false") boolean force) {

        log.info("Testing fingerprint for profile {} with URL: {}", profileId, testUrl);

        try {
            Profile profile = profileRepository.findById(profileId)
                    .orElseThrow(() -> new ProfileNotFoundException(profileId));

            FingerprintCheck check;
            if (force) {
                // Принудительная проверка
                check = fingerprintMonitor.monitorProfile(profile);
            } else {
                // Стандартная проверка
                check = fingerprintMonitor.testProfileFingerprint(profileId, testUrl);
            }

            FingerprintTestResponse response = FingerprintTestResponse.fromCheck(check);

            return ResponseEntity.ok(response);

        } catch (ProfileNotFoundException e) {
            log.error("Profile not found: {}", profileId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(FingerprintTestResponse.error(profileId, "Profile not found"));

        } catch (Exception e) {
            log.error("Fingerprint test failed for profile {}", profileId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(FingerprintTestResponse.error(profileId, e.getMessage()));
        }
    }

    @PostMapping("/{profileId}/rotate")
    @Operation(summary = "Сменить fingerprint профиля")
    public ResponseEntity<ProfileDTO> rotateFingerprint(
            @PathVariable Long profileId,
            @Valid @RequestBody RotateFingerprintRequest request) {

        log.info("Rotating fingerprint for profile {} with deviceType: {}, level: {}",
                profileId, request.getDeviceType(), request.getDetectionLevel());

        try {
            Profile profile = profilesService.updateFingerprint(
                    profileId,
                    request.getDeviceType(),
                    request.getDetectionLevel()
            );

            // Сохраняем userDataPath если нужно сохранить сессии
            if (Boolean.TRUE.equals(request.getKeepSessions())) {
                log.info("Keeping sessions for profile {}", profileId);
                // UserDataPath уже сохранен в методе updateFingerprint
            }

            // Выполняем проверку после ротации если требуется
            if (Boolean.TRUE.equals(request.getTestAfterRotate())) {
                fingerprintMonitor.testProfileFingerprint(profileId, null);
            }

            // Если нужно, обновляем инъекционные скрипты
            if (Boolean.TRUE.equals(request.getUpdateScripts())) {
                updateInjectionScripts(profile);
            }

            return ResponseEntity.ok(ProfileDTO.fromEntity(profile));

        } catch (ProfileNotFoundException e) {
            log.error("Profile not found: {}", profileId);
            return ResponseEntity.notFound().build();

        } catch (Exception e) {
            log.error("Failed to rotate fingerprint for profile {}", profileId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/{profileId}/check-history")
    public ResponseEntity<PaginatedResponse<FingerprintCheckDto>> getCheckHistory(
            @PathVariable Long profileId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "checkedAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDirection) {

        // Проверяем существование профиля
        if (!profileRepository.existsById(profileId)) {
            return ResponseEntity.notFound().build();
        }

        // Создаем спецификацию для сортировки
        Sort sort = sortDirection.equalsIgnoreCase("desc")
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();

        Pageable pageable = PageRequest.of(page, size, sort);

        // Получаем страницу проверок
        Page<FingerprintCheck> checkPage = checkRepository.findByProfileId(profileId, pageable);

        // Преобразуем в DTO
        List<FingerprintCheckDto> dtos = checkPage.getContent().stream()
                .map(FingerprintCheckDto::fromEntity)
                .collect(Collectors.toList());

        PaginatedResponse<FingerprintCheckDto> response = PaginatedResponse.<FingerprintCheckDto>builder()
                .content(dtos)
                .pageNumber(checkPage.getNumber())
                .pageSize(checkPage.getSize())
                .totalElements(checkPage.getTotalElements())
                .totalPages(checkPage.getTotalPages())
                .last(checkPage.isLast())
                .first(checkPage.isFirst())
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{profileId}/statistics")
    @Operation(summary = "Получить статистику по проверкам профиля")
    public ResponseEntity<ProfileStatistics> getStatistics(@PathVariable Long profileId) {

        if (!profileRepository.existsById(profileId)) {
            return ResponseEntity.notFound().build();
        }

        ProfileStatistics statistics = fingerprintMonitor.getProfileStatistics(profileId);
        return ResponseEntity.ok(statistics);
    }

    @GetMapping("/{profileId}/latest-check")
    @Operation(summary = "Получить последнюю проверку профиля")
    public ResponseEntity<FingerprintCheckDto> getLatestCheck(@PathVariable Long profileId) {

        return checkRepository.findLatestCheckByProfileId(profileId)
                .map(FingerprintCheckDto::fromEntity)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/high-risk")
    @Operation(summary = "Получить профили с высоким риском")
    public ResponseEntity<List<ProfileRiskInfo>> getHighRiskProfiles(
            @RequestParam(defaultValue = "0.7") double threshold,
            @RequestParam(defaultValue = "false") boolean onlyActive) {

        List<FingerprintCheck> highRiskChecks = checkRepository.findHighRiskChecks(threshold);

        // Группируем по профилям и фильтруем если нужно только активные
        Map<Long, List<FingerprintCheck>> checksByProfile = highRiskChecks.stream()
                .filter(check -> !onlyActive || "BUSY".equals(check.getProfile().getStatus()))
                .collect(Collectors.groupingBy(check -> check.getProfile().getId()));

        List<ProfileRiskInfo> riskProfiles = checksByProfile.entrySet().stream()
                .map(entry -> {
                    Profile profile = profileRepository.findById(entry.getKey()).orElse(null);
                    if (profile == null) return null;

                    // Находим последнюю проверку
                    FingerprintCheck latestCheck = entry.getValue().stream()
                            .max(Comparator.comparing(FingerprintCheck::getCheckedAt))
                            .orElse(null);

                    if (latestCheck == null) return null;

                    return ProfileRiskInfo.builder()
                            .profileId(profile.getId())
                            .externalKey(profile.getExternalKey())
                            .userAgent(profile.getUserAgent())
                            .platform(profile.getPlatform())
                            .status(profile.getStatus())
                            .latestRisk(latestCheck.getOverallRisk())
                            .riskLevel(latestCheck.getRiskLevel())
                            .lastCheckTime(latestCheck.getCheckedAt())
                            .checkCount(entry.getValue().size())
                            .recommendations(latestCheck.getRecommendations())
                            .build();
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ProfileRiskInfo::getLatestRisk).reversed())
                .collect(Collectors.toList());

        return ResponseEntity.ok(riskProfiles);
    }

    @PostMapping("/{profileId}/enhance")
    @Operation(summary = "Улучшить уровень антидетекта профиля")
    public ResponseEntity<ProfileDTO> enhanceDetectionLevel(
            @PathVariable Long profileId,
            @Valid @RequestBody EnhanceLevelRequest request) {

        // Валидация уровня
        if (!List.of("BASIC", "ENHANCED", "AGGRESSIVE").contains(request.getTargetLevel())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid detection level. Must be BASIC, ENHANCED or AGGRESSIVE");
        }

        return profileRepository.findById(profileId)
                .map(profile -> {
                    String currentLevel = profile.getDetectionLevel();
                    String targetLevel = request.getTargetLevel();

                    // Если текущий уровень уже выше или равен целевому
                    if (getLevelValue(currentLevel) >= getLevelValue(targetLevel)) {
                        log.info("Profile {} already at level {} (target: {})",
                                profileId, currentLevel, targetLevel);
                        return ResponseEntity.ok(ProfileDTO.fromEntity(profile));
                    }

                    // Определяем тип устройства для нового fingerprint
                    String deviceType = getDeviceTypeForUpgrade(profile, targetLevel);

                    // Обновляем профиль с новым уровнем
                    Profile updatedProfile = profilesService.updateFingerprint(
                            profileId, deviceType, targetLevel);

                    log.info("Profile {} enhanced from {} to {} with device type {}",
                            profileId, currentLevel, targetLevel, deviceType);

                    // Если нужно, обновляем инъекционные скрипты
                    if (Boolean.TRUE.equals(request.getUpdateScripts())) {
                        updateInjectionScripts(updatedProfile);
                    }

                    return ResponseEntity.ok(ProfileDTO.fromEntity(updatedProfile));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Получить дашборд статистики fingerprint")
    public ResponseEntity<FingerprintDashboard> getDashboard(
            @RequestParam(defaultValue = "7") int days) {

        Instant fromDate = Instant.now().minus(days, ChronoUnit.DAYS);

        // Общая статистика
        long totalProfiles = profileRepository.count();
        long activeProfiles = profileRepository.findByStatus("BUSY").size();
        long highRiskProfiles = profileRepository.findByDetectionRiskGreaterThanEqual(0.7).size();

        // Статистика проверок
        List<FingerprintCheck> recentChecks = checkRepository.findChecksBetweenDates(fromDate, Instant.now());

        Map<String, Long> checksByDay = recentChecks.stream()
                .collect(Collectors.groupingBy(
                        check -> check.getCheckedAt().atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                        Collectors.counting()
                ));

        Map<String, Long> riskDistribution = recentChecks.stream()
                .collect(Collectors.groupingBy(
                        FingerprintCheck::getRiskLevel,
                        Collectors.counting()
                ));

        double averageRisk = recentChecks.stream()
                .mapToDouble(check -> check.getOverallRisk() != null ? check.getOverallRisk() : 0.0)
                .average()
                .orElse(0.0);

        // Статистика по устройствам
        Map<String, Long> deviceDistribution = profileRepository.findAll().stream()
                .filter(p -> p.getPlatform() != null)
                .collect(Collectors.groupingBy(
                        Profile::getPlatform,
                        Collectors.counting()
                ));

        // Создаем дашборд
        FingerprintDashboard dashboard = FingerprintDashboard.builder()
                .totalProfiles(totalProfiles)
                .activeProfiles(activeProfiles)
                .highRiskProfiles(highRiskProfiles)
                .totalChecks((long) recentChecks.size())
                .averageRisk(averageRisk)
                .checksByDay(checksByDay)
                .riskDistribution(riskDistribution)
                .deviceDistribution(deviceDistribution)
                .generatedAt(Instant.now())
                .timeRangeDays(days)
                .build();

        return ResponseEntity.ok(dashboard);
    }

    @GetMapping("/available-devices")
    @Operation(summary = "Получить список доступных типов устройств")
    public ResponseEntity<List<DeviceTypeInfo>> getAvailableDevices() {

        List<DeviceTypeInfo> devices = List.of(
                DeviceTypeInfo.builder()
                        .id("iphone_14_pro")
                        .name("iPhone 14 Pro")
                        .platform("iOS")
                        .screenSize("393x852")
                        .pixelRatio(3.0)
                        .userAgent("Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1")
                        .build(),
                DeviceTypeInfo.builder()
                        .id("samsung_galaxy_s23")
                        .name("Samsung Galaxy S23")
                        .platform("Android")
                        .screenSize("412x915")
                        .pixelRatio(2.63)
                        .userAgent("Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36")
                        .build(),
                DeviceTypeInfo.builder()
                        .id("google_pixel_7")
                        .name("Google Pixel 7")
                        .platform("Android")
                        .screenSize("412x915")
                        .pixelRatio(2.6)
                        .userAgent("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")
                        .build(),
                DeviceTypeInfo.builder()
                        .id("random")
                        .name("Random Device")
                        .platform("Mixed")
                        .screenSize("Variable")
                        .pixelRatio(null)
                        .userAgent("Random")
                        .build()
        );

        return ResponseEntity.ok(devices);
    }

    @GetMapping("/{profileId}/injection-script")
    @Operation(summary = "Получить инъекционный скрипт для профиля")
    public ResponseEntity<InjectionScriptResponse> getInjectionScript(@PathVariable Long profileId) {

        return profileRepository.findById(profileId)
                .map(profile -> {
                    String script = scriptInjector.generateInjectionScript(profile);

                    InjectionScriptResponse response = InjectionScriptResponse.builder()
                            .profileId(profileId)
                            .externalKey(profile.getExternalKey())
                            .detectionLevel(profile.getDetectionLevel())
                            .script(script)
                            .scriptLength(script.length())
                            .generatedAt(Instant.now())
                            .build();

                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/batch-test")
    @Operation(summary = "Массовое тестирование профилей")
    public ResponseEntity<BatchTestResponse> batchTestProfiles(
            @Valid @RequestBody BatchTestRequest request) {

        log.info("Batch testing profiles: {}", request.getProfileIds());

        List<Long> profileIds = request.getProfileIds();
        if (profileIds == null || profileIds.isEmpty()) {
            // Если не указаны ID, тестируем все активные профили
            profileIds = profileRepository.findByStatus("BUSY").stream()
                    .map(Profile::getId)
                    .collect(Collectors.toList());
        }

        List<BatchTestResult> results = new ArrayList<>();
        int successCount = 0;
        int failedCount = 0;

        for (Long profileId : profileIds) {
            try {
                FingerprintCheck check = fingerprintMonitor.testProfileFingerprint(profileId, request.getTestUrl());

                BatchTestResult result = BatchTestResult.builder()
                        .profileId(profileId)
                        .success(true)
                        .riskLevel(check.getRiskLevel())
                        .overallRisk(check.getOverallRisk())
                        .passed(check.getPassed())
                        .message("Test completed successfully")
                        .build();

                results.add(result);
                successCount++;

            } catch (Exception e) {
                log.error("Failed to test profile {} in batch", profileId, e);

                BatchTestResult result = BatchTestResult.builder()
                        .profileId(profileId)
                        .success(false)
                        .errorMessage(e.getMessage())
                        .message("Test failed: " + e.getMessage())
                        .build();

                results.add(result);
                failedCount++;
            }
        }

        BatchTestResponse response = BatchTestResponse.builder()
                .totalProfiles(profileIds.size())
                .successCount(successCount)
                .failedCount(failedCount)
                .results(results)
                .completedAt(Instant.now())
                .build();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{profileId}/simulate-detection")
    @Operation(summary = "Симулировать различные типы детекции")
    public ResponseEntity<DetectionSimulationResponse> simulateDetection(
            @PathVariable Long profileId,
            @Valid @RequestBody SimulationRequest request) {

        log.info("Simulating detection for profile {}: {}", profileId, request.getDetectionType());

        return profileRepository.findById(profileId)
                .map(profile -> {
                    // Генерируем симулированные результаты
                    Map<String, SimulationResult> simulations = simulateDetectionTypes(
                            profile, request.getDetectionType());

                    DetectionSimulationResponse response = DetectionSimulationResponse.builder()
                            .profileId(profileId)
                            .externalKey(profile.getExternalKey())
                            .detectionType(request.getDetectionType())
                            .simulations(simulations)
                            .overallVulnerability(calculateOverallVulnerability(simulations))
                            .recommendations(generateSimulationRecommendations(simulations))
                            .simulatedAt(Instant.now())
                            .build();

                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ==================

    /**
     * Получает числовое значение уровня антидетекта
     */
    private int getLevelValue(String level) {
        if (level == null) return 0;
        return switch (level) {
            case "AGGRESSIVE" -> 3;
            case "ENHANCED" -> 2;
            case "BASIC" -> 1;
            default -> 0;
        };
    }

    /**
     * Определяет тип устройства для апгрейда уровня
     */
    private String getDeviceTypeForUpgrade(Profile profile, String targetLevel) {
        if (profile.getDeviceProfile() != null && profile.getDeviceProfile().getDeviceType() != null) {
            return profile.getDeviceProfile().getDeviceType();
        }

        // Для агрессивного уровня используем iPhone (лучший антидетект)
        if ("AGGRESSIVE".equals(targetLevel)) {
            return "iphone_14_pro";
        }

        // Для ENHANCED используем Samsung
        if ("ENHANCED".equals(targetLevel)) {
            return "samsung_galaxy_s23";
        }

        // По умолчанию случайное устройство
        return "random";
    }

    /**
     * Обновляет инъекционные скрипты для профиля
     */
    private void updateInjectionScripts(Profile profile) {
        try {
            // Генерируем новый скрипт
            String newScript = scriptInjector.generateInjectionScript(profile);

            // Здесь можно сохранить скрипт в профиль или отправить в running browser
            log.info("Generated new injection script for profile {} (length: {})",
                    profile.getId(), newScript.length());

        } catch (Exception e) {
            log.error("Failed to update injection scripts for profile {}", profile.getId(), e);
        }
    }

    /**
     * Симулирует различные типы детекции
     */
    private Map<String, SimulationResult> simulateDetectionTypes(Profile profile, String detectionType) {
        Map<String, SimulationResult> simulations = new HashMap<>();

        // Canvas fingerprint detection
        simulations.put("canvas", SimulationResult.builder()
                .detected(Math.random() < 0.3)
                .confidence(0.3 + Math.random() * 0.5)
                .description("Canvas fingerprint analysis")
                .build());

        // WebGL detection
        simulations.put("webgl", SimulationResult.builder()
                .detected(Math.random() < 0.2)
                .confidence(0.2 + Math.random() * 0.4)
                .description("WebGL hardware fingerprinting")
                .build());

        // Font detection
        simulations.put("fonts", SimulationResult.builder()
                .detected(Math.random() < 0.4)
                .confidence(0.4 + Math.random() * 0.4)
                .description("Font enumeration detection")
                .build());

        // WebDriver detection
        simulations.put("webdriver", SimulationResult.builder()
                .detected(Math.random() < 0.1)
                .confidence(0.1 + Math.random() * 0.3)
                .description("WebDriver/automation detection")
                .build());

        // Timezone detection
        simulations.put("timezone", SimulationResult.builder()
                .detected(Math.random() < 0.5)
                .confidence(0.5 + Math.random() * 0.3)
                .description("Timezone inconsistency detection")
                .build());

        // Proxy detection
        if (profile.getProxyUrl() != null && !profile.getProxyUrl().isEmpty()) {
            simulations.put("proxy", SimulationResult.builder()
                    .detected(true)
                    .confidence(0.8 + Math.random() * 0.2)
                    .description("Proxy server detection")
                    .build());
        }

        return simulations;
    }

    /**
     * Рассчитывает общую уязвимость на основе симуляций
     */
    private double calculateOverallVulnerability(Map<String, SimulationResult> simulations) {
        if (simulations == null || simulations.isEmpty()) return 0.0;

        double totalConfidence = simulations.values().stream()
                .filter(SimulationResult::isDetected)
                .mapToDouble(SimulationResult::getConfidence)
                .sum();

        long detectedCount = simulations.values().stream()
                .filter(SimulationResult::isDetected)
                .count();

        return detectedCount > 0 ? totalConfidence / detectedCount : 0.0;
    }

    /**
     * Генерирует рекомендации на основе симуляций
     */
    private String generateSimulationRecommendations(Map<String, SimulationResult> simulations) {
        if (simulations == null || simulations.isEmpty()) {
            return "No simulations available for analysis.";
        }

        List<String> recommendations = new ArrayList<>();

        simulations.forEach((String type, SimulationResult result) -> {
            if (result != null && result.isDetected() && result.getConfidence() > 0.5) {
                String confidencePercent = String.format("%.0f%%", result.getConfidence() * 100);

                switch (type.toLowerCase()) {
                    case "canvas":
                        recommendations.add("Improve canvas fingerprint spoofing - confidence: " + confidencePercent);
                        break;
                    case "webgl":
                        recommendations.add("Enhance WebGL fingerprint protection - confidence: " + confidencePercent);
                        break;
                    case "fonts":
                        recommendations.add("Optimize font fingerprint masking - confidence: " + confidencePercent);
                        break;
                    case "timezone":
                        recommendations.add("Fix timezone consistency - confidence: " + confidencePercent);
                        break;
                    case "webdriver":
                        recommendations.add("Hide WebDriver traces - confidence: " + confidencePercent);
                        break;
                    case "automation":
                        recommendations.add("Reduce automation detection - confidence: " + confidencePercent);
                        break;
                    case "proxy":
                        recommendations.add("Improve proxy detection masking - confidence: " + confidencePercent);
                        break;
                    case "headless":
                        recommendations.add("Fix headless browser detection - confidence: " + confidencePercent);
                        break;
                    case "language":
                        recommendations.add("Adjust language settings - confidence: " + confidencePercent);
                        break;
                    case "platform":
                        recommendations.add("Fix platform inconsistencies - confidence: " + confidencePercent);
                        break;
                    default:
                        recommendations.add("Address " + type + " detection - confidence: " + confidencePercent);
                        break;
                }
            }
        });

        // Убедитесь, что у нас есть данные для анализа
        if (recommendations.isEmpty()) {
            // Проверим, есть ли вообще какие-то результаты
            boolean hasAnyResults = simulations.values().stream()
                    .anyMatch(result -> result != null);

            if (!hasAnyResults) {
                return "No simulation results found.";
            }

            // Проверим, есть ли недетектированные результаты
            long nonDetectedCount = simulations.values().stream()
                    .filter(result -> result != null && !result.isDetected())
                    .count();

            if (nonDetectedCount > 0) {
                return String.format("Good news! %d simulations passed without detection.", nonDetectedCount);
            }

            // Проверим результаты с низкой уверенностью
            long lowConfidenceCount = simulations.values().stream()
                    .filter(result -> result != null && result.isDetected() && result.getConfidence() <= 0.5)
                    .count();

            if (lowConfidenceCount > 0) {
                return String.format("%d detections with low confidence (<50%%) - monitor closely.", lowConfidenceCount);
            }

            return "No significant vulnerabilities detected.";
        }

        // Считаем high-confidence детекции
        long highConfidenceCount = simulations.values().stream()
                .filter(result -> result != null && result.isDetected() && result.getConfidence() > 0.7)
                .count();

        if (highConfidenceCount > 0) {
            recommendations.add(0, "URGENT: " + highConfidenceCount +
                    " high-confidence detections require immediate attention.");

            // Добавим срочные рекомендации в зависимости от типа
            if (simulations.containsKey("webdriver") &&
                    simulations.get("webdriver") != null &&
                    simulations.get("webdriver").isDetected() &&
                    simulations.get("webdriver").getConfidence() > 0.7) {
                recommendations.add(1, "CRITICAL: WebDriver detection is a major red flag!");
            }
        }

        // Добавим общее резюме
        long totalDetected = simulations.values().stream()
                .filter(result -> result != null && result.isDetected())
                .count();

        long totalSimulations = simulations.values().stream()
                .filter(result -> result != null)
                .count();

        if (totalSimulations > 0) {
            String summary = String.format("\nSummary: %d/%d simulations detected vulnerabilities (%.0f%%)",
                    totalDetected, totalSimulations, (totalDetected * 100.0 / totalSimulations));
            recommendations.add(summary);
        }

        return String.join("\n", recommendations);
    }


    // ================== EXCEPTION КЛАССЫ ==================

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class ProfileNotFoundException extends RuntimeException {
        public ProfileNotFoundException(Long profileId) {
            super("Profile not found with id: " + profileId);
        }
    }
}

//POST /api/fingerprint/{profileId}/test - тестирование fingerprint
//
//POST /api/fingerprint/{profileId}/rotate - смена fingerprint
//
//GET /api/fingerprint/{profileId}/checks - история проверок
//
//GET /api/fingerprint/{profileId}/statistics - статистика профиля
//
//GET /api/fingerprint/high-risk - профили с высоким риском
//
//POST /api/fingerprint/{profileId}/enhance - улучшение уровня антидетекта
//
//GET /api/fingerprint/dashboard - дашборд статистики
//
//GET /api/fingerprint/available-devices - доступные устройства
//
//POST /api/fingerprint/batch-test - массовое тестирование
//
//POST /api/fingerprint/{profileId}/simulate-detection - симуляция детекции
