package com.hunt.peoples.browser.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.*;
import com.hunt.peoples.browser.config.AppProperties;
import com.hunt.peoples.browser.config.DevToolsClient;
import com.hunt.peoples.browser.dto.BrowserStartResult;
import com.hunt.peoples.browser.dto.ContainerInfo;
import com.hunt.peoples.profiles.entity.Profile;
import com.hunt.peoples.profiles.repository.ProfileRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import com.hunt.peoples.browser.config.DevToolsSession;
import java.io.File;
import java.io.IOException;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import com.github.dockerjava.api.model.*;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.*;
import java.util.concurrent.*;


/**
 * Запуск контейнера + базовая CDP-настройка для QA/эмуляции устройства:
 * - viewport / touch / locale / timezone
 * - UA override (если задан в профиле)
 *
 * НЕ содержит логики "обхода детекта" / скрытия сигналов.
 */

@Service
@RequiredArgsConstructor
@Slf4j
public class BrowserContainerService {

    private final DockerClient dockerClient;
    private final AppProperties appProperties;
    private final ProfileRepository profileRepository;
    private final ObjectMapper objectMapper;
    private final DevToolsClient devToolsClient;
    private final ProfileRepository profilesRepository;
    private final BrowserWarmUpService warmUpService;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final String IMAGE_NAME = "multi-browser-chrome-vnc";
    private static final int VNC_CONTAINER_PORT = 6080;      // noVNC из start.sh
    private static final int DEVTOOLS_CONTAINER_PORT = 9223; // EXTERNAL DevTools (через socat proxy в start.sh)


    private static final Map<Long, ContainerInfo> ACTIVE_CONTAINERS = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<Long, ReentrantLock> STOP_LOCKS = new ConcurrentHashMap<>();


    @Value("${browser.container.memory.mb:2048}")
    private int containerMemoryMB;

    @Value("${browser.container.cpu.shares:1024}")
    private int containerCpuShares;

    @Value("${browser.container.startup.timeout:60}")
    private int startupTimeoutSeconds;

    @Value("${browser.container.inject-scripts:false}")
    private boolean injectScripts;

    @Value("${browser.container.monitor-on-start:false}")
    private boolean monitorOnStart;

    @Value("${browser.devtools.websocket.connect-timeout:5000}")
    private int websocketConnectTimeout;

    @Value("${browser.container.shm-size.mb:512}")
    private int shmSizeMB;

    @Value("${browser.container.max-containers:50}")
    private int maxContainers;

    @PostConstruct
    public void init() {
        log.info("BrowserContainerService initialized. Ready to inject full fingerprints.");
    }

