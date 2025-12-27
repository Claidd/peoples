package com.hunt.peoples.profiles.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.hunt.peoples.profiles.entity.Profile;
import com.hunt.peoples.profiles.repository.ProfileRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;


@Slf4j
@Component
@RequiredArgsConstructor
public class FingerprintGenerator {

    private final ProfileRepository profileRepository;

    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    private static final String CHROME_MAJOR = "143";
    private static final String CHROME_FULL = "143.0.7486.23";
    private final org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
    private static final String GEO_API_URL = "http://ip-api.com/json/%s?fields=status,message,countryCode,timezone,lat,lon,query";
    // Список популярных DNSBL серверов для проверки репутации IP
    private static final List<String> DNSBL_SERVICES = List.of("zen.spamhaus.org", "dnsbl.sorbs.net");

    private static final List<DeviceTemplate> ANDROID_DEVICE_TEMPLATES = List.of(
            // --- SAMSUNG ---
            DeviceTemplate.builder()
                    .model("SM-S918B").marketingName("Samsung Galaxy S23 Ultra")
                    .androidVer("13").androidVerFull("13.0.0")
                    .width(384).height(854).pixelRatio(3.75)
                    .gpuVendor("Qualcomm").gpuRenderer("Adreno (TM) 740")
                    .ram(12).cpuCores(8).build(),

            DeviceTemplate.builder()
                    .model("SM-A546B").marketingName("Samsung Galaxy A54 5G")
                    .androidVer("13").androidVerFull("13.0.0")
                    .width(412).height(915).pixelRatio(2.625)
                    .gpuVendor("ARM").gpuRenderer("Mali-G68")
                    .ram(8).cpuCores(8).build(),

            // --- GOOGLE PIXEL ---
            DeviceTemplate.builder()
                    .model("Pixel 7 Pro").marketingName("Google Pixel 7 Pro")
                    .androidVer("13").androidVerFull("13.0.0")
                    .width(412).height(892).pixelRatio(3.5)
                    .gpuVendor("ARM").gpuRenderer("Mali-G710") // Метод fillGpuAndHardware подхватит, если стереть
                    .ram(12).cpuCores(8).build(),

            DeviceTemplate.builder()
                    .model("Pixel 6a").marketingName("Google Pixel 6a")
                    .androidVer("12").androidVerFull("12.0.0")
                    .width(412).height(915).pixelRatio(2.625)
                    .gpuVendor("ARM").gpuRenderer("Mali-G78")
                    .ram(6).cpuCores(8).build(),

            // --- XIAOMI ---
            DeviceTemplate.builder()
                    .model("23127PN0CG").marketingName("Xiaomi 14")
                    .androidVer("14").androidVerFull("14.0.0")
                    .width(393).height(852).pixelRatio(3.0)
                    .gpuVendor("Qualcomm").gpuRenderer("Adreno (TM) 750")
                    .ram(12).cpuCores(8).build(),

            DeviceTemplate.builder()
                    .model("2210132G").marketingName("Xiaomi 13T Pro")
                    .androidVer("13").androidVerFull("13.0.0")
                    .width(412).height(915).pixelRatio(3.0)
                    .gpuVendor("ARM").gpuRenderer("Mali-G715")
                    .ram(12).cpuCores(8).build(),

            // --- ONEPLUS ---
            DeviceTemplate.builder()
                    .model("CPH2449").marketingName("OnePlus 11")
                    .androidVer("13").androidVerFull("13.0.0")
                    .width(360).height(800).pixelRatio(3.0)
                    .gpuVendor("Qualcomm").gpuRenderer("Adreno (TM) 740")
                    .ram(16).cpuCores(8).build()
    );

    public Profile generateCompleteProfile(String externalKey, String deviceType, String detectionLevel, String proxyUrl) {
        try {
            DeviceTemplate template = selectTemplateInternal(deviceType);
            String userAgent = generateUserAgent(template);
            ClientHintsData clientHints = generateClientHints(template);

            int navbarHeight = 48 + random.nextInt(37);
            // 1. Сначала проверяем прокси и получаем данные о локации
            GeoIpResponse geoData = resolveGeoData(proxyUrl);

            Profile profile = new Profile();
            profile.setDetectionLevel(detectionLevel != null ? detectionLevel : "ENHANCED");

            // Сначала считаем базовый риск
            double risk = calculateRiskForDetectionLevel(profile.getDetectionLevel());
            // Если IP в блэклисте — накидываем штраф
            if (geoData.isBlacklisted()) {
                risk += 0.3;
                log.warn("IP {} is blacklisted! Risk increased.", geoData.getQuery());
            }
            profile.setDetectionRisk(Math.min(risk, 1.0)); // Устанавливаем итоговый риск
            if (geoData.isBlacklisted()) {
                risk += 0.3; // Увеличиваем риск, если IP "грязный"
                log.warn("IP {} is blacklisted! Risk increased.", geoData.getQuery());
            }
            profile.setDetectionRisk(Math.min(risk, 1.0));

            // 1. БАЗОВЫЕ ПОЛЯ (Системные)
            profile.setExternalKey(externalKey);
            profile.setName(template.getMarketingName() + " - " + externalKey);
            profile.setStatus("FREE");
            profile.setIsActive(true);
            profile.setCheckCount(0);
            profile.setDetectionLevel(detectionLevel != null ? detectionLevel : "ENHANCED");
            profile.setDetectionRisk(calculateRiskForDetectionLevel(profile.getDetectionLevel()));

            // 2. ЭКРАН (Fingerprint Basics)
            profile.setUserAgent(userAgent);
            profile.setPlatform("Linux armv8l");
            profile.setScreenWidth(template.getWidth());
            profile.setScreenHeight(template.getHeight());
            profile.setPixelRatio(template.getPixelRatio());
            profile.setScreenAvailWidth(template.getWidth());
            profile.setScreenAvailHeight(template.getHeight() - navbarHeight); // Имитация Navbar
            profile.setScreenColorDepth(24);
            profile.setScreenPixelDepth(24);
            profile.setScreenColorGamut("p3");

            // 3. ЖЕЛЕЗО (Hardware)
            profile.setHardwareConcurrency(template.getCpuCores() > 0 ? template.getCpuCores() : 8);
            profile.setDeviceMemory(template.getRam());
            profile.setMaxTouchPoints(5);

            // 4. ГРАФИКА (WebGL)
// Заменяем старый блок на вызов метода:
            fillGpuAndHardware(profile, template);

            profile.setWebglVersion("WebGL 2.0 (OpenGL ES 3.0 Chromium)");
            profile.setCanvasFingerprint(generateHash("canvas"));
            profile.setCanvasNoiseHash(generateHash("noise"));

            // 5. CLIENT HINTS (Важно для Chrome)
            profile.setUaChBrandsJson(clientHints.getBrandsJson());
            profile.setUaChPlatform("Android");
            profile.setUaChPlatformVersion(clientHints.getPlatformVersion());
            profile.setUaChArchitecture("arm");
            profile.setUaChModel(template.getModel());
            profile.setUaChMobile(true);
            profile.setUaChBitness("64");

            // 6. ВЕРСИИ
            profile.setChromeVersion(CHROME_FULL);
            profile.setOsVersion(template.getAndroidVerFull());
            profile.setOsArchitecture("arm64");

            // 7. ЗАПОЛНЕНИЕ ВСЕХ ОСТАЛЬНЫХ ПОЛЕЙ (Объединенный метод)
            fillRemainingFieldsConsistent(profile, template);

            // МЕТАДАННЫЕ
            profile.setFingerprintCreatedAt(Instant.now());
            profile.setFingerprintUpdatedAt(Instant.now());
            profile.setFingerprintHash(generateHash(profile.getUserAgent() + externalKey));

            // В самом конце перед return:
            applyLocationData(profile, geoData, proxyUrl);

            log.info("Successfully generated profile for {} (Loc: {})", externalKey, geoData.getTimezone());


            // 8. Сохраняем профиль
            Profile savedProfile = profileRepository.save(profile);
            log.info("Created profile with random mobile params: id={}, userAgent={}, screen={}x{}",
                    savedProfile.getId(), savedProfile.getUserAgent(),
                    savedProfile.getScreenWidth(), savedProfile.getScreenHeight());
            System.out.println(savedProfile);
            return savedProfile;
        } catch (Exception e) {
            log.error("Generation failed for key: {}", externalKey, e);
            throw new RuntimeException("Profile generation error", e);
        }
    }

