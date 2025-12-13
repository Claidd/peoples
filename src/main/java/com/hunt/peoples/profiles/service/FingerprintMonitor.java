package com.hunt.peoples.profiles.service;

import com.hunt.peoples.profiles.dto.ProfileStatistics;
import com.hunt.peoples.profiles.entity.FingerprintCheck;
import com.hunt.peoples.profiles.entity.Profile;
import com.hunt.peoples.profiles.repository.FingerprintCheckRepository;
import com.hunt.peoples.profiles.repository.ProfileRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class FingerprintMonitor {

    private final ProfileRepository profileRepository;
    private final FingerprintCheckRepository checkRepository;
    private final ProfilesService profilesService;
    private final ObjectMapper objectMapper;

    private final Map<Long, List<FingerprintCheck>> profileChecksCache = new ConcurrentHashMap<>();
    private final Map<Long, Instant> lastCheckTime = new ConcurrentHashMap<>();

    // Конфигурация
    @Value("${fingerprint.monitor.enabled:true}")
    private boolean monitorEnabled;

    @Value("${fingerprint.monitor.check-interval:300000}")
    private long checkIntervalMs; // 5 минут по умолчанию

    @Value("${fingerprint.monitor.risk-threshold:0.7}")
    private double riskThreshold;

    @Value("${fingerprint.monitor.auto-rotate:false}")
    private boolean autoRotate;

    @Value("${fingerprint.monitor.test-urls:https://browserleaks.com/canvas,https://coveryourtracks.eff.org}")
    private List<String> testUrls;

    /**
     * Основной метод мониторинга (запускается по расписанию)
     */
    @Scheduled(fixedDelayString = "${fingerprint.monitor.check-interval:300000}")
    public void monitorActiveProfiles() {
        if (!monitorEnabled) {
            log.debug("Fingerprint monitoring is disabled");
            return;
        }

        log.info("Starting fingerprint monitoring cycle");

        // Получаем активные профили
        List<Profile> activeProfiles = profileRepository.findByStatus("BUSY");

        if (activeProfiles.isEmpty()) {
            log.debug("No active profiles to monitor");
            return;
        }

        log.info("Monitoring {} active profiles", activeProfiles.size());

        for (Profile profile : activeProfiles) {
            try {
                monitorProfile(profile);
            } catch (Exception e) {
                log.error("Error monitoring profile {}: {}", profile.getId(), e.getMessage(), e);
            }
        }

        cleanupOldData();
        log.info("Fingerprint monitoring cycle completed");
    }

    /**
     * Мониторинг одного профиля
     */
    public FingerprintCheck monitorProfile(Profile profile) {
        // Проверяем, не слишком ли часто проверяем
        if (shouldSkipCheck(profile.getId())) {
            log.debug("Skipping check for profile {} - too recent", profile.getId());
            return null;
        }

        log.info("Monitoring fingerprint for profile {} ({})",
                profile.getId(), profile.getExternalKey());

        // Выполняем проверки
        FingerprintCheck check = performFingerprintCheck(profile);

        // Сохраняем результат
        FingerprintCheck savedCheck = checkRepository.save(check);

        // Кэшируем результат
        profileChecksCache.computeIfAbsent(profile.getId(), k -> new ArrayList<>())
                .add(savedCheck);
        lastCheckTime.put(profile.getId(), Instant.now());

        // Обработка результатов
        processCheckResults(profile, savedCheck);

        return savedCheck;
    }

    /**
     * Выполнение всех проверок fingerprint
     */
    private FingerprintCheck performFingerprintCheck(Profile profile) {
        FingerprintCheck.FingerprintCheckBuilder checkBuilder = FingerprintCheck.builder()
                .profile(profile)
                .checkedAt(Instant.now())
                .userAgentUsed(profile.getUserAgent());

        long startTime = System.currentTimeMillis();

        try {
            // 1. Проверка консистентности Canvas
            CanvasCheckResult canvasResult = checkCanvasConsistency(profile);
            checkBuilder.canvasConsistent(canvasResult.isConsistent())
                    .canvasDetails(canvasResult.getDetails())
                    .canvasRisk(canvasResult.getRiskScore());

            // 2. Проверка WebGL
            WebGLCheckResult webglResult = checkWebGLConsistency(profile);
            checkBuilder.webglConsistent(webglResult.isConsistent())
                    .webglDetails(webglResult.getDetails())
                    .webglRisk(webglResult.getRiskScore());

            // 3. Проверка шрифтов
            FontsCheckResult fontsResult = checkFontsConsistency(profile);
            checkBuilder.fontsConsistent(fontsResult.isConsistent())
                    .fontsDetails(fontsResult.getDetails())
                    .fontsRisk(fontsResult.getRiskScore());

            // 4. Проверка timezone
            TimezoneCheckResult timezoneResult = checkTimezoneConsistency(profile);
            checkBuilder.timezoneConsistent(timezoneResult.isConsistent());

            // 5. Детекция автоматизации
            AutomationCheckResult automationResult = checkAutomationDetection(profile);
            checkBuilder.webDriverDetected(automationResult.isWebDriverDetected())
                    .automationDetected(automationResult.isAutomationDetected())
                    .automationDetails(automationResult.getDetails())
                    .automationRisk(automationResult.getRiskScore());

            // 6. Проверка прокси
            ProxyCheckResult proxyResult = checkProxyDetection(profile);
            checkBuilder.proxyDetected(proxyResult.isProxyDetected())
                    .proxyRisk(proxyResult.getRiskScore());

            // 7. Headless детекция
            HeadlessCheckResult headlessResult = checkHeadlessDetection(profile);
            checkBuilder.headlessDetected(headlessResult.isHeadlessDetected());

        } catch (Exception e) {
            log.error("Error during fingerprint check for profile {}: {}",
                    profile.getId(), e.getMessage(), e);
        }

        // Расчет общего риска
        double overallRisk = calculateOverallRisk(checkBuilder);
        String riskLevel = determineRiskLevel(overallRisk);

        checkBuilder.responseTimeMs(System.currentTimeMillis() - startTime)
                .overallRisk(overallRisk)
                .riskLevel(riskLevel)
                .passed(overallRisk < riskThreshold)
                .needsAction(overallRisk >= riskThreshold)
                .testUrl(testUrls.get(0))
                .recommendations(generateRecommendations(checkBuilder.build()));

        return checkBuilder.build();
    }

    /**
     * Проверка консистентности Canvas fingerprint
     */
    private CanvasCheckResult checkCanvasConsistency(Profile profile) {
        try {
            // Здесь была бы реальная проверка через браузер
            // Пока эмулируем результат

            boolean isConsistent = Math.random() > 0.3; // 70% шанс консистентности

            return CanvasCheckResult.builder()
                    .consistent(isConsistent)
                    .details(isConsistent ?
                            "Canvas fingerprint matches profile configuration" :
                            "Canvas fingerprint deviation detected")
                    .riskScore(isConsistent ? 0.1 : 0.8)
                    .build();

        } catch (Exception e) {
            log.error("Canvas check failed: {}", e.getMessage());
            return CanvasCheckResult.builder()
                    .consistent(false)
                    .details("Check failed: " + e.getMessage())
                    .riskScore(0.5)
                    .build();
        }
    }

    /**
     * Проверка WebGL консистентности
     */
    private WebGLCheckResult checkWebGLConsistency(Profile profile) {
        try {
            boolean isConsistent = Math.random() > 0.2; // 80% шанс

            return WebGLCheckResult.builder()
                    .consistent(isConsistent)
                    .details(isConsistent ?
                            "WebGL parameters match configured values" :
                            "WebGL vendor/renderer mismatch")
                    .riskScore(isConsistent ? 0.05 : 0.7)
                    .build();

        } catch (Exception e) {
            log.error("WebGL check failed: {}", e.getMessage());
            return WebGLCheckResult.errorResult(e.getMessage());
        }
    }

    /**
     * Проверка шрифтов
     */
    private FontsCheckResult checkFontsConsistency(Profile profile) {
        try {
            List<String> profileFonts = profile.getFontsList();
            boolean hasFonts = profileFonts != null && !profileFonts.isEmpty();

            boolean isConsistent = hasFonts && Math.random() > 0.4; // 60% шанс

            return FontsCheckResult.builder()
                    .consistent(isConsistent)
                    .details(isConsistent ?
                            "Font enumeration matches profile" :
                            "Font detection anomalies")
                    .riskScore(isConsistent ? 0.1 : 0.6)
                    .build();

        } catch (Exception e) {
            log.error("Fonts check failed: {}", e.getMessage());
            return FontsCheckResult.errorResult(e.getMessage());
        }
    }

    /**
     * Проверка timezone
     */
    private TimezoneCheckResult checkTimezoneConsistency(Profile profile) {
        try {
            boolean isConsistent = profile.getTimezone() != null &&
                    profile.getTimezoneOffset() != null;

            return TimezoneCheckResult.builder()
                    .consistent(isConsistent)
                    .details(isConsistent ?
                            "Timezone configuration valid" :
                            "Timezone not configured")
                    .build();

        } catch (Exception e) {
            log.error("Timezone check failed: {}", e.getMessage());
            return TimezoneCheckResult.errorResult(e.getMessage());
        }
    }

    /**
     * Детекция автоматизации
     */
    private AutomationCheckResult checkAutomationDetection(Profile profile) {
        try {
            // Эмуляция проверки на WebDriver и другие automation признаки
            boolean webDriverDetected = Math.random() < 0.1; // 10% шанс детекции
            boolean automationDetected = Math.random() < 0.15; // 15% шанс

            double riskScore = 0.0;
            if (webDriverDetected) riskScore += 0.6;
            if (automationDetected) riskScore += 0.4;

            return AutomationCheckResult.builder()
                    .webDriverDetected(webDriverDetected)
                    .automationDetected(automationDetected)
                    .details(webDriverDetected ?
                            "WebDriver detected" :
                            automationDetected ?
                                    "Automation patterns detected" :
                                    "No automation detected")
                    .riskScore(riskScore)
                    .build();

        } catch (Exception e) {
            log.error("Automation check failed: {}", e.getMessage());
            return AutomationCheckResult.errorResult(e.getMessage());
        }
    }

    /**
     * Проверка прокси
     */
    private ProxyCheckResult checkProxyDetection(Profile profile) {
        try {
            boolean proxyDetected = profile.getProxyUrl() != null &&
                    !profile.getProxyUrl().isEmpty();

            // Риск зависит от типа прокси (datacenter vs residential)
            double riskScore = proxyDetected ? 0.3 : 0.0;

            return ProxyCheckResult.builder()
                    .proxyDetected(proxyDetected)
                    .details(proxyDetected ?
                            "Proxy is configured" :
                            "No proxy configured")
                    .riskScore(riskScore)
                    .build();

        } catch (Exception e) {
            log.error("Proxy check failed: {}", e.getMessage());
            return ProxyCheckResult.errorResult(e.getMessage());
        }
    }

    /**
     * Headless детекция
     */
    private HeadlessCheckResult checkHeadlessDetection(Profile profile) {
        try {
            boolean headlessDetected = Math.random() < 0.05; // 5% шанс

            return HeadlessCheckResult.builder()
                    .headlessDetected(headlessDetected)
                    .details(headlessDetected ?
                            "Headless browser detected" :
                            "No headless detection")
                    .riskScore(headlessDetected ? 0.9 : 0.0)
                    .build();

        } catch (Exception e) {
            log.error("Headless check failed: {}", e.getMessage());
            return HeadlessCheckResult.errorResult(e.getMessage());
        }
    }

    /**
     * Расчет общего риска
     */
    private double calculateOverallRisk(FingerprintCheck.FingerprintCheckBuilder builder) {
        Double canvasRisk = builder.build().getCanvasRisk();
        Double webglRisk = builder.build().getWebglRisk();
        Double fontsRisk = builder.build().getFontsRisk();
        Double automationRisk = builder.build().getAutomationRisk();
        Double proxyRisk = builder.build().getProxyRisk();

        double total = 0.0;
        double weight = 0.0;

        if (canvasRisk != null) {
            total += canvasRisk * 0.3; // 30% вес
            weight += 0.3;
        }
        if (webglRisk != null) {
            total += webglRisk * 0.25; // 25% вес
            weight += 0.25;
        }
        if (fontsRisk != null) {
            total += fontsRisk * 0.15; // 15% вес
            weight += 0.15;
        }
        if (automationRisk != null) {
            total += automationRisk * 0.2; // 20% вес
            weight += 0.2;
        }
        if (proxyRisk != null) {
            total += proxyRisk * 0.1; // 10% вес
            weight += 0.1;
        }

        return weight > 0 ? total / weight : 0.0;
    }

    /**
     * Определение уровня риска
     */
    private String determineRiskLevel(double risk) {
        if (risk >= 0.8) return "CRITICAL";
        if (risk >= 0.7) return "HIGH";
        if (risk >= 0.4) return "MEDIUM";
        return "LOW";
    }

    /**
     * Генерация рекомендаций
     */
    private String generateRecommendations(FingerprintCheck check) {
        List<String> recommendations = new ArrayList<>();

        if (Boolean.FALSE.equals(check.getCanvasConsistent())) {
            recommendations.add("Update canvas fingerprint settings");
        }
        if (Boolean.FALSE.equals(check.getWebglConsistent())) {
            recommendations.add("Adjust WebGL vendor/renderer values");
        }
        if (Boolean.FALSE.equals(check.getFontsConsistent())) {
            recommendations.add("Review font list configuration");
        }
        if (Boolean.TRUE.equals(check.getWebDriverDetected())) {
            recommendations.add("Improve WebDriver masking scripts");
        }
        if (Boolean.TRUE.equals(check.getAutomationDetected())) {
            recommendations.add("Enhance automation detection bypass");
        }
        if (check.getOverallRisk() != null && check.getOverallRisk() >= 0.7) {
            recommendations.add("Consider rotating fingerprint");
        }

        return String.join("; ", recommendations);
    }

    /**
     * Обработка результатов проверки
     */
    private void processCheckResults(Profile profile, FingerprintCheck check) {
        // Обновляем профиль с результатами проверки
        profile.setLastCheckedAt(check.getCheckedAt());
        profile.setCheckCount(profile.getCheckCount() != null ?
                profile.getCheckCount() + 1 : 1);
        profile.setDetectionRisk(check.getOverallRisk());
        profile.setLastCheckResult(check.getPassed() ? "PASSED" : "FAILED");

        profileRepository.save(profile);

        // Логируем результат
        if (check.isHighRisk()) {
            log.warn("HIGH RISK detected for profile {}: {} (risk: {})",
                    profile.getId(), profile.getExternalKey(), check.getOverallRisk());

            // Автоматическая ротация при высоком риске
            if (autoRotate && check.getOverallRisk() >= 0.8) {
                autoRotateFingerprint(profile);
            }

            // Можно добавить уведомления (email, telegram, etc.)
            sendRiskNotification(profile, check);

        } else if (Boolean.TRUE.equals(check.getPassed())) {
            log.info("Profile {} passed fingerprint check (risk: {})",
                    profile.getId(), check.getOverallRisk());
        }
    }

    /**
     * Автоматическая ротация fingerprint
     */
    private void autoRotateFingerprint(Profile profile) {
        try {
            log.info("Auto-rotating fingerprint for high-risk profile {}", profile.getId());

            String currentDeviceType = profile.getDeviceProfile() != null ?
                    profile.getDeviceProfile().getDeviceType() : "random";

            // Чередуем между устройствами
            String newDeviceType = currentDeviceType.equals("iphone_14_pro") ?
                    "samsung_galaxy_s23" : "iphone_14_pro";

            profilesService.updateFingerprint(
                    profile.getId(),
                    newDeviceType,
                    profile.getDetectionLevel()
            );

            log.info("Auto-rotated fingerprint for profile {} to {}",
                    profile.getId(), newDeviceType);

        } catch (Exception e) {
            log.error("Failed to auto-rotate fingerprint for profile {}: {}",
                    profile.getId(), e.getMessage(), e);
        }
    }

    /**
     * Отправка уведомления о риске
     */
    private void sendRiskNotification(Profile profile, FingerprintCheck check) {
        // Реализация отправки уведомлений (email, telegram, slack и т.д.)
        log.warn("RISK NOTIFICATION - Profile: {}, Risk: {}, Level: {}",
                profile.getExternalKey(), check.getOverallRisk(), check.getRiskLevel());
    }

    /**
     * Проверка, нужно ли пропустить проверку (слишком часто)
     */
    private boolean shouldSkipCheck(Long profileId) {
        Instant lastCheck = lastCheckTime.get(profileId);
        if (lastCheck == null) return false;

        return lastCheck.plusMillis(checkIntervalMs).isAfter(Instant.now());
    }

    /**
     * Очистка старых данных
     */
    private void cleanupOldData() {
        try {
            Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
            checkRepository.deleteOldChecks(thirtyDaysAgo);

            log.debug("Cleaned up fingerprint checks older than 30 days");

        } catch (Exception e) {
            log.error("Error cleaning up old fingerprint checks: {}", e.getMessage(), e);
        }
    }

    /**
     * Получить историю проверок профиля
     */
    public List<FingerprintCheck> getProfileChecks(Long profileId) {
        return profileChecksCache.getOrDefault(profileId,
                checkRepository.findByProfileIdOrderByCheckedAtDesc(profileId));
    }

    /**
     * Получить статистику по профилю
     */
    public ProfileStatistics getProfileStatistics(Long profileId) {
        List<FingerprintCheck> checks = getProfileChecks(profileId);

        if (checks.isEmpty()) {
            return ProfileStatistics.empty(profileId);
        }

        long totalChecks = checks.size();
        long passedChecks = checks.stream().filter(c -> Boolean.TRUE.equals(c.getPassed())).count();
        long failedChecks = totalChecks - passedChecks;
        long highRiskChecks = checks.stream().filter(FingerprintCheck::isHighRisk).count();

        double averageRisk = checks.stream()
                .mapToDouble(c -> c.getOverallRisk() != null ? c.getOverallRisk() : 0.0)
                .average()
                .orElse(0.0);

        Optional<FingerprintCheck> latestCheck = checks.stream()
                .max(Comparator.comparing(FingerprintCheck::getCheckedAt));

        return ProfileStatistics.builder()
                .profileId(profileId)
                .totalChecks(totalChecks)
                .passedChecks(passedChecks)
                .failedChecks(failedChecks)
                .highRiskChecks(highRiskChecks)
                .passRate(totalChecks > 0 ? (double) passedChecks / totalChecks : 0.0)
                .averageRisk(averageRisk)
                .latestRisk(latestCheck.map(FingerprintCheck::getOverallRisk).orElse(0.0))
                .latestCheckTime(latestCheck.map(FingerprintCheck::getCheckedAt).orElse(null))
                .build();
    }

    /**
     * Тестовый метод для проверки профиля
     */
    public FingerprintCheck testProfileFingerprint(Long profileId, String testUrl) {
        return profileRepository.findById(profileId)
                .map(profile -> {
                    // Устанавливаем тестовый URL
                    if (testUrl != null && !testUrl.isEmpty()) {
                        List<String> urls = new ArrayList<>(testUrls);
                        urls.add(0, testUrl);
                        testUrls = urls;
                    }

                    return monitorProfile(profile);
                })
                .orElseThrow(() -> new RuntimeException("Profile not found: " + profileId));
    }

    // DTO классы для результатов проверок
    @Data
    @Builder
    private static class CanvasCheckResult {
        private boolean consistent;
        private String details;
        private double riskScore;
    }

    @Data
    @Builder
    private static class WebGLCheckResult {
        private boolean consistent;
        private String details;
        private double riskScore;

        public static WebGLCheckResult errorResult(String error) {
            return builder()
                    .consistent(false)
                    .details("Error: " + error)
                    .riskScore(0.5)
                    .build();
        }
    }

    @Data
    @Builder
    private static class FontsCheckResult {
        private boolean consistent;
        private String details;
        private double riskScore;

        public static FontsCheckResult errorResult(String error) {
            return builder()
                    .consistent(false)
                    .details("Error: " + error)
                    .riskScore(0.5)
                    .build();
        }
    }

    @Data
    @Builder
    private static class TimezoneCheckResult {
        private boolean consistent;
        private String details;

        public static TimezoneCheckResult errorResult(String error) {
            return builder()
                    .consistent(false)
                    .details("Error: " + error)
                    .build();
        }
    }

    @Data
    @Builder
    private static class AutomationCheckResult {
        private boolean webDriverDetected;
        private boolean automationDetected;
        private String details;
        private double riskScore;

        public static AutomationCheckResult errorResult(String error) {
            return builder()
                    .webDriverDetected(false)
                    .automationDetected(false)
                    .details("Error: " + error)
                    .riskScore(0.5)
                    .build();
        }
    }

    @Data
    @Builder
    private static class ProxyCheckResult {
        private boolean proxyDetected;
        private String details;
        private double riskScore;

        public static ProxyCheckResult errorResult(String error) {
            return builder()
                    .proxyDetected(false)
                    .details("Error: " + error)
                    .riskScore(0.5)
                    .build();
        }
    }

    @Data
    @Builder
    private static class HeadlessCheckResult {
        private boolean headlessDetected;
        private String details;
        private double riskScore;

        public static HeadlessCheckResult errorResult(String error) {
            return builder()
                    .headlessDetected(false)
                    .details("Error: " + error)
                    .riskScore(0.5)
                    .build();
        }
    }
}