    public BrowserStartResult startBrowser(Profile profile, String proxyOverride) {
        String effectiveProxy = resolveProxy(proxyOverride, profile.getProxyUrl());

        // Авто-подбор часового пояса и локали по прокси
        Map<String, String> geoData = getGeoDataByProxy(effectiveProxy);

        if (!geoData.isEmpty()) {
            log.info("Auto-adjusting profile to proxy geo: {}", geoData);
            profile.setTimezone(geoData.get("timezone"));
            profile.setLocale(getLocaleByCountry(geoData.get("countryCode"), profile.getLocale()));
            // Также можно обновить язык (accept-language)
            profile.setLanguage(profile.getLocale() + "," + profile.getLocale().split("-")[0] + ";q=0.9");
        }

        Long profileId = profile.getId();
        String externalKey = profile.getExternalKey();
        String containerName = "browser_profile_" + profileId;

        log.info("=== START BROWSER DEEP === profileId={} key={}", profileId, externalKey);

        checkContainerLimit();

        if (ACTIVE_CONTAINERS.containsKey(profileId)) {
            // В продакшене лучше проверить, жив ли контейнер, но пока так:
            throw new IllegalStateException("Browser already running for profile=" + profileId);
        }

        cleanupOldContainerGracefully(containerName);

        int[] ports = findTwoDistinctFreePorts();
        int hostVncPort = ports[0];
        int hostDevToolsPort = ports[1];

        // 1. Настройка Docker Config

        // Создаем папку
        if (profile.getUserDataPath() == null || profile.getUserDataPath().isEmpty()) {
            // Формируем путь: profiles/profile_29
            String newPath = "profiles/profile_" + profileId;
            profile.setUserDataPath(newPath);
            // Сохраняем в БД, чтобы путь закрепился за профилем
            profilesRepository.save(profile);
        }

// Теперь безопасно создаем папку на диске
        File profileDir = new File(profile.getUserDataPath());
        if (!profileDir.exists()) {
            profileDir.mkdirs();
            log.info("📂 Created new physical directory for profile: {}", profileDir.getAbsolutePath());
        }


// Теперь Bind не упадет с NPE
        Bind bind = new Bind(
                profileDir.getAbsolutePath(),
                new Volume("/data/user-data")
        );

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withBinds(bind)
                .withAutoRemove(true) // Удалять контейнер, если он упал при старте
                .withPortBindings(
                        new PortBinding(Ports.Binding.bindPort(hostVncPort), ExposedPort.tcp(VNC_CONTAINER_PORT)),
                        new PortBinding(Ports.Binding.bindPort(hostDevToolsPort), ExposedPort.tcp(DEVTOOLS_CONTAINER_PORT))
                )
                .withCapAdd(Capability.SYS_ADMIN)
                .withSecurityOpts(List.of("seccomp=unconfined")); // Важно для Chrome в Docker

        // 2. Подготовка ENV с расширенными флагами Chrome (ARGS)
        List<String> envVars = prepareEnvironmentVars(profile, proxyOverride);

        // 3. Создание и запуск
        CreateContainerResponse container = dockerClient.createContainerCmd(IMAGE_NAME)
                .withName(containerName)
                .withHostConfig(hostConfig)
                .withEnv(envVars)
                .withUser("1000") // Запуск от имени созданного пользователя
                .withExposedPorts(ExposedPort.tcp(VNC_CONTAINER_PORT), ExposedPort.tcp(DEVTOOLS_CONTAINER_PORT))
                .exec();

        String containerId = container.getId();
        dockerClient.startContainerCmd(containerId).exec();

        ACTIVE_CONTAINERS.put(profileId, ContainerInfo.builder()
                .containerId(containerId).containerName(containerName).profileId(profileId)
                .hostVncPort(hostVncPort).hostDevToolsPort(hostDevToolsPort).startedAt(Instant.now())
                .build());

        String vncUrl = buildVncUrl(appProperties.getHostBaseUrl(), hostVncPort);
        String devToolsUrl = buildDevToolsUrl(appProperties.getHostBaseUrl(), hostDevToolsPort);

        // Ждем готовности портов
        waitForPortReady("127.0.0.1", hostVncPort, Duration.ofSeconds(startupTimeoutSeconds));
        waitForPortReady("127.0.0.1", hostDevToolsPort, Duration.ofSeconds(40));

        // 4. ГЛУБОКАЯ НАСТРОЙКА ЧЕРЕЗ CDP
        boolean isNewProfile = !new File(profileDir, "Default").exists();
        configureBrowserDeep(devToolsUrl, profile, isNewProfile);

        updateProfileStatus(profileId, "BUSY");

        System.out.println("Результат запуска BrowserStartResult" + BrowserStartResult.builder()
                .profileId(profileId)
                .externalKey(externalKey)
                .vncUrl(vncUrl)
                .devToolsUrl(devToolsUrl)
                .containerId(containerId)
                .startedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build());

        return BrowserStartResult.builder()
                .profileId(profileId)
                .externalKey(externalKey)
                .vncUrl(vncUrl)
                .devToolsUrl(devToolsUrl)
                .containerId(containerId)
                .startedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
    }

    // -----------------------------------------------------------------------------------------
    // ЧАСТЬ 1: Environment & Chrome Flags
    // -----------------------------------------------------------------------------------------

    private List<String> prepareEnvironmentVars(Profile profile, String proxyOverride) {
        List<String> env = new ArrayList<>();

        // Базовые порты и пути
        env.add("USER_DATA_DIR=/data/user-data");
        env.add("NOVNC_PORT=6080");
        env.add("DEVTOOLS_PORT=9223");
        env.add("DEVTOOLS_PORT_INTERNAL=9222");

        // Настройки экрана
        env.add("SCREEN_WIDTH=" + (profile.getScreenWidth() != null ? profile.getScreenWidth() : 1920));
        env.add("SCREEN_HEIGHT=" + (profile.getScreenHeight() != null ? profile.getScreenHeight() : 1080));
        env.add("SCREEN_COLOR_DEPTH=" + (profile.getScreenColorDepth() != null ? profile.getScreenColorDepth() : 24));

        // Передаем Timezone и Язык для системы (важно для форматов даты/времени)
        if (profile.getTimezone() != null) env.add("TIMEZONE=" + profile.getTimezone());
        if (profile.getLanguage() != null) env.add("LANGUAGE=" + profile.getLanguage().split(",")[0]);

        List<String> chromeArgs = new ArrayList<>();

        // 1. Анти-детект флаги
//        chromeArgs.add("--disable-blink-features=AutomationControlled");
//        chromeArgs.add("--excludeSwitches=enable-automation");
//        chromeArgs.add("--disable-infobars");

        // 2. Прокси
        String proxyToUse = resolveProxy(proxyOverride, profile.getProxyUrl());
        if (proxyToUse != null && !proxyToUse.isBlank()) {
            chromeArgs.add("--proxy-server=" + proxyToUse);
        }

        // 3. Остальные настройки (WebRTC, Мобильность)
        if ("DISABLED".equals(profile.getWebrtcMode())) {
            chromeArgs.add("--force-webrtc-ip-handling-policy=disable_non_proxied_udp");
        }

        // ВАЖНО: Добавляем в env ТОЛЬКО ОДИН РАЗ в самом конце
        env.add("EXTRA_CHROME_ARGS=" + String.join(" ", chromeArgs));

        if (profile.getUserAgent() != null) {
            env.add("UA_STRING=" + profile.getUserAgent());
        }

        return env;
    }

    // -----------------------------------------------------------------------------------------
    // ЧАСТЬ 2: CDP Configuration (Глубокая настройка)
    // -----------------------------------------------------------------------------------------