    @SneakyThrows
    private void fillRemainingFieldsConsistent(Profile profile, DeviceTemplate template) {
        // --- 1. Поведенческие параметры ---
        profile.setMouseMovementVariance(0.5 + random.nextDouble());
        profile.setTypingSpeed(80 + random.nextInt(100));
        profile.setScrollSpeed(1.0 + random.nextDouble());
        profile.setCommonWebsitesJson(generateRandomWebsitesJson());

        // --- 2. Audio Fingerprint (Объединено) ---
        Map<String, Object> audioFpMap = new HashMap<>();
        audioFpMap.put("sampleRate", 48000);
        audioFpMap.put("channelCount", 2);
        audioFpMap.put("noiseHash", generateHash("audio"));
        profile.setAudioFingerprintJson(objectMapper.writeValueAsString(audioFpMap));
        profile.setAudioSampleRate(48000);
        profile.setAudioChannelCount("2");
        profile.setAudioContextLatency(0.01 + (random.nextDouble() * 0.02));

        // --- 3. Battery (Улучшенная версия) ---
// Большинство людей начинают работу с зарядом от 20% до 95%
        double initialLevel = 0.2 + random.nextDouble() * 0.75;

// Редко кто работает на зарядке (около 15% случаев)
        boolean isCharging = random.nextDouble() < 0.15;

        profile.setBatteryCharging(isCharging);
        profile.setBatteryLevel(initialLevel);

// Если заряжается — время до полной зарядки, если нет — время до разряда
        profile.setBatteryChargingTime(isCharging ? (random.nextInt(3600) + 600) : 0);
        profile.setBatteryDischargingTime(isCharging ? 0 : (random.nextInt(15000) + 5000));

        Map<String, Object> batteryJson = new HashMap<>();
        batteryJson.put("charging", isCharging);
        batteryJson.put("level", initialLevel);
        batteryJson.put("chargingTime", profile.getBatteryChargingTime());
        batteryJson.put("dischargingTime", isCharging ? "Infinity" : profile.getBatteryDischargingTime());

        profile.setBatteryInfoJson(objectMapper.writeValueAsString(batteryJson));

        // --- 4. Navigator & Info ---
        profile.setCookieEnabled(true);
        profile.setOnline(true);
        profile.setDoNotTrack("unspecified");
        Map<String, Object> navInfo = new HashMap<>();
        navInfo.put("vendor", "Google Inc.");
        navInfo.put("product", "Gecko");
        navInfo.put("language", "en-US");
        profile.setNavigatorInfoJson(objectMapper.writeValueAsString(navInfo));
        profile.setLocale("en-US");
        profile.setLanguage("en-US");
        profile.setTimezone("UTC"); // Будет изменено при подключении прокси
        profile.setTimezoneOffset(0);

        // --- 5. Connection (Объединено) ---
        profile.setConnectionEffectiveType("4g");
        profile.setConnectionDownlink(10.0 + random.nextDouble() * 5);
        profile.setConnectionRtt(50 + random.nextInt(100));
        profile.setConnectionSaveData(false);
        profile.setConnectionType("cellular");

        Map<String, Object> connJson = new HashMap<>();
        connJson.put("effectiveType", profile.getConnectionEffectiveType());
        connJson.put("downlink", profile.getConnectionDownlink());
        connJson.put("rtt", profile.getConnectionRtt());
        connJson.put("saveData", profile.getConnectionSaveData());
        connJson.put("type", profile.getConnectionType());
        profile.setConnectionInfoJson(objectMapper.writeValueAsString(connJson));

        // --- 6. Media & Plugins ---
        profile.setPluginsJson("[]"); // Chrome Mobile не имеет плагинов
        List<Map<String, String>> devices = List.of(
                Map.of("kind", "audioinput", "deviceId", generateHash("mic"), "label", "Microphone"),
                Map.of("kind", "videoinput", "deviceId", generateHash("cam"), "label", "Camera front")
        );
        profile.setMediaDevicesJson(objectMapper.writeValueAsString(devices));

        // --- 7. WebRTC ---
        profile.setWebrtcMode("FAKE_PUBLIC");
        profile.setWebrtcLocalIp(generateRealisticLocalIp());
        profile.setWebrtcPublicIp("CHANGE_ME");

        // --- 8. WebGL Extensions ---
// Используем динамический список на основе выбранного рендерера
        List<String> dynamicExt = getExtensionsForGpu(profile.getWebglRenderer());
        profile.setWebglExtensionsJson(objectMapper.writeValueAsString(dynamicExt));

        // --- 9. Geolocation & Permissions ---
        profile.setGeoPermission("PROMPT");
        profile.setGeoAccuracy(20.0);
        profile.setGeoLatitude(0.0);
        profile.setGeoLongitude(0.0);
        profile.setNotificationPermission("default");

        // --- 10. Fonts ---
        profile.setFontsListJson(objectMapper.writeValueAsString(List.of("Roboto", "Noto Sans")));

        // --- 11. DNS & Scripts (Пустые/Нулевые) ---
        profile.setDnsServersJson("[]");
        profile.setInjectionScript(null);

        // --- 12. Device Profile JSON (Полный слепок шаблона) ---
        profile.setDeviceProfileJson(objectMapper.writeValueAsString(template));
    }

    // --- Вспомогательные методы ---

    private String generateUserAgent(DeviceTemplate t) {
        return String.format(
                "Mozilla/5.0 (Linux; Android %s; %s) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/%s Mobile Safari/537.36",
                t.getAndroidVer(), t.getModel(), CHROME_FULL
        );
    }

    private DeviceTemplate selectTemplateInternal(String deviceType) {
        DeviceTemplate t = findTemplate(deviceType);
        return (t != null) ? t : ANDROID_DEVICE_TEMPLATES.get(random.nextInt(ANDROID_DEVICE_TEMPLATES.size()));
    }

    @SneakyThrows
    private ClientHintsData generateClientHints(DeviceTemplate t) {
        ArrayNode brands = objectMapper.createArrayNode();

        // Список вариантов для "Grease" бренда (алгоритм Chrome периодически их меняет)
        List<String> greaseOptions = List.of("Not(A:Brand", "Not_A Brand", "Not/A)Brand");
        String selectedGrease = greaseOptions.get(random.nextInt(greaseOptions.size()));
        String greaseVersion = random.nextBoolean() ? CHROME_MAJOR : "99";

        // Chrome обычно перемешивает порядок брендов
        List<Map<String, String>> brandsList = new ArrayList<>();
        brandsList.add(Map.of("brand", selectedGrease, "version", greaseVersion));
        brandsList.add(Map.of("brand", "Chromium", "version", CHROME_MAJOR));
        brandsList.add(Map.of("brand", "Google Chrome", "version", CHROME_MAJOR));

        Collections.shuffle(brandsList); // Перемешиваем для естественности

        for (Map<String, String> b : brandsList) {
            brands.addObject().put("brand", b.get("brand")).put("version", b.get("version"));
        }

        return ClientHintsData.builder()
                .brandsJson(objectMapper.writeValueAsString(brands))
                .platformVersion(t.getAndroidVerFull())
                .build();
    }

    private DeviceTemplate findTemplate(String modelOrName) {
        if (modelOrName == null) return null;
        return ANDROID_DEVICE_TEMPLATES.stream()
                .filter(t -> t.getModel().equalsIgnoreCase(modelOrName) ||
                        t.getMarketingName().equalsIgnoreCase(modelOrName))
                .findFirst()
                .orElse(null);
    }

    private Double calculateRiskForDetectionLevel(String level) {
        return switch (level != null ? level.toUpperCase() : "") {
            case "BASIC" -> 0.4;
            case "ENHANCED" -> 0.1;
            case "AGGRESSIVE" -> 0.01;
            default -> 0.2;
        };
    }

