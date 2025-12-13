package com.hunt.peoples.profiles.dto;


import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.peoples.browser.dto.ContainerInfo;
import com.hunt.peoples.browser.service.BrowserContainerService;
import com.hunt.peoples.profiles.entity.DeviceProfile;
import com.hunt.peoples.profiles.entity.Profile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Slf4j
public class ProfileDTO {

    // Идентификаторы
    private Long id;
    private String externalKey;
    private String name;

    // Статус и использование
    private String status;              // FREE, BUSY, ERROR
    private Instant lastUsedAt;
    private String lockedByUserId;
    private Boolean isActive;

    // Настройки браузера
    private String userDataPath;
    private String proxyUrl;

    // Fingerprint - основные параметры
    private String userAgent;
    private String platform;
    private Integer screenWidth;
    private Integer screenHeight;
    private Double pixelRatio;
    private String detectionLevel;      // BASIC, ENHANCED, AGGRESSIVE
    private Double detectionRisk;       // 0.0 - 1.0

    // Аппаратные параметры
    private Integer hardwareConcurrency;
    private Integer deviceMemory;
    private Integer maxTouchPoints;

    // WebGL информация
    private String webglVendor;
    private String webglRenderer;
    private String webglVersion; // Добавлено новое поле

    // Canvas fingerprint
    private String canvasFingerprint;
    private String canvasNoiseHash;

    // Дополнительная информация
    private String timezone;
    private String locale;
    private String language;
    private String chromeVersion;
    private String osVersion;
    private String osArchitecture;

    // Расширенные поля fingerprint (опционально)
    private Boolean hasWebglExtensions;
    private Boolean hasPlugins;
    private Boolean hasMediaDevices;

    // Статистика и мониторинг
    private Instant lastCheckedAt;
    private Integer checkCount;
    private String lastCheckResult;
    private String fingerprintHash;

    // Временные метки
    private Instant createdAt;
    private Instant updatedAt;
    private Instant fingerprintCreatedAt;
    private Instant fingerprintUpdatedAt;

    // Полный device profile (опционально)
    private DeviceProfile deviceProfile;

    // Информация о контейнере (если запущен)
    private ContainerInfo containerInfo;

    // Статистика проверок
    private ProfileStatistics statistics;

    /**
     * Конвертирует Entity в DTO
     */
    public static ProfileDTO fromEntity(Profile profile) {
        if (profile == null) return null;

        ProfileDTOBuilder builder = ProfileDTO.builder()
                .id(profile.getId())
                .externalKey(profile.getExternalKey())
                .name(profile.getName())
                .status(profile.getStatus())
                .lastUsedAt(profile.getLastUsedAt())
                .lockedByUserId(profile.getLockedByUserId())
                .isActive(profile.getIsActive())
                .userDataPath(profile.getUserDataPath())
                .proxyUrl(profile.getProxyUrl())
                .userAgent(profile.getUserAgent())
                .platform(profile.getPlatform())
                .screenWidth(profile.getScreenWidth())
                .screenHeight(profile.getScreenHeight())
                .pixelRatio(profile.getPixelRatio())
                .detectionLevel(profile.getDetectionLevel())
                .detectionRisk(profile.getDetectionRisk())
                .hardwareConcurrency(profile.getHardwareConcurrency())
                .deviceMemory(profile.getDeviceMemory())
                .maxTouchPoints(profile.getMaxTouchPoints())
                .webglVendor(profile.getWebglVendor())
                .webglRenderer(profile.getWebglRenderer())
                .webglVersion(profile.getWebglVersion())
                .canvasFingerprint(profile.getCanvasFingerprint())
                .canvasNoiseHash(profile.getCanvasNoiseHash())
                .timezone(profile.getTimezone())
                .locale(profile.getLocale())
                .language(profile.getLanguage())
                .chromeVersion(profile.getChromeVersion())
                .osVersion(profile.getOsVersion())
                .osArchitecture(profile.getOsArchitecture())
                .lastCheckedAt(profile.getLastCheckedAt())
                .checkCount(profile.getCheckCount())
                .lastCheckResult(profile.getLastCheckResult())
                .fingerprintHash(profile.getFingerprintHash())
                .createdAt(profile.getCreatedAt())
                .updatedAt(profile.getUpdatedAt())
                .fingerprintCreatedAt(profile.getFingerprintCreatedAt())
                .fingerprintUpdatedAt(profile.getFingerprintUpdatedAt())
                // Новые поля
                .hasWebglExtensions(profile.getWebglExtensionsJson() != null && !profile.getWebglExtensionsJson().isEmpty())
                .hasPlugins(profile.getPluginsJson() != null && !profile.getPluginsJson().isEmpty())
                .hasMediaDevices(profile.getMediaDevicesJson() != null && !profile.getMediaDevicesJson().isEmpty());

        // Добавляем deviceProfile если есть
        try {
            DeviceProfile deviceProfile = profile.getDeviceProfile();
            if (deviceProfile != null) {
                builder.deviceProfile(deviceProfile);
            }
        } catch (Exception e) {
            log.warn("Failed to parse device profile for profile {}", profile.getId(), e);
        }

        return builder.build();
    }

