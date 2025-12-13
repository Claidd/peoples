package com.hunt.peoples.profiles.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.peoples.browser.config.BrowserProperties;
import com.hunt.peoples.profiles.entity.DeviceProfile;
import com.hunt.peoples.profiles.entity.Profile;
import com.hunt.peoples.profiles.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfilesService {

    private final ProfileRepository profileRepository;
    private final BrowserProperties browserProperties;
    private final FingerprintGenerator fingerprintGenerator;
    private final ObjectMapper objectMapper;

    // Список мобильных устройств для случайного выбора
    private static final List<String> MOBILE_DEVICE_TYPES = Arrays.asList(
            "iphone_14_pro",
            "iphone_13",
            "samsung_galaxy_s23",
            "google_pixel_7",
            "xiaomi_13",
            "samsung_galaxy_tab_s8",
            "ipad_pro"
    );

    // Мапа с описанием устройств
    private static final Map<String, String> DEVICE_DISPLAY_NAMES = Map.of(
            "iphone_14_pro", "iPhone 14 Pro",
            "iphone_13", "iPhone 13",
            "samsung_galaxy_s23", "Samsung Galaxy S23",
            "google_pixel_7", "Google Pixel 7",
            "xiaomi_13", "Xiaomi 13",
            "samsung_galaxy_tab_s8", "Samsung Galaxy Tab S8",
            "ipad_pro", "iPad Pro"
    );

    /**
     * Основной метод для интеграции - создает или находит профиль по externalKey
     */
    @Transactional
    public Profile findOrCreateProfileForIntegration(String externalKey, String proxyUrl) {
        return findOrCreateByExternalKey(externalKey, proxyUrl, null, null, false);
    }

    /**
     * Поиск или создание профиля по externalKey
     */
    @Transactional
    public Profile findOrCreateByExternalKey(String externalKey, String proxyUrl,
                                             String deviceType, String detectionLevel,
                                             boolean forceNew) {
        String safeKey = sanitizeExternalKey(externalKey);

        log.info("Processing profile request: key={}, proxy={}, deviceType={}, level={}, forceNew={}",
                safeKey, proxyUrl, deviceType, detectionLevel, forceNew);

        // Если не forceNew, ищем существующий профиль
        if (!forceNew) {
            Optional<Profile> existingProfile = profileRepository.findByExternalKey(safeKey);
            if (existingProfile.isPresent()) {
                Profile profile = existingProfile.get();
                log.info("Found existing profile: id={}, userAgent={}",
                        profile.getId(), profile.getUserAgent());

                // Обновляем прокси если нужно
                if (proxyUrl != null && !proxyUrl.equals(profile.getProxyUrl())) {
                    profile.setProxyUrl(proxyUrl);
                    profile.setUpdatedAt(Instant.now());
                    profileRepository.save(profile);
                    log.info("Updated proxy for profile {}: {}", safeKey, proxyUrl);
                }

                // Проверяем, нуждается ли профиль в обновлении fingerprint
                if (profile.needsFingerprintUpdate()) {
                    log.info("Profile {} needs fingerprint update (last update: {})",
                            profile.getId(), profile.getFingerprintUpdatedAt());
                    return updateFingerprintWithRandomMobile(profile);
                }

                return profile;
            }
        } else {
            // Если forceNew, удаляем старый профиль если он существует
            profileRepository.findByExternalKey(safeKey).ifPresent(profile -> {
                log.info("Force new requested, deleting old profile: {}", profile.getId());
                try {
                    deleteProfileWithDirectory(profile.getId());
                } catch (Exception e) {
                    log.warn("Failed to delete old profile directory, continuing...", e);
                }
            });
        }

        // Если deviceType не указан, выбираем случайное мобильное устройство
        String deviceTypeToUse = deviceType;
        if (deviceTypeToUse == null || deviceTypeToUse.trim().isEmpty()) {
            deviceTypeToUse = getRandomMobileDeviceType();
            log.info("No deviceType specified, using random mobile device: {}", deviceTypeToUse);
        }

        // Если detectionLevel не указан, используем ENHANCED
        String detectionLevelToUse = detectionLevel;
        if (detectionLevelToUse == null || detectionLevelToUse.trim().isEmpty()) {
            detectionLevelToUse = "ENHANCED";
        }

        // Создаем новый профиль с рандомными мобильными параметрами
        return createProfileWithRandomMobileParams(safeKey, proxyUrl, deviceTypeToUse, detectionLevelToUse);
    }

    /**
     * Создает профиль с рандомными мобильными параметрами
     */
    private Profile createProfileWithRandomMobileParams(String externalKey, String proxyUrl,
                                                        String deviceType, String detectionLevel) {
        log.info("Creating profile with random mobile params: key={}, deviceType={}, level={}",
                externalKey, deviceType, detectionLevel);

        try {
            // 1. Генерируем DeviceProfile с помощью fingerprintGenerator
            DeviceProfile deviceProfile;
            if ("random".equalsIgnoreCase(deviceType)) {
                deviceProfile = fingerprintGenerator.generateRandomMobileProfile();
                log.info("Generated random mobile profile");
            } else {
                deviceProfile = fingerprintGenerator.generateDeviceProfile(deviceType, detectionLevel);
                log.info("Generated device profile for type: {}", deviceType);
            }

            if (deviceProfile == null) {
                log.error("Failed to generate device profile for type: {}", deviceType);
                throw new RuntimeException("Failed to generate device profile");
            }

            // 2. Создаем новый профиль и заполняем его данными
            Profile profile = new Profile();
            profile.setExternalKey(externalKey);
            profile.setName(getDeviceDisplayName(deviceType) + " - " + externalKey);
            profile.setDetectionLevel(detectionLevel);
            profile.setDetectionRisk(calculateRiskForDetectionLevel(detectionLevel));

            // 3. Заполняем базовые поля из DeviceProfile
            profile.setUserAgent(deviceProfile.getUserAgent());
            profile.setScreenWidth(deviceProfile.getWidth());
            profile.setScreenHeight(deviceProfile.getHeight());
            profile.setPixelRatio(deviceProfile.getPixelRatio());
            profile.setPlatform(deviceProfile.getPlatform());
            profile.setHardwareConcurrency(deviceProfile.getHardwareConcurrency());
            profile.setDeviceMemory(deviceProfile.getDeviceMemory());
            profile.setMaxTouchPoints(deviceProfile.getMaxTouchPoints());
            profile.setWebglVendor(deviceProfile.getWebglVendor());
            profile.setWebglRenderer(deviceProfile.getWebglRenderer());
            profile.setWebglVersion(deviceProfile.getWebglVersion());
            profile.setAudioContextLatency(deviceProfile.getAudioContextLatency());
            profile.setCanvasFingerprint(deviceProfile.getCanvasFingerprint());
            profile.setCanvasNoiseHash(deviceProfile.getCanvasNoiseHash());

            // 4. Устанавливаем DeviceProfile JSON
            try {
                profile.setDeviceProfileJson(objectMapper.writeValueAsString(deviceProfile));
            } catch (Exception e) {
                log.warn("Failed to serialize device profile to JSON", e);
            }

            // 5. Устанавливаем рандомные мобильные параметры
            setRandomMobileParameters(profile, deviceProfile.isMobile());

            // 6. Создаем директорию профиля
            String userDataPath = createProfileDirectory(externalKey);
            profile.setUserDataPath(userDataPath);

            // 7. Устанавливаем остальные поля
            profile.setProxyUrl(proxyUrl);
            profile.setStatus("FREE");
            profile.setIsActive(true);
            profile.setCheckCount(0);
            profile.setFingerprintCreatedAt(Instant.now());
            profile.setFingerprintUpdatedAt(Instant.now());
            profile.updateFingerprintHash();

            // 8. Сохраняем профиль
            Profile savedProfile = profileRepository.save(profile);
            log.info("Created profile with random mobile params: id={}, userAgent={}, screen={}x{}",
                    savedProfile.getId(), savedProfile.getUserAgent(),
                    savedProfile.getScreenWidth(), savedProfile.getScreenHeight());

            return savedProfile;

        } catch (Exception e) {
            log.error("Failed to create profile with random mobile params for key: {}", externalKey, e);
            throw new RuntimeException("Failed to create profile: " + e.getMessage(), e);
        }
    }

    /**
     * Обновляет fingerprint существующего профиля
     */
    @Transactional
    public Profile updateFingerprint(Long profileId, String deviceType, String detectionLevel) {
        log.info("Updating fingerprint for profile: {}, deviceType: {}, level: {}",
                profileId, deviceType, detectionLevel);

        return profileRepository.findById(profileId)
                .map(profile -> {
                    // Сохраняем старый userDataPath чтобы не потерять сессии
                    String oldUserDataPath = profile.getUserDataPath();
                    String externalKey = profile.getExternalKey();

                    // Генерируем новый профиль
                    Profile newProfileData = fingerprintGenerator.generateCompleteProfile(
                            externalKey, deviceType, detectionLevel
                    );

                    // Копируем fingerprint данные в существующий профиль
                    copyFingerprintData(profile, newProfileData);

                    // Восстанавливаем важные поля
                    profile.setUserDataPath(oldUserDataPath);
                    profile.setFingerprintUpdatedAt(Instant.now());
                    profile.updateFingerprintHash();
                    profile.setDetectionLevel(detectionLevel);
                    profile.setUpdatedAt(Instant.now());

                    // Пересчитываем риск на основе нового уровня детекции
                    profile.setDetectionRisk(calculateRiskForDetectionLevel(detectionLevel));

                    log.info("Updated fingerprint for profile {} to {} level",
                            profileId, detectionLevel);

                    return profileRepository.save(profile);
                })
                .orElseThrow(() -> {
                    log.error("Profile not found for fingerprint update: {}", profileId);
                    return new RuntimeException("Profile not found: " + profileId);
                });
    }

    /**
     * Копирование всех fingerprint данных из одного профиля в другой (ОБНОВЛЕННЫЙ)
     */
    private void copyFingerprintData(Profile target, Profile source) {
        try {
            // 1. Копируем DeviceProfile
            if (source.getDeviceProfile() != null) {
                target.setDeviceProfile(source.getDeviceProfile());
            }

            // 2. Копируем базовые поля
            target.setUserAgent(source.getUserAgent());
            target.setScreenWidth(source.getScreenWidth());
            target.setScreenHeight(source.getScreenHeight());
            target.setPixelRatio(source.getPixelRatio());
            target.setPlatform(source.getPlatform());
            target.setMaxTouchPoints(source.getMaxTouchPoints());
            target.setHardwareConcurrency(source.getHardwareConcurrency());
            target.setDeviceMemory(source.getDeviceMemory());
            target.setWebglVendor(source.getWebglVendor());
            target.setWebglRenderer(source.getWebglRenderer());
            target.setWebglVersion(source.getWebglVersion());
            target.setCanvasFingerprint(source.getCanvasFingerprint());
            target.setCanvasNoiseHash(source.getCanvasNoiseHash());

            // 3. Копируем расширенные поля
            target.setAudioFingerprintJson(source.getAudioFingerprintJson());
            target.setAudioSampleRate(source.getAudioSampleRate());
            target.setAudioChannelCount(source.getAudioChannelCount());
            target.setAudioContextLatency(source.getAudioContextLatency());

            // 4. Копируем JSON поля
            copyJsonField(target::setFontsListJson, source.getFontsListJson());
            copyJsonField(target::setBatteryInfoJson, source.getBatteryInfoJson());
            copyJsonField(target::setConnectionInfoJson, source.getConnectionInfoJson());
            copyJsonField(target::setMediaDevicesJson, source.getMediaDevicesJson());
            copyJsonField(target::setWebglExtensionsJson, source.getWebglExtensionsJson());
            copyJsonField(target::setPluginsJson, source.getPluginsJson());
            copyJsonField(target::setNavigatorInfoJson, source.getNavigatorInfoJson());
            copyJsonField(target::setCommonWebsitesJson, source.getCommonWebsitesJson());

            // 5. Копируем поля батареи
            target.setBatteryCharging(source.getBatteryCharging());
            target.setBatteryChargingTime(source.getBatteryChargingTime());
            target.setBatteryDischargingTime(source.getBatteryDischargingTime());
            target.setBatteryLevel(source.getBatteryLevel());

            // 6. Копируем поля соединения
            target.setConnectionDownlink(source.getConnectionDownlink());
            target.setConnectionEffectiveType(source.getConnectionEffectiveType());
            target.setConnectionRtt(source.getConnectionRtt());
            target.setConnectionSaveData(source.getConnectionSaveData());
            target.setConnectionType(source.getConnectionType());

            // 7. Копируем локальные настройки
            target.setTimezone(source.getTimezone());
            target.setLocale(source.getLocale());
            target.setTimezoneOffset(source.getTimezoneOffset());
            target.setLanguage(source.getLanguage());

            // 8. Копируем детали экрана (ДОБАВЛЕНО)
            target.setScreenAvailWidth(source.getScreenAvailWidth());
            target.setScreenAvailHeight(source.getScreenAvailHeight());
            target.setScreenColorDepth(source.getScreenColorDepth());
            target.setScreenPixelDepth(source.getScreenPixelDepth());

            // 9. Копируем информацию навигатора (ДОБАВЛЕНО)
            target.setCookieEnabled(source.getCookieEnabled());
            target.setDoNotTrack(source.getDoNotTrack());
            target.setOnline(source.getOnline());

            // 10. Копируем поведенческие параметры
            target.setMouseMovementVariance(source.getMouseMovementVariance());
            target.setTypingSpeed(source.getTypingSpeed());
            target.setScrollSpeed(source.getScrollSpeed());

            // 11. Копируем версии и метаданные
            target.setChromeVersion(source.getChromeVersion());
            target.setOsVersion(source.getOsVersion());
            target.setOsArchitecture(source.getOsArchitecture());
            target.setFingerprintCreatedAt(source.getFingerprintCreatedAt());
            target.setDetectionRisk(source.getDetectionRisk());

            // 12. Обновляем хэш fingerprint
            target.updateFingerprintHash();

            log.debug("Copied all fingerprint data from profile {} to {}",
                    source.getId(), target.getId());

        } catch (Exception e) {
            log.error("Error copying fingerprint data: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to copy fingerprint data", e);
        }
    }

    /**
     * Вспомогательный метод для копирования JSON полей
     */
    private void copyJsonField(java.util.function.Consumer<String> setter, String jsonValue) {
        if (jsonValue != null && !jsonValue.isEmpty() && !jsonValue.equals("null")) {
            setter.accept(jsonValue);
        }
    }


    /**
     * Обновляет fingerprint профиля с новым случайным мобильным устройством
     */
    @Transactional
    public Profile updateFingerprintWithRandomMobile(Profile existingProfile) {
        log.info("Updating fingerprint with random mobile for profile: {}", existingProfile.getId());

        try {
            // Генерируем новый случайный мобильный профиль
            DeviceProfile newDeviceProfile = fingerprintGenerator.generateRandomMobileProfile();

            // Сохраняем старый userDataPath чтобы не потерять сессии
            String oldUserDataPath = existingProfile.getUserDataPath();
            String externalKey = existingProfile.getExternalKey();

            // Обновляем базовые поля
            existingProfile.setUserAgent(newDeviceProfile.getUserAgent());
            existingProfile.setScreenWidth(newDeviceProfile.getWidth());
            existingProfile.setScreenHeight(newDeviceProfile.getHeight());
            existingProfile.setPixelRatio(newDeviceProfile.getPixelRatio());
            existingProfile.setPlatform(newDeviceProfile.getPlatform());
            existingProfile.setHardwareConcurrency(newDeviceProfile.getHardwareConcurrency());
            existingProfile.setDeviceMemory(newDeviceProfile.getDeviceMemory());
            existingProfile.setMaxTouchPoints(newDeviceProfile.getMaxTouchPoints());
            existingProfile.setWebglVendor(newDeviceProfile.getWebglVendor());
            existingProfile.setWebglRenderer(newDeviceProfile.getWebglRenderer());
            existingProfile.setWebglVersion(newDeviceProfile.getWebglVersion());
            existingProfile.setAudioContextLatency(newDeviceProfile.getAudioContextLatency());
            existingProfile.setCanvasFingerprint(newDeviceProfile.getCanvasFingerprint());
            existingProfile.setCanvasNoiseHash(newDeviceProfile.getCanvasNoiseHash());

            // Обновляем DeviceProfile JSON
            try {
                existingProfile.setDeviceProfileJson(objectMapper.writeValueAsString(newDeviceProfile));
            } catch (Exception e) {
                log.warn("Failed to serialize device profile to JSON", e);
            }

            // Обновляем мобильные параметры
            setRandomMobileParameters(existingProfile, newDeviceProfile.isMobile());

            // Восстанавливаем важные поля
            existingProfile.setUserDataPath(oldUserDataPath);
            existingProfile.setFingerprintUpdatedAt(Instant.now());
            existingProfile.updateFingerprintHash();
            existingProfile.setUpdatedAt(Instant.now());

            // Пересчитываем риск
            existingProfile.setDetectionRisk(calculateRiskForDetectionLevel(
                    existingProfile.getDetectionLevel()));

            log.info("Updated fingerprint for profile {} with new mobile device", existingProfile.getId());
            return profileRepository.save(existingProfile);

        } catch (Exception e) {
            log.error("Failed to update fingerprint for profile {}", existingProfile.getId(), e);
            throw new RuntimeException("Failed to update fingerprint", e);
        }
    }

    /**
     * Устанавливает рандомные мобильные параметры для профиля
     */
    private void setRandomMobileParameters(Profile profile, boolean isMobile) {
        Random random = new Random();

        if (isMobile) {
            // Мобильные параметры
            profile.setScreenAvailWidth(profile.getScreenWidth() - 20);
            profile.setScreenAvailHeight(profile.getScreenHeight() - 80);
            profile.setScreenColorDepth(24);
            profile.setScreenPixelDepth(24);
            profile.setTimezoneOffset(random.nextInt(13) * 60 - 720); // -12 to +12 hours
            profile.setCookieEnabled(true);
            profile.setDoNotTrack("unspecified");
            profile.setOnline(true);

            // Устанавливаем язык и локаль
            String[] mobileLanguages = {"en-US", "ru-RU", "zh-CN", "es-ES", "fr-FR", "de-DE"};
            String language = mobileLanguages[random.nextInt(mobileLanguages.length)];
            profile.setLanguage(language);
            profile.setLocale(language.replace("-", "_"));

            // Устанавливаем часовой пояс
            String[] mobileTimezones = {
                    "Europe/Moscow", "America/New_York", "Europe/London",
                    "Asia/Tokyo", "Australia/Sydney", "Asia/Shanghai"
            };
            profile.setTimezone(mobileTimezones[random.nextInt(mobileTimezones.length)]);

            // Устанавливаем версии
            profile.setChromeVersion("120.0.0.0");
            profile.setOsVersion(isIosDevice(profile.getUserAgent()) ? "16.0" : "13.0");
            profile.setOsArchitecture("arm64");

            // Устанавливаем аудио параметры
            profile.setAudioSampleRate(48000);
            profile.setAudioChannelCount("stereo");
            profile.setAudioContextLatency(0.005 + random.nextDouble() * 0.001);

            // Устанавливаем параметры батареи для мобильных
            Map<String, Object> batteryInfo = new HashMap<>();
            batteryInfo.put("charging", random.nextBoolean());
            batteryInfo.put("chargingTime", random.nextBoolean() ? 1800 : 0);
            batteryInfo.put("dischargingTime", 7200 + random.nextInt(3600));
            batteryInfo.put("level", 0.3 + random.nextDouble() * 0.6);
            try {
                profile.setBatteryInfoJson(objectMapper.writeValueAsString(batteryInfo));
            } catch (Exception e) {
                log.warn("Failed to serialize battery info", e);
            }

            // Устанавливаем параметры соединения
            Map<String, Object> connectionInfo = new HashMap<>();
            String[] connectionTypes = {"wifi", "cellular", "bluetooth", "ethernet"};
            String[] effectiveTypes = {"4g", "3g", "2g"};
            connectionInfo.put("downlink", 5.0 + random.nextDouble() * 10.0);
            connectionInfo.put("effectiveType", effectiveTypes[random.nextInt(effectiveTypes.length)]);
            connectionInfo.put("rtt", 50 + random.nextInt(150));
            connectionInfo.put("saveData", random.nextBoolean());
            connectionInfo.put("type", connectionTypes[random.nextInt(connectionTypes.length)]);
            try {
                profile.setConnectionInfoJson(objectMapper.writeValueAsString(connectionInfo));
            } catch (Exception e) {
                log.warn("Failed to serialize connection info", e);
            }

            // Устанавливаем поведенческие параметры для мобильных
            profile.setMouseMovementVariance(0.1 + random.nextDouble() * 0.3);
            profile.setTypingSpeed(30 + random.nextInt(40));
            profile.setScrollSpeed(50 + random.nextDouble() * 100);

            // Устанавливаем WebGL extensions
            Map<String, Object> webglExtensions = new HashMap<>();
            webglExtensions.put("EXT_blend_minmax", true);
            webglExtensions.put("WEBGL_compressed_texture_s3tc", true);
            webglExtensions.put("WEBGL_debug_renderer_info", false); // Скрываем для безопасности
            try {
                profile.setWebglExtensionsJson(objectMapper.writeValueAsString(webglExtensions));
            } catch (Exception e) {
                log.warn("Failed to serialize WebGL extensions", e);
            }

            // Устанавливаем плагины для мобильных
            List<Map<String, Object>> plugins = new ArrayList<>();
            Map<String, Object> plugin = new HashMap<>();
            plugin.put("name", "PDF Viewer");
            plugin.put("filename", "internal-pdf-viewer");
            plugin.put("description", "Portable Document Format");
            plugin.put("length", 1);
            plugins.add(plugin);
            try {
                profile.setPluginsJson(objectMapper.writeValueAsString(plugins));
            } catch (Exception e) {
                log.warn("Failed to serialize plugins", e);
            }

            // Устанавливаем медиа устройства
            List<Map<String, Object>> mediaDevices = new ArrayList<>();
            String[] deviceKinds = {"audioinput", "audiooutput", "videoinput"};
            for (String kind : deviceKinds) {
                Map<String, Object> device = new HashMap<>();
                device.put("deviceId", "default");
                device.put("groupId", "default-group");
                device.put("kind", kind);
                device.put("label", "");
                mediaDevices.add(device);
            }
            try {
                profile.setMediaDevicesJson(objectMapper.writeValueAsString(mediaDevices));
            } catch (Exception e) {
                log.warn("Failed to serialize media devices", e);
            }

            // Устанавливаем шрифты для мобильных
            List<String> fonts = Arrays.asList(
                    "Arial", "Helvetica", "Times New Roman", "Courier New",
                    "Verdana", "Georgia", "Palatino", "Garamond",
                    "Bookman", "Comic Sans MS", "Trebuchet MS", "Arial Black",
                    "Impact", "Tahoma", "Courier", "Lucida Console"
            );
            try {
                profile.setFontsListJson(objectMapper.writeValueAsString(fonts));
            } catch (Exception e) {
                log.warn("Failed to serialize fonts", e);
            }

            // Устанавливаем информацию навигатора
            Map<String, Object> navigatorInfo = new HashMap<>();
            navigatorInfo.put("cookieEnabled", true);
            navigatorInfo.put("doNotTrack", "unspecified");
            navigatorInfo.put("online", true);
            try {
                profile.setNavigatorInfoJson(objectMapper.writeValueAsString(navigatorInfo));
            } catch (Exception e) {
                log.warn("Failed to serialize navigator info", e);
            }

            // Устанавливаем список сайтов
            List<String> websites = Arrays.asList(
                    "https://google.com",
                    "https://youtube.com",
                    "https://facebook.com",
                    "https://amazon.com",
                    "https://twitter.com"
            );
            try {
                profile.setCommonWebsitesJson(objectMapper.writeValueAsString(websites));
            } catch (Exception e) {
                log.warn("Failed to serialize websites", e);
            }
        }
    }

    /**
     * Создает директорию профиля
     */
    private String createProfileDirectory(String externalKey) {
        String baseDir = browserProperties.getBaseDir();
        String userDataPath = baseDir + "/" + externalKey + "/userDataDir";

        try {
            Path profilePath = Paths.get(userDataPath);
            Files.createDirectories(profilePath);
            log.info("Created profile directory: {}", profilePath.toAbsolutePath());

            // Создаем поддиректории
            createProfileSubdirectories(profilePath);

            return userDataPath;

        } catch (IOException e) {
            log.error("Failed to create profile directory: {}", userDataPath, e);
            throw new RuntimeException("Failed to create profile directory for " + externalKey, e);
        }
    }

    /**
     * Создает поддиректории профиля
     */
    private void createProfileSubdirectories(Path profilePath) throws IOException {
        Files.createDirectories(profilePath);

        String[] subDirs = {
                "Cache",
                "CacheStorage",
                "Code Cache",
                "File System",
                "GPUCache",
                "IndexedDB",
                "Local Storage",
                "Session Storage",
                "Service Worker",
                "Storage",
                "WebRTCIdentity"
        };

        for (String dir : subDirs) {
            Path subDir = profilePath.resolve(dir);
            Files.createDirectories(subDir);
        }

        Path firstRunFile = profilePath.resolve("First Run");
        if (!Files.exists(firstRunFile)) {
            Files.createFile(firstRunFile);
        }

        log.debug("Created profile subdirectories in: {}", profilePath);
    }

    /**
     * Получает случайный тип мобильного устройства
     */
    private String getRandomMobileDeviceType() {
        Random random = new Random();
        return MOBILE_DEVICE_TYPES.get(random.nextInt(MOBILE_DEVICE_TYPES.size()));
    }

    /**
     * Получает отображаемое имя устройства
     */
    private String getDeviceDisplayName(String deviceType) {
        return DEVICE_DISPLAY_NAMES.getOrDefault(deviceType, deviceType);
    }

    /**
     * Проверяет, является ли устройство iOS по User Agent
     */
    private boolean isIosDevice(String userAgent) {
        if (userAgent == null) return false;
        String ua = userAgent.toLowerCase();
        return ua.contains("iphone") || ua.contains("ipad");
    }

    /**
     * Рассчитывает риск на основе уровня детекции
     */
    private Double calculateRiskForDetectionLevel(String detectionLevel) {
        if (detectionLevel == null) {
            return 0.2;
        }

        switch (detectionLevel.toUpperCase()) {
            case "BASIC":
                return 0.3;
            case "ENHANCED":
                return 0.1;
            case "AGGRESSIVE":
                return 0.05;
            default:
                return 0.2;
        }
    }

    /**
     * Очистка externalKey от недопустимых символов
     */
    private String sanitizeExternalKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("External key cannot be null or empty");
        }

        String sanitized = key.trim();
        sanitized = sanitized.replaceAll("\\s+", "");
        sanitized = sanitized.replaceAll("[^0-9A-Za-z_-]", "_");

        if (sanitized.length() > 100) {
            sanitized = sanitized.substring(0, 100);
        }

        sanitized = sanitized.replaceAll("^[._]+", "");

        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("External key becomes empty after sanitization: " + key);
        }

        return sanitized;
    }

    /**
     * Получает все доступные типы мобильных устройств
     */
    public List<String> getAvailableMobileDeviceTypes() {
        return new ArrayList<>(MOBILE_DEVICE_TYPES);
    }

    /**
     * Получает профиль по ID или выбрасывает исключение
     */
    public Profile getProfileOrThrow(Long profileId) {
        return profileRepository.findById(profileId)
                .orElseThrow(() -> {
                    log.error("Profile not found with id: {}", profileId);
                    return new RuntimeException("Profile not found with id: " + profileId);
                });
    }

    /**
     * Обновляет статус профиля
     */
    @Transactional
    public Profile updateProfileStatus(Long profileId, String status) {
        log.debug("Updating status for profile {} to {}", profileId, status);

        return profileRepository.findById(profileId)
                .map(profile -> {
                    profile.setStatus(status);
                    profile.setUpdatedAt(Instant.now());

                    if ("BUSY".equals(status)) {
                        profile.setLastUsedAt(Instant.now());
                    }

                    Profile saved = profileRepository.save(profile);
                    log.info("Updated profile {} status to {}", profileId, status);
                    return saved;
                })
                .orElseThrow(() -> {
                    log.error("Profile not found for status update: {}", profileId);
                    return new RuntimeException("Profile not found: " + profileId);
                });
    }

    /**
     * Блокирует профиль пользователем
     */
    @Transactional
    public Profile lockProfile(Long profileId, String userId) {
        log.info("Locking profile {} for user {}", profileId, userId);

        return profileRepository.findById(profileId)
                .map(profile -> {
                    profile.setStatus("BUSY");
                    profile.setLockedByUserId(userId);
                    profile.setLastUsedAt(Instant.now());
                    profile.setUpdatedAt(Instant.now());

                    Profile saved = profileRepository.save(profile);
                    log.info("Profile {} locked by user {}", profileId, userId);
                    return saved;
                })
                .orElseThrow(() -> {
                    log.error("Profile not found for locking: {}", profileId);
                    return new RuntimeException("Profile not found: " + profileId);
                });
    }

    /**
     * Разблокирует профиль
     */
    @Transactional
    public Profile unlockProfile(Long profileId) {
        log.info("Unlocking profile {}", profileId);

        return profileRepository.findById(profileId)
                .map(profile -> {
                    profile.setStatus("FREE");
                    profile.setLockedByUserId(null);
                    profile.setUpdatedAt(Instant.now());

                    Profile saved = profileRepository.save(profile);
                    log.info("Profile {} unlocked", profileId);
                    return saved;
                })
                .orElseThrow(() -> {
                    log.error("Profile not found for unlocking: {}", profileId);
                    return new RuntimeException("Profile not found: " + profileId);
                });
    }

    /**
     * Удаляет профиль вместе с его директорией
     */
    @Transactional
    public void deleteProfileWithDirectory(Long profileId) {
        log.info("Deleting profile with directory: {}", profileId);

        Profile profile = profileRepository.findById(profileId)
                .orElseThrow(() -> {
                    log.error("Profile not found for deletion: {}", profileId);
                    return new RuntimeException("Profile not found: " + profileId);
                });

        // Удаляем директорию профиля
        String userDataPath = profile.getUserDataPath();
        if (userDataPath != null && !userDataPath.trim().isEmpty()) {
            try {
                Path profilePath = Paths.get(userDataPath);
                if (Files.exists(profilePath)) {
                    deleteDirectoryRecursively(profilePath);
                    log.info("Deleted profile directory: {}", profilePath);
                }
            } catch (IOException e) {
                log.warn("Failed to delete profile directory {}: {}", userDataPath, e.getMessage());
            }
        }

        // Удаляем профиль из БД
        profileRepository.delete(profile);
        log.info("Deleted profile from database: {}", profileId);
    }

    /**
     * Рекурсивное удаление директории
     */
    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> stream = Files.list(path)) {
                stream.forEach(p -> {
                    try {
                        deleteDirectoryRecursively(p);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to delete: " + p, e);
                    }
                });
            }
        }
        Files.delete(path);
    }

    /**
     * Получает информацию о размере директории профиля
     */
    public long getProfileDirectorySize(Long profileId) {
        return profileRepository.findById(profileId)
                .map(profile -> {
                    String userDataPath = profile.getUserDataPath();
                    if (userDataPath == null || userDataPath.trim().isEmpty()) {
                        return 0L;
                    }

                    try {
                        Path profilePath = Paths.get(userDataPath);
                        if (!Files.exists(profilePath)) {
                            return 0L;
                        }

                        try (Stream<Path> stream = Files.walk(profilePath)) {
                            return stream
                                    .filter(Files::isRegularFile)
                                    .mapToLong(p -> {
                                        try {
                                            return Files.size(p);
                                        } catch (IOException e) {
                                            return 0L;
                                        }
                                    })
                                    .sum();
                        }
                    } catch (IOException e) {
                        log.warn("Failed to calculate directory size for profile {}: {}",
                                profileId, e.getMessage());
                        return 0L;
                    }
                })
                .orElse(0L);
    }

    /**
     * Проверяет, существует ли директория профиля
     */
    public boolean profileDirectoryExists(Long profileId) {
        return profileRepository.findById(profileId)
                .map(profile -> {
                    String userDataPath = profile.getUserDataPath();
                    if (userDataPath == null || userDataPath.trim().isEmpty()) {
                        return false;
                    }

                    Path profilePath = Paths.get(userDataPath);
                    return Files.exists(profilePath) && Files.isDirectory(profilePath);
                })
                .orElse(false);
    }

    /**
     * Восстанавливает директорию профиля если она была удалена
     */
    @Transactional
    public Profile restoreProfileDirectory(Long profileId) {
        log.info("Restoring profile directory for: {}", profileId);

        return profileRepository.findById(profileId)
                .map(profile -> {
                    String userDataPath = profile.getUserDataPath();
                    if (userDataPath == null || userDataPath.trim().isEmpty()) {
                        String baseDir = browserProperties.getBaseDir();
                        String externalKey = profile.getExternalKey();
                        userDataPath = baseDir + "/" + externalKey + "/userDataDir";
                        profile.setUserDataPath(userDataPath);
                    }

                    try {
                        Path profilePath = Paths.get(userDataPath);
                        if (!Files.exists(profilePath)) {
                            Files.createDirectories(profilePath);
                            createProfileSubdirectories(profilePath);
                            log.info("Restored profile directory: {}", profilePath);
                        }

                        profile.setUpdatedAt(Instant.now());
                        return profileRepository.save(profile);

                    } catch (IOException e) {
                        log.error("Failed to restore profile directory for {}: {}",
                                profileId, e.getMessage(), e);
                        throw new RuntimeException("Failed to restore profile directory", e);
                    }
                })
                .orElseThrow(() -> {
                    log.error("Profile not found for directory restoration: {}", profileId);
                    return new RuntimeException("Profile not found: " + profileId);
                });
    }

    /**
     * Получает список всех externalKeys
     */
    public List<String> getAllExternalKeys() {
        return profileRepository.findAll().stream()
                .map(Profile::getExternalKey)
                .filter(key -> key != null && !key.trim().isEmpty())
                .distinct()
                .toList();
    }

    /**
     * Проверяет, существует ли профиль с данным externalKey
     */
    public boolean existsByExternalKey(String externalKey) {
        return profileRepository.findByExternalKey(externalKey).isPresent();
    }

    /**
     * Получает количество профилей по статусу
     */
    public long countByStatus(String status) {
        return profileRepository.countByStatus(status);
    }

    /**
     * Получает активные профили
     */
    public List<Profile> getActiveProfiles() {
        return profileRepository.findByStatus("BUSY");
    }

    /**
     * Получает свободные профили
     */
    public List<Profile> getFreeProfiles() {
        return profileRepository.findByStatus("FREE");
    }

    /**
     * Получает профили с высоким риском детекции
     */
    public List<Profile> getHighRiskProfiles(double threshold) {
        return profileRepository.findByDetectionRiskGreaterThanEqual(threshold);
    }

    /**
     * Очищает старые профили (старше определенного времени)
     */
    @Transactional
    public int cleanupOldProfiles(int days) {
        Instant cutoffDate = Instant.now().minusSeconds(days * 24L * 60 * 60);
        List<Profile> oldProfiles = profileRepository.findByStatus("FREE").stream()
                .filter(profile -> profile.getLastUsedAt() != null)
                .filter(profile -> profile.getLastUsedAt().isBefore(cutoffDate))
                .toList();

        int deletedCount = 0;
        for (Profile profile : oldProfiles) {
            try {
                deleteProfileWithDirectory(profile.getId());
                deletedCount++;
            } catch (Exception e) {
                log.error("Failed to delete old profile {}: {}", profile.getId(), e.getMessage());
            }
        }

        log.info("Cleaned up {} old profiles (older than {} days)", deletedCount, days);
        return deletedCount;
    }

    /**
     * Получает профиль по externalKey или создает новый с рандомными параметрами
     */
    public Profile getOrCreateProfile(String externalKey, String proxyUrl) {
        return findOrCreateByExternalKey(externalKey, proxyUrl, null, "ENHANCED", false);
    }

    /**
     * Создает новый профиль с принудительно новыми параметрами
     */
    public Profile createNewProfile(String externalKey, String proxyUrl) {
        return findOrCreateByExternalKey(externalKey, proxyUrl, null, "ENHANCED", true);
    }
}