    // Хотите, чтобы я помог настроить авторизацию на прокси? Если ваш proxyUrl содержит логин и пароль (например, user:pass@host:port), Chromium не примет их через аргументы командной строки — он покажет окно ввода пароля. Это нужно обрабатывать через CDP (событие Fetch.authRequired). Подсказать, как добавить этот обработчик в ваш configureBrowserDeep?

    private void configureBrowserDeep(String devToolsUrl, Profile profile, boolean isNewProfile) {
        DevToolsSession cdp = null;
        try {
            String wsUrl = getDevToolsWebSocketUrl(devToolsUrl);
            if (wsUrl == null) {
                log.error("❌ [PROFILE {}] Could not resolve WebSocket URL", profile.getId());
                return;
            }

            log.info("🔌 [PROFILE {}] Connecting to CDP: {}", profile.getId(), wsUrl);
            cdp = devToolsClient.connect(wsUrl);

            // --- 1. Включаем домены ---
            sendAndLog(cdp, "Page.enable", Map.of(), "Page Domain");
            sendAndLog(cdp, "Runtime.enable", Map.of(), "Runtime Domain");
            sendAndLog(cdp, "Network.enable", Map.of(), "Network Domain");

            // --- 2. Скрытие автоматизации ---
            sendAndLog(cdp, "Emulation.setAutomationOverride", Map.of("enabled", false), "Disable Automation Override");

            // --- 3. Инъекция JS (Fingerprint Polyfills) ---
            // Убрали ручную строку с webdriver, так как она внутри buildPolyfillScript
            String fullScript = buildPolyfillScript(profile);
            sendAndLog(cdp, "Page.addScriptToEvaluateOnNewDocument", Map.of("source", fullScript), "JS Fingerprint Injection");

            if (isNewProfile) {
                if (profile.getCookiesJson() != null && !profile.getCookiesJson().equals("[]")) {
                    log.info("🍪 [PROFILE {}] Первый запуск: импортируем куки из базы данных", profile.getId());
                    injectCookies(cdp, profile.getCookiesJson());
                }
            } else {
                log.info("📂 [PROFILE {}] Повторный запуск: используем сессию из папки на диске (БД игнорируем)", profile.getId());
            }

            // Авторизация прокси (всегда нужна)
            if (profile.getProxyUrl() != null && profile.getProxyUrl().contains("@")) {
                setupProxyAuth(cdp, profile.getProxyUrl());
            }

            // --- 5. Эмуляция железа и ГЕО ---
            sendAndLog(cdp, "Emulation.setGeolocationOverride", Map.of(
                    "latitude", profile.getGeoLatitude(),
                    "longitude", profile.getGeoLongitude(),
                    "accuracy", profile.getGeoAccuracy()
            ), "Geolocation");

            if (profile.getUserAgent() != null && profile.getUserAgent().contains("Mobile")) {
                sendAndLog(cdp, "Emulation.setTouchEmulationEnabled", Map.of("enabled", true, "configuration", "mobile"), "Touch Emulation");

                sendAndLog(cdp, "Emulation.setDeviceMetricsOverride", Map.of(
                        "width", profile.getScreenWidth(),
                        "height", profile.getScreenHeight(),
                        "deviceScaleFactor", profile.getPixelRatio(),
                        "mobile", true
                ), "Mobile Metrics");
            }

            // --- 6. ПРИМЕНЕНИЕ И ПРОВЕРКА ---
            log.info("🚀 [PROFILE {}] Finalizing injection...", profile.getId());

            // Переходим на пустую страницу для активации всех скриптов
            cdp.send("Page.navigate", Map.of("url", "about:blank"), 10000L);
            Thread.sleep(1000);

            // Вызываем обновленный (безопасный) Integrity Check
            boolean isOk = verifyProfileIntegrity(cdp, profile);

            if (isOk) {
                log.info("✅ [PROFILE {}] Integrity check PASSED.", profile.getId());
                // Прогрев запускаем асинхронно
                final DevToolsSession finalCdp = cdp;
                CompletableFuture.runAsync(() -> {
                    try {
                        warmUpService.runWarmUp(finalCdp, profile);
                    } catch (Exception e) {
                        log.error("❌ Warm-up error: {}", e.getMessage());
                    }
                }, executorService);
            } else {
                log.warn("⚠️ [PROFILE {}] Integrity check FAILED. Potential detection risk!", profile.getId());
            }

        } catch (Exception e) {
            log.error("❌ [PROFILE {}] Critical failure: {}", profile.getId(), e.getMessage());
        }
    }

    /**
     * Вспомогательный метод для отправки команды и логирования результата
     */
    private void sendAndLog(DevToolsSession cdp, String method, Map<String, Object> params, String label) {
        try {
            JsonNode response = cdp.send(method, params, 10000L);
            if (response != null && !response.has("error")) {
                log.info("  └─ ✅ {}: OK", label);
            } else {
                log.error("  └─ ❌ {}: FAILED. Response: {}", label, response);
            }
        } catch (Exception e) {
            log.error("  └─ ❌ {}: EXCEPTION: {}", label, e.getMessage());
        }
    }