    /**
     * Конвертирует список Entity в список DTO
     */
    public static List<ProfileDTO> fromEntities(List<Profile> profiles) {
        return profiles.stream()
                .map(ProfileDTO::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Конвертирует Page Entity в Page DTO
     */
    public static Page<ProfileDTO> fromEntityPage(Page<Profile> profilePage) {
        return profilePage.map(ProfileDTO::fromEntity);
    }

    /**
     * Создает упрощенную версию DTO (без heavy данных)
     */
    public static ProfileDTO lightFromEntity(Profile profile) {
        if (profile == null) return null;

        return ProfileDTO.builder()
                .id(profile.getId())
                .externalKey(profile.getExternalKey())
                .name(profile.getName())
                .status(profile.getStatus())
                .lastUsedAt(profile.getLastUsedAt())
                .userAgent(profile.getUserAgent())
                .platform(profile.getPlatform())
                .screenWidth(profile.getScreenWidth())
                .screenHeight(profile.getScreenHeight())
                .detectionLevel(profile.getDetectionLevel())
                .detectionRisk(profile.getDetectionRisk())
                .chromeVersion(profile.getChromeVersion())
                .osVersion(profile.getOsVersion())
                .createdAt(profile.getCreatedAt())
                .updatedAt(profile.getUpdatedAt())
                .fingerprintUpdatedAt(profile.getFingerprintUpdatedAt())
                .build();
    }

    /**
     * Получает строку с разрешением экрана
     */
    @JsonIgnore
    public String getScreenResolution() {
        if (screenWidth != null && screenHeight != null) {
            return screenWidth + "x" + screenHeight;
        }
        return "Unknown";
    }

    /**
     * Проверяет, является ли профиль мобильным
     */
    @JsonIgnore
    public boolean isMobile() {
        if (userAgent == null) return false;
        String ua = userAgent.toLowerCase();
        return ua.contains("mobile") ||
                ua.contains("android") ||
                ua.contains("iphone") ||
                ua.contains("ipad");
    }

    /**
     * Проверяет, является ли профиль iOS
     */
    @JsonIgnore
    public boolean isIos() {
        if (userAgent == null) return false;
        String ua = userAgent.toLowerCase();
        return ua.contains("iphone") || ua.contains("ipad") || ua.contains("mac os x");
    }

    /**
     * Проверяет, является ли профиль Android
     */
    @JsonIgnore
    public boolean isAndroid() {
        if (userAgent == null) return false;
        return userAgent.toLowerCase().contains("android");
    }

    /**
     * Проверяет, является ли профиль десктопным
     */
    @JsonIgnore
    public boolean isDesktop() {
        return !isMobile() && !isIos() && !isAndroid();
    }

    /**
     * Проверяет, устарел ли fingerprint
     */
    @JsonIgnore
    public boolean isFingerprintStale() {
        if (fingerprintUpdatedAt == null) return true;

        // Считаем устаревшим, если обновлялся более 7 дней назад
        return fingerprintUpdatedAt.isBefore(
                Instant.now().minus(7, ChronoUnit.DAYS)
        );
    }

    /**
     * Проверяет, нуждается ли профиль в проверке
     */
    @JsonIgnore
    public boolean needsCheck() {
        if (lastCheckedAt == null) return true;

        // Проверяем каждые 4 часа
        return lastCheckedAt.isBefore(
                Instant.now().minus(4, ChronoUnit.HOURS)
        );
    }

    /**
     * Получает уровень риска в текстовом виде
     */
    @JsonIgnore
    public String getRiskLevelText() {
        if (detectionRisk == null) return "UNKNOWN";

        if (detectionRisk >= 0.8) return "CRITICAL";
        if (detectionRisk >= 0.7) return "HIGH";
        if (detectionRisk >= 0.4) return "MEDIUM";
        return "LOW";
    }

    /**
     * Получает цвет для отображения риска
     */
    @JsonIgnore
    public String getRiskColor() {
        if (detectionRisk == null) return "gray";

        if (detectionRisk >= 0.8) return "red";
        if (detectionRisk >= 0.7) return "orange";
        if (detectionRisk >= 0.4) return "yellow";
        return "green";
    }

    /**
     * Получает иконку для типа устройства
     */
    @JsonIgnore
    public String getDeviceIcon() {
        if (isIos()) {
            if (platform != null && platform.contains("iPad")) {
                return "tablet";
            }
            return "phone";
        } else if (isAndroid()) {
            if (platform != null && platform.contains("Tablet")) {
                return "tablet";
            }
            return "phone";
        } else if (isDesktop()) {
            return "desktop";
        }
        return "device-unknown";
    }

    /**
     * Получает понятное название устройства
     */
    @JsonIgnore
    public String getDeviceDisplayName() {
        if (isIos()) {
            if (platform != null && platform.contains("iPad")) {
                return "iPad";
            }
            return "iPhone";
        } else if (isAndroid()) {
            if (platform != null && platform.contains("Tablet")) {
                return "Android Tablet";
            }
            return "Android Phone";
        } else if (isDesktop()) {
            if (platform != null && platform.contains("Mac")) {
                return "Mac";
            } else if (platform != null && platform.contains("Windows")) {
                return "Windows PC";
            } else if (platform != null && platform.contains("Linux")) {
                return "Linux PC";
            }
            return "Desktop";
        }
        return "Unknown Device";
    }

    /**
     * Получает срок давности последнего использования
     */
    @JsonIgnore
    public String getLastUsedAgo() {
        if (lastUsedAt == null) return "Never";

        long days = ChronoUnit.DAYS.between(lastUsedAt, Instant.now());
        if (days > 0) {
            return days + " days ago";
        }

        long hours = ChronoUnit.HOURS.between(lastUsedAt, Instant.now());
        if (hours > 0) {
            return hours + " hours ago";
        }

        long minutes = ChronoUnit.MINUTES.between(lastUsedAt, Instant.now());
        if (minutes > 0) {
            return minutes + " minutes ago";
        }

        return "Just now";
    }

    /**
     * Проверяет, можно ли запустить браузер
     */
    @JsonIgnore
    public boolean canStartBrowser() {
        return "FREE".equals(status) || "ERROR".equals(status);
    }

    /**
     * Проверяет, можно ли остановить браузер
     */
    @JsonIgnore
    public boolean canStopBrowser() {
        return "BUSY".equals(status);
    }

    /**
     * Проверяет, является ли профиль высокорисковым
     */
    @JsonIgnore
    public boolean isHighRisk() {
        return detectionRisk != null && detectionRisk >= 0.7;
    }

    /**
     * Получает понятное описание уровня антидетекта
     */
    @JsonIgnore
    public String getDetectionLevelDescription() {
        if (detectionLevel == null) return "Unknown";

        switch (detectionLevel) {
            case "BASIC":
                return "Basic protection (fastest)";
            case "ENHANCED":
                return "Enhanced protection (recommended)";
            case "AGGRESSIVE":
                return "Aggressive protection (maximum security)";
            default:
                return detectionLevel;
        }
    }
}