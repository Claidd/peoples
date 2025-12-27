package com.hunt.peoples.profiles.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Entity
@Slf4j
@Table(name = "profiles", uniqueConstraints = {
        @UniqueConstraint(columnNames = "externalKey")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Profile implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // === БАЗОВЫЕ ПОЛЯ ПРОФИЛЯ ===
    @Column(nullable = false)
    private String name;

    @Column(name = "external_key", unique = true, nullable = false)
    private String externalKey;

    @Column(name = "user_data_path")
    private String userDataPath;

    @Column(name = "proxy_url")
    private String proxyUrl;

    @Builder.Default
    private String status = "FREE";

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "locked_by_user_id")
    private String lockedByUserId;


    // === БАЗОВЫЙ FINGERPRINT ===

    // 1. Device basics
    @Column(columnDefinition = "TEXT", name = "device_profile_json")
    private String deviceProfileJson;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "screen_width")
    private Integer screenWidth;

    @Column(name = "screen_height")
    private Integer screenHeight;

    @Column(name = "pixel_ratio")
    private Double pixelRatio;

    @Column(name = "platform")
    private String platform;

    // 2. Hardware capabilities
    @Column(name = "max_touch_points")
    private Integer maxTouchPoints;

    @Column(name = "hardware_concurrency")
    private Integer hardwareConcurrency;

    @Column(name = "device_memory")
    private Integer deviceMemory;

    // 3. WebGL
    @Column(name = "webgl_vendor")
    private String webglVendor;

    @Column(name = "webgl_renderer")
    private String webglRenderer;

    @Column(name = "webgl_version")
    private String webglVersion;

    // 4. Canvas fingerprint
    @Column(name = "canvas_fingerprint")
    private String canvasFingerprint;

    @Column(name = "canvas_noise_hash")
    private String canvasNoiseHash;

    // Поддержка HDR, палитры и т.д.
    @Column(name = "screen_color_gamut")
    private String screenColorGamut; // "srgb", "p3", "rec2020"

    // === РАСШИРЕННЫЙ ANTIDETECT FINGERPRINT ===

    // 5. Audio fingerprint
    @Column(columnDefinition = "TEXT", name = "audio_fingerprint_json")
    private String audioFingerprintJson;

    @Column(name = "audio_sample_rate")
    private Integer audioSampleRate;

    @Column(name = "audio_channel_count")
    private String audioChannelCount;

    @Column(name = "audio_context_latency")
    private Double audioContextLatency;

    // 6. Fonts
    @Column(columnDefinition = "TEXT", name = "fonts_list_json")
    private String fontsListJson;

    // 7. Battery API
    @Column(columnDefinition = "TEXT", name = "battery_info_json")
    private String batteryInfoJson;

    @Column(name = "battery_charging")
    private Boolean batteryCharging;

    @Column(name = "battery_charging_time")
    private Integer batteryChargingTime;

    @Column(name = "battery_discharging_time")
    private Integer batteryDischargingTime;

    @Column(name = "battery_level")
    private Double batteryLevel;

    // 8. Connection API
    @Column(columnDefinition = "TEXT", name = "connection_info_json")
    private String connectionInfoJson;

    @Column(name = "connection_downlink")
    private Double connectionDownlink;

    @Column(name = "connection_effective_type")
    private String connectionEffectiveType;

    @Column(name = "connection_rtt")
    private Integer connectionRtt;

    @Column(name = "connection_save_data")
    private Boolean connectionSaveData;

    @Column(name = "connection_type")
    private String connectionType;

    // 9. Media Devices
    @Column(columnDefinition = "TEXT", name = "media_devices_json")
    private String mediaDevicesJson;

    // 10. WebGL Extensions
    @Column(columnDefinition = "TEXT", name = "webgl_extensions_json")
    private String webglExtensionsJson;

    // 11. Plugins
    @Column(columnDefinition = "TEXT", name = "plugins_json")
    private String pluginsJson;


    // 12. Locale & Timezone
    @Column(name = "timezone")
    private String timezone;

    @Column(name = "locale")
    private String locale;

    @Column(name = "timezone_offset")
    private Integer timezoneOffset;

    @Column(name = "language")
    private String language;

    // 13. Screen details
    @Column(name = "screen_avail_width")
    private Integer screenAvailWidth;

    @Column(name = "screen_avail_height")
    private Integer screenAvailHeight;

    @Column(name = "screen_color_depth")
    private Integer screenColorDepth;

    @Column(name = "screen_pixel_depth")
    private Integer screenPixelDepth;

    // 14. Navigator info
    @Column(columnDefinition = "TEXT", name = "navigator_info_json")
    private String navigatorInfoJson;

    @Column(name = "cookie_enabled")
    private Boolean cookieEnabled;

    @Column(name = "do_not_track")
    private String doNotTrack;

    @Column(name = "online")
    private Boolean online;

    // === ПОВЕДЕНЧЕСКИЕ ПАРАМЕТРЫ ===

    @Column(name = "mouse_movement_variance")
    private Double mouseMovementVariance;

    @Column(name = "typing_speed")
    private Integer typingSpeed;

    @Column(name = "scroll_speed")
    private Double scrollSpeed;

    @Column(columnDefinition = "TEXT", name = "common_websites_json")
    private String commonWebsitesJson;


    // === ДОБАВЬТЕ ЭТО ПОЛЕ СЮДА ===
    @Column(name = "cookies_json", columnDefinition = "TEXT")
    private String cookiesJson;

    // === ВЕРСИИ И МЕТАДАННЫЕ ===

    @Column(name = "chrome_version")
    private String chromeVersion;

    @Column(name = "os_version")
    private String osVersion;

    @Column(name = "os_architecture")
    private String osArchitecture;

    @Column(name = "fingerprint_created_at")
    private Instant fingerprintCreatedAt;

    @Column(name = "fingerprint_updated_at")
    private Instant fingerprintUpdatedAt;

    @Column(name = "fingerprint_hash", length = 32)
    private String fingerprintHash;

    @Column(name = "detection_level")
    private String detectionLevel;

    @Column(name = "detection_risk")
    private Double detectionRisk;

    // === СТАТУС И МОНИТОРИНГ ===

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "check_count")
    @Builder.Default
    private Integer checkCount = 0;

    @Column(name = "last_check_result", columnDefinition = "TEXT")
    private String lastCheckResult;

    // === АУДИТ ПОЛЯ ===

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "notification_permission")
    private String notificationPermission; // default|denied|granted

    @Column(name = "injection_script", columnDefinition = "TEXT")
    private String injectionScript;

    @Column(name = "injection_script_updated_at")
    private Instant injectionScriptUpdatedAt;

    @Column(name = "injection_script_hash", length = 64)
    private String injectionScriptHash;



    // === CLIENT HINTS (UA-CH) ===

    // Пример: "Google Chrome";v="119", "Chromium";v="119", "Not?A_Brand";v="24"
    @Column(columnDefinition = "TEXT", name = "ua_ch_brands_json")
    private String uaChBrandsJson;

    // Windows, Linux, Android, iOS
    @Column(name = "ua_ch_platform")
    private String uaChPlatform;

    // Версия платформы (напр. "10.0.0")
    @Column(name = "ua_ch_platform_version")
    private String uaChPlatformVersion;

    // Архитектура: "x86", "arm"
    @Column(name = "ua_ch_architecture")
    private String uaChArchitecture;

    // Модель устройства (напр. "Pixel 6")
    @Column(name = "ua_ch_model")
    private String uaChModel;

    // Битность: "64" или "32"
    @Column(name = "ua_ch_bitness")
    private String uaChBitness;

    // Является ли мобильным устройством
    @Column(name = "ua_ch_mobile")
    private Boolean uaChMobile;



    // === WEBRTC & NETWORK ===

    // REAL, DISABLED, FAKE_LOCAL, FAKE_PUBLIC
    @Column(name = "webrtc_mode")
    private String webrtcMode;

    // IP, который мы показываем как локальный (напр. 192.168.1.55)
    @Column(name = "webrtc_local_ip")
    private String webrtcLocalIp;

    // IP, который показываем как публичный (должен совпадать с Proxy IP!)
    @Column(name = "webrtc_public_ip")
    private String webrtcPublicIp;

    // Кастомные DNS, чтобы избежать DNS Leaks через провайдера
    @Column(columnDefinition = "TEXT", name = "dns_servers_json")
    private String dnsServersJson;


    // === GEOLOCATION ===

    // Режим: ALLOW, BLOCK, PROMPT
    @Column(name = "geo_permission")
    private String geoPermission;

    @Column(name = "geo_latitude")
    private Double geoLatitude;

    @Column(name = "geo_longitude")
    private Double geoLongitude;

    // Точность в метрах (рандомизировать, напр. 10-50м)
    @Column(name = "geo_accuracy")
    private Double geoAccuracy;



    // Список голосов (Google US English, Microsoft David, etc.)
    @Column(columnDefinition = "TEXT", name = "speech_voices_json")
    private String speechVoicesJson;

    // === ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ===

    /**
     * Получает DeviceProfile объект из JSON
     */
    @Transient
    public DeviceProfile getDeviceProfile() {
        try {
            if (deviceProfileJson != null && !deviceProfileJson.isEmpty() && !deviceProfileJson.equals("null")) {
                return new ObjectMapper().readValue(deviceProfileJson, DeviceProfile.class);
            }
        } catch (Exception e) {
            log.error("Failed to parse deviceProfileJson for profile {}", id, e);
        }
        return null;
    }

    /**
     * Устанавливает DeviceProfile объект как JSON
     */
    @Transient
    public void setDeviceProfile(DeviceProfile deviceProfile) {
        try {
            this.deviceProfileJson = new ObjectMapper().writeValueAsString(deviceProfile);

            // Автоматически заполняем базовые поля
            if (deviceProfile != null) {
                this.userAgent = deviceProfile.getUserAgent();
                this.screenWidth = deviceProfile.getWidth();
                this.screenHeight = deviceProfile.getHeight();
                this.pixelRatio = deviceProfile.getPixelRatio();
                this.platform = deviceProfile.getPlatform();
                this.maxTouchPoints = deviceProfile.getMaxTouchPoints();
                this.hardwareConcurrency = deviceProfile.getHardwareConcurrency();
                this.deviceMemory = deviceProfile.getDeviceMemory();
                this.webglVendor = deviceProfile.getWebglVendor();
                this.webglRenderer = deviceProfile.getWebglRenderer();
                this.webglVersion = deviceProfile.getWebglVersion();
                this.audioContextLatency = deviceProfile.getAudioContextLatency();
                this.canvasFingerprint = deviceProfile.getCanvasFingerprint();
                this.canvasNoiseHash = deviceProfile.getCanvasNoiseHash();
            }
        } catch (Exception e) {
            log.error("Failed to serialize deviceProfile for profile {}", id, e);
        }
    }

    /**
     * Получает список шрифтов
     */
    @Transient
    public List<String> getFontsList() {
        try {
            if (fontsListJson != null && !fontsListJson.isEmpty() && !fontsListJson.equals("null")) {
                return new ObjectMapper().readValue(
                        fontsListJson,
                        new TypeReference<List<String>>() {}
                );
            }
        } catch (Exception e) {
            log.error("Failed to parse fontsListJson for profile {}", id, e);
        }
        return new ArrayList<>();
    }

    /**
     * Устанавливает список шрифтов
     */
    @Transient
    public void setFontsList(List<String> fonts) {
        try {
            this.fontsListJson = new ObjectMapper().writeValueAsString(fonts);
        } catch (Exception e) {
            log.error("Failed to serialize fontsList for profile {}", id, e);
            this.fontsListJson = "[]";
        }
    }

    /**
     * Получает информацию о батарее
     */
    @Transient
    public Map<String, Object> getBatteryInfo() {
        try {
            if (batteryInfoJson != null && !batteryInfoJson.isEmpty() && !batteryInfoJson.equals("null")) {
                return new ObjectMapper().readValue(
                        batteryInfoJson,
                        new TypeReference<Map<String, Object>>() {}
                );
            }
        } catch (Exception e) {
            log.error("Failed to parse batteryInfoJson for profile {}", id, e);
        }
        return new HashMap<>();
    }

    /**
     * Устанавливает информацию о батарее
     */
    @Transient
    public void setBatteryInfo(Map<String, Object> batteryInfo) {
        try {
            this.batteryInfoJson = new ObjectMapper().writeValueAsString(batteryInfo);

            // Автоматически заполняем поля
            if (batteryInfo != null) {
                this.batteryCharging = (Boolean) batteryInfo.getOrDefault("charging", false);
                this.batteryChargingTime = ((Number) batteryInfo.getOrDefault("chargingTime", 0)).intValue();
                this.batteryDischargingTime = ((Number) batteryInfo.getOrDefault("dischargingTime", 3600)).intValue();
                this.batteryLevel = ((Number) batteryInfo.getOrDefault("level", 0.85)).doubleValue();
            }
        } catch (Exception e) {
            log.error("Failed to serialize batteryInfo for profile {}", id, e);
            this.batteryInfoJson = "{}";
        }
    }

    /**
     * Получает информацию о соединении
     */
    @Transient
    public Map<String, Object> getConnectionInfo() {
        return parseJsonToMap(connectionInfoJson);
    }

    /**
     * Устанавливает информацию о соединении
     */
    @Transient
    public void setConnectionInfo(Map<String, Object> connectionInfo) {
        try {
            this.connectionInfoJson = new ObjectMapper().writeValueAsString(connectionInfo);

            if (connectionInfo != null) {
                this.connectionDownlink = ((Number) connectionInfo.getOrDefault("downlink", 10.0)).doubleValue();
                this.connectionEffectiveType = (String) connectionInfo.getOrDefault("effectiveType", "4g");
                this.connectionRtt = ((Number) connectionInfo.getOrDefault("rtt", 100)).intValue();
                this.connectionSaveData = (Boolean) connectionInfo.getOrDefault("saveData", false);
                this.connectionType = (String) connectionInfo.getOrDefault("type", "wifi");
            }
        } catch (Exception e) {
            log.error("Failed to serialize connectionInfo for profile {}", id, e);
            this.connectionInfoJson = "{}";
        }
    }

    /**
     * Получает WebGL extensions
     */
    @Transient
    public Map<String, Object> getWebglExtensions() {
        return parseJsonToMap(webglExtensionsJson);
    }

    /**
     * Устанавливает WebGL extensions
     */
    @Transient
    public void setWebglExtensions(Map<String, Object> extensions) {
        try {
            this.webglExtensionsJson = new ObjectMapper().writeValueAsString(extensions);
        } catch (Exception e) {
            log.error("Failed to serialize webglExtensions for profile {}", id, e);
            this.webglExtensionsJson = "{}";
        }
    }

    /**
     * Получает плагины
     */
    @Transient
    public List<Map<String, Object>> getPlugins() {
        try {
            if (pluginsJson != null && !pluginsJson.isEmpty() && !pluginsJson.equals("null")) {
                return new ObjectMapper().readValue(
                        pluginsJson,
                        new TypeReference<List<Map<String, Object>>>() {}
                );
            }
        } catch (Exception e) {
            log.error("Failed to parse pluginsJson for profile {}", id, e);
        }
        return new ArrayList<>();
    }

    /**
     * Устанавливает плагины
     */
    @Transient
    public void setPlugins(List<Map<String, Object>> plugins) {
        try {
            this.pluginsJson = new ObjectMapper().writeValueAsString(plugins);
        } catch (Exception e) {
            log.error("Failed to serialize plugins for profile {}", id, e);
            this.pluginsJson = "[]";
        }
    }

    /**
     * Получает медиа устройства
     */
    @Transient
    public List<Map<String, Object>> getMediaDevices() {
        try {
            if (mediaDevicesJson != null && !mediaDevicesJson.isEmpty() && !mediaDevicesJson.equals("null")) {
                return new ObjectMapper().readValue(
                        mediaDevicesJson,
                        new TypeReference<List<Map<String, Object>>>() {}
                );
            }
        } catch (Exception e) {
            log.error("Failed to parse mediaDevicesJson for profile {}", id, e);
        }
        return new ArrayList<>();
    }

    /**
     * Устанавливает медиа устройства
     */
    @Transient
    public void setMediaDevices(List<Map<String, Object>> devices) {
        try {
            this.mediaDevicesJson = new ObjectMapper().writeValueAsString(devices);
        } catch (Exception e) {
            log.error("Failed to serialize mediaDevices for profile {}", id, e);
            this.mediaDevicesJson = "[]";
        }
    }

    /**
     * Получает информацию навигатора
     */
    @Transient
    public Map<String, Object> getNavigatorInfo() {
        return parseJsonToMap(navigatorInfoJson);
    }

    /**
     * Устанавливает информацию навигатора
     */
    @Transient
    public void setNavigatorInfo(Map<String, Object> navigatorInfo) {
        try {
            this.navigatorInfoJson = new ObjectMapper().writeValueAsString(navigatorInfo);

            if (navigatorInfo != null) {
                this.cookieEnabled = (Boolean) navigatorInfo.getOrDefault("cookieEnabled", true);
                this.doNotTrack = (String) navigatorInfo.getOrDefault("doNotTrack", "unspecified");
                this.online = (Boolean) navigatorInfo.getOrDefault("online", true);
            }
        } catch (Exception e) {
            log.error("Failed to serialize navigatorInfo for profile {}", id, e);
            this.navigatorInfoJson = "{}";
        }
    }

    /**
     * Получает список сайтов
     */
    @Transient
    // Добавьте это в класс Profile.java
    public List<String> getCommonWebsites() {
        if (this.commonWebsitesJson == null || this.commonWebsitesJson.isBlank()) {
            return List.of();
        }
        try {
            // Если это JSON массив ["site1", "site2"]
            return new ObjectMapper().readValue(this.commonWebsitesJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            // Если там просто строка через запятую
            return Arrays.asList(this.commonWebsitesJson.split("\\s*,\\s*"));
        }
    }

    /**
     * Устанавливает список сайтов
     */
    @Transient
    public void setCommonWebsites(List<String> websites) {
        try {
            this.commonWebsitesJson = new ObjectMapper().writeValueAsString(websites);
        } catch (Exception e) {
            this.commonWebsitesJson = "[]";
        }
    }

    /**
     * Получает аудио fingerprint
     */
    @Transient
    public Map<String, Object> getAudioFingerprint() {
        return parseJsonToMap(audioFingerprintJson);
    }

    /**
     * Устанавливает аудио fingerprint
     */
    @Transient
    public void setAudioFingerprint(Map<String, Object> audioFingerprint) {
        try {
            this.audioFingerprintJson = new ObjectMapper().writeValueAsString(audioFingerprint);
        } catch (Exception e) {
            log.error("Failed to serialize audioFingerprint for profile {}", id, e);
            this.audioFingerprintJson = "{}";
        }
    }

    /**
     * Проверяет, нуждается ли профиль в обновлении fingerprint
     */
    @Transient
    public boolean needsFingerprintUpdate() {
        if (fingerprintUpdatedAt == null) return true;

        // Если fingerprint старше 7 дней
        return fingerprintUpdatedAt.isBefore(
                Instant.now().minus(7, ChronoUnit.DAYS)
        );
    }

    /**
     * Обновляет хэш fingerprint
     */
    @Transient
    public void updateFingerprintHash() {
        try {
            // Создаем строку из ключевых параметров
            StringBuilder dataBuilder = new StringBuilder();
            dataBuilder.append(userAgent != null ? userAgent : "");
            dataBuilder.append(platform != null ? platform : "");
            dataBuilder.append(screenWidth != null ? screenWidth : "");
            dataBuilder.append(screenHeight != null ? screenHeight : "");
            dataBuilder.append(pixelRatio != null ? pixelRatio : "");
            dataBuilder.append(Instant.now().toEpochMilli());

            String data = dataBuilder.toString();

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data.getBytes(StandardCharsets.UTF_8));

            // Конвертируем в hex строку
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            this.fingerprintHash = hexString.toString().substring(0, 32);

        } catch (Exception e) {
            log.error("Failed to generate fingerprint hash for profile {}: {}", id, e.getMessage());
            this.fingerprintHash = "error_" + UUID.randomUUID().toString().substring(0, 16);
        }
    }

    /**
     * Рассчитывает риск детекции на основе уровня
     */
    @Transient
    public double calculateDetectionRisk() {
        if (detectionLevel == null) {
            return 0.3;
        }

        return switch (detectionLevel.toUpperCase()) {
            case "BASIC" -> 0.5;
            case "ENHANCED" -> 0.2;
            case "AGGRESSIVE" -> 0.05;
            default -> 0.3;
        };
    }

    /**
     * Получает разрешение экрана в формате "ширинаxвысота"
     */
    @Transient
    public String getScreenResolution() {
        if (screenWidth != null && screenHeight != null) {
            return screenWidth + "x" + screenHeight;
        }
        return "N/A";
    }

    /**
     * Проверяет, является ли устройство мобильным
     */
    @Transient
    public boolean isMobileDevice() {
        if (userAgent == null) return false;
        String ua = userAgent.toLowerCase();
        return ua.contains("mobile") ||
                ua.contains("android") ||
                ua.contains("iphone") ||
                ua.contains("ipad") ||
                ua.contains("tablet");
    }

    /**
     * Проверяет, является ли устройство iOS
     */
    @Transient
    public boolean isIosDevice() {
        if (userAgent == null) return false;
        String ua = userAgent.toLowerCase();
        return ua.contains("iphone") || ua.contains("ipad");
    }

    /**
     * Проверяет, является ли устройство Android
     */
    @Transient
    public boolean isAndroidDevice() {
        if (userAgent == null) return false;
        return userAgent.toLowerCase().contains("android");
    }

    /**
     * Проверяет, является ли профиль валидным
     */
    @Transient
    public boolean isValid() {
        return userAgent != null &&
                screenWidth != null &&
                screenHeight != null &&
                platform != null &&
                externalKey != null;
    }

    /**
     * Проверяет, готов ли профиль к использованию
     */
    @Transient
    public boolean isReadyForUse() {
        return isValid() &&
                isActive != null &&
                isActive &&
                userDataPath != null &&
                !userDataPath.isEmpty();
    }

    /**
     * Создает копию профиля с микро-вариациями
     */
    @Transient
    public Profile withMicroVariations() {
        Random random = new Random();

        Profile variedProfile = Profile.builder()
                .id(this.id)
                .name(this.name)
                .externalKey(this.externalKey)
                .userDataPath(this.userDataPath)
                .proxyUrl(this.proxyUrl)
                .status(this.status)
                .lastUsedAt(this.lastUsedAt)
                .lockedByUserId(this.lockedByUserId)
                .build();

        // Добавляем вариации к базовым параметрам
        if (this.userAgent != null && this.userAgent.contains("Chrome/")) {
            variedProfile.userAgent = addUaVariation(this.userAgent);
        }

        if (this.pixelRatio != null) {
            variedProfile.pixelRatio = round(this.pixelRatio + random.nextDouble() * 0.02 - 0.01, 3);
        }

        if (this.audioContextLatency != null) {
            variedProfile.audioContextLatency = round(this.audioContextLatency + random.nextDouble() * 0.002 - 0.001, 4);
        }

        // Обновляем хэши
        variedProfile.canvasFingerprint = generateCanvasHash();
        variedProfile.canvasNoiseHash = generateNoiseHash();
        variedProfile.updateFingerprintHash();

        return variedProfile;
    }

    private String addUaVariation(String userAgent) {
        if (userAgent == null || !userAgent.contains("Chrome/")) {
            return userAgent;
        }

        try {
            // Меняем минорную версию Chrome на +/- 1
            Pattern pattern = Pattern.compile("Chrome/(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");
            Matcher matcher = pattern.matcher(userAgent);

            if (matcher.find()) {
                String major = matcher.group(1);
                String minor = matcher.group(2);
                String build = matcher.group(3);
                String patch = matcher.group(4);

                int minorInt = Integer.parseInt(minor);
                int newMinor = minorInt + (new Random().nextBoolean() ? 1 : -1);
                if (newMinor < 0) newMinor = 0;

                return userAgent.replace(
                        "Chrome/" + major + "." + minor + "." + build + "." + patch,
                        "Chrome/" + major + "." + newMinor + "." + build + "." + patch
                );
            }
        } catch (Exception e) {
            // В случае ошибки возвращаем оригинальный UA
            log.debug("Failed to add UA variation: {}", e.getMessage());
        }

        return userAgent;
    }

    private double round(double value, int places) {
        if (places < 0) throw new IllegalArgumentException();
        BigDecimal bd = BigDecimal.valueOf(value);
        bd = bd.setScale(places, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }

    private String generateCanvasHash() {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest((this.userAgent + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash).substring(0, 16);
        } catch (Exception e) {
            return "defaultCanvasHash";
        }
    }

    private String generateNoiseHash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash).substring(0, 32);
        } catch (Exception e) {
            return "defaultNoiseHash";
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Вспомогательный метод для парсинга JSON в Map
     */
    private Map<String, Object> parseJsonToMap(String json) {
        try {
            if (json != null && !json.isEmpty() && !json.equals("null")) {
                return new ObjectMapper().readValue(
                        json,
                        new TypeReference<Map<String, Object>>() {}
                );
            }
        } catch (Exception e) {
            log.error("Failed to parse JSON to Map: {}", e.getMessage());
        }
        return new HashMap<>();
    }

    /**
     * Вспомогательный метод для сериализации Map в JSON
     */
    private String mapToJson(Map<String, Object> map) {
        try {
            if (map != null && !map.isEmpty()) {
                return new ObjectMapper().writeValueAsString(map);
            }
        } catch (Exception e) {
            log.error("Failed to serialize Map to JSON: {}", e.getMessage());
        }
        return "{}";
    }

    @PrePersist
    @PreUpdate
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        this.updatedAt = Instant.now();

        if (this.fingerprintCreatedAt == null && this.userAgent != null) {
            this.fingerprintCreatedAt = Instant.now();
        }

        if (this.detectionRisk == null && this.detectionLevel != null) {
            this.detectionRisk = calculateDetectionRisk();
        }

        if (this.fingerprintHash == null) {
            updateFingerprintHash();
        }

        // Устанавливаем дефолтные значения для обязательных полей
        if (this.status == null) {
            this.status = "FREE";
        }
        if (this.isActive == null) {
            this.isActive = true;
        }
        if (this.checkCount == null) {
            this.checkCount = 0;
        }
    }

    @PostLoad
    public void postLoad() {
        // При загрузке можно выполнить дополнительные проверки
        if (!isValid()) {
            log.warn("Profile {} loaded but is invalid", id);
        }
    }

    // === СТАТИЧЕСКИЕ ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ===

    /**
     * Создает новый профиль с минимальными требованиями
     */
    /**
     * Создает новый профиль с мобильными параметрами по умолчанию
     */
    public static Profile createBasicProfile(String externalKey, String name) {
        Random random = new Random();
        List<String> mobileDevices = Arrays.asList(
                "iPhone 14 Pro",
                "iPhone 13",
                "Samsung Galaxy S23",
                "Google Pixel 7",
                "Xiaomi 13",
                "iPad Pro"
        );

        String randomDevice = mobileDevices.get(random.nextInt(mobileDevices.size()));
        boolean isIos = randomDevice.contains("iPhone") || randomDevice.contains("iPad");

        return Profile.builder()
                .externalKey(externalKey)
                .name(name + " - " + randomDevice)
                .userAgent(isIos ?
                        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1" :
                        "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36")
                .screenWidth(isIos ? 393 : 412)
                .screenHeight(isIos ? 852 : 915)
                .pixelRatio(isIos ? 3.0 : 2.63)
                .platform(isIos ? "iPhone" : "Linux armv8l")
                .hardwareConcurrency(isIos ? 6 : 8)
                .deviceMemory(isIos ? 4 : 8)
                .maxTouchPoints(5)
                .timezone("Europe/Moscow")
                .language("en-US")
                .locale("en-US")
                .timezoneOffset(-180)
                .cookieEnabled(true)
                .online(true)
                .doNotTrack("unspecified")
                .screenAvailWidth(isIos ? 393 : 412)
                .screenAvailHeight(isIos ? 852 : 915)
                .screenColorDepth(24)
                .screenPixelDepth(24)
                .chromeVersion("120.0.0.0")
                .osVersion(isIos ? "16.0" : "13.0")
                .osArchitecture("arm64")
                .webglVendor(isIos ? "Apple Inc." : "Google Inc. (Qualcomm)")
                .webglRenderer(isIos ? "Apple GPU" : "Adreno (TM) 740")
                .webglVersion("WebGL 2.0")
                .audioSampleRate(48000)
                .audioChannelCount("stereo")
                .audioContextLatency(0.005)
                .batteryCharging(false)
                .batteryLevel(0.85)
                .connectionDownlink(10.0)
                .connectionEffectiveType("4g")
                .connectionRtt(100)
                .detectionLevel("ENHANCED")
                .detectionRisk(0.1)  // Низкий риск для мобильных
                .isActive(true)
                .status("FREE")
                .build();
    }


}