    private String generateHash(String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest((salt + UUID.randomUUID() + System.nanoTime()).getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash).substring(0, 16);
        } catch (Exception e) {
            return UUID.randomUUID().toString().substring(0, 16);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) sb.append('0');
            sb.append(hex);
        }
        return sb.toString();
    }

    private void fillGpuAndHardware(Profile profile, DeviceTemplate template) {
        // Авто-определение GPU на основе модели/бренда, если в шаблоне пусто
        if (template.getGpuVendor() == null) {
            if (template.getModel().contains("Pixel")) {
                profile.setWebglVendor("ARM");
                profile.setWebglRenderer("Mali-G710"); // Для новых Pixel
            } else if (template.getModel().startsWith("SM-S")) { // Флагманы Samsung
                profile.setWebglVendor("Qualcomm");
                profile.setWebglRenderer("Adreno (TM) 740");
            } else {
                profile.setWebglVendor("ARM");
                profile.setWebglRenderer("Mali-G68");
            }
        } else {
            profile.setWebglVendor(template.getGpuVendor());
            profile.setWebglRenderer(template.getGpuRenderer());
        }
    }

    private String generateRealisticLocalIp() {
        int type = random.nextInt(3);
        return switch (type) {
            case 0 -> "192.168.0." + (random.nextInt(253) + 2);   // TP-Link / D-Link
            case 1 -> "192.168.1." + (random.nextInt(253) + 2);   // ASUS / Keenetic
            case 2 -> "10.0.0." + (random.nextInt(253) + 2);      // Корпоративные/Apple
            default -> "192.168.1.15";
        };
    }

    private List<String> getExtensionsForGpu(String renderer) {
        List<String> common = new ArrayList<>(List.of(
                "EXT_blend_minmax", "EXT_sRGB", "OES_texture_float", "WEBGL_debug_renderer_info"
        ));

        if (renderer.contains("Adreno")) {
            common.addAll(List.of("GL_OES_EGL_image", "WEBGL_compressed_texture_atc"));
        } else if (renderer.contains("Mali")) {
            common.addAll(List.of("WEBGL_compressed_texture_astc", "EXT_color_buffer_half_float"));
        }
        return common;
    }

    /**
     * Проверка IP через DNSBL (DNS Blackhole List)
     */
    private boolean isIpBlacklisted(String ip) {
        try {
            String[] parts = ip.split("\\.");
            String reversedIp = parts[3] + "." + parts[2] + "." + parts[1] + "." + parts[0];
            for (String service : DNSBL_SERVICES) {
                try {
                    InetAddress.getByName(reversedIp + "." + service);
                    return true; // Если DNS нашел запись, значит IP в блэклисте
                } catch (UnknownHostException ignored) { }
            }
        } catch (Exception e) {
            log.error("DNSBL check error", e);
        }
        return false;
    }

    /**
     * Метод синхронизирует данные профиля с текущим прокси.
     * Если прокси отсутствует, устанавливаются данные для г. Новосибирск.
     */
    /**
     * Логика получения Geo-данных с проверкой прокси
     */
    private GeoIpResponse resolveGeoData(String proxyUrl) {
        if (proxyUrl == null || proxyUrl.isBlank()) {
            return createDefaultNskResponse();
        }

        ProxyValidationResult validation = validateProxyDeep(proxyUrl);
        if (!validation.isAlive()) {
            return createDefaultNskResponse();
        }

        try {
            String host = extractHostFromProxy(proxyUrl);
            GeoIpResponse response = restTemplate.getForObject(String.format(GEO_API_URL, host), GeoIpResponse.class);

            if (response != null && "success".equals(response.getStatus())) {
                // Проверка по блэклистам
                response.setBlacklisted(isIpBlacklisted(host));
                return response;
            }
        } catch (Exception e) {
            log.error("GeoIP error: {}", e.getMessage());
        }
        return createDefaultNskResponse();
    }


    /**
     * Проверка работоспособности прокси (через Socket)
     */
    private boolean isProxyAlive(String proxyUrl) {
        try {
            String host = extractHostFromProxy(proxyUrl);
            int port = extractPortFromProxy(proxyUrl);

            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new InetSocketAddress(host, port), 5000); // 5 секунд таймаут
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Настройка локали, времени и координат
     */
    private void applyLocationData(Profile profile, GeoIpResponse geo, String proxyUrl) {
        profile.setProxyUrl(proxyUrl);
        profile.setTimezone(geo.getTimezone());
        profile.setWebrtcPublicIp(geo.getQuery() != null ? geo.getQuery() : "CHANGE_ME");

        // Вычисляем Timezone Offset (JS Style)
        ZoneId zoneId = ZoneId.of(geo.getTimezone());
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        int offset = now.getZone().getRules().getOffset(now.toInstant()).getTotalSeconds() / 60 * -1;
        profile.setTimezoneOffset(offset);

        // Геолокация
        profile.setGeoLatitude(geo.getLat() + (random.nextDouble() * 0.005));
        profile.setGeoLongitude(geo.getLon() + (random.nextDouble() * 0.005));
        profile.setGeoAccuracy(20.0);
        profile.setGeoPermission("ALLOW");

        // Язык и локаль
        String baseLang = "RU".equalsIgnoreCase(geo.getCountryCode()) ? "ru-RU" : "en-US";
        profile.setLocale(baseLang);
        profile.setLanguage(baseLang + "," + baseLang.substring(0, 2) + ";q=0.9,en;q=0.8");
    }

    private GeoIpResponse createDefaultNskResponse() {
        GeoIpResponse res = new GeoIpResponse();
        res.setStatus("success");
        res.setCountryCode("RU");
        res.setCity("Novosibirsk");
        res.setTimezone("Asia/Novosibirsk");
        res.setLat(55.0084);
        res.setLon(82.9357);
        res.setQuery("127.0.0.1");
        return res;
    }

    // Вспомогательные методы парсинга строк прокси...
    private String extractHostFromProxy(String url) {
        String clean = url.replaceAll(".*@", "").replaceAll("http://|https://", "");
        return clean.contains(":") ? clean.split(":")[0] : clean;
    }

    private int extractPortFromProxy(String url) {
        try {
            String clean = url.replaceAll(".*@", "");
            return Integer.parseInt(clean.split(":")[1]);
        } catch (Exception e) { return 80; }
    }

    @Data
    @Builder
    private static class DeviceTemplate implements Serializable {
        private String model;
        private String marketingName;
        private String androidVer;
        private String androidVerFull;
        private Integer width;
        private Integer height;
        private Double pixelRatio;
        private String gpuVendor;
        private String gpuRenderer;
        private int ram;
        private int cpuCores;
    }

    @Data
    @Builder
    private static class ClientHintsData {
        private String brandsJson;
        private String platformVersion;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class GeoIpResponse {
        private String status;      // "success"
        private String countryCode; // "RU"
        private String city;        // "Novosibirsk"
        private String timezone;    // "Asia/Novosibirsk"
        private Double lat;         // 55.0084
        private Double lon;         // 82.9357
        private String query;       // IP адрес
        private boolean isBlacklisted;
    }

    @Data
    @Builder
    public static class ProxyValidationResult {
        private boolean alive;
        private long speedMs; // Скорость отклика
        private String realIp;
        private boolean isProxy; // Проверка заголовков (Via, Proxy-Connection)
    }


    /**
     * Глубокая проверка прокси: проверяем реальный проход трафика
     */
    private ProxyValidationResult validateProxyDeep(String proxyUrl) {
        long startTime = System.currentTimeMillis();
        try {
            String host = extractHostFromProxy(proxyUrl);
            int port = extractPortFromProxy(proxyUrl);
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));

            HttpURLConnection conn = (HttpURLConnection) new URL("http://www.google.com/generate_204").openConnection(proxy);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() > 0) {
                return ProxyValidationResult.builder().alive(true).speedMs(System.currentTimeMillis() - startTime).build();
            }
        } catch (Exception e) {
            log.warn("Proxy check failed: {}", proxyUrl);
        }
        return ProxyValidationResult.builder().alive(false).build();
    }

    public String generateRandomWebsitesJson() { // Убрали Profile из аргументов, просто генерим строку
        List<String> pool = List.of(
                "https://www.google.com", "https://www.wikipedia.org",
                "https://www.reddit.com", "https://www.amazon.com",
                "https://www.youtube.com", "https://www.bing.com",
                "https://www.yahoo.com", "https://www.imdb.com"
        );

        List<String> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled);
        List<String> randomSites = shuffled.subList(0, 3 + new Random().nextInt(3));

        try {
            return new ObjectMapper().writeValueAsString(randomSites);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize websites", e);
            return "[]"; // Возвращаем пустой массив в случае ошибки
        }
    }

    public void assignUserDataPath(Profile profile) {
        // Создаем путь вида: ./profiles/profile_28
        String path = "./profiles/profile_" + profile.getId();
        profile.setUserDataPath(path);
    }

}