    /**
     * Обработка авторизации прокси (Username/Password)
     */
    private void setupProxyAuth(DevToolsSession cdp, String proxyUrl) {
        if (proxyUrl == null || !proxyUrl.contains("@")) return;
        // ОБЯЗАТЕЛЬНО: Включаем домен Fetch, иначе перехват авторизации не сработает
        cdp.send("Fetch.enable", Map.of("handleAuthRequests", true), 30000L);

        try {
            // Парсим логин:пароль из формата http://user:pass@host:port
            String authPart = proxyUrl.split("@")[0].replace("http://", "").replace("https://", "");
            String[] creds = authPart.split(":");
            if (creds.length < 2) return;

            String username = creds[0];
            String password = creds[1];

            cdp.onEvent("Fetch.authRequired", params -> {
                String requestId = params.get("requestId").asText(); // Извлекаем ID как строку

                Map<String, Object> authResponse = Map.of(
                        "response", "ProvideCredentials",
                        "username", username,
                        "password", password
                );

                Map<String, Object> sendParams = Map.of(
                        "requestId", requestId,
                        "authChallengeResponse", authResponse
                );

                // Вызываем с явным указанием таймаута
                cdp.send("Fetch.continueWithAuth", sendParams, 30000);
            });
        } catch (Exception e) {
            log.warn("Could not setup proxy auth: {}", e.getMessage());
        }
    }



    // -----------------------------------------------------------------------------------------
    // ЧАСТЬ 3: JS Injection (Polyfills)
    // -----------------------------------------------------------------------------------------


    private String buildPolyfillScript(Profile profile) {
        StringBuilder js = new StringBuilder();
        js.append("(() => {\n");

        // Передаем профиль в JS-контекст один раз
        js.append("  const p = ").append(convertProfileToJsonSafe(profile)).append(";\n");

        // 1. Утилита для патчинга свойств (делает их похожими на нативные)
        js.append("""
      const patch = (obj, prop, value) => {
        if (!obj) return;
        try {
            Object.defineProperty(obj, prop, {
              get: () => value,
              enumerable: true,
              configurable: true
            });
        } catch (e) { /* ignore */ }
      };
    """);

        // 2. Hardware & Basic Info
        js.append("  patch(navigator, 'hardwareConcurrency', p.hardwareConcurrency || 8);\n");
        js.append("  patch(navigator, 'deviceMemory', p.deviceMemory || 8);\n");
        js.append("  patch(navigator, 'maxTouchPoints', p.maxTouchPoints || 5);\n");
        js.append("  patch(navigator, 'platform', p.platform || 'Linux armv8l');\n");
        js.append("  patch(navigator, 'webdriver', false);\n");

        // 3. UserAgentData & Client Hints (Убирает MISMATCH: Linux x86_64)
        js.append("""
      if (navigator.userAgentData) {
        const isMobile = p.userAgent.includes('Mobile');
        const brands = [
            { brand: 'Not_A Brand', version: '99' },
            { brand: 'Google Chrome', version: '143' },
            { brand: 'Chromium', version: '143' }
        ];
        
        // Патчим основные свойства
        patch(navigator.userAgentData, 'mobile', isMobile);
        patch(navigator.userAgentData, 'platform', isMobile ? 'Android' : 'Windows');
        patch(navigator.userAgentData, 'brands', brands);

        // Патчим глубокие проверки (getHighEntropyValues)
        const origGetHEV = navigator.userAgentData.getHighEntropyValues;
        navigator.userAgentData.getHighEntropyValues = function(hints) {
            return Promise.resolve({
                architecture: p.platform.includes('arm') ? 'arm' : 'x86',
                bitness: '64',
                brands: brands,
                mobile: isMobile,
                model: p.deviceProfileJson?.model || 'SM-S918B',
                platform: isMobile ? 'Android' : 'Windows',
                platformVersion: '13.0.0',
                uaFullVersion: '143.0.7486.23'
            });
        };
      }
    """);

        // 4. WebGL Spoofing (Убирает LEAKED REAL GPU: SwiftShader)
        js.append("""
      const patchWebGL = (proto) => {
        if (!proto) return;
        const origGetParameter = proto.getParameter;
        const origGetExtension = proto.getExtension;

        // Патчим получение расширения с инфой о GPU
        proto.getExtension = function(name) {
          if (name === 'WEBGL_debug_renderer_info') {
            return {
              UNMASKED_VENDOR_WEBGL: 37445,
              UNMASKED_RENDERER_WEBGL: 37446
            };
          }
          return origGetExtension.apply(this, arguments);
        };

        // Патчим возврат параметров
        proto.getParameter = function(param) {
          if (param === 37445) return p.webglVendor || 'Qualcomm';
          if (param === 37446) return p.webglRenderer || 'Adreno (TM) 740';
          if (param === 7936)  return p.webglVendor || 'Qualcomm';
          if (param === 7937)  return p.webglRenderer || 'Adreno (TM) 740';
          return origGetParameter.apply(this, arguments);
        };
      };
      
      if (window.WebGLRenderingContext) patchWebGL(WebGLRenderingContext.prototype);
      if (window.WebGL2RenderingContext) patchWebGL(WebGL2RenderingContext.prototype);
    """);

        // 5. Plugins & Languages
        js.append("""
      const mockPlugins = [
          { name: 'PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
          { name: 'Chrome PDF Viewer', filename: 'internal-pdf-viewer', description: 'Google Chrome PDF' },
          { name: 'Chromium PDF Viewer', filename: 'internal-pdf-viewer', description: 'Chromium PDF' }
      ];
      
      Object.defineProperty(navigator, 'plugins', {
          get: () => {
              const pList = [...mockPlugins];
              pList.item = (i) => pList[i];
              pList.namedItem = (n) => pList.find(x => x.name === n);
              pList.refresh = () => {};
              return pList;
          },
          configurable: true
      });
      patch(navigator, 'languages', (p.language || 'ru-RU,ru,en-US,en').split(','));
    """);

        // 6. Canvas Noise (ваша логика)
        js.append("""
      const origGetImageData = CanvasRenderingContext2D.prototype.getImageData;
      CanvasRenderingContext2D.prototype.getImageData = function(x, y, w, h) {
        const image = origGetImageData.apply(this, arguments);
        if (p.canvasNoiseHash) {
          const n = parseInt(p.canvasNoiseHash.substring(0, 2), 16) % 3 + 1;
          for (let i = 0; i < image.data.length; i += 4) {
            image.data[i] = image.data[i] + (n % 2);
          }
        }
        return image;
      };
    """);

        // 7. Battery API (ваша логика)
        js.append("""
      if (navigator.getBattery) {
        const initialLevel = p.batteryLevel || 0.85;
        navigator.getBattery = async () => ({
          charging: false,
          chargingTime: 0,
          dischargingTime: 8600,
          level: initialLevel,
          addEventListener: () => {},
          removeEventListener: () => {},
          onlevelchange: null
        });
      }
    """);

        // 8. Самодиагностика (Помогает вам при отладке через VNC)
        js.append("""
            console.group('%c🛡️ FINGERPRINT SPOOFING ACTIVE', 'color: #00ff00; font-weight: bold;');
            console.log('Profile ID:', p.id);
            console.log('Platform (Nav):', navigator.platform);
            console.log('Platform (UAData):', navigator.userAgentData ? navigator.userAgentData.platform : 'N/A');
            console.log('WebGL Renderer:', p.webglRenderer);
            console.log('Canvas Noise:', p.canvasNoiseHash ? '✅ Active' : '❌ None');
            console.groupEnd();
        """);

        // 9. Fonts Fingerprint (Простая заглушка)
        js.append("""
            try {
                const injectedFonts = JSON.parse(p.fontsListJson || '[]');
                if (injectedFonts.length > 0 && document.fonts) {
                    // Мы не блокируем шрифты (это ломает верстку), 
                    // но логируем для себя, что профиль содержит специфичный набор
                    console.log('Fonts loaded from profile:', injectedFonts.length);
                }
            } catch (e) {}
        """);

        js.append("})();");
        return js.toString();
    }