//public static Profile createBasicProfile(String externalKey, String name) {
//    return Profile.builder()
//            .externalKey(externalKey)
//            .name(name)
//            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
//            .screenWidth(1920)
//            .screenHeight(1080)
//            .pixelRatio(1.0)
//            .platform("Win32")
//            .hardwareConcurrency(4)
//            .deviceMemory(4)
//            .maxTouchPoints(0)
//            .timezone("Europe/Moscow")
//            .language("en-US")
//            .locale("en-US")
//            .timezoneOffset(-180)
//            .cookieEnabled(true)
//            .online(true)
//            .doNotTrack("unspecified")
//            .screenAvailWidth(1920)
//            .screenAvailHeight(1040)
//            .screenColorDepth(24)
//            .screenPixelDepth(24)
//            .chromeVersion("120.0.0.0")
//            .osVersion("10.0.22631")
//            .osArchitecture("x64")
//            .webglVendor("Google Inc.")
//            .webglRenderer("ANGLE (Intel, Mesa Intel(R) UHD Graphics 630 (CML GT2), OpenGL 4.6)")
//            .webglVersion("WebGL 1.0")
//            .audioSampleRate(48000)
//            .audioChannelCount("stereo")
//            .audioContextLatency(0.005)
//            .batteryCharging(false)
//            .batteryLevel(0.85)
//            .connectionDownlink(10.0)
//            .connectionEffectiveType("4g")
//            .connectionRtt(100)
//            .detectionLevel("ENHANCED")
//            .detectionRisk(0.2)
//            .isActive(true)
//            .status("FREE")
//            .build();
//}