//@Slf4j
//@Component
//@RequiredArgsConstructor
//public class FingerprintGenerator {
//
//    private final ObjectMapper objectMapper;
//
//    /**
//     * Плейсхолдер для "неизвестной" версии Chrome.
//     * Если в профиле стоит это значение — BrowserContainerService сможет заменить chromeVersion на runtime-версию.
//     */
//    private static final String CHROME_PLACEHOLDER = "0.0.0.0";
//
//    // ---------------------------
//    // Android templates (Chrome Mobile)
//    // ---------------------------
//    private static final List<DeviceTemplate> ANDROID_DEVICE_TEMPLATES = List.of(
//            DeviceTemplate.builder()
//                    .model("SM-S918B").marketingName("Samsung Galaxy S23 Ultra")
//                    .androidVer("13").androidVerFull("13.0.0")
//                    .width(412).height(915).pixelRatio(3.0) // WQHD+ downscaled
//                    .gpuVendor("Qualcomm").gpuRenderer("Adreno (TM) 740")
//                    .ram(12)
//                    .build(),
//
//            // 2. Google Pixel 7 Pro (Mali-G710)
//            DeviceTemplate.builder()
//                    .model("Pixel 7 Pro").marketingName("Google Pixel 7 Pro")
//                    .androidVer("13").androidVerFull("13.0.0")
//                    .width(412).height(892).pixelRatio(3.5)
//                    .gpuVendor("ARM").gpuRenderer("Mali-G710")
//                    .ram(12)
//                    .build(),
//
//            // 3. Xiaomi 13 (Adreno 740)
//            DeviceTemplate.builder()
//                    .model("2211133G").marketingName("Xiaomi 13")
//                    .androidVer("13").androidVerFull("13.0.0")
//                    .width(393).height(851).pixelRatio(3.0)
//                    .gpuVendor("Qualcomm").gpuRenderer("Adreno (TM) 740")
//                    .ram(8)
//                    .build(),
//
//            // 4. Samsung Galaxy A54 (Mali-G68) - Популярный средний класс
//            DeviceTemplate.builder()
//                    .model("SM-A546B").marketingName("Samsung Galaxy A54 5G")
//                    .androidVer("13").androidVerFull("13.0.0")
//                    .width(384).height(824).pixelRatio(2.625) // Нестандартный ratio
//                    .gpuVendor("ARM").gpuRenderer("Mali-G68")
//                    .ram(8)
//                    .build(),
//
//            // 5. OnePlus 11 (Adreno 740)
//            DeviceTemplate.builder()
//                    .model("CPH2449").marketingName("OnePlus 11")
//                    .androidVer("13").androidVerFull("13.0.0")
//                    .width(360).height(800).pixelRatio(3.0)
//                    .gpuVendor("Qualcomm").gpuRenderer("Adreno (TM) 740")
//                    .ram(16)
//                    .build()
//    );
//
//
//
//    /** 100% Android */
//    public DeviceProfile generateRandomMobileProfile() {
//        Random r = new Random();
//        DeviceTemplate t = ANDROID_DEVICE_TEMPLATES.get(r.nextInt(ANDROID_DEVICE_TEMPLATES.size()));
//        DeviceProfile dp = buildDeviceProfileFromTemplate(t);
//        dp = applyMicroVariations(dp);
//        return dp;
//    }
//
//    private DeviceProfile buildDeviceProfileFromTemplate(DeviceTemplate t) {
//        return DeviceProfile.builder()
//                .deviceType(t.getModel())
//                .userAgent(sanitizeAndroidUa(t.getUserAgent()))
//                .width(t.getWidth())
//                .height(t.getHeight())
//                .pixelRatio(t.getPixelRatio())
//                .platform(sanitizeAndroidPlatform(t.getPlatform()))
//                .maxTouchPoints(t.getMaxTouchPoints())
//                .hardwareConcurrency(t.getHardwareConcurrency())
//                .deviceMemory(t.getDeviceMemory())
//                .webglVendor(t.getWebglVendor())
//                .webglRenderer(t.getWebglRenderer())
//                .webglVersion(t.getWebglVersion())
//                .audioContextLatency(0.005 + Math.random() * 0.001)
//                .canvasFingerprint(generateCanvasHash())
//                .canvasNoiseHash(generateNoiseHash())
//                .vendor("Google Inc.")
//                .renderer(t.getWebglRenderer())
//                .generatedAt(Instant.now())
//                .withVariations(false)
//                .build();
//    }
//
//    private DeviceProfile applyMicroVariations(DeviceProfile dp) {
//        Random r = new Random();
//
//        double pr = dp.getPixelRatio() != null ? dp.getPixelRatio() : 2.0;
//        double prJitter = (r.nextDouble() * 0.05);
//        double newPr = Math.max(1.0, pr + (r.nextBoolean() ? prJitter : -prJitter));
//
//        double lat = dp.getAudioContextLatency() != null ? dp.getAudioContextLatency() : 0.005;
//        double latJitter = (r.nextDouble() * 0.0006);
//        double newLat = Math.max(0.001, lat + (r.nextBoolean() ? latJitter : -latJitter));
//
//        Integer w = dp.getWidth();
//        Integer h = dp.getHeight();
//        int wJ = r.nextInt(4);
//        int hJ = r.nextInt(6);
//
//        int newW = (w != null ? w : 412) + (r.nextBoolean() ? wJ : -wJ);
//        int newH = (h != null ? h : 915) + (r.nextBoolean() ? hJ : -hJ);
//
//        return DeviceProfile.builder()
//                .deviceType(dp.getDeviceType())
//                .userAgent(dp.getUserAgent())
//                .width(newW)
//                .height(newH)
//                .pixelRatio(newPr)
//                .platform(dp.getPlatform())
//                .maxTouchPoints(dp.getMaxTouchPoints())
//                .hardwareConcurrency(dp.getHardwareConcurrency())
//                .deviceMemory(dp.getDeviceMemory())
//                .webglVendor(dp.getWebglVendor())
//                .webglRenderer(dp.getWebglRenderer())
//                .webglVersion(dp.getWebglVersion())
//                .audioContextLatency(newLat)
//                .canvasFingerprint(dp.getCanvasFingerprint())
//                .canvasNoiseHash(dp.getCanvasNoiseHash())
//                .vendor(dp.getVendor())
//                .renderer(dp.getRenderer())
//                .generatedAt(Instant.now())
//                .withVariations(true)
//                .build();
//    }
//
//    public Profile generateCompleteProfile(String externalKey, String deviceType, String detectionLevel) {
//        log.info("Generating complete profile for key={}, deviceType={}, level={}", externalKey, deviceType, detectionLevel);
//
//        try {
//            DeviceProfile dp;
//
//            if (deviceType == null || deviceType.trim().isEmpty() || "random".equalsIgnoreCase(deviceType)) {
//                dp = generateRandomMobileProfile(); // 100% Android
//            } else {
//                dp = generateDeviceProfile(deviceType, detectionLevel);
//            }
//
//            Profile profile = new Profile();
//            profile.setExternalKey(externalKey);
//            profile.setName(getDisplayName(deviceType) + " - " + externalKey);
//            profile.setDetectionLevel(detectionLevel != null ? detectionLevel : "ENHANCED");
//            profile.setDetectionRisk(calculateRiskForDetectionLevel(profile.getDetectionLevel()));
//
//            // --- Жёсткая согласованность по Android ---
//            String cleanUa = sanitizeAndroidUa(dp.getUserAgent());
//            String cleanPlatform = sanitizeAndroidPlatform(dp.getPlatform());
//
//            profile.setUserAgent(cleanUa);
//            profile.setPlatform(cleanPlatform);
//
//            profile.setScreenWidth(dp.getWidth());
//            profile.setScreenHeight(dp.getHeight());
//            profile.setPixelRatio(dp.getPixelRatio());
//            profile.setHardwareConcurrency(dp.getHardwareConcurrency());
//            profile.setDeviceMemory(dp.getDeviceMemory());
//            profile.setMaxTouchPoints(dp.getMaxTouchPoints());
//            profile.setWebglVendor(dp.getWebglVendor());
//            profile.setWebglRenderer(dp.getWebglRenderer());
//            profile.setWebglVersion(dp.getWebglVersion());
//            profile.setAudioContextLatency(dp.getAudioContextLatency());
//            profile.setCanvasFingerprint(dp.getCanvasFingerprint());
//            profile.setCanvasNoiseHash(dp.getCanvasNoiseHash());
//
//            // OS
//            profile.setOsVersion(extractAndroidOsVersionFromUa(cleanUa));
//            profile.setOsArchitecture(guessOsArchitecture(cleanPlatform));
//
//            // JSON DeviceProfile
//            try {
//                profile.setDeviceProfileJson(objectMapper.writeValueAsString(dp));
//            } catch (Exception e) {
//                log.warn("Failed to serialize device profile JSON", e);
//            }
//
//            // рандомные параметры (Android-only)
//            setRandomParameters(profile);
//
//            profile.setFingerprintCreatedAt(Instant.now());
//            profile.setFingerprintUpdatedAt(Instant.now());
//            profile.updateFingerprintHash();
//            profile.setIsActive(true);
//            profile.setStatus("FREE");
//            profile.setCheckCount(0);
//
//            log.info("Generated profile: ua={}, screen={}x{}, platform={}",
//                    profile.getUserAgent(), profile.getScreenWidth(), profile.getScreenHeight(), profile.getPlatform());
//
//            return profile;
//
//        } catch (Exception e) {
//            log.error("Failed to generate complete profile for key={}", externalKey, e);
//            throw new RuntimeException("Failed to generate profile: " + e.getMessage(), e);
//        }
//    }
//
//    public DeviceProfile generateDeviceProfile(String deviceType, String detectionLevel) {
//        DeviceTemplate t = findTemplate(deviceType);
//        if (t == null) {
//            log.warn("Device template not found: {} -> random ANDROID", deviceType);
//            return generateRandomMobileProfile();
//        }
//
//        DeviceProfile dp = buildDeviceProfileFromTemplate(t);
//
//        boolean basic = detectionLevel != null && detectionLevel.equalsIgnoreCase("BASIC");
//        if (!basic) {
//            dp = applyMicroVariations(dp);
//        }
//
//        return dp;
//    }
//
//
//
//
//
//    // ----------------- Random parameters (Android-only) -----------------
//
//    private void setRandomParameters(Profile profile) {
//        Random random = new Random();
//
//        profile.setScreenAvailWidth(Math.max(0, safeInt(profile.getScreenWidth(), 412) - random.nextInt(12)));
//        profile.setScreenAvailHeight(Math.max(0, safeInt(profile.getScreenHeight(), 915) - random.nextInt(60)));
//        profile.setScreenColorDepth(24);
//        profile.setScreenPixelDepth(24);
//
//        String[] timezones = {"Europe/Moscow", "Asia/Tokyo"};
//        String[] languages = {"en-US", "ru-RU"};
//
//        String timezone = timezones[random.nextInt(timezones.length)];
//        String language = languages[random.nextInt(languages.length)];
//
//        profile.setTimezone(timezone);
//        profile.setLanguage(language);
//        profile.setLocale(language.replace("-", "_"));
//        profile.setTimezoneOffset(calculateTimezoneOffset(timezone));
//
//        profile.setCookieEnabled(true);
//        profile.setDoNotTrack("unspecified");
//        profile.setOnline(true);
//
//        // версии
//        String chrome = getChromeVersionFromUserAgent(profile.getUserAgent());
//        profile.setChromeVersion((chrome == null || CHROME_PLACEHOLDER.equals(chrome)) ? CHROME_PLACEHOLDER : chrome);
//
//        profile.setOsVersion(extractAndroidOsVersionFromUa(profile.getUserAgent()));
//        profile.setOsArchitecture(getOsArchitecture(profile.getPlatform()));
//
//        // audio
//        profile.setAudioSampleRate(48000);
//        profile.setAudioChannelCount("stereo");
//        profile.setAudioContextLatency(0.005 + random.nextDouble() * 0.001);
//
//        setJsonParameters(profile, random);
//    }
//
//    private void setJsonParameters(Profile profile, Random random) {
//        try {
//            Map<String, Object> batteryInfo = new HashMap<>();
//            batteryInfo.put("charging", random.nextBoolean());
//            batteryInfo.put("chargingTime", random.nextBoolean() ? 1800 : 0);
//            batteryInfo.put("dischargingTime", 7200 + random.nextInt(3600));
//            batteryInfo.put("level", 0.3 + random.nextDouble() * 0.6);
//            profile.setBatteryInfoJson(objectMapper.writeValueAsString(batteryInfo));
//
//            Map<String, Object> connectionInfo = new HashMap<>();
//            String[] connectionTypes = new String[]{"wifi", "cellular", "bluetooth"};
//            String[] effectiveTypes = new String[]{"4g", "3g", "2g"};
//
//            connectionInfo.put("downlink", 5.0 + random.nextDouble() * 10.0);
//            connectionInfo.put("effectiveType", effectiveTypes[random.nextInt(effectiveTypes.length)]);
//            connectionInfo.put("rtt", 50 + random.nextInt(150));
//            connectionInfo.put("saveData", random.nextBoolean());
//            connectionInfo.put("type", connectionTypes[random.nextInt(connectionTypes.length)]);
//            profile.setConnectionInfoJson(objectMapper.writeValueAsString(connectionInfo));
//
//            Map<String, Object> webglExtensions = new HashMap<>();
//            webglExtensions.put("EXT_blend_minmax", true);
//            webglExtensions.put("WEBGL_compressed_texture_s3tc", true);
//            webglExtensions.put("WEBGL_debug_renderer_info", false);
//            webglExtensions.put("WEBGL_depth_texture", true);
//            webglExtensions.put("WEBGL_lose_context", true);
//            profile.setWebglExtensionsJson(objectMapper.writeValueAsString(webglExtensions));
//
//            profile.setPluginsJson(objectMapper.writeValueAsString(Collections.emptyList()));
//
//            List<Map<String, Object>> mediaDevices = new ArrayList<>();
//            for (String kind : List.of("audioinput", "audiooutput", "videoinput")) {
//                Map<String, Object> device = new HashMap<>();
//                device.put("deviceId", "default");
//                device.put("groupId", "default-group");
//                device.put("kind", kind);
//                device.put("label", "");
//                mediaDevices.add(device);
//            }
//            profile.setMediaDevicesJson(objectMapper.writeValueAsString(mediaDevices));
//
//            profile.setFontsListJson(objectMapper.writeValueAsString(getAndroidFontsList()));
//
//            Map<String, Object> nav = new HashMap<>();
//            nav.put("cookieEnabled", true);
//            nav.put("doNotTrack", "unspecified");
//            nav.put("online", true);
//            nav.put("product", "Gecko");
//            nav.put("productSub", "20030107");
//            nav.put("vendor", "Google Inc.");
//            nav.put("vendorSub", "");
//            nav.put("platform", sanitizeAndroidPlatform(profile.getPlatform()));
//            profile.setNavigatorInfoJson(objectMapper.writeValueAsString(nav));
//
//            profile.setCommonWebsitesJson(objectMapper.writeValueAsString(List.of(
//                    "https://google.com",
//                    "https://youtube.com",
//                    "https://facebook.com",
//                    "https://amazon.com",
//                    "https://twitter.com"
//            )));
//
//            Map<String, Object> audioFp = new HashMap<>();
//            audioFp.put("sampleRate", 48000);
//            audioFp.put("channelCount", 2);
//            audioFp.put("latency", 0.005);
//            audioFp.put("noiseProfile", generateNoiseHash().substring(0, 16));
//            profile.setAudioFingerprintJson(objectMapper.writeValueAsString(audioFp));
//
//        } catch (Exception e) {
//            log.warn("Failed to set JSON parameters", e);
//        }
//    }
//
//    private List<String> getAndroidFontsList() {
//        return new ArrayList<>(List.of(
//                "Roboto", "Roboto Condensed", "Noto Sans", "Noto Sans UI",
//                "Droid Sans", "Arial", "Helvetica", "Verdana",
//                "Times New Roman", "Courier New", "Georgia"
//        ));
//    }
//
//    // ----------------- Санитайз Android UA / Platform -----------------
//
//    private String sanitizeAndroidUa(String ua) {
//        if (ua == null) return "";
//        return ua.trim().replaceAll("\\s{2,}", " ");
//    }
//
//    private String sanitizeAndroidPlatform(String platform) {
//        if (platform == null || platform.isBlank()) return "Linux armv8l";
//        String p = platform.trim();
//        if (p.equalsIgnoreCase("Android")) return "Linux armv8l";
//        return p;
//    }
//
//    // ----------------- Остальное -----------------
//
//    private DeviceTemplate findTemplate(String deviceType) {
//        for (DeviceTemplate t : ANDROID_DEVICE_TEMPLATES) {
//            if (t.getModel().equals(deviceType)) return t;
//        }
//        return null;
//    }
//
//    private String getDisplayName(String deviceType) {
//        if (deviceType == null || deviceType.isBlank() || "random".equalsIgnoreCase(deviceType)) {
//            return "Android Device";
//        }
//        DeviceTemplate t = findTemplate(deviceType);
//        return t != null ? t.getMarketingName() : deviceType;
//    }
//
//    private String generateCanvasHash() {
//        try {
//            MessageDigest md = MessageDigest.getInstance("MD5");
//            byte[] hash = md.digest((UUID.randomUUID() + ":" + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
//            return bytesToHex(hash).substring(0, 16);
//        } catch (Exception e) {
//            return "defaultCanvasHash";
//        }
//    }
//
//    private String generateNoiseHash() {
//        try {
//            MessageDigest md = MessageDigest.getInstance("SHA-256");
//            byte[] hash = md.digest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
//            return bytesToHex(hash).substring(0, 32);
//        } catch (Exception e) {
//            return "defaultNoiseHash";
//        }
//    }
//
//    private String bytesToHex(byte[] bytes) {
//        StringBuilder sb = new StringBuilder();
//        for (byte b : bytes) {
//            String hex = Integer.toHexString(0xff & b);
//            if (hex.length() == 1) sb.append('0');
//            sb.append(hex);
//        }
//        return sb.toString();
//    }
//
//    private Double calculateRiskForDetectionLevel(String detectionLevel) {
//        if (detectionLevel == null) return 0.2;
//        return switch (detectionLevel.toUpperCase()) {
//            case "BASIC" -> 0.3;
//            case "ENHANCED" -> 0.1;
//            case "AGGRESSIVE" -> 0.05;
//            default -> 0.2;
//        };
//    }
//
//    private String getChromeVersionFromUserAgent(String userAgent) {
//        if (userAgent == null) return null;
//        Matcher m = Pattern.compile("Chrome/(\\d+\\.\\d+\\.\\d+\\.\\d+)").matcher(userAgent);
//        return m.find() ? m.group(1) : null;
//    }
//
//    private String extractAndroidOsVersionFromUa(String ua) {
//        if (ua == null) return "0.0";
//        Matcher android = Pattern.compile("Android (\\d+)(?:\\.(\\d+))?").matcher(ua);
//        if (android.find()) {
//            String major = android.group(1);
//            String minor = android.group(2) != null ? android.group(2) : "0";
//            return major + "." + minor;
//        }
//        return "0.0";
//    }
//
//    private String guessOsArchitecture(String platform) {
//        if (platform == null) return "arm64";
//        String p = platform.toLowerCase();
//        if (p.contains("arm") || p.contains("aarch")) return "arm64";
//        if (p.contains("x64") || p.contains("amd64")) return "x64";
//        if (p.contains("x86") || p.contains("i386")) return "x86";
//        return "arm64";
//    }
//
//    private String getOsArchitecture(String platform) {
//        return guessOsArchitecture(platform);
//    }
//
//    private Integer calculateTimezoneOffset(String timezone) {
//        Map<String, Integer> tz = Map.of(
//                "Europe/Moscow", -180,
//                "Asia/Tokyo", -540,
//        );
//        return tz.getOrDefault(timezone, -180);
//    }
//
//    private int safeInt(Integer v, int def) {
//        return (v == null || v <= 0) ? def : v;
//    }
//
//    public List<String> getAvailableDeviceTypes() {
//        List<String> out = new ArrayList<>();
//        ANDROID_DEVICE_TEMPLATES.forEach(t -> out.add(t.getModel()));
//        out.add("random");
//        return out;
//    }
//
//    public Optional<DeviceProfile> getDeviceProfileTemplate(String deviceType) {
//        DeviceTemplate t = findTemplate(deviceType);
//        if (t == null) return Optional.empty();
//        return Optional.of(buildDeviceProfileFromTemplate(t));
//    }
//
//    @lombok.Data
//    @lombok.Builder
//    private static class DeviceTemplate {
//        private String model;
//        private String marketingName;
//        private String androidVer;
//        private String androidVerFull;
//        private String userAgent;
//        private Integer width;
//        private Integer height;
//        private Double pixelRatio;
//        private String platform;
//        private Integer maxTouchPoints;
//        private Integer hardwareConcurrency;
//        private Integer deviceMemory;
//        private String gpuVendor;
//        private String gpuRenderer;
//        private int ram;
//        private String webglVendor;
//        private String webglRenderer;
//        private String webglVersion;
//        private String osVersion;
//        private String osArchitecture;
//    }
//}