    private Map<String, String> getGeoDataByProxy(String proxyUrl) {
        if (proxyUrl == null || proxyUrl.isBlank()) return Map.of();

        try {
            // Извлекаем только host из user:pass@host:port или host:port
            String host = proxyUrl.contains("@") ? proxyUrl.split("@")[1].split(":")[0] : proxyUrl.split(":")[0];

            // Запрос к API геопозиционирования (ip-api возвращает JSON с timezone)
            JsonNode resp = restTemplate.getForObject("http://ip-api.com/json/" + host, JsonNode.class);

            if (resp != null && "success".equals(resp.path("status").asText())) {
                Map<String, String> data = new HashMap<>();
                data.put("timezone", resp.path("timezone").asText()); // Например: Europe/Berlin
                data.put("countryCode", resp.path("countryCode").asText().toLowerCase()); // Например: de
                return data;
            }
        } catch (Exception e) {
            log.warn("Failed to fetch geo data for proxy {}: {}", proxyUrl, e.getMessage());
        }
        return Map.of();
    }

    private String getLocaleByCountry(String countryCode, String defaultLocale) {
        if (countryCode == null) return defaultLocale;
        return switch (countryCode.toLowerCase()) {
            case "ru" -> "ru-RU";
            case "de" -> "de-DE";
            case "us" -> "en-US";
            case "gb" -> "en-GB";
            case "fr" -> "fr-FR";
            default -> defaultLocale;
        };
    }

    @SneakyThrows
    private String convertProfileToJsonSafe(Profile p) {
        Map<String, Object> map = new HashMap<>();
        map.put("hardwareConcurrency", p.getHardwareConcurrency());
        map.put("deviceMemory", p.getDeviceMemory());
        map.put("maxTouchPoints", p.getMaxTouchPoints());
        map.put("platform", p.getPlatform());
        map.put("webglVendor", p.getWebglVendor());
        map.put("webglRenderer", p.getWebglRenderer());
        map.put("webglVersion", p.getWebglVersion());
        map.put("webrtcLocalIp", p.getWebrtcLocalIp());
        map.put("canvasNoiseHash", p.getCanvasNoiseHash());
        map.put("audioSampleRate", p.getAudioSampleRate());
        map.put("batteryInfoJson", p.getBatteryInfoJson());
        map.put("mediaDevicesJson", p.getMediaDevicesJson());
        return objectMapper.writeValueAsString(map);
    }

