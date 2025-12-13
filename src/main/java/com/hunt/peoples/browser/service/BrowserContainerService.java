package com.hunt.peoples.browser.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.*;
import com.hunt.peoples.browser.config.AppProperties;
import com.hunt.peoples.browser.dto.BrowserStartResult;
import com.hunt.peoples.browser.dto.ContainerInfo;
import com.hunt.peoples.profiles.entity.Profile;
import com.hunt.peoples.profiles.repository.ProfileRepository;
import com.hunt.peoples.profiles.service.FingerprintMonitor;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrowserContainerService {

    private final DockerClient dockerClient;
    private final AppProperties appProperties;
    private final ProfileRepository profileRepository;
    private final ObjectMapper objectMapper;
    private final BrowserScriptInjector scriptInjector;
    private final FingerprintMonitor fingerprintMonitor;

    // Конфигурация
    private static final String IMAGE_NAME = "multi-browser-chrome-vnc";
    private static final int VNC_CONTAINER_PORT = 6080;
    private static final int DEVTOOLS_CONTAINER_PORT = 9222;

    // Трекер активных контейнеров
    private static final Map<Long, ContainerInfo> ACTIVE_CONTAINERS = new ConcurrentHashMap<>();

    // Трекер запросов к WebSocket
    private final Map<Integer, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private int messageIdCounter = 1;

    // Конфигурация из application.yml
    @Value("${browser.container.memory.mb:2048}")
    private int containerMemoryMB;

    @Value("${browser.container.cpu.shares:1024}")
    private int containerCpuShares;

    @Value("${browser.container.startup.timeout:60}")
    private int startupTimeoutSeconds;

    @Value("${browser.container.inject-scripts:true}")
    private boolean injectScripts;

    @Value("${browser.container.monitor-on-start:true}")
    private boolean monitorOnStart;

    @Value("${browser.devtools.websocket.connect-timeout:5000}")
    private int websocketConnectTimeout;

    @Value("${browser.devtools.websocket.read-timeout:10000}")
    private int websocketReadTimeout;

    @Value("${browser.script-injection.timeout:10000}")
    private int scriptInjectionTimeout;

    @Value("${browser.container.shm-size.mb:512}")
    private int shmSizeMB;

    @Value("${browser.container.max-containers:50}")
    private int maxContainers;

    // Executor для асинхронных задач
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @PostConstruct
    public void init() {
        log.info("BrowserContainerService initialized with:");
        log.info("  - Memory per container: {} MB", containerMemoryMB);
        log.info("  - Max containers: {}", maxContainers);
        log.info("  - SHM size: {} MB", shmSizeMB);
        log.info("  - Script injection: {}", injectScripts);
        log.info("  - Monitor on start: {}", monitorOnStart);
    }

    /**
     * Запускает контейнер с браузером для профиля
     */
    public BrowserStartResult startBrowser(Profile profile, String proxyOverride) {
        Long profileId = profile.getId();
        String userDataDir = profile.getUserDataPath();
        String hostBaseUrl = appProperties.getHostBaseUrl();
        String externalKey = profile.getExternalKey();

        String containerName = "browser_profile_" + profileId;

        log.info("=== STARTING BROWSER CONTAINER ===");
        log.info("Container: {} for profile {} ({})", containerName, profileId, externalKey);
        log.info("User data path: {}", userDataDir);
        log.info("Fingerprint: {}x{}, UA: {}, Level: {}",
                profile.getScreenWidth(), profile.getScreenHeight(),
                profile.getUserAgent(), profile.getDetectionLevel());

        // Проверяем лимит контейнеров
        checkContainerLimit();

        // Проверяем, не запущен ли уже контейнер
        if (ACTIVE_CONTAINERS.containsKey(profileId)) {
            ContainerInfo existing = ACTIVE_CONTAINERS.get(profileId);
            log.warn("Profile {} already has running container: {} (since {})",
                    profileId, existing.getContainerName(), existing.getStartedAt());
            throw new IllegalStateException("Browser already running for profile " + profileId);
        }

        // Останавливаем старый контейнер если есть
        cleanupOldContainer(containerName);

        // Находим свободные порты
        int hostVncPort = findFreePort();
        int hostDevToolsPort = findFreePort();

        log.info("Allocated ports - VNC: {}, DevTools: {}", hostVncPort, hostDevToolsPort);

        // Настройка портов
        ExposedPort vncPort = ExposedPort.tcp(VNC_CONTAINER_PORT);
        ExposedPort devToolsPort = ExposedPort.tcp(DEVTOOLS_CONTAINER_PORT);

        Ports portBindings = new Ports();
        portBindings.bind(vncPort, Ports.Binding.bindPort(hostVncPort));
        portBindings.bind(devToolsPort, Ports.Binding.bindPort(hostDevToolsPort));

        // Нормализуем путь и создаем директорию
        String hostPath = normalizePath(userDataDir);
        ensureDirectoryExists(hostPath);

        // Host config
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withPortBindings(portBindings)
                .withBinds(new Bind(hostPath, new Volume("/data/user-data")))
                .withMemory(containerMemoryMB * 1024 * 1024L)
                .withMemorySwap((containerMemoryMB * 2) * 1024 * 1024L)
                .withCpuShares(containerCpuShares)
                .withPrivileged(false)
                .withShmSize(shmSizeMB * 1024 * 1024L)
                .withRestartPolicy(RestartPolicy.noRestart())
                .withCapAdd(Capability.valueOf("SYS_ADMIN"))
                .withSecurityOpts(Arrays.asList("seccomp=unconfined", "apparmor=unconfined"));

        // Подготавливаем environment variables
        List<String> envVars = prepareEnvironmentVars(profile, proxyOverride);

        // Создаем контейнер
        CreateContainerResponse container;
        try {
            container = dockerClient.createContainerCmd(IMAGE_NAME)
                    .withName(containerName)
                    .withHostConfig(hostConfig)
                    .withEnv(envVars)
                    .withExposedPorts(vncPort, devToolsPort)
                    .withTty(true)
                    .withStdinOpen(true)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();
        } catch (Exception e) {
            log.error("Failed to create container for profile {}: {}", profileId, e.getMessage(), e);
            throw new RuntimeException("Failed to create container: " + e.getMessage(), e);
        }

        String containerId = container.getId();
        log.debug("Container created with ID: {}", containerId);

        try {
            // Запускаем контейнер
            dockerClient.startContainerCmd(containerId).exec();
            log.info("Container {} started successfully", containerId);
        } catch (Exception e) {
            log.error("Failed to start container {}: {}", containerId, e.getMessage(), e);
            // Пытаемся удалить созданный контейнер
            try {
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            } catch (Exception ex) {
                log.warn("Failed to remove failed container {}: {}", containerId, ex.getMessage());
            }
            throw new RuntimeException("Failed to start container: " + e.getMessage(), e);
        }

        // Сохраняем информацию о запущенном контейнере
        ContainerInfo containerInfo = ContainerInfo.builder()
                .containerId(containerId)
                .containerName(containerName)
                .profileId(profileId)
                .hostVncPort(hostVncPort)
                .hostDevToolsPort(hostDevToolsPort)
                .startedAt(Instant.now())
                .build();

        ACTIVE_CONTAINERS.put(profileId, containerInfo);

        // Формируем URL
        String vncUrl = buildVncUrl(hostBaseUrl, hostVncPort);
        String devToolsUrl = buildDevToolsUrl(hostBaseUrl, hostDevToolsPort);

        log.info("Container {} ready. VNC: {}, DevTools: {}", containerName, vncUrl, devToolsUrl);

        // Ждем готовности VNC
        waitForVncReady(vncUrl, Duration.ofSeconds(startupTimeoutSeconds));

        // Ждем готовности DevTools
        waitForDevToolsReady(devToolsUrl, Duration.ofSeconds(20));

        // Внедряем anti-detection скрипты если нужно
        if (injectScripts && shouldInjectScripts(profile)) {
            injectAntiDetectionScripts(devToolsUrl, profile);
        }

        // Обновляем статус профиля
        updateProfileStatus(profileId, "BUSY");

        // Мониторинг fingerprint при запуске
        if (monitorOnStart) {
            monitorFingerprintAfterStart(profile);
        }

        log.info("=== BROWSER CONTAINER STARTED SUCCESSFULLY ===");

        return BrowserStartResult.builder()
                .profileId(profileId)
                .vncUrl(vncUrl)
                .externalKey(externalKey)
                .devToolsUrl(devToolsUrl)
                .containerId(containerId)
                .startedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
    }

    /**
     * Подготавливает environment variables для контейнера
     */
    private List<String> prepareEnvironmentVars(Profile profile, String proxyOverride) {
        List<String> envVars = new ArrayList<>();

        log.debug("Preparing environment variables for profile {}", profile.getId());

        // === БАЗОВЫЕ ПЕРЕМЕННЫЕ ===
        envVars.add("USER_DATA_DIR=/data/user-data");
        envVars.add("PROFILE_ID=" + profile.getId());
        envVars.add("EXTERNAL_KEY=" + profile.getExternalKey());
        envVars.add("PROFILE_NAME=" + (profile.getName() != null ? profile.getName() : ""));

        // === FINGERPRINT ПЕРЕМЕННЫЕ ===

        // 1. Базовый fingerprint
        envVars.add("USER_AGENT=" + (profile.getUserAgent() != null ? profile.getUserAgent() : ""));
        envVars.add("SCREEN_WIDTH=" + (profile.getScreenWidth() != null ? profile.getScreenWidth() : 1920));
        envVars.add("SCREEN_HEIGHT=" + (profile.getScreenHeight() != null ? profile.getScreenHeight() : 1080));

        if (profile.getPixelRatio() != null) {
            envVars.add("PIXEL_RATIO=" + profile.getPixelRatio());
        }
        if (profile.getPlatform() != null) {
            envVars.add("PLATFORM=" + profile.getPlatform());
        }

        // 2. Аппаратные возможности
        if (profile.getHardwareConcurrency() != null) {
            envVars.add("HARDWARE_CONCURRENCY=" + profile.getHardwareConcurrency());
        }
        if (profile.getDeviceMemory() != null) {
            envVars.add("DEVICE_MEMORY=" + profile.getDeviceMemory());
        }
        if (profile.getMaxTouchPoints() != null) {
            envVars.add("MAX_TOUCH_POINTS=" + profile.getMaxTouchPoints());
        }

        // 3. WebGL
        if (profile.getWebglVendor() != null) {
            envVars.add("WEBGL_VENDOR=" + profile.getWebglVendor());
        }
        if (profile.getWebglRenderer() != null) {
            envVars.add("WEBGL_RENDERER=" + profile.getWebglRenderer());
        }
        if (profile.getWebglVersion() != null) {
            envVars.add("WEBGL_VERSION=" + profile.getWebglVersion());
        }

        // 4. Локаль и время
        if (profile.getTimezone() != null) {
            envVars.add("TIMEZONE=" + profile.getTimezone());
        }
        if (profile.getLocale() != null) {
            envVars.add("LOCALE=" + profile.getLocale());
        }
        if (profile.getLanguage() != null) {
            envVars.add("LANGUAGE=" + profile.getLanguage());
        }
        if (profile.getTimezoneOffset() != null) {
            envVars.add("TZ_OFFSET=" + profile.getTimezoneOffset());
        }

        // 5. Детали экрана
        if (profile.getScreenAvailWidth() != null) {
            envVars.add("SCREEN_AVAIL_WIDTH=" + profile.getScreenAvailWidth());
        }
        if (profile.getScreenAvailHeight() != null) {
            envVars.add("SCREEN_AVAIL_HEIGHT=" + profile.getScreenAvailHeight());
        }
        if (profile.getScreenColorDepth() != null) {
            envVars.add("SCREEN_COLOR_DEPTH=" + profile.getScreenColorDepth());
        }
        if (profile.getScreenPixelDepth() != null) {
            envVars.add("SCREEN_PIXEL_DEPTH=" + profile.getScreenPixelDepth());
        }

        // 6. Навигатор свойства
        if (profile.getCookieEnabled() != null) {
            envVars.add("COOKIE_ENABLED=" + profile.getCookieEnabled());
        }
        if (profile.getDoNotTrack() != null) {
            envVars.add("DO_NOT_TRACK=" + profile.getDoNotTrack());
        }
        if (profile.getOnline() != null) {
            envVars.add("ONLINE=" + profile.getOnline());
        }

        // 7. Версии и метаданные
        if (profile.getChromeVersion() != null) {
            envVars.add("CHROME_VERSION=" + profile.getChromeVersion());
        }
        if (profile.getOsVersion() != null) {
            envVars.add("OS_VERSION=" + profile.getOsVersion());
        }
        if (profile.getOsArchitecture() != null) {
            envVars.add("OS_ARCH=" + profile.getOsArchitecture());
        }

        // 8. Audio параметры
        if (profile.getAudioSampleRate() != null) {
            envVars.add("AUDIO_SAMPLE_RATE=" + profile.getAudioSampleRate());
        }
        if (profile.getAudioChannelCount() != null) {
            envVars.add("AUDIO_CHANNEL_COUNT=" + profile.getAudioChannelCount());
        }
        if (profile.getAudioContextLatency() != null) {
            envVars.add("AUDIO_CONTEXT_LATENCY=" + profile.getAudioContextLatency());
        }

        // 9. Battery параметры
        if (profile.getBatteryCharging() != null) {
            envVars.add("BATTERY_CHARGING=" + profile.getBatteryCharging());
        }
        if (profile.getBatteryLevel() != null) {
            envVars.add("BATTERY_LEVEL=" + profile.getBatteryLevel());
        }
        if (profile.getBatteryChargingTime() != null) {
            envVars.add("BATTERY_CHARGING_TIME=" + profile.getBatteryChargingTime());
        }
        if (profile.getBatteryDischargingTime() != null) {
            envVars.add("BATTERY_DISCHARGING_TIME=" + profile.getBatteryDischargingTime());
        }

        // 10. Connection параметры
        if (profile.getConnectionDownlink() != null) {
            envVars.add("CONNECTION_DOWNLINK=" + profile.getConnectionDownlink());
        }
        if (profile.getConnectionEffectiveType() != null) {
            envVars.add("CONNECTION_EFFECTIVE_TYPE=" + profile.getConnectionEffectiveType());
        }
        if (profile.getConnectionRtt() != null) {
            envVars.add("CONNECTION_RTT=" + profile.getConnectionRtt());
        }
        if (profile.getConnectionSaveData() != null) {
            envVars.add("CONNECTION_SAVE_DATA=" + profile.getConnectionSaveData());
        }
        if (profile.getConnectionType() != null) {
            envVars.add("CONNECTION_TYPE=" + profile.getConnectionType());
        }

        // 11. Поведенческие параметры
        if (profile.getMouseMovementVariance() != null) {
            envVars.add("MOUSE_VARIANCE=" + profile.getMouseMovementVariance());
        }
        if (profile.getTypingSpeed() != null) {
            envVars.add("TYPING_SPEED=" + profile.getTypingSpeed());
        }
        if (profile.getScrollSpeed() != null) {
            envVars.add("SCROLL_SPEED=" + profile.getScrollSpeed());
        }

        // === ПРОКСИ ===
        String proxyToUse = resolveProxy(proxyOverride, profile.getProxyUrl());
        if (proxyToUse != null && !proxyToUse.trim().isEmpty()) {
            envVars.add("PROXY_URL=" + proxyToUse);
        }

        // === ДЕТЕКЦИЯ ===
        envVars.add("DETECTION_LEVEL=" +
                (profile.getDetectionLevel() != null ? profile.getDetectionLevel() : "ENHANCED"));

        // === ДОПОЛНИТЕЛЬНЫЕ НАСТРОЙКИ ===
        envVars.add("ENABLE_VNC=true");
//        envVars.add("VNC_PASSWORD=" + generateVncPassword());

        // Canvas fingerprint
        if (profile.getCanvasFingerprint() != null) {
            envVars.add("CANVAS_FINGERPRINT=" + profile.getCanvasFingerprint());
        }
        if (profile.getCanvasNoiseHash() != null) {
            envVars.add("CANVAS_NOISE_HASH=" + profile.getCanvasNoiseHash());
        }

        // Передаем WebGL Extensions и Plugins как JSON
        try {
            if (profile.getWebglExtensionsJson() != null && !profile.getWebglExtensionsJson().isEmpty() && !profile.getWebglExtensionsJson().equals("null")) {
                envVars.add("WEBGL_EXTENSIONS_JSON=" + profile.getWebglExtensionsJson());
            }
            if (profile.getPluginsJson() != null && !profile.getPluginsJson().isEmpty() && !profile.getPluginsJson().equals("null")) {
                envVars.add("PLUGINS_JSON=" + profile.getPluginsJson());
            }
            if (profile.getFontsListJson() != null && !profile.getFontsListJson().isEmpty() && !profile.getFontsListJson().equals("null")) {
                envVars.add("FONTS_LIST_JSON=" + profile.getFontsListJson());
            }
            if (profile.getMediaDevicesJson() != null && !profile.getMediaDevicesJson().isEmpty() && !profile.getMediaDevicesJson().equals("null")) {
                envVars.add("MEDIA_DEVICES_JSON=" + profile.getMediaDevicesJson());
            }
            if (profile.getBatteryInfoJson() != null && !profile.getBatteryInfoJson().isEmpty() && !profile.getBatteryInfoJson().equals("null")) {
                envVars.add("BATTERY_INFO_JSON=" + profile.getBatteryInfoJson());
            }
            if (profile.getConnectionInfoJson() != null && !profile.getConnectionInfoJson().isEmpty() && !profile.getConnectionInfoJson().equals("null")) {
                envVars.add("CONNECTION_INFO_JSON=" + profile.getConnectionInfoJson());
            }
            if (profile.getAudioFingerprintJson() != null && !profile.getAudioFingerprintJson().isEmpty() && !profile.getAudioFingerprintJson().equals("null")) {
                envVars.add("AUDIO_FINGERPRINT_JSON=" + profile.getAudioFingerprintJson());
            }
        } catch (Exception e) {
            log.warn("Failed to serialize JSON data for environment variables: {}", e.getMessage());
        }

        log.debug("Prepared {} environment variables for container", envVars.size());
        return envVars;
    }



    /**
     * Строит строку флагов для Chrome
     */
    private String buildChromeFlags(Profile profile, String proxyUrl) {
        List<String> flags = new ArrayList<>();

        // === БАЗОВЫЕ ФЛАГИ БЕЗОПАСНОСТИ ===
        flags.add("--no-sandbox");
        flags.add("--disable-dev-shm-usage");
        flags.add("--disable-gpu");
        flags.add("--disable-software-rasterizer");

        // === ПРОИЗВОДИТЕЛЬНОСТЬ ===
        flags.add("--disable-background-timer-throttling");
        flags.add("--disable-renderer-backgrounding");
        flags.add("--disable-backgrounding-occluded-windows");
        flags.add("--disable-ipc-flooding-protection");

        // === БЕЗОПАСНОСТЬ И ПРИВАТНОСТЬ ===
        flags.add("--disable-client-side-phishing-detection");
        flags.add("--disable-component-update");
        flags.add("--disable-default-apps");
        flags.add("--disable-domain-reliability");
        flags.add("--disable-extensions");
        flags.add("--disable-sync");
        flags.add("--disable-web-security");
        flags.add("--metrics-recording-only");
        flags.add("--safebrowsing-disable-auto-update");
        flags.add("--disable-breakpad");
        flags.add("--password-store=basic");
        flags.add("--use-mock-keychain");

        // === FINGERPRINT И АНТИДЕТЕКТ ===
        flags.add("--user-agent=\"" + profile.getUserAgent() + "\"");
        flags.add("--window-size=" + profile.getScreenWidth() + "," + profile.getScreenHeight());

        if (profile.getPixelRatio() != null) {
            flags.add("--force-device-scale-factor=" + profile.getPixelRatio());
        }

        // === ЯЗЫК И РЕГИОН ===
        if (profile.getLanguage() != null) {
            flags.add("--lang=" + profile.getLanguage());
        }

        flags.add("--disable-blink-features=AutomationControlled");
        flags.add("--disable-features=TranslateUI,BlinkGenPropertyTrees");
        flags.add("--disable-features=PrivacySandboxSettings4");
        flags.add("--disable-features=AdInterestGroupAPI,Fledge,LargeFaviconFromGoogle");
        flags.add("--disable-features=PrivacySandboxAdsAPIsOverride");
        flags.add("--disable-features=WebRtcHideLocalIpsWithMdns");
        flags.add("--disable-features=InterestCohortAPI");
        flags.add("--disable-features=AudioServiceOutOfProcess");
        flags.add("--disable-features=IsolateOrigins,site-per-process");

        // === ПРОКСИ ===
        if (proxyUrl != null && !proxyUrl.trim().isEmpty()) {
            flags.add("--proxy-server=" + proxyUrl);
            flags.add("--host-resolver-rules=\"MAP * ~NOTFOUND , EXCLUDE 127.0.0.1\"");
        }

        // === DevTools ===
        flags.add("--remote-debugging-port=" + DEVTOOLS_CONTAINER_PORT);
        flags.add("--remote-debugging-address=0.0.0.0");

        // === ДОПОЛНИТЕЛЬНЫЕ ===
        flags.add("--no-first-run");
        flags.add("--no-default-browser-check");
        flags.add("--disable-popup-blocking");
        flags.add("--disable-prompt-on-repost");
        flags.add("--disable-background-networking");
        flags.add("--disable-backgrounding-occluded-windows");
        flags.add("--disable-renderer-backgrounding");

        return String.join(" ", flags);
    }

    /**
     * Внедряет anti-detection скрипты через DevTools
     */
    private void injectAntiDetectionScripts(String devToolsUrl, Profile profile) {
        try {
            log.info("Injecting anti-detection scripts for profile {} via {}",
                    profile.getId(), devToolsUrl);

            // Генерируем скрипт на основе профиля
            String injectionScript = scriptInjector.generateInjectionScript(profile);

            // Получаем WebSocket URL для DevTools
            String wsUrl = getDevToolsWebSocketUrl(devToolsUrl);

            if (wsUrl != null) {
                boolean wsSuccess = injectScriptViaWebSocket(wsUrl, injectionScript, profile.getId());

                if (wsSuccess) {
                    log.info("Anti-detection scripts injected via WebSocket for profile {}", profile.getId());

                    // Дополнительная инъекция для медиа устройств и WebGL extensions
                    injectAdditionalScripts(wsUrl, profile);

                    // Проверяем инъекцию
                    verifyScriptInjection(wsUrl, profile);
                } else {
                    log.warn("WebSocket injection failed, trying HTTP API for profile {}", profile.getId());
                    boolean httpSuccess = injectScriptViaHttpApi(devToolsUrl, injectionScript, profile.getId());

                    if (httpSuccess) {
                        log.info("Anti-detection scripts injected via HTTP API for profile {}", profile.getId());
                    } else {
                        log.error("All injection methods failed for profile {}", profile.getId());
                    }
                }
            } else {
                log.warn("Could not get WebSocket URL for DevTools, trying HTTP API");
                boolean httpSuccess = injectScriptViaHttpApi(devToolsUrl, injectionScript, profile.getId());

                if (!httpSuccess) {
                    log.error("HTTP API injection also failed for profile {}", profile.getId());
                }
            }

        } catch (Exception e) {
            log.error("Failed to inject anti-detection scripts for profile {}",
                    profile.getId(), e);
        }
    }

    /**
     * Дополнительные инъекции для медиа устройств и WebGL extensions
     */
    private void injectAdditionalScripts(String wsUrl, Profile profile) {
        try {
            // Инъекция для медиа устройств
            String mediaDevicesScript = scriptInjector.generateMediaDevicesScript(profile);
            if (mediaDevicesScript != null) {
                log.debug("Injecting media devices script for profile {}", profile.getId());
                boolean success = injectScriptViaWebSocket(wsUrl, mediaDevicesScript, profile.getId());
                if (success) {
                    log.info("Media devices script injected successfully");
                }
            }

            // Инъекция для WebGL extensions
            String webglExtensionsScript = scriptInjector.generateWebGLExtensionsScript(profile);
            if (webglExtensionsScript != null) {
                log.debug("Injecting WebGL extensions script for profile {}", profile.getId());
                boolean success = injectScriptViaWebSocket(wsUrl, webglExtensionsScript, profile.getId());
                if (success) {
                    log.info("WebGL extensions script injected successfully");
                }
            }

            // Инъекция для аудио fingerprint
            String audioScript = scriptInjector.generateAudioScript(profile);
            if (audioScript != null) {
                log.debug("Injecting audio fingerprint script for profile {}", profile.getId());
                boolean success = injectScriptViaWebSocket(wsUrl, audioScript, profile.getId());
                if (success) {
                    log.info("Audio fingerprint script injected successfully");
                }
            }

        } catch (Exception e) {
            log.warn("Failed to inject additional scripts for profile {}", profile.getId(), e);
        }
    }

    /**
     * Получает WebSocket URL из DevTools
     */
    private String getDevToolsWebSocketUrl(String devToolsUrl) {
        try {
            // Получаем JSON с информацией о targets
            String targetsUrl = devToolsUrl + "/json";

            RestTemplate restTemplate = new RestTemplate();
            restTemplate.setRequestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory());
            ((org.springframework.http.client.SimpleClientHttpRequestFactory) restTemplate.getRequestFactory())
                    .setConnectTimeout(3000);
            ((org.springframework.http.client.SimpleClientHttpRequestFactory) restTemplate.getRequestFactory())
                    .setReadTimeout(3000);

            String response = restTemplate.getForObject(targetsUrl, String.class);

            if (response != null) {
                JsonNode nodes = objectMapper.readTree(response);
                if (nodes.isArray() && nodes.size() > 0) {
                    // Берем первый target типа "page"
                    for (JsonNode node : nodes) {
                        if (node.has("type") && "page".equals(node.get("type").asText())) {
                            if (node.has("webSocketDebuggerUrl")) {
                                String wsUrl = node.get("webSocketDebuggerUrl").asText();
                                log.debug("Found WebSocket URL: {}", wsUrl);
                                return wsUrl;
                            }
                        }
                    }

                    // Если не нашли page, берем любой target с WebSocket URL
                    for (JsonNode node : nodes) {
                        if (node.has("webSocketDebuggerUrl")) {
                            String wsUrl = node.get("webSocketDebuggerUrl").asText();
                            log.debug("Found fallback WebSocket URL: {}", wsUrl);
                            return wsUrl;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get DevTools WebSocket URL: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Внедряет скрипт через WebSocket
     */
    private boolean injectScriptViaWebSocket(String wsUrl, String script, Long profileId) {
        CompletableFuture<Boolean> resultFuture = new CompletableFuture<>();

        try {
            URI uri = new URI(wsUrl);

            WebSocketClient wsClient = new WebSocketClient(uri) {
                private int currentMessageId;

                @Override
                public void onOpen(ServerHandshake handshake) {
                    log.debug("WebSocket connection opened for profile {}: {}", profileId, wsUrl);

                    // Генерируем ID сообщения
                    synchronized (BrowserContainerService.this) {
                        currentMessageId = messageIdCounter++;
                    }

                    // Создаем сообщение для выполнения скрипта
                    Map<String, Object> message = new HashMap<>();
                    message.put("id", currentMessageId);
                    message.put("method", "Runtime.evaluate");

                    Map<String, Object> params = new HashMap<>();
                    params.put("expression", script);
                    params.put("includeCommandLineAPI", true);
                    params.put("silent", false);
                    params.put("returnByValue", false);
                    params.put("generatePreview", false);
                    params.put("userGesture", true);
                    params.put("awaitPromise", false);
                    params.put("replMode", false);
                    params.put("allowUnsafeEvalBlockedByCSP", false);

                    message.put("params", params);

                    try {
                        String jsonMessage = objectMapper.writeValueAsString(message);

                        log.debug("Sending script to DevTools (profile={}, id={}, script length={})",
                                profileId, currentMessageId, script.length());

                        // Отправляем сообщение
                        send(jsonMessage);

                        // Создаем Future для ожидания ответа
                        CompletableFuture<JsonNode> responseFuture = new CompletableFuture<>();
                        pendingRequests.put(currentMessageId, responseFuture);

                        // Ожидаем ответ с таймаутом
                        executorService.submit(() -> {
                            try {
                                JsonNode response = responseFuture.get(scriptInjectionTimeout, TimeUnit.MILLISECONDS);

                                // Проверяем результат
                                if (response != null && response.has("result")) {
                                    JsonNode resultNode = response.get("result");

                                    if (resultNode.has("type")) {
                                        String type = resultNode.get("type").asText();

                                        if ("object".equals(type) && resultNode.has("subtype")) {
                                            String subtype = resultNode.get("subtype").asText();
                                            if ("error".equals(subtype)) {
                                                log.error("Script execution error for profile {}: {}",
                                                        profileId, resultNode);
                                                resultFuture.complete(false);
                                                return;
                                            }
                                        }

                                        log.debug("Script executed successfully for profile {}, result type: {}",
                                                profileId, type);
                                        resultFuture.complete(true);
                                        return;
                                    }
                                }

                                resultFuture.complete(false);

                            } catch (TimeoutException e) {
                                log.warn("Script injection timeout for profile {}", profileId);
                                resultFuture.complete(false);
                            } catch (Exception e) {
                                log.error("Error waiting for script response for profile {}", profileId, e);
                                resultFuture.complete(false);
                            }
                        });

                    } catch (Exception e) {
                        log.error("Failed to serialize WebSocket message for profile {}", profileId, e);
                        resultFuture.complete(false);
                        close();
                    }
                }

                @Override
                public void onMessage(String message) {
                    try {
                        log.trace("Received WebSocket message for profile {}: {}", profileId,
                                message.length() > 200 ? message.substring(0, 200) + "..." : message);

                        JsonNode response = objectMapper.readTree(message);

                        // Проверяем, является ли это ответом на наш запрос
                        if (response.has("id")) {
                            int responseId = response.get("id").asInt();
                            CompletableFuture<JsonNode> pendingFuture = pendingRequests.remove(responseId);

                            if (pendingFuture != null) {
                                if (response.has("error")) {
                                    JsonNode error = response.get("error");
                                    log.error("DevTools error for profile {}: {}", profileId, error);
                                    pendingFuture.completeExceptionally(
                                            new RuntimeException("DevTools error: " + error)
                                    );
                                } else {
                                    log.debug("Received successful response from DevTools for profile {} (id={})",
                                            profileId, responseId);
                                    pendingFuture.complete(response);
                                }
                            }
                        } else if (response.has("method")) {
                            // Это событие, а не ответ - можем обработать если нужно
                            String method = response.get("method").asText();
                            log.trace("Received DevTools event for profile {}: {}", profileId, method);
                        }
                    } catch (Exception e) {
                        log.error("Failed to parse WebSocket message for profile {}", profileId, e);
                    }
                }

                @Override
                public void onMessage(ByteBuffer bytes) {
                    // Бинарные сообщения не ожидаем в DevTools Protocol
                    log.trace("Received binary WebSocket message for profile {} ({} bytes)",
                            profileId, bytes.remaining());
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    log.debug("WebSocket connection closed for profile {}: {} - {}",
                            profileId, code, reason);

                    // Если есть незавершенный запрос, помечаем его как неудачный
                    if (currentMessageId > 0) {
                        CompletableFuture<JsonNode> pendingFuture = pendingRequests.remove(currentMessageId);
                        if (pendingFuture != null && !pendingFuture.isDone()) {
                            pendingFuture.completeExceptionally(
                                    new RuntimeException("WebSocket connection closed before response: " + reason)
                            );
                        }
                    }

                    // Если resultFuture еще не завершен, завершаем его
                    if (!resultFuture.isDone()) {
                        resultFuture.complete(false);
                    }
                }

                @Override
                public void onError(Exception ex) {
                    log.error("WebSocket error for profile {}", profileId, ex);

                    // Если resultFuture еще не завершен, завершаем его
                    if (!resultFuture.isDone()) {
                        resultFuture.completeExceptionally(ex);
                    }
                }
            };

            // Устанавливаем таймауты
            wsClient.setConnectionLostTimeout(30);

            // Подключаемся
            boolean connected = wsClient.connectBlocking(websocketConnectTimeout, TimeUnit.MILLISECONDS);

            if (!connected) {
                log.warn("Failed to connect to WebSocket for profile {} within {}ms",
                        profileId, websocketConnectTimeout);
                return false;
            }

            // Ожидаем результат
            try {
                return resultFuture.get(websocketReadTimeout, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("Script injection timeout for profile {}", profileId);
                return false;
            } catch (Exception e) {
                log.error("Error during script injection for profile {}", profileId, e);
                return false;
            } finally {
                // Закрываем соединение
                if (wsClient.isOpen()) {
                    wsClient.close();
                }
            }

        } catch (Exception e) {
            log.error("Failed to inject script via WebSocket for profile {}", profileId, e);
            return false;
        }
    }

    /**
     * Инжектирует скрипт через HTTP API DevTools
     */
    private boolean injectScriptViaHttpApi(String devToolsUrl, String script, Long profileId) {
        try {
            // Получаем список targets
            String targetsUrl = devToolsUrl + "/json";
            RestTemplate restTemplate = new RestTemplate();

            // Устанавливаем таймауты
            restTemplate.setRequestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory());
            ((org.springframework.http.client.SimpleClientHttpRequestFactory) restTemplate.getRequestFactory())
                    .setConnectTimeout(3000);
            ((org.springframework.http.client.SimpleClientHttpRequestFactory) restTemplate.getRequestFactory())
                    .setReadTimeout(5000);

            ResponseEntity<String> targetsResponse = restTemplate.getForEntity(targetsUrl, String.class);

            if (!targetsResponse.getStatusCode().is2xxSuccessful() || targetsResponse.getBody() == null) {
                log.warn("Failed to get targets from DevTools for profile {}", profileId);
                return false;
            }

            JsonNode targets = objectMapper.readTree(targetsResponse.getBody());
            if (!targets.isArray() || targets.size() == 0) {
                log.warn("No targets found in DevTools for profile {}", profileId);
                return false;
            }

            // Ищем target типа "page"
            String targetId = null;
            for (JsonNode target : targets) {
                if (target.has("type") && "page".equals(target.get("type").asText())) {
                    if (target.has("id")) {
                        targetId = target.get("id").asText();
                        break;
                    }
                }
            }

            if (targetId == null) {
                // Берем первый target
                targetId = targets.get(0).get("id").asText();
            }

            // Создаем сессию
            String sessionUrl = devToolsUrl + "/json/session/" + targetId;
            ResponseEntity<String> sessionResponse = restTemplate.postForEntity(
                    sessionUrl, null, String.class);

            if (!sessionResponse.getStatusCode().is2xxSuccessful() || sessionResponse.getBody() == null) {
                log.warn("Failed to create session for profile {}", profileId);
                return false;
            }

            JsonNode sessionResult = objectMapper.readTree(sessionResponse.getBody());
            if (!sessionResult.has("sessionId")) {
                log.warn("No sessionId in response for profile {}", profileId);
                return false;
            }

            String sessionId = sessionResult.get("sessionId").asText();

            // Выполняем скрипт
            String executeUrl = devToolsUrl + "/json/session/" + sessionId + "/command";

            Map<String, Object> request = new HashMap<>();
            request.put("id", 1);
            request.put("method", "Runtime.evaluate");

            Map<String, Object> params = new HashMap<>();
            params.put("expression", script);
            params.put("includeCommandLineAPI", true);
            params.put("silent", false);
            params.put("returnByValue", false);

            request.put("params", params);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
            ResponseEntity<String> executeResponse = restTemplate.exchange(
                    executeUrl, HttpMethod.POST, entity, String.class);

            if (!executeResponse.getStatusCode().is2xxSuccessful()) {
                log.warn("Failed to execute script via HTTP API for profile {}", profileId);
                return false;
            }

            JsonNode executeResult = objectMapper.readTree(executeResponse.getBody());

            // Проверяем результат
            if (executeResult.has("result") && !executeResult.has("error")) {
                log.debug("Script executed successfully via HTTP API for profile {}", profileId);
                return true;
            } else {
                log.warn("Script execution error via HTTP API for profile {}: {}",
                        profileId, executeResult);
                return false;
            }

        } catch (Exception e) {
            log.error("Failed to inject script via HTTP API for profile {}", profileId, e);
            return false;
        }
    }

    /**
     * Проверяет, что скрипт был успешно применен
     */
    private void verifyScriptInjection(String wsUrl, Profile profile) {
        try {
            // Создаем скрипт для проверки
            String verificationScript = String.format("""
                (function() {
                    const checks = {
                        webdriver: navigator.webdriver === undefined,
                        userAgent: navigator.userAgent === "%s",
                        platform: navigator.platform === "%s",
                        screenWidth: screen.width === %d,
                        screenHeight: screen.height === %d,
                        hardwareConcurrency: navigator.hardwareConcurrency === %d
                    };
                    
                    const passed = Object.values(checks).filter(v => v).length;
                    const total = Object.keys(checks).length;
                    
                    return {
                        checks: checks,
                        score: passed / total,
                        allPassed: passed === total,
                        timestamp: new Date().toISOString()
                    };
                })();
                """,
                    profile.getUserAgent().replace("\"", "\\\""),
                    profile.getPlatform() != null ? profile.getPlatform().replace("\"", "\\\"") : "",
                    profile.getScreenWidth() != null ? profile.getScreenWidth() : 0,
                    profile.getScreenHeight() != null ? profile.getScreenHeight() : 0,
                    profile.getHardwareConcurrency() != null ? profile.getHardwareConcurrency() : 4
            );

            CompletableFuture<JsonNode> future = executeScriptViaWebSocket(wsUrl, verificationScript);

            future.thenAccept(result -> {
                if (result != null && result.has("result")) {
                    JsonNode scriptResult = result.get("result");
                    if (scriptResult.has("value")) {
                        JsonNode value = scriptResult.get("value");
                        double score = value.has("score") ? value.get("score").asDouble() : 0;
                        boolean allPassed = value.has("allPassed") && value.get("allPassed").asBoolean();

                        if (score >= 0.8) {
                            log.info("Script injection verification PASSED for profile {}: score={}, allPassed={}",
                                    profile.getId(), score, allPassed);
                        } else {
                            log.warn("Script injection verification WARNING for profile {}: score={}, allPassed={}",
                                    profile.getId(), score, allPassed);
                        }
                    }
                }
            }).exceptionally(ex -> {
                log.debug("Script injection verification failed for profile {}: {}",
                        profile.getId(), ex.getMessage());
                return null;
            });

        } catch (Exception e) {
            log.debug("Script injection verification failed for profile {}", profile.getId(), e);
        }
    }

    /**
     * Выполняет скрипт через WebSocket и возвращает результат
     */
    private CompletableFuture<JsonNode> executeScriptViaWebSocket(String wsUrl, String script) {
        CompletableFuture<JsonNode> resultFuture = new CompletableFuture<>();

        try {
            URI uri = new URI(wsUrl);

            WebSocketClient wsClient = new WebSocketClient(uri) {
                private int currentMessageId;

                @Override
                public void onOpen(ServerHandshake handshake) {
                    synchronized (BrowserContainerService.this) {
                        currentMessageId = messageIdCounter++;
                    }

                    Map<String, Object> message = new HashMap<>();
                    message.put("id", currentMessageId);
                    message.put("method", "Runtime.evaluate");

                    Map<String, Object> params = new HashMap<>();
                    params.put("expression", script);
                    params.put("returnByValue", true);

                    message.put("params", params);

                    try {
                        String jsonMessage = objectMapper.writeValueAsString(message);
                        send(jsonMessage);

                        pendingRequests.put(currentMessageId, resultFuture);

                    } catch (Exception e) {
                        resultFuture.completeExceptionally(e);
                        close();
                    }
                }

                @Override
                public void onMessage(String message) {
                    try {
                        JsonNode response = objectMapper.readTree(message);

                        if (response.has("id")) {
                            int responseId = response.get("id").asInt();
                            CompletableFuture<JsonNode> pendingFuture = pendingRequests.remove(responseId);

                            if (pendingFuture != null) {
                                if (response.has("error")) {
                                    pendingFuture.completeExceptionally(
                                            new RuntimeException("DevTools error: " + response.get("error"))
                                    );
                                } else {
                                    pendingFuture.complete(response);
                                }
                                close();
                            }
                        }
                    } catch (Exception e) {
                        log.error("Failed to parse WebSocket message", e);
                    }
                }

                @Override
                public void onMessage(ByteBuffer bytes) {
                    // Игнорируем бинарные сообщения
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    if (!resultFuture.isDone()) {
                        resultFuture.completeExceptionally(
                                new RuntimeException("Connection closed: " + reason)
                        );
                    }
                }

                @Override
                public void onError(Exception ex) {
                    if (!resultFuture.isDone()) {
                        resultFuture.completeExceptionally(ex);
                    }
                }
            };

            wsClient.setConnectionLostTimeout(10);
            wsClient.connectBlocking(3000, TimeUnit.MILLISECONDS);

            // Устанавливаем таймаут
            executorService.submit(() -> {
                try {
                    Thread.sleep(5000);
                    if (!resultFuture.isDone()) {
                        resultFuture.completeExceptionally(new TimeoutException("Script execution timeout"));
                        if (wsClient.isOpen()) {
                            wsClient.close();
                        }
                    }
                } catch (Exception e) {
                    // Игнорируем
                }
            });

        } catch (Exception e) {
            resultFuture.completeExceptionally(e);
        }

        return resultFuture;
    }

    /**
     * Останавливает браузер для профиля
     */
    public void stopBrowser(Long profileId) {
        String containerName = "browser_profile_" + profileId;
        log.info("Stopping browser container {}", containerName);

        // Удаляем из активных контейнеров
        ContainerInfo containerInfo = ACTIVE_CONTAINERS.remove(profileId);

        if (containerInfo != null) {
            Duration runtime = Duration.between(containerInfo.getStartedAt(), Instant.now());
            log.info("Container {} ran for {} minutes", containerName, runtime.toMinutes());
        }

        try {
            // Останавливаем контейнер
            dockerClient.stopContainerCmd(containerName)
                    .withTimeout(10)
                    .exec();
            log.debug("Container {} stopped", containerName);

        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            log.debug("Container {} not found when stopping", containerName);
        } catch (Exception e) {
            log.warn("Error stopping container {}: {}", containerName, e.getMessage());
        }

        try {
            // Удаляем контейнер
            dockerClient.removeContainerCmd(containerName)
                    .withForce(true)
                    .withRemoveVolumes(false) // Не удаляем volume с user data
                    .exec();
            log.debug("Container {} removed", containerName);

        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            log.debug("Container {} not found when removing", containerName);
        } catch (Exception e) {
            log.warn("Error removing container {}: {}", containerName, e.getMessage());
        }

        // Обновляем статус профиля
        updateProfileStatus(profileId, "FREE");
    }

    /**
     * Останавливает все активные контейнеры
     */
    public void stopAllBrowsers() {
        log.info("Stopping all browser containers");

        List<Long> profileIds = new ArrayList<>(ACTIVE_CONTAINERS.keySet());

        for (Long profileId : profileIds) {
            try {
                stopBrowser(profileId);
            } catch (Exception e) {
                log.error("Failed to stop browser for profile {}", profileId, e);
            }
        }

        ACTIVE_CONTAINERS.clear();
        log.info("All browser containers stopped");
    }

    /**
     * Получает информацию о запущенном контейнере
     */
    public Optional<ContainerInfo> getContainerInfo(Long profileId) {
        return Optional.ofNullable(ACTIVE_CONTAINERS.get(profileId));
    }

    /**
     * Получает список всех активных контейнеров
     */
    public List<ContainerInfo> getActiveContainers() {
        return new ArrayList<>(ACTIVE_CONTAINERS.values());
    }

    /**
     * Проверяет, запущен ли браузер для профиля
     */
    public boolean isBrowserRunning(Long profileId) {
        return ACTIVE_CONTAINERS.containsKey(profileId);
    }

    /**
     * Получает количество активных контейнеров
     */
    public int getActiveContainersCount() {
        return ACTIVE_CONTAINERS.size();
    }

    // ================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ==================

    /**
     * Определяет, какой прокси использовать
     */
    private String resolveProxy(String override, String fromProfile) {
        if (override != null && !override.isBlank()) {
            return override;
        }
        return (fromProfile == null || fromProfile.isBlank()) ? null : fromProfile;
    }

    /**
     * Проверяет, настроен ли прокси
     */
    private boolean isProxyConfigured(String override, String fromProfile) {
        String proxy = resolveProxy(override, fromProfile);
        return proxy != null && !proxy.trim().isEmpty();
    }

    /**
     * Парсит настройки прокси
     */
    private List<String> parseProxySettings(String proxyUrl) {
        List<String> settings = new ArrayList<>();

        try {
            // Пример: http://user:pass@host:port
            URL url = new URL(proxyUrl);

            if (url.getUserInfo() != null) {
                settings.add("PROXY_AUTH=" + url.getUserInfo());
            }

            String hostPort = url.getHost() + ":" + (url.getPort() > 0 ? url.getPort() : 80);
            settings.add("PROXY_HOST_PORT=" + hostPort);
            settings.add("PROXY_SCHEME=" + url.getProtocol());

        } catch (Exception e) {
            log.warn("Failed to parse proxy URL: {}", proxyUrl, e);
        }

        return settings;
    }

    /**
     * Создает директорию если не существует
     */
    private void ensureDirectoryExists(String path) {
        if (path == null) return;

        try {
            File dir = new File(path);
            if (!dir.exists()) {
                if (dir.mkdirs()) {
                    log.info("Created directory: {}", path);
                } else {
                    log.warn("Failed to create directory: {}", path);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to ensure directory exists at {}: {}", path, e.getMessage());
        }
    }

    /**
     * Нормализует путь для текущей ОС
     */
    private String normalizePath(String path) {
        if (path == null) return null;

        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("win")) {
            // Windows: заменяем / на \
            return path.replace('/', '\\');
        } else {
            // Unix-like: заменяем \ на /
            return path.replace('\\', '/');
        }
    }

    /**
     * Находит свободный порт
     */
    private int findFreePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Cannot find free port", e);
        }
    }

    /**
     * Генерирует пароль для VNC
     */
    private String generateVncPassword() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Строит URL для VNC
     */
    private String buildVncUrl(String hostBaseUrl, int port) {
        if (hostBaseUrl == null || hostBaseUrl.isEmpty()) {
            return "http://localhost:" + port + "/vnc.html";
        }
        // Убираем протокол если есть
        String host = hostBaseUrl.replaceFirst("^https?://", "");
        return "http://" + host + ":" + port + "/vnc.html";
    }

    /**
     * Строит URL для DevTools
     */
    private String buildDevToolsUrl(String hostBaseUrl, int port) {
        if (hostBaseUrl == null || hostBaseUrl.isEmpty()) {
            return "http://localhost:" + port;
        }
        String host = hostBaseUrl.replaceFirst("^https?://", "");
        return "http://" + host + ":" + port;
    }

    /**
     * Ждет готовности VNC
     */
    private void waitForVncReady(String vncUrl, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        int attempt = 0;
        boolean isReady = false;

        log.info("Waiting for VNC to be ready at {} (timeout: {}s)", vncUrl, timeout.getSeconds());

        while (System.nanoTime() < deadline && !isReady) {
            attempt++;

            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(vncUrl).openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setRequestMethod("GET");

                int code = conn.getResponseCode();

                if (code == 200) {
                    // Проверяем, что есть контент
                    try (InputStream is = conn.getInputStream()) {
                        byte[] buffer = new byte[1024];
                        int bytesRead = is.read(buffer);

                        if (bytesRead > 0) {
                            isReady = true;
                            log.info("VNC READY after {} attempts at {}", attempt, vncUrl);
                            break;
                        }
                    }
                }

                if (attempt % 5 == 0) {
                    log.debug("VNC not ready yet (code={}), attempt {}", code, attempt);
                }

            } catch (IOException e) {
                if (attempt % 5 == 0) {
                    log.debug("VNC not ready yet ({}), attempt {}", e.getMessage(), attempt);
                }
            }

            // Ждем перед следующей попыткой
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (!isReady) {
            log.warn("VNC did NOT become ready within {} seconds: {}", timeout.getSeconds(), vncUrl);
        }
    }

    /**
     * Ждет готовности DevTools
     */
    private void waitForDevToolsReady(String devToolsUrl, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        int attempt = 0;
        boolean isReady = false;

        log.debug("Waiting for DevTools to be ready at {}", devToolsUrl);

        while (System.nanoTime() < deadline && !isReady) {
            attempt++;

            try {
                String jsonUrl = devToolsUrl + "/json";
                HttpURLConnection conn = (HttpURLConnection) new URL(jsonUrl).openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                conn.setRequestMethod("GET");

                int code = conn.getResponseCode();

                if (code == 200) {
                    try (InputStream is = conn.getInputStream()) {
                        String response = new String(is.readAllBytes());
                        if (response.contains("webSocketDebuggerUrl")) {
                            isReady = true;
                            log.debug("DevTools READY after {} attempts", attempt);
                            break;
                        }
                    }
                }

            } catch (IOException e) {
                // Игнорируем ошибки подключения
            }

            // Ждем перед следующей попыткой
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (!isReady) {
            log.debug("DevTools not ready after {} attempts, continuing anyway", attempt);
        }
    }

    /**
     * Очищает старый контейнер
     */
    private void cleanupOldContainer(String containerName) {
        try {
            dockerClient.stopContainerCmd(containerName)
                    .withTimeout(5)
                    .exec();
            Thread.sleep(500);
        } catch (Exception e) {
            // Контейнера может не существовать - это нормально
        }

        try {
            dockerClient.removeContainerCmd(containerName)
                    .withForce(true)
                    .withRemoveVolumes(false)
                    .exec();
        } catch (Exception e) {
            // Контейнера может не существовать - это нормально
        }
    }

    /**
     * Проверяет, нужно ли внедрять скрипты
     */
    private boolean shouldInjectScripts(Profile profile) {
        if (!injectScripts) return false;

        String level = profile.getDetectionLevel();
        return level != null &&
                (level.equals("ENHANCED") || level.equals("AGGRESSIVE"));
    }

    /**
     * Мониторинг fingerprint после запуска
     */
    private void monitorFingerprintAfterStart(Profile profile) {
        try {
            // Запускаем мониторинг в отдельном потоке через 10 секунд
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(10000); // Ждем 10 секунд
                    fingerprintMonitor.monitorProfile(profile);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("Failed to monitor fingerprint after start", e);
                }
            });
        } catch (Exception e) {
            log.warn("Failed to schedule fingerprint monitoring", e);
        }
    }

    /**
     * Обновляет статус профиля
     */
    private void updateProfileStatus(Long profileId, String status) {
        profileRepository.findById(profileId).ifPresent(profile -> {
            profile.setStatus(status);
            profile.setLastUsedAt(Instant.now());

            if ("FREE".equals(status)) {
                profile.setLockedByUserId(null);
            }

            profileRepository.save(profile);

            log.debug("Profile {} status updated to {}", profileId, status);
        });
    }

    /**
     * Проверяет лимит контейнеров
     */
    private void checkContainerLimit() {
        int activeCount = ACTIVE_CONTAINERS.size();
        if (activeCount >= maxContainers) {
            throw new IllegalStateException(
                    String.format("Cannot start new container. Maximum limit reached: %d/%d",
                            activeCount, maxContainers)
            );
        }
        log.debug("Active containers: {}/{}", activeCount, maxContainers);
    }

    /**
     * Очищает неиспользуемые контейнеры
     */
    public void cleanupInactiveContainers() {
        log.info("Cleaning up inactive containers...");

        try {
            List<com.github.dockerjava.api.model.Container> containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .exec();

            for (com.github.dockerjava.api.model.Container container : containers) {
                String[] names = container.getNames();
                if (names != null && names.length > 0) {
                    String name = names[0];
                    if (name.startsWith("/browser_profile_")) {
                        // Проверяем, активен ли этот контейнер в нашем трекере
                        boolean isActive = ACTIVE_CONTAINERS.values().stream()
                                .anyMatch(info -> info.getContainerName().equals(name.substring(1)));

                        if (!isActive) {
                            log.info("Removing inactive container: {}", name);
                            try {
                                dockerClient.removeContainerCmd(container.getId())
                                        .withForce(true)
                                        .withRemoveVolumes(false)
                                        .exec();
                            } catch (Exception e) {
                                log.warn("Failed to remove container {}: {}", name, e.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to cleanup inactive containers: {}", e.getMessage(), e);
        }
    }

    // ================== DTO И ВСПОМОГАТЕЛЬНЫЕ КЛАССЫ ==================


}