//DeviceTemplate.builder()
//                    .deviceType("samsung_galaxy_s23")
//                    .displayName("Samsung Galaxy S23")
//                    .userAgent("Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + CHROME_PLACEHOLDER + " Mobile Safari/537.36")
//                    .width(412).height(915).pixelRatio(2.63)
//                    .platform("Linux armv8l")
//                    .maxTouchPoints(5)
//                    .hardwareConcurrency(8)
//                    .deviceMemory(8)
//                    .webglVendor("Google Inc. (Qualcomm)")
//                    .webglRenderer("Adreno (TM) 740")
//                    .webglVersion("WebGL 2.0")
//                    .osVersion("13.0")
//                    .osArchitecture("arm64")
//                    .build(),
//
//            DeviceTemplate.builder()
//                    .deviceType("google_pixel_7")
//                    .displayName("Google Pixel 7")
//                    .userAgent("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + CHROME_PLACEHOLDER + " Mobile Safari/537.36")
//                    .width(412).height(915).pixelRatio(2.63)
//                    .platform("Linux armv8l")
//                    .maxTouchPoints(5)
//                    .hardwareConcurrency(8)
//                    .deviceMemory(8)
//                    .webglVendor("Google Inc. (Google)")
//                    .webglRenderer("Mali-G710")
//                    .webglVersion("WebGL 2.0")
//                    .osVersion("13.0")
//                    .osArchitecture("arm64")
//                    .build(),
//
//            DeviceTemplate.builder()
//                    .deviceType("xiaomi_13")
//                    .displayName("Xiaomi 13")
//                    .userAgent("Mozilla/5.0 (Linux; Android 13; 2211133G) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + CHROME_PLACEHOLDER + " Mobile Safari/537.36")
//                    .width(412).height(915).pixelRatio(2.63)
//                    .platform("Linux armv8l")
//                    .maxTouchPoints(5)
//                    .hardwareConcurrency(8)
//                    .deviceMemory(8)
//                    .webglVendor("Google Inc. (Qualcomm)")
//                    .webglRenderer("Adreno (TM) 730")
//                    .webglVersion("WebGL 2.0")
//                    .osVersion("13.0")
//                    .osArchitecture("arm64")
//                    .build(),
//
//            DeviceTemplate.builder()
//                    .deviceType("samsung_galaxy_tab_s8")
//                    .displayName("Samsung Galaxy Tab S8")
//                    .userAgent("Mozilla/5.0 (Linux; Android 13; SM-X706B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + CHROME_PLACEHOLDER + " Safari/537.36")
//                    .width(800).height(1280).pixelRatio(2.0)
//                    .platform("Linux armv8l")
//                    .maxTouchPoints(5)
//                    .hardwareConcurrency(8)
//                    .deviceMemory(8)
//                    .webglVendor("Google Inc. (Qualcomm)")
//                    .webglRenderer("Adreno (TM) 660")
//                    .webglVersion("WebGL 2.0")
//                    .osVersion("13.0")
//                    .osArchitecture("arm64")
//                    .build()