    // -----------------------------------------------------------------------------------------
    // Вспомогательные методы (Helpers)
    // -----------------------------------------------------------------------------------------

//    public boolean verifyProfileIntegrity(DevToolsSession cdp, Profile profile) {
//        try {
//            log.info("🧪 Starting FAST integrity check on about:blank for profile: {}", profile.getId());
//
//            // 1. Переходим на пустую страницу - это мгновенно
//            cdp.send("Page.navigate", Map.of("url", "about:blank"), 30000L);
//
//            // Небольшая пауза, чтобы CDP успел проинициализировать контекст
//            Thread.sleep(2500);
//
//            // 2. Скрипт проверки (тот же самый)
//            String verificationJs = """
//            (async () => {
//                const getIP = () => new Promise(res => {
//                    try {
//                        const pc = new RTCPeerConnection({iceServers:[]});
//                        pc.createDataChannel("");
//                        pc.createOffer().then(o => pc.setLocalDescription(o));
//                        setTimeout(() => {
//                            const sdp = pc.localDescription?.sdp || "";
//                            const m = sdp.match(/([0-9]{1,3}(\\\\.[0-9]{1,3}){3})/);
//                            res(m ? m[1] : "not found");
//                            pc.close();
//                        }, 500);
//                    } catch(e) { res("error"); }
//                });
//
//                return {
//                    ua: navigator.userAgent,
//                    webdriver: navigator.webdriver,
//                    webrtc: await getIP()
//                };
//            })()
//        """;
//
//            // 3. Выполняем
//            var response = cdp.send("Runtime.evaluate", Map.of(
//                    "expression", verificationJs,
//                    "returnByValue", true,
//                    "awaitPromise", true
//            ), 10000L);
//
//            JsonNode val = response.path("result").path("value");
//
//            // Если проверка на about:blank прошла - значит JS инъекция РАБОТАЕТ глобально
//            log.info("--- 🛡️ INTEGRITY REPORT ---");
//            log.info("Detected IP: {}", val.path("webrtc").asText());
//            log.info("Webdriver:   {}", val.path("webdriver").asBoolean() ? "🚩 DETECTED" : "✅ HIDDEN");
//
//            return !val.path("webdriver").asBoolean(false);
//
//        } catch (Exception e) {
//            log.error("❌ Fast integrity check failed: {}", e.getMessage());
//            return true; // Не блокируем запуск
//        }
//    }
private boolean verifyProfileIntegrity(DevToolsSession cdp, Profile profile) {
    try {
        log.info("🧪 [PROFILE {}] Running Deep Integrity Check...", profile.getId());

        String verifyJs = """
        (() => {
            try {
                const getWebGL = () => {
                    const canvas = document.createElement('canvas');
                    const gl = canvas.getContext('webgl');
                    if (!gl) return { vendor: 'n/a', renderer: 'n/a' };
                    const debugInfo = gl.getExtension('WEBGL_debug_renderer_info');
                    return {
                        vendor: debugInfo ? gl.getParameter(debugInfo.UNMASKED_VENDOR_WEBGL) : 'unknown',
                        renderer: debugInfo ? gl.getParameter(debugInfo.UNMASKED_RENDERER_WEBGL) : 'unknown'
                    };
                };
                return {
                    webdriver: navigator.webdriver,
                    pluginsCount: navigator.plugins ? navigator.plugins.length : 0,
                    platform: navigator.platform,
                    cdc_found: !!(document.$cdc_asdjflasjkdfp_ || document.__webdriver_evaluate),
                    webgl: getWebGL()
                };
            } catch (e) {
                return { error: e.message };
            }
        })()
        """;

        JsonNode response = cdp.send("Runtime.evaluate", Map.of(
                "expression", verifyJs,
                "returnByValue", true
        ), 10000L);

        JsonNode result = response.at("/result/result/value");

        // ПРОВЕРКА НА ОШИБКИ
        if (result.isMissingNode() || result.has("error")) {
            log.error("❌ JS Execution Error: {}", result.has("error") ? result.get("error").asText() : "No result");
            return false;
        }

        // Безопасное получение значений
        boolean webdriverHidden = result.has("webdriver") && !result.get("webdriver").asBoolean();
        int pluginsCount = result.has("pluginsCount") ? result.get("pluginsCount").asInt() : 0;
        boolean cdcHidden = result.has("cdc_found") && !result.get("cdc_found").asBoolean();
        String detectedPlatform = result.has("platform") ? result.get("platform").asText() : "unknown";
        String webglRenderer = result.at("/webgl/renderer").asText("");

        log.info("--- 🛡️ INTEGRITY REPORT [Profile {}] ---", profile.getId());
        log.info("  ├─ Webdriver:  {}", webdriverHidden ? "✅ HIDDEN" : "❌ LEAKED");
        log.info("  ├─ CDC/Driver: {}", cdcHidden ? "✅ CLEAN" : "❌ FOUND");
        log.info("  ├─ Plugins:    {}", pluginsCount > 0 ? "✅ " + pluginsCount : "❌ EMPTY");
        log.info("  ├─ Platform:   {}", detectedPlatform.equals(profile.getPlatform()) ? "✅ MATCH" : "⚠️ MISMATCH: " + detectedPlatform);
        log.info("  └─ WebGL:      {}", webglRenderer.contains(profile.getWebglRenderer()) ? "✅ MATCH" : "❌ LEAK: " + webglRenderer);

        return webdriverHidden && (pluginsCount > 0);
    } catch (Exception e) {
        log.error("❌ Integrity check failed: {}", e.getMessage());
        return false;
    }
}