//@Slf4j
//@Component
//@RequiredArgsConstructor
//public class FingerprintGenerator {
//
//    private final ObjectMapper objectMapper;
//
//    // Список мобильных устройств для случайного выбора
//    private static final List<DeviceTemplate> MOBILE_DEVICE_TEMPLATES = Arrays.asList(
//            // iOS устройства
//            DeviceTemplate.builder()
//                    .deviceType("iphone_14_pro")
//                    .displayName("iPhone 14 Pro")
//                    .userAgent("Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1")
//                    .width(393)
//                    .height(852)
//                    .pixelRatio(3.0)
//                    .platform("iPhone")
//                    .maxTouchPoints(5)
//                    .hardwareConcurrency(6)
//                    .deviceMemory(4)
//                    .webglVendor("Apple Inc.")
//                    .webglRenderer("Apple GPU")
//                    .webglVersion("WebGL 2.0")
//                    .osVersion("16.0")
//                    .osArchitecture("arm64")
//                    .build(),
//
//            DeviceTemplate.builder()
//                    .deviceType("iphone_13")
//                    .displayName("iPhone 13")
//                    .userAgent("Mozilla/5.0 (iPhone; CPU iPhone OS 15_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.0 Mobile/15E148 Safari/604.1")
//                    .width(390)
//                    .height(844)
//                    .pixelRatio(3.0)
//                    .platform("iPhone")
//                    .maxTouchPoints(5)
//                    .hardwareConcurrency(6)
//                    .deviceMemory(4)
//                    .webglVendor("Apple Inc.")
//                    .webglRenderer("Apple GPU")
//                    .webglVersion("WebGL 2.0")
//                    .osVersion("15.0")
//                    .osArchitecture("arm64")
//                    .build(),
//
//            DeviceTemplate.builder()
//                    .deviceType("ipad_pro")
//                    .displayName("iPad Pro")
//                    .userAgent("Mozilla/5.0 (iPad; CPU OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1")
//                    .width(1024)
//                    .height(1366)
//                    .pixelRatio(2.0)
//                    .platform("iPad")
//                    .maxTouchPoints(5)
//                    .hardwareConcurrency(8)
//                    .deviceMemory(6)
//                    .webglVendor("Apple Inc.")
//                    .webglRenderer("Apple GPU")
//                    .webglVersion("WebGL 2.0")
//                    .osVersion("16.0")
//                    .osArchitecture("arm64")
//                    .build(),
//
//            // Android устройства
//            DeviceTemplate.builder()
//                    .deviceType("samsung_galaxy_s23")
//                    .displayName("Samsung Galaxy S23")
//                    .userAgent("Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36")
//                    .width(412)
//                    .height(915)
//                    .pixelRatio(2.63)
//                    .platform("Linux armv8l")
//                    .maxTouchPoints(5)
//                    .hardwareConcurrency(8)
//                    .deviceMemory(8)
//                    .webglVendor("Google Inc. (Qualcomm)")
//                    .webglRenderer("Adreno (TM) 740")
//                    .webglVersion("WebGL 2.0")
//                    .osVersion("13.0")
//                    .osArchitecture("arm64")
//                    .build(),
//
//            DeviceTemplate.builder()
//                    .deviceType("google_pixel_7")
//                    .displayName("Google Pixel 7")
//                    .userAgent("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36")
//                    .width(412)
//                    .height(915)
//                    .pixelRatio(2.63)
//                    .platform("Linux armv8l")
//                    .maxTouchPoints(5)
//                    .hardwareConcurrency(8)
//                    .deviceMemory(8)
//                    .webglVendor("Google Inc. (Google)")
//                    .webglRenderer("Mali-G710")
//                    .webglVersion("WebGL 2.0")
//                    .osVersion("13.0")
//                    .osArchitecture("arm64")
//                    .build(),
//
//            DeviceTemplate.builder()
//                    .deviceType("xiaomi_13")
//                    .displayName("Xiaomi 13")
//                    .userAgent("Mozilla/5.0 (Linux; Android 13; 2211133G) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36")
//                    .width(412)
//                    .height(915)
//                    .pixelRatio(2.63)
//                    .platform("Linux armv8l")
//                    .maxTouchPoints(5)
//                    .hardwareConcurrency(8)
//                    .deviceMemory(8)
//                    .webglVendor("Google Inc. (Qualcomm)")
//                    .webglRenderer("Adreno (TM) 730")
//                    .webglVersion("WebGL 2.0")
//                    .osVersion("13.0")
//                    .osArchitecture("arm64")
//                    .build(),
//
//            DeviceTemplate.builder()
//                    .deviceType("samsung_galaxy_tab_s8")
//                    .displayName("Samsung Galaxy Tab S8")
//                    .userAgent("Mozilla/5.0 (Linux; Android 13; SM-X706B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Safari/537.36")
//                    .width(800)
//                    .height(1280)
//                    .pixelRatio(2.0)
//                    .platform("Linux armv8l")
//                    .maxTouchPoints(5)
//                    .hardwareConcurrency(8)
//                    .deviceMemory(8)
//                    .webglVendor("Google Inc. (Qualcomm)")
//                    .webglRenderer("Adreno (TM) 660")
//                    .webglVersion("WebGL 2.0")
//                    .osVersion("13.0")
//                    .osArchitecture("arm64")
//                    .build()
//    );
//
//    // Шаблон для Windows PC (на всякий случай)
//    private static final DeviceTemplate WINDOWS_TEMPLATE = DeviceTemplate.builder()
//            .deviceType("windows_pc")
//            .displayName("Windows PC")
//            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
//            .width(1920)
//            .height(1080)
//            .pixelRatio(1.0)
//            .platform("Win32")
//            .maxTouchPoints(0)
//            .hardwareConcurrency(8)
//            .deviceMemory(8)
//            .webglVendor("Google Inc.")
//            .webglRenderer("ANGLE (Intel, Mesa Intel(R) UHD Graphics 630 (CML GT2), OpenGL 4.6)")
//            .webglVersion("WebGL 1.0")
//            .osVersion("10.0.22631")
//            .osArchitecture("x64")
//            .build();
//
//    /**
//     * Генерирует полный профиль с fingerprint
//     */
//    public Profile generateCompleteProfile(String externalKey, String deviceType, String detectionLevel) {
//        log.info("Generating complete profile for key: {}, deviceType: {}, level: {}",
//                externalKey, deviceType, detectionLevel);
//
//        try {
//            // 1. Получаем DeviceProfile
//            DeviceProfile deviceProfile;
//            if ("random".equalsIgnoreCase(deviceType) || deviceType == null || deviceType.trim().isEmpty()) {
//                deviceProfile = generateRandomMobileProfile();
//                log.info("Generated random mobile profile for key: {}", externalKey);
//            } else {
//                deviceProfile = generateDeviceProfile(deviceType, detectionLevel);
//                log.info("Generated device profile for type: {}, key: {}", deviceType, externalKey);
//            }
//
//            // 2. Создаем профиль
//            Profile profile = new Profile();
//            profile.setExternalKey(externalKey);
//            profile.setName(getDisplayName(deviceType) + " - " + externalKey);
//            profile.setDetectionLevel(detectionLevel != null ? detectionLevel : "ENHANCED");
//            profile.setDetectionRisk(calculateRiskForDetectionLevel(detectionLevel));
//
//            // 3. Заполняем базовые поля из DeviceProfile
//            profile.setUserAgent(deviceProfile.getUserAgent());
//            profile.setScreenWidth(deviceProfile.getWidth());
//            profile.setScreenHeight(deviceProfile.getHeight());
//            profile.setPixelRatio(deviceProfile.getPixelRatio());
//            profile.setPlatform(deviceProfile.getPlatform());
//            profile.setHardwareConcurrency(deviceProfile.getHardwareConcurrency());
//            profile.setDeviceMemory(deviceProfile.getDeviceMemory());
//            profile.setMaxTouchPoints(deviceProfile.getMaxTouchPoints());
//            profile.setWebglVendor(deviceProfile.getWebglVendor());
//            profile.setWebglRenderer(deviceProfile.getWebglRenderer());
//            profile.setWebglVersion(deviceProfile.getWebglVersion());
//            profile.setAudioContextLatency(deviceProfile.getAudioContextLatency());
//            profile.setCanvasFingerprint(deviceProfile.getCanvasFingerprint());
//            profile.setCanvasNoiseHash(deviceProfile.getCanvasNoiseHash());
//
//            // 4. Устанавливаем DeviceProfile JSON
//            try {
//                profile.setDeviceProfileJson(objectMapper.writeValueAsString(deviceProfile));
//            } catch (Exception e) {
//                log.warn("Failed to serialize device profile to JSON", e);
//            }
//
//            // 5. Устанавливаем рандомные параметры
//            setRandomParameters(profile, deviceProfile.isMobile());
//
//            // 6. Устанавливаем метаданные
//            profile.setFingerprintCreatedAt(Instant.now());
//            profile.setFingerprintUpdatedAt(Instant.now());
//            profile.updateFingerprintHash();
//            profile.setIsActive(true);
//            profile.setStatus("FREE");
//            profile.setCheckCount(0);
//
//            log.info("Generated complete profile: userAgent={}, screen={}x{}, platform={}",
//                    profile.getUserAgent(), profile.getScreenWidth(),
//                    profile.getScreenHeight(), profile.getPlatform());
//
//            return profile;
//
//        } catch (Exception e) {
//            log.error("Failed to generate complete profile for key: {}", externalKey, e);
//            throw new RuntimeException("Failed to generate profile: " + e.getMessage(), e);
//        }
//    }
//
//    /**
//     * Генерирует DeviceProfile для указанного типа устройства
//     */
//    public DeviceProfile generateDeviceProfile(String deviceType, String detectionLevel) {
//        log.debug("Generating device profile for type: {}, level: {}", deviceType, detectionLevel);
//
//        DeviceTemplate template = findTemplate(deviceType);
//        if (template == null) {
//            log.warn("Device template not found for type: {}, using random mobile", deviceType);
//            return generateRandomMobileProfile();
//        }
//
//        DeviceProfile deviceProfile = buildDeviceProfileFromTemplate(template);
//
//        // Применяем микро-вариации если уровень не BASIC
//        if (detectionLevel != null && !detectionLevel.equalsIgnoreCase("BASIC")) {
//            deviceProfile = deviceProfile.withMicroVariations();
//        }
//
//        return deviceProfile;
//    }
//
//    /**
//     * Генерирует случайный мобильный DeviceProfile
//     */
//    public DeviceProfile generateRandomMobileProfile() {
//        Random random = new Random();
//        DeviceTemplate template = MOBILE_DEVICE_TEMPLATES.get(random.nextInt(MOBILE_DEVICE_TEMPLATES.size()));
//
//        log.debug("Generating random mobile profile from template: {}", template.getDeviceType());
//
//        DeviceProfile deviceProfile = buildDeviceProfileFromTemplate(template);
//
//        // Добавляем микро-вариации для уникальности
//        return deviceProfile.withMicroVariations();
//    }
//
//    /**
//     * Строит DeviceProfile из шаблона
//     */
//    private DeviceProfile buildDeviceProfileFromTemplate(DeviceTemplate template) {
//        return DeviceProfile.builder()
//                .deviceType(template.getDeviceType())
//                .userAgent(template.getUserAgent())
//                .width(template.getWidth())
//                .height(template.getHeight())
//                .pixelRatio(template.getPixelRatio())
//                .platform(template.getPlatform())
//                .maxTouchPoints(template.getMaxTouchPoints())
//                .hardwareConcurrency(template.getHardwareConcurrency())
//                .deviceMemory(template.getDeviceMemory())
//                .webglVendor(template.getWebglVendor())
//                .webglRenderer(template.getWebglRenderer())
//                .webglVersion(template.getWebglVersion())
//                .audioContextLatency(0.005 + Math.random() * 0.001) // 5-6ms
//                .canvasFingerprint(generateCanvasHash())
//                .canvasNoiseHash(generateNoiseHash())
//                .vendor(getVendorFromPlatform(template.getPlatform()))
//                .renderer(template.getWebglRenderer())
//                .generatedAt(Instant.now())
//                .withVariations(false)
//                .build();
//    }
//
//    /**
//     * Устанавливает рандомные параметры для профиля
//     */
//    private void setRandomParameters(Profile profile, boolean isMobile) {
//        Random random = new Random();
//
//        // Устанавливаем общие параметры
//        profile.setScreenAvailWidth(profile.getScreenWidth() - random.nextInt(20));
//        profile.setScreenAvailHeight(profile.getScreenHeight() - random.nextInt(80));
//        profile.setScreenColorDepth(24);
//        profile.setScreenPixelDepth(24);
//
//        // Устанавливаем временные параметры
//        String[] timezones = {"Europe/Moscow","Asia/Tokyo"};
//        String[] languages = {"en-US", "ru-RU"};
//
//        String timezone = timezones[random.nextInt(timezones.length)];
//        String language = languages[random.nextInt(languages.length)];
//
//        profile.setTimezone(timezone);
//        profile.setLanguage(language);
//        profile.setLocale(language.replace("-", "_"));
//        profile.setTimezoneOffset(calculateTimezoneOffset(timezone));
//
//        // Устанавливаем навигатор свойства
//        profile.setCookieEnabled(true);
//        profile.setDoNotTrack("unspecified");
//        profile.setOnline(true);
//
//        // Устанавливаем версии
//        profile.setChromeVersion(getChromeVersionFromUserAgent(profile.getUserAgent()));
//        profile.setOsVersion(getOsVersionFromUserAgent(profile.getUserAgent()));
//        profile.setOsArchitecture(getOsArchitecture(profile.getPlatform()));
//
//        // Устанавливаем аудио параметры
//        profile.setAudioSampleRate(48000);
//        profile.setAudioChannelCount("stereo");
//        profile.setAudioContextLatency(0.005 + random.nextDouble() * 0.001);
//
//        // Устанавливаем дополнительные JSON поля
//        setJsonParameters(profile, isMobile, random);
//    }
//
//    /**
//     * Устанавливает JSON параметры
//     */
//    private void setJsonParameters(Profile profile, boolean isMobile, Random random) {
//        try {
//            // Battery info
//            Map<String, Object> batteryInfo = new HashMap<>();
//            batteryInfo.put("charging", random.nextBoolean());
//            batteryInfo.put("chargingTime", random.nextBoolean() ? 1800 : 0);
//            batteryInfo.put("dischargingTime", 7200 + random.nextInt(3600));
//            batteryInfo.put("level", 0.3 + random.nextDouble() * 0.6);
//            profile.setBatteryInfoJson(objectMapper.writeValueAsString(batteryInfo));
//
//            // Connection info
//            Map<String, Object> connectionInfo = new HashMap<>();
//            String[] connectionTypes = isMobile ? new String[]{"wifi", "cellular", "bluetooth"}
//                    : new String[]{"wifi", "ethernet", "bluetooth"};
//            String[] effectiveTypes = isMobile ? new String[]{"4g", "3g", "2g"} : new String[]{"4g", "3g"};
//
//            connectionInfo.put("downlink", isMobile ? 5.0 + random.nextDouble() * 10.0 : 50.0 + random.nextDouble() * 50.0);
//            connectionInfo.put("effectiveType", effectiveTypes[random.nextInt(effectiveTypes.length)]);
//            connectionInfo.put("rtt", isMobile ? 50 + random.nextInt(150) : 20 + random.nextInt(80));
//            connectionInfo.put("saveData", random.nextBoolean());
//            connectionInfo.put("type", connectionTypes[random.nextInt(connectionTypes.length)]);
//            profile.setConnectionInfoJson(objectMapper.writeValueAsString(connectionInfo));
//
//            // WebGL extensions
//            Map<String, Object> webglExtensions = new HashMap<>();
//            webglExtensions.put("EXT_blend_minmax", true);
//            webglExtensions.put("WEBGL_compressed_texture_s3tc", true);
//            webglExtensions.put("WEBGL_debug_renderer_info", false);
//            webglExtensions.put("WEBGL_depth_texture", true);
//            webglExtensions.put("WEBGL_lose_context", true);
//            profile.setWebglExtensionsJson(objectMapper.writeValueAsString(webglExtensions));
//
//            // Plugins
//            List<Map<String, Object>> plugins = new ArrayList<>();
//            Map<String, Object> pdfPlugin = new HashMap<>();
//            pdfPlugin.put("name", "PDF Viewer");
//            pdfPlugin.put("filename", "internal-pdf-viewer");
//            pdfPlugin.put("description", "Portable Document Format");
//            pdfPlugin.put("length", 1);
//            plugins.add(pdfPlugin);
//
//            if (!isMobile) {
//                Map<String, Object> chromePlugin = new HashMap<>();
//                chromePlugin.put("name", "Chrome PDF Viewer");
//                chromePlugin.put("filename", "mhjfbmdgcfjbbpaeojofohoefgiehjai");
//                chromePlugin.put("description", "Portable Document Format");
//                chromePlugin.put("length", 1);
//                plugins.add(chromePlugin);
//            }
//            profile.setPluginsJson(objectMapper.writeValueAsString(plugins));
//
//            // Media devices
//            List<Map<String, Object>> mediaDevices = new ArrayList<>();
//            String[] deviceKinds = {"audioinput", "audiooutput", "videoinput"};
//            for (String kind : deviceKinds) {
//                Map<String, Object> device = new HashMap<>();
//                device.put("deviceId", "default");
//                device.put("groupId", "default-group");
//                device.put("kind", kind);
//                device.put("label", "");
//                mediaDevices.add(device);
//            }
//            profile.setMediaDevicesJson(objectMapper.writeValueAsString(mediaDevices));
//
//            // Fonts list
//            List<String> fonts = getFontsList(isMobile);
//            profile.setFontsListJson(objectMapper.writeValueAsString(fonts));
//
//            // Navigator info
//            Map<String, Object> navigatorInfo = new HashMap<>();
//            navigatorInfo.put("cookieEnabled", true);
//            navigatorInfo.put("doNotTrack", "unspecified");
//            navigatorInfo.put("online", true);
//            navigatorInfo.put("product", "Gecko");
//            navigatorInfo.put("productSub", "20030107");
//            navigatorInfo.put("vendor", isMobile ? "Apple Inc." : "Google Inc.");
//            navigatorInfo.put("vendorSub", "");
//            profile.setNavigatorInfoJson(objectMapper.writeValueAsString(navigatorInfo));
//
//            // Common websites
//            List<String> websites = Arrays.asList(
//                    "https://google.com",
//                    "https://youtube.com",
//                    "https://facebook.com",
//                    "https://amazon.com",
//                    "https://twitter.com"
//            );
//            profile.setCommonWebsitesJson(objectMapper.writeValueAsString(websites));
//
//            // Audio fingerprint
//            Map<String, Object> audioFingerprint = new HashMap<>();
//            audioFingerprint.put("sampleRate", 48000);
//            audioFingerprint.put("channelCount", 2);
//            audioFingerprint.put("latency", 0.005);
//            audioFingerprint.put("noiseProfile", generateNoiseHash().substring(0, 16));
//            profile.setAudioFingerprintJson(objectMapper.writeValueAsString(audioFingerprint));
//
//        } catch (Exception e) {
//            log.warn("Failed to set JSON parameters", e);
//        }
//    }
//
//    /**
//     * Получает список шрифтов в зависимости от платформы
//     */
//    private List<String> getFontsList(boolean isMobile) {
//        List<String> fonts = new ArrayList<>();
//
//        if (isMobile) {
//            // iOS/Android шрифты
//            fonts.addAll(Arrays.asList(
//                    "Arial",
//                    "Helvetica",
//                    "Times New Roman",
//                    "Courier New",
//                    "Verdana",
//                    "Georgia",
//                    "Palatino",
//                    "Garamond",
//                    "Bookman",
//                    "Comic Sans MS",
//                    "Trebuchet MS",
//                    "Arial Black",
//                    "Impact",
//                    "Tahoma",
//                    "Courier",
//                    "Lucida Console"
//            ));
//        } else {
//            // Windows шрифты
//            fonts.addAll(Arrays.asList(
//                    "Arial",
//                    "Arial Black",
//                    "Arial Narrow",
//                    "Calibri",
//                    "Cambria",
//                    "Candara",
//                    "Comic Sans MS",
//                    "Consolas",
//                    "Constantia",
//                    "Corbel",
//                    "Courier New",
//                    "Georgia",
//                    "Impact",
//                    "Lucida Console",
//                    "Lucida Sans Unicode",
//                    "Microsoft Sans Serif",
//                    "Palatino Linotype",
//                    "Segoe UI",
//                    "Tahoma",
//                    "Times New Roman",
//                    "Trebuchet MS",
//                    "Verdana",
//                    "Webdings",
//                    "Wingdings"
//            ));
//        }
//
//        return fonts;
//    }
//
//    /**
//     * Находит шаблон устройства по типу
//     */
//    private DeviceTemplate findTemplate(String deviceType) {
//        return MOBILE_DEVICE_TEMPLATES.stream()
//                .filter(t -> t.getDeviceType().equals(deviceType))
//                .findFirst()
//                .orElse(null);
//    }
//
//    /**
//     * Получает отображаемое имя устройства
//     */
//    private String getDisplayName(String deviceType) {
//        if (deviceType == null || "random".equalsIgnoreCase(deviceType)) {
//            return "Random Mobile Device";
//        }
//
//        return MOBILE_DEVICE_TEMPLATES.stream()
//                .filter(t -> t.getDeviceType().equals(deviceType))
//                .map(DeviceTemplate::getDisplayName)
//                .findFirst()
//                .orElse(deviceType);
//    }
//
//    /**
//     * Генерирует хэш для canvas fingerprint
//     */
//    private String generateCanvasHash() {
//        try {
//            MessageDigest md = MessageDigest.getInstance("MD5");
//            byte[] hash = md.digest((UUID.randomUUID().toString() + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
//            return bytesToHex(hash).substring(0, 16);
//        } catch (Exception e) {
//            return "defaultCanvasHash";
//        }
//    }
//
//    /**
//     * Генерирует хэш шума
//     */
//    private String generateNoiseHash() {
//        try {
//            MessageDigest md = MessageDigest.getInstance("SHA-256");
//            byte[] hash = md.digest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
//            return bytesToHex(hash).substring(0, 32);
//        } catch (Exception e) {
//            return "defaultNoiseHash";
//        }
//    }
//
//    /**
//     * Конвертирует байты в hex строку
//     */
//    private String bytesToHex(byte[] bytes) {
//        StringBuilder hexString = new StringBuilder();
//        for (byte b : bytes) {
//            String hex = Integer.toHexString(0xff & b);
//            if (hex.length() == 1) hexString.append('0');
//            hexString.append(hex);
//        }
//        return hexString.toString();
//    }
//
//    /**
//     * Рассчитывает риск на основе уровня детекции
//     */
//    private Double calculateRiskForDetectionLevel(String detectionLevel) {
//        if (detectionLevel == null) {
//            return 0.2;
//        }
//
//        return switch (detectionLevel.toUpperCase()) {
//            case "BASIC" -> 0.3;
//            case "ENHANCED" -> 0.1;
//            case "AGGRESSIVE" -> 0.05;
//            default -> 0.2;
//        };
//    }
//
//    /**
//     * Получает производителя из платформы
//     */
//    private String getVendorFromPlatform(String platform) {
//        if (platform == null) {
//            return "Unknown";
//        }
//
//        String platformLower = platform.toLowerCase();
//        if (platformLower.contains("iphone") || platformLower.contains("ipad")) {
//            return "Apple Inc.";
//        } else if (platformLower.contains("linux")) {
//            return "Google Inc.";
//        } else if (platformLower.contains("win")) {
//            return "Microsoft Corporation";
//        }
//
//        return "Unknown";
//    }
//
//    /**
//     * Получает версию Chrome из User Agent
//     */
//    private String getChromeVersionFromUserAgent(String userAgent) {
//        if (userAgent == null) {
//            return "120.0.0.0";
//        }
//
//        Pattern pattern = Pattern.compile("Chrome/(\\d+\\.\\d+\\.\\d+\\.\\d+)");
//        Matcher matcher = pattern.matcher(userAgent);
//
//        if (matcher.find()) {
//            return matcher.group(1);
//        }
//
//        return "120.0.0.0";
//    }
//
//    /**
//     * Получает версию OS из User Agent
//     */
//    private String getOsVersionFromUserAgent(String userAgent) {
//        if (userAgent == null) {
//            return "10.0";
//        }
//
//        // Для iOS
//        Pattern iosPattern = Pattern.compile("CPU (?:iPhone )?OS (\\d+_\\d+)");
//        Matcher iosMatcher = iosPattern.matcher(userAgent);
//        if (iosMatcher.find()) {
//            return iosMatcher.group(1).replace("_", ".");
//        }
//
//        // Для Android
//        Pattern androidPattern = Pattern.compile("Android (\\d+)");
//        Matcher androidMatcher = androidPattern.matcher(userAgent);
//        if (androidMatcher.find()) {
//            return androidMatcher.group(1) + ".0";
//        }
//
//        // Для Windows
//        Pattern windowsPattern = Pattern.compile("Windows NT (\\d+\\.\\d+)");
//        Matcher windowsMatcher = windowsPattern.matcher(userAgent);
//        if (windowsMatcher.find()) {
//            return windowsMatcher.group(1);
//        }
//
//        return "10.0";
//    }
//
//    /**
//     * Получает архитектуру OS из платформы
//     */
//    private String getOsArchitecture(String platform) {
//        if (platform == null) {
//            return "x64";
//        }
//
//        String platformLower = platform.toLowerCase();
//        if (platformLower.contains("arm") || platformLower.contains("aarch")) {
//            return "arm64";
//        } else if (platformLower.contains("x64") || platformLower.contains("amd64")) {
//            return "x64";
//        } else if (platformLower.contains("x86") || platformLower.contains("i386")) {
//            return "x86";
//        }
//
//        return "x64";
//    }
//
//    /**
//     * Рассчитывает смещение часового пояса
//     */
//    private Integer calculateTimezoneOffset(String timezone) {
//        Map<String, Integer> timezoneOffsets = Map.of(
//                "Europe/Moscow", -180,      // UTC+3
//                "America/New_York", 300,    // UTC-5
//                "Europe/London", 0,         // UTC+0
//                "Asia/Tokyo", -540,        // UTC+9
//                "Australia/Sydney", -600    // UTC+10
//        );
//
//        return timezoneOffsets.getOrDefault(timezone, -180);
//    }
//
//    /**
//     * Получает список доступных типов устройств
//     */
//    public List<String> getAvailableDeviceTypes() {
//        List<String> deviceTypes = new ArrayList<>();
//        MOBILE_DEVICE_TEMPLATES.forEach(t -> deviceTypes.add(t.getDeviceType()));
//        deviceTypes.add("random");
//        return deviceTypes;
//    }
//
//    /**
//     * Получает шаблон устройства по типу (опционально)
//     */
//    public Optional<DeviceProfile> getDeviceProfileTemplate(String deviceType) {
//        DeviceTemplate template = findTemplate(deviceType);
//        if (template != null) {
//            return Optional.of(buildDeviceProfileFromTemplate(template));
//        }
//        return Optional.empty();
//    }
//
//    /**
//     * Вспомогательный класс для шаблонов устройств
//     */
//    @lombok.Data
//    @lombok.Builder
//    private static class DeviceTemplate {
//        private String deviceType;
//        private String displayName;
//        private String userAgent;
//        private Integer width;
//        private Integer height;
//        private Double pixelRatio;
//        private String platform;
//        private Integer maxTouchPoints;
//        private Integer hardwareConcurrency;
//        private Integer deviceMemory;
//        private String webglVendor;
//        private String webglRenderer;
//        private String webglVersion;
//        private String osVersion;
//        private String osArchitecture;
//    }
//}