    private void injectCookies(DevToolsSession cdp, String cookiesJson) {
        if (cookiesJson == null || cookiesJson.isBlank() || "[]".equals(cookiesJson)) return;

        try {
            JsonNode cookiesNode = objectMapper.readTree(cookiesJson);
            if (cookiesNode.isArray()) {
                List<Map<String, Object>> cookieList = new ArrayList<>();
                for (JsonNode c : cookiesNode) {
                    Map<String, Object> cp = new HashMap<>();
                    cp.put("name", c.path("name").asText());
                    cp.put("value", c.path("value").asText());
                    // Обязательно убираем 'http://' из домена, если он там есть
                    String domain = c.path("domain").asText().replace("http://", "").replace("https://", "");
                    cp.put("domain", domain);
                    cp.put("path", c.path("path").asText("/"));

                    // Обработка срока действия (Expiration)
                    if (c.has("expirationDate")) {
                        cp.put("expires", c.get("expirationDate").asDouble());
                    }
                    cookieList.add(cp);
                }
                // Отправляем пачкой
                cdp.send("Network.setCookies", Map.of("cookies", cookieList), 30000L);
                log.info("Successfully injected {} cookies", cookieList.size());
            }
        } catch (Exception e) {
            log.error("Cookie injection failed: {}", e.getMessage());
        }
    }

    private String getDevToolsWebSocketUrl(String devToolsUrl) {
        log.info("Resolving WebSocket URL from: {}", devToolsUrl);

        for (int i = 0; i < 15; i++) { // Увеличим до 15 попыток
            try {
                String url = devToolsUrl.endsWith("/") ? devToolsUrl + "json/list" : devToolsUrl + "/json/list";

                // Получаем ответ как строку, чтобы избежать проблем с маппингом типов
                ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode root = mapper.readTree(response.getBody());

                    if (root.isArray() && !root.isEmpty()) {
                        for (JsonNode node : root) {
                            String type = node.path("type").asText();
                            String wsUrl = node.path("webSocketDebuggerUrl").asText();

                            // Ищем именно основную страницу (page)
                            if ("page".equals(type) && !wsUrl.isEmpty()) {
                                log.info("Successfully found WebSocket URL on attempt {}: {}", i + 1, wsUrl);
                                return wsUrl;
                            }
                        }
                    }
                }
                log.debug("Attempt {}: Page target not found in /json/list, retrying...", i + 1);
            } catch (Exception e) {
                log.warn("Attempt {}: DevTools API not reachable yet: {}", i + 1, e.getMessage());
            }

            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
        }

        log.error("CRITICAL: Failed to resolve WebSocket URL after 15 attempts");
        return null;
    }

    // --- Реализация методов, которых не хватало в твоем коде ---

    private void checkContainerLimit() {
        if (ACTIVE_CONTAINERS.size() >= maxContainers) {
            throw new RuntimeException("Max container limit reached: " + maxContainers);
        }
    }

    private void cleanupOldContainerGracefully(String containerName) {
        try {
            dockerClient.removeContainerCmd(containerName).withForce(true).exec();
            log.info("Removed old container: {}", containerName);
        } catch (Exception ignored) {
            // Контейнера не было, все ок
        }
    }

    @SneakyThrows
    private int[] findTwoDistinctFreePorts() {
        try (ServerSocket s1 = new ServerSocket(0);
             ServerSocket s2 = new ServerSocket(0)) {
            return new int[]{s1.getLocalPort(), s2.getLocalPort()};
        }
    }

    private String resolveProxy(String override, String profileProxy) {
        if (override != null && !override.isBlank()) return override;
        return profileProxy;
    }

    private void waitForPortReady(String host, int port, Duration timeout) {
        long end = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < end) {
            try (Socket s = new Socket(host, port)) {
                return;
            } catch (IOException ignored) {
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
        log.warn("Port {} not ready after {}s", port, timeout.getSeconds());
    }

    private String buildVncUrl(String baseUrl, int port) {
        // Упрощенная сборка URL. Базовый URL обычно без порта
        String host = baseUrl.replace("http://", "").replace("https://", "").split(":")[0];
        return "http://" + host + ":" + port + "/vnc.html";
    }

    private String buildDevToolsUrl(String baseUrl, int port) {
        String host = baseUrl.replace("http://", "").replace("https://", "").split(":")[0];
        return "http://" + host + ":" + port;
    }

    private void updateProfileStatus(Long profileId, String status) {
        // Простейшая реализация, в идеале через Transactional сервис
        Profile p = profileRepository.findById(profileId).orElse(null);
        if (p != null) {
            p.setStatus(status);
            profileRepository.save(p);
        }
    }

    @SneakyThrows
    private String normalizePath(String path) {
        if (path == null) return "/tmp/browser_profiles/default";
        // Для Windows/Linux совместимости
        if (path.contains("~")) {
            path = path.replace("~", System.getProperty("user.home"));
        }
        return new File(path).getAbsolutePath();
    }

    @SneakyThrows
    private void ensureDirectoryExists(String path) {
        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            Files.createDirectories(p);
        }
    }


    // -------------------- STOP --------------------
    // ШАГ 7: Остановка браузера
    public boolean stopBrowser(Long profileId) {
        final String containerName = "browser_profile_" + profileId;

        ReentrantLock lock = STOP_LOCKS.computeIfAbsent(profileId, id -> new ReentrantLock());
        lock.lock();
        try {
            var inspected = inspectContainerQuiet(containerName);
            if (inspected == null) {
                ACTIVE_CONTAINERS.remove(profileId);
                updateProfileStatus(profileId, "FREE");
                return true;
            }
// --- ВАЖНО: ВЫЗЫВАЕМ ЗДЕСЬ ---
            // Пока контейнер еще работает (State.Running == true),
            // подключаемся по CDP и забираем куки в БД.
            saveCookiesBeforeStop(profileId);
            // ------------------------------
            String containerId = inspected.getId();
            updateProfileStatus(profileId, "STOPPING");

            try {
                dockerClient.stopContainerCmd(containerId).withTimeout(180).exec();
            } catch (com.github.dockerjava.api.exception.NotFoundException e) {
                ACTIVE_CONTAINERS.remove(profileId);
                updateProfileStatus(profileId, "FREE");
                return true;
            } catch (Exception e) {
                log.warn("Error stopping container {}: {}", containerName, e.getMessage());
            }

            boolean stopped = waitStoppedById(containerId, 240);
            if (!stopped) {
                log.warn("Container {} did not stop in time; keep STOPPING", containerName);
                return false;
            }

            try {
                dockerClient.removeContainerCmd(containerId).withForce(false).withRemoveVolumes(false).exec();
            } catch (Exception e) {
                log.warn("Error removing container {}: {}", containerName, e.getMessage());
            }

            ACTIVE_CONTAINERS.remove(profileId);
            updateProfileStatus(profileId, "FREE");
            return true;

        } finally {
            lock.unlock();
        }
    }




    private boolean waitStoppedById(String containerId, int seconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);
        while (System.currentTimeMillis() < deadline) {
            try {
                var c = dockerClient.inspectContainerCmd(containerId).exec();
                Boolean running = c.getState() != null ? c.getState().getRunning() : null;
                if (running == null || !running) return true;
            } catch (com.github.dockerjava.api.exception.NotFoundException e) {
                return true;
            } catch (Exception ignore) {}
            sleep(500);
        }
        return false;
    }

    private InspectContainerResponse inspectContainerQuiet(String nameOrId) {
        try {
            return dockerClient.inspectContainerCmd(nameOrId).exec();
        } catch (Exception e) {
            return null;
        }
    }


    public boolean isBrowserRunning(Long profileId) {
        ContainerInfo info = ACTIVE_CONTAINERS.get(profileId);
        if (info == null) return false;

        var inspected = inspectContainerQuiet(info.getContainerId());
        boolean running = inspected != null
                && inspected.getState() != null
                && Boolean.TRUE.equals(inspected.getState().getRunning());

        if (!running) ACTIVE_CONTAINERS.remove(profileId);
        return running;
    }

    public Optional<ContainerInfo> getContainerInfo(Long profileId) {
        return Optional.ofNullable(ACTIVE_CONTAINERS.get(profileId));
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public void saveCookiesBeforeStop(Long profileId) {
        ContainerInfo info = ACTIVE_CONTAINERS.get(profileId);
        if (info == null) return;

        try {
            String devToolsUrl = "http://127.0.0.1:" + info.getHostDevToolsPort();
            String wsUrl = getDevToolsWebSocketUrl(devToolsUrl);

            if (wsUrl != null) {
                // Подключаемся на секунду, чтобы забрать куки
                try (DevToolsSession cdp = devToolsClient.connect(wsUrl)) {
                    var response = cdp.send("Network.getAllCookies", Map.of(), 5000L);
                    JsonNode cookies = response.path("cookies");

                    if (cookies.isArray() && cookies.size() > 0) {
                        Profile profile = profileRepository.findById(profileId).orElse(null);
                        if (profile != null) {
                            profile.setCookiesJson(cookies.toString());
                            profileRepository.save(profile);
                            log.info("💾 [PROFILE {}] Куки синхронизированы с БД перед остановкой", profileId);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ Не удалось сохранить куки перед остановкой (возможно браузер уже закрыт): {}", e.getMessage());
        }
    }

    // -------------------- DTO --------------------


}
