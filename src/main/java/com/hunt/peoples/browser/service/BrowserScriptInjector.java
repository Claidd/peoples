package com.hunt.peoples.browser.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.peoples.browser.config.DevToolsClient;
import com.hunt.peoples.browser.config.DevToolsSession;
import com.hunt.peoples.profiles.entity.Profile;
import com.hunt.peoples.profiles.repository.ProfileRepository;
import com.hunt.peoples.profiles.service.QaScriptGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class BrowserScriptInjector {

    private final ObjectMapper objectMapper;
    private final ProfileRepository profileRepository;
    private final DevToolsClient devToolsClient;
    private final QaScriptGenerator qaScriptGenerator;
    @Value("${browser.cdp.inject.enabled:true}")
    private boolean injectEnabled;


    @Value("${browser.cdp.inject.cmd-timeout-ms:3000}")
    private long cmdTimeoutMs;

    // чтобы не улететь в огромные payload’ы (можно подкрутить)
    @Value("${browser.cdp.inject.max-script-bytes:250000}") // ~250KB на 1 скрипт
    private int maxScriptBytes;

    @Value("${browser.cdp.inject.max-total-bytes:1500000}") // ~1.5MB суммарно
    private long maxTotalBytes;

    /**
     * Генерирует полный инъекционный скрипт для профиля
     */
    public String generateInjectionScript(Profile profile) {
        try {
            return generateFullInjectionScript(profile);
        } catch (Exception e) {
            log.error("Failed to generate injection script for profile {}", profile.getId(), e);
            return generateBasicInjectionScript(profile);
        }
    }

    /**
     * Генерирует полный скрипт с подменой всех параметров
     */
    private String generateFullInjectionScript(Profile profile) throws JsonProcessingException {
        // Получаем все данные из профиля
        String userAgent = profile.getUserAgent();
        String platform = profile.getPlatform();
        Integer width = profile.getScreenWidth();
        Integer height = profile.getScreenHeight();
        Double pixelRatio = profile.getPixelRatio();
        String webglVendor = profile.getWebglVendor();
        String webglRenderer = profile.getWebglRenderer();
        String webglVersion = profile.getWebglVersion();
        Integer timezoneOffset = profile.getTimezoneOffset();
        String language = profile.getLanguage();
        String locale = profile.getLocale();

        // Получаем аппаратные параметры
        Integer hardwareConcurrency = profile.getHardwareConcurrency();
        Integer deviceMemory = profile.getDeviceMemory();
        Integer maxTouchPoints = profile.getMaxTouchPoints();

        // Получаем детали экрана
        Integer availWidth = profile.getScreenAvailWidth();
        Integer availHeight = profile.getScreenAvailHeight();
        Integer colorDepth = profile.getScreenColorDepth();
        Integer pixelDepth = profile.getScreenPixelDepth();

        // Получаем навигатор свойства
        Boolean cookieEnabled = profile.getCookieEnabled();
        String doNotTrack = profile.getDoNotTrack();
        Boolean online = profile.getOnline();

        // Получаем дополнительные данные из JSON полей
        Map<String, Object> batteryInfo = profile.getBatteryInfo();
        Map<String, Object> connectionInfo = profile.getConnectionInfo();
        List<String> fontsList = profile.getFontsList();
        List<Map<String, Object>> plugins = profile.getPlugins();

        // Парсим battery info
        boolean isCharging = batteryInfo != null && Boolean.TRUE.equals(batteryInfo.get("charging"));
        Integer chargingTime = batteryInfo != null && batteryInfo.get("chargingTime") != null ?
                ((Number) batteryInfo.get("chargingTime")).intValue() : 0;
        Integer dischargingTime = batteryInfo != null && batteryInfo.get("dischargingTime") != null ?
                ((Number) batteryInfo.get("dischargingTime")).intValue() : 3600;
        Double batteryLevel = batteryInfo != null && batteryInfo.get("level") != null ?
                ((Number) batteryInfo.get("level")).doubleValue() : 0.85;

        // Парсим connection info
        Double downlink = connectionInfo != null && connectionInfo.get("downlink") != null ?
                ((Number) connectionInfo.get("downlink")).doubleValue() : 10.0;
        String effectiveType = connectionInfo != null && connectionInfo.get("effectiveType") != null ?
                connectionInfo.get("effectiveType").toString() : "4g";
        Integer rtt = connectionInfo != null && connectionInfo.get("rtt") != null ?
                ((Number) connectionInfo.get("rtt")).intValue() : 100;
        boolean saveData = connectionInfo != null && Boolean.TRUE.equals(connectionInfo.get("saveData"));
        String connectionType = connectionInfo != null && connectionInfo.get("type") != null ?
                connectionInfo.get("type").toString() : "wifi";

        // Парсим audio параметры
        Integer audioSampleRate = profile.getAudioSampleRate() != null ? profile.getAudioSampleRate() : 48000;

        // Устанавливаем дефолтные значения, если параметры null
        if (width == null) width = 1920;
        if (height == null) height = 1080;
        if (pixelRatio == null) pixelRatio = 1.0;
        if (webglVendor == null) webglVendor = "Google Inc.";
        if (webglRenderer == null) webglRenderer = "ANGLE (Intel, Mesa Intel(R) UHD Graphics 630 (CML GT2), OpenGL 4.6)";
        if (webglVersion == null) webglVersion = "WebGL 1.0";
        if (timezoneOffset == null) timezoneOffset = -180;
        if (language == null) language = "en-US";
        if (locale == null) locale = "en-US";
        if (hardwareConcurrency == null) hardwareConcurrency = 4;
        if (deviceMemory == null) deviceMemory = 4;
        if (maxTouchPoints == null) maxTouchPoints = 0;
        if (availWidth == null) availWidth = width;
        if (availHeight == null) availHeight = height;
        if (colorDepth == null) colorDepth = 24;
        if (pixelDepth == null) pixelDepth = 24;
        if (cookieEnabled == null) cookieEnabled = true;
        if (doNotTrack == null) doNotTrack = "unspecified";
        if (online == null) online = true;

        // Генерация полного скрипта
        return String.format("""
            (function() {
                'use strict';
                
                // === ОСНОВНЫЕ ПЕРЕОПРЕДЕЛЕНИЯ ===
                
                // 1. User Agent и платформа
                Object.defineProperty(navigator, 'userAgent', {
                    get: () => "%s",
                    configurable: false,
                    enumerable: true
                });
                
                Object.defineProperty(navigator, 'platform', {
                    get: () => "%s",
                    configurable: false,
                    enumerable: true
                });
                
                // 2. WebDriver детекция
                Object.defineProperty(navigator, 'webdriver', {
                    get: () => undefined,
                    configurable: false,
                    enumerable: false
                });
                
                // 3. Chrome runtime
                if (window.chrome) {
                    Object.defineProperty(window.chrome, 'runtime', {
                        value: undefined,
                        configurable: false,
                        enumerable: false
                    });
                    
                    // Удаляем другие automation-флаги
                    delete window.chrome.csi;
                    delete window.chrome.loadTimes;
                }
                
                // 4. Automation контролируемые флаги
                delete window.cdc_adoQpoasnfa76pfcZLmcfl;
                delete window._Selenium_IDE_Recorder;
                delete window._phantom;
                delete window.callPhantom;
                delete window.__nightmare;
                delete window.Buffer;
                
                // === HARDWARE И SCREEN ===
                
                // 5. Screen свойства
                Object.defineProperty(screen, 'width', {
                    get: () => %d,
                    configurable: false,
                    enumerable: true
                });
                
                Object.defineProperty(screen, 'height', {
                    get: () => %d,
                    configurable: false,
                    enumerable: true
                });
                
                Object.defineProperty(screen, 'availWidth', {
                    get: () => %d,
                    configurable: false,
                    enumerable: true
                });
                
                Object.defineProperty(screen, 'availHeight', {
                    get: () => %d,
                    configurable: false,
                    enumerable: true
                });
                
                Object.defineProperty(screen, 'colorDepth', {
                    get: () => %d,
                    configurable: false,
                    enumerable: true
                });
                
                Object.defineProperty(screen, 'pixelDepth', {
                    get: () => %d,
                    configurable: false,
                    enumerable: true
                });
                
                // 6. Pixel ratio
                Object.defineProperty(window, 'devicePixelRatio', {
                    get: () => %f,
                    configurable: false,
                    enumerable: true
                });
                
                // 7. Touch points
                Object.defineProperty(navigator, 'maxTouchPoints', {
                    get: () => %d,
                    configurable: false,
                    enumerable: true
                });
                
                // 8. Hardware concurrency
                Object.defineProperty(navigator, 'hardwareConcurrency', {
                    get: () => %d,
                    configurable: false,
                    enumerable: true
                });
                
                // 9. Device memory
                if ('deviceMemory' in navigator) {
                    Object.defineProperty(navigator, 'deviceMemory', {
                        get: () => %d,
                        configurable: false,
                        enumerable: true
                    });
                }
                
                // === WEBGL СПУФИНГ ===
                
                // 10. WebGL vendor/renderer/version
                const originalGetContext = HTMLCanvasElement.prototype.getContext;
                HTMLCanvasElement.prototype.getContext = function(contextType, contextAttributes) {
                    const context = originalGetContext.call(this, contextType, contextAttributes);
                    
                    if (contextType === 'webgl' || contextType === 'experimental-webgl' || 
                        contextType === 'webgl2') {
                        
                        // Сохраняем оригинальные методы
                        const originalGetParameter = context.getParameter;
                        const originalGetSupportedExtensions = context.getSupportedExtensions;
                        const originalGetExtension = context.getExtension;
                        
                        // Переопределяем getParameter
                        context.getParameter = function(pname) {
                            // Возвращаем наши значения
                            switch(pname) {
                                case 37445: // VENDOR
                                    return "%s";
                                case 37446: // RENDERER
                                    return "%s";
                                case 37444: // VERSION
                                    return "%s";
                                case 37443: // SHADING_LANGUAGE_VERSION
                                    return "WebGL GLSL ES 1.0";
                                case 37447: // UNMASKED_VENDOR_WEBGL
                                    return "%s";
                                case 37448: // UNMASKED_RENDERER_WEBGL
                                    return "%s";
                                default:
                                    return originalGetParameter.call(this, pname);
                            }
                        };
                        
                        // Переопределяем getSupportedExtensions
                        context.getSupportedExtensions = function() {
                            const extensions = originalGetSupportedExtensions.call(this);
                            // Можем фильтровать или добавлять расширения
                            return extensions;
                        };
                        
                        // Переопределяем getExtension
                        context.getExtension = function(name) {
                            // Можем контролировать доступные расширения
                            return originalGetExtension.call(this, name);
                        };
                    }
                    
                    return context;
                };
                
                // === CANVAS FINGERPRINT ЗАЩИТА ===
                
                // 11. Canvas noise injection
                const originalToDataURL = HTMLCanvasElement.prototype.toDataURL;
                HTMLCanvasElement.prototype.toDataURL = function(type, quality) {
                    // Получаем оригинальный canvas
                    const original = originalToDataURL.call(this, type, quality);
                    
                    // Для базовой защиты можно добавить минимальные изменения
                    // Это сложная тема, оставляем как есть
                    return original;
                };
                
                const originalGetImageData = CanvasRenderingContext2D.prototype.getImageData;
                CanvasRenderingContext2D.prototype.getImageData = function(sx, sy, sw, sh) {
                    const imageData = originalGetImageData.call(this, sx, sy, sw, sh);
                    
                    // Добавляем микровариации в первые несколько пикселей
                    if (imageData && imageData.data && imageData.data.length > 3) {
                        // Минимальное изменение (1 бит в альфа-канале)
                        imageData.data[3] ^= 1;
                    }
                    
                    return imageData;
                };
                
                // === BATTERY API ===
                
                // 12. Battery API спуфинг
                if ('getBattery' in navigator) {
                    navigator.getBattery = function() {
                        return Promise.resolve({
                            charging: %s,
                            chargingTime: %d,
                            dischargingTime: %d,
                            level: %f,
                            addEventListener: function() {},
                            removeEventListener: function() {},
                            dispatchEvent: function() { return true; }
                        });
                    };
                }
                
                // === CONNECTION API ===
                
                // 13. Network information
                if ('connection' in navigator) {
                    Object.defineProperty(navigator, 'connection', {
                        value: {
                            downlink: %f,
                            effectiveType: "%s",
                            rtt: %d,
                            saveData: %s,
                            type: "%s",
                            addEventListener: function() {},
                            removeEventListener: function() {},
                            dispatchEvent: function() { return true; }
                        },
                        configurable: false,
                        enumerable: true
                    });
                }
                
                // === TIMEZONE И LOCALE ===
                
                // 14. Timezone подмена
                const originalGetTimezoneOffset = Date.prototype.getTimezoneOffset;
                Date.prototype.getTimezoneOffset = function() {
                    return %d;
                };
                
                // 15. Locale подмена
                Object.defineProperty(navigator, 'language', {
                    get: () => "%s",
                    configurable: false,
                    enumerable: true
                });
                
                Object.defineProperty(navigator, 'languages', {
                    get: () => ["%s", "%s", "%s-*", "*"],
                    configurable: false,
                    enumerable: true
                });
                
                // 16. Locale дополнительно
                Object.defineProperty(navigator, 'locale', {
                    get: () => "%s",
                    configurable: false,
                    enumerable: true
                });
                
                // === FONT DETECTION ЗАЩИТА ===
                
                // 17. Font enumeration protection
                if ('fonts' in document) {
                    Object.defineProperty(document, 'fonts', {
                        value: {
                            ready: Promise.resolve(),
                            check: function() { return true; },
                            load: function() { return Promise.resolve(); },
                            addEventListener: function() {},
                            removeEventListener: function() {},
                            dispatchEvent: function() { return true; }
                        },
                        configurable: false,
                        enumerable: true
                    });
                }
                
                // === PLUGINS И MIME TYPES ===
                
                // 18. Plugins спуфинг на основе данных профиля
                const pluginData = %s;
                Object.defineProperty(navigator, 'plugins', {
                    get: () => {
                        const plugins = [];
                        if (pluginData && Array.isArray(pluginData)) {
                            pluginData.forEach(plugin => {
                                plugins.push({
                                    name: plugin.name || 'PDF Viewer',
                                    filename: plugin.filename || 'internal-pdf-viewer',
                                    description: plugin.description || 'Portable Document Format',
                                    length: plugin.length || 1
                                });
                            });
                        } else {
                            // Дефолтные плагины
                            for (let i = 0; i < 3; i++) {
                                plugins.push({
                                    name: 'PDF Viewer',
                                    filename: 'internal-pdf-viewer',
                                    description: 'Portable Document Format',
                                    length: 1
                                });
                            }
                        }
                        return plugins;
                    },
                    configurable: false,
                    enumerable: true
                });
                
                // 19. MIME types спуфинг
                Object.defineProperty(navigator, 'mimeTypes', {
                    get: () => {
                        const mimes = [];
                        for (let i = 0; i < 3; i++) {
                            mimes.push({
                                type: 'application/pdf',
                                suffixes: 'pdf',
                                description: 'PDF document',
                                enabledPlugin: {
                                    name: 'PDF Viewer',
                                    filename: 'internal-pdf-viewer',
                                    description: 'Portable Document Format'
                                }
                            });
                        }
                        return mimes;
                    },
                    configurable: false,
                    enumerable: true
                });
                
                // === NAVIGATOR СВОЙСТВА ===
                
                // 20. Навигатор свойства
                Object.defineProperty(navigator, 'cookieEnabled', {
                    get: () => %s,
                    configurable: false,
                    enumerable: true
                });
                
                Object.defineProperty(navigator, 'doNotTrack', {
                    get: () => "%s",
                    configurable: false,
                    enumerable: true
                });
                
                Object.defineProperty(navigator, 'onLine', {
                    get: () => %s,
                    configurable: false,
                    enumerable: true
                });
                
                // === АУДИО ЗАЩИТА ===
                
                // 21. Audio context спуфинг
                const originalCreateOscillator = AudioContext.prototype.createOscillator;
                AudioContext.prototype.createOscillator = function() {
                    const oscillator = originalCreateOscillator.call(this);
                    
                    // Модифицируем параметры осциллятора для защиты от fingerprinting
                    const originalStart = oscillator.start;
                    oscillator.start = function(when) {
                        // Добавляем микровариации
                        return originalStart.call(this, when + (Math.random() * 0.0001));
                    };
                    
                    return oscillator;
                };
                
                // Устанавливаем sample rate
                Object.defineProperty(AudioContext.prototype, 'sampleRate', {
                    get: () => %d,
                    configurable: false,
                    enumerable: true
                });
                
                // === ДОПОЛНИТЕЛЬНЫЕ МЕТОДЫ ЗАЩИТЫ ===
                
                // 22. Защита от fingerprinting библиотек
                window.Fingerprint2 = undefined;
                window.ClientJS = undefined;
                window.fpCollect = undefined;
                
                // 23. Замена функций, которые могут использоваться для детекции
                const originalQuerySelector = Document.prototype.querySelector;
                Document.prototype.querySelector = function(selectors) {
                    if (typeof selectors === 'string') {
                        const lowerSelectors = selectors.toLowerCase();
                        if (lowerSelectors.includes('webdriver') || 
                            lowerSelectors.includes('automation') ||
                            lowerSelectors.includes('selenium') ||
                            lowerSelectors.includes('driver') ||
                            lowerSelectors.includes('phantom')) {
                            return null;
                        }
                    }
                    return originalQuerySelector.call(this, selectors);
                };
                
                // 24. Защита от Performance API fingerprinting
                if (window.performance && window.performance.now) {
                    const originalNow = performance.now;
                    performance.now = function() {
                        return originalNow.call(performance) + (Math.random() * 0.1 - 0.05);
                    };
                }
                
                // 25. Защита от перечисления свойств
                const originalGetOwnPropertyNames = Object.getOwnPropertyNames;
                Object.getOwnPropertyNames = function(obj) {
                    const names = originalGetOwnPropertyNames.call(this, obj);
                    
                    // Фильтруем sensitive свойства
                    return names.filter(name => {
                        const lowerName = name.toLowerCase();
                        return !(lowerName.includes('webdriver') || 
                                lowerName.includes('selenium') ||
                                lowerName.includes('automation') ||
                                lowerName.includes('driver'));
                    });
                };
                
                console.debug('[Anti-Detect] Все защиты успешно применены для профиля: %s');
                
                // Возвращаем true для успешного выполнения
                return true;
                
            })();
            """,
                // Аргументы для форматирования
                userAgent,
                platform != null ? platform : "Win32",
                width,
                height,
                availWidth,
                availHeight,
                colorDepth,
                pixelDepth,
                pixelRatio,
                maxTouchPoints,
                hardwareConcurrency,
                deviceMemory,
                webglVendor,
                webglRenderer,
                webglVersion,
                webglVendor,
                webglRenderer,
                isCharging,
                chargingTime,
                dischargingTime,
                batteryLevel,
                downlink,
                effectiveType,
                rtt,
                saveData,
                connectionType,
                timezoneOffset,
                language,
                language,
                language.substring(0, 2),
                language,
                locale,
                plugins != null ? objectMapper.writeValueAsString(plugins) : "null",
                cookieEnabled,
                doNotTrack,
                online,
                audioSampleRate,
                profile.getExternalKey()
        );
    }

    /**
     * Генерирует скрипт для подмены медиа устройств
     */
    public String generateMediaDevicesScript(Profile profile) {
        try {
            List<Map<String, Object>> mediaDevices = profile.getMediaDevices();

            if (mediaDevices == null || mediaDevices.isEmpty()) {
                return null;
            }

            return """
                (function() {
                    'use strict';
                    
                    if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) {
                        return;
                    }
                    
                    const originalEnumerateDevices = navigator.mediaDevices.enumerateDevices;
                    
                    navigator.mediaDevices.enumerateDevices = async function() {
                        const devices = await originalEnumerateDevices.call(this);
                        
                        // Подменяем deviceId и groupId для приватности
                        const modifiedDevices = devices.map(device => {
                            const modifiedDevice = {
                                deviceId: device.deviceId ? 'spoofed-' + Math.random().toString(36).substr(2, 9) : '',
                                groupId: device.groupId ? 'spoofed-group-' + Math.random().toString(36).substr(2, 9) : '',
                                kind: device.kind,
                                label: device.label ? '' : '', // Убираем метки
                                toJSON: device.toJSON
                            };
                            
                            // Сохраняем оригинальные методы
                            if (device.getCapabilities) {
                                modifiedDevice.getCapabilities = device.getCapabilities;
                            }
                            if (device.getSettings) {
                                modifiedDevice.getSettings = device.getSettings;
                            }
                            
                            return modifiedDevice;
                        });
                        
                        return modifiedDevices;
                    };
                    
                    // Также переопределяем getUserMedia для дополнительной защиты
                    if (navigator.mediaDevices.getUserMedia) {
                        const originalGetUserMedia = navigator.mediaDevices.getUserMedia;
                        navigator.mediaDevices.getUserMedia = function(constraints) {
                            // Можем модифицировать constraints
                            return originalGetUserMedia.call(this, constraints);
                        };
                    }
                    
                    console.debug('[Anti-Detect] Media devices protection applied');
                })();
                """;
        } catch (Exception e) {
            log.warn("Failed to generate media devices script: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Генерирует скрипт для подмены WebGL extensions
     */
    public String generateWebGLExtensionsScript(Profile profile) {
        try {
            Map<String, Object> webglExtensions = profile.getWebglExtensions();

            if (webglExtensions == null || webglExtensions.isEmpty()) {
                return null;
            }

            return """
                (function() {
                    'use strict';
                    
                    // Кэшируем оригинальные методы
                    const originalGetSupportedExtensions = WebGLRenderingContext.prototype.getSupportedExtensions;
                    const originalGetExtension = WebGLRenderingContext.prototype.getExtension;
                    
                    // Список extensions, которые мы хотим скрыть или модифицировать
                    const filteredExtensions = [
                        'WEBGL_debug_renderer_info',
                        'WEBGL_debug_shaders',
                        'WEBGL_debug'
                    ];
                    
                    WebGLRenderingContext.prototype.getSupportedExtensions = function() {
                        const extensions = originalGetSupportedExtensions.call(this);
                        
                        if (!extensions) {
                            return [];
                        }
                        
                        // Фильтруем extensions, которые могут выдать информацию
                        return extensions.filter(ext => !filteredExtensions.includes(ext));
                    };
                    
                    WebGLRenderingContext.prototype.getExtension = function(name) {
                        // Блокируем доступ к определенным extensions
                        if (filteredExtensions.includes(name)) {
                            return null;
                        }
                        
                        const extension = originalGetExtension.call(this, name);
                        
                        // Можем модифицировать extension объект
                        if (extension && name === 'WEBGL_debug_renderer_info') {
                            // Скрываем реальную информацию
                            Object.defineProperty(extension, 'UNMASKED_VENDOR_WEBGL', {
                                get: () => 'Google Inc.'
                            });
                            Object.defineProperty(extension, 'UNMASKED_RENDERER_WEBGL', {
                                get: () => 'ANGLE (Intel, Mesa Intel(R) UHD Graphics 630 (CML GT2), OpenGL 4.6)'
                            });
                        }
                        
                        return extension;
                    };
                    
                    console.debug('[Anti-Detect] WebGL extensions protection applied');
                })();
                """;
        } catch (Exception e) {
            log.warn("Failed to generate WebGL extensions script: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Генерирует скрипт для аудио fingerprint защиты
     */
    public String generateAudioScript(Profile profile) {
        try {
            Integer audioSampleRate = profile.getAudioSampleRate() != null ? profile.getAudioSampleRate() : 48000;
            String audioChannelCount = profile.getAudioChannelCount() != null ? profile.getAudioChannelCount() : "stereo";
            Double audioLatency = profile.getAudioContextLatency() != null ? profile.getAudioContextLatency() : 0.005;

            return String.format("""
                (function() {
                    'use strict';
                    
                    // 1. Защита AudioContext от fingerprinting
                    const originalCreateOscillator = AudioContext.prototype.createOscillator;
                    AudioContext.prototype.createOscillator = function() {
                        const oscillator = originalCreateOscillator.call(this);
                        
                        // Добавляем микровариации для защиты
                        const originalStart = oscillator.start;
                        oscillator.start = function(when) {
                            // Микровариации в диапазоне +/- 0.0001 секунды
                            return originalStart.call(this, when + (Math.random() * 0.0002 - 0.0001));
                        };
                        
                        return oscillator;
                    };
                    
                    // 2. Устанавливаем sample rate
                    Object.defineProperty(AudioContext.prototype, 'sampleRate', {
                        get: () => %d,
                        configurable: false,
                        enumerable: true
                    });
                    
                    // 3. Защита от анализа аудио buffer
                    const originalCreateBuffer = AudioContext.prototype.createBuffer;
                    AudioContext.prototype.createBuffer = function(numberOfChannels, length, sampleRate) {
                        // Добавляем минимальные вариации
                        const buffer = originalCreateBuffer.call(this, numberOfChannels, length, sampleRate);
                        
                        if (buffer && buffer.getChannelData) {
                            // Добавляем минимальный шум в первые несколько сэмплов
                            const channelData = buffer.getChannelData(0);
                            if (channelData && channelData.length > 10) {
                                for (let i = 0; i < 10; i++) {
                                    channelData[i] += (Math.random() * 0.000001 - 0.0000005);
                                }
                            }
                        }
                        
                        return buffer;
                    };
                    
                    // 4. Защита AnalyserNode
                    const originalCreateAnalyser = AudioContext.prototype.createAnalyser;
                    AudioContext.prototype.createAnalyser = function() {
                        const analyser = originalCreateAnalyser.call(this);
                        
                        // Добавляем вариации в FFT
                        const originalGetFloatFrequencyData = analyser.getFloatFrequencyData;
                        analyser.getFloatFrequencyData = function(array) {
                            const result = originalGetFloatFrequencyData.call(this, array);
                            
                            // Добавляем минимальные вариации
                            if (array && array.length > 0) {
                                for (let i = 0; i < Math.min(5, array.length); i++) {
                                    array[i] += (Math.random() * 0.1 - 0.05);
                                }
                            }
                            
                            return result;
                        };
                        
                        return analyser;
                    };
                    
                    // 5. Защита от измерения латентности
                    const originalCreateDynamicsCompressor = AudioContext.prototype.createDynamicsCompressor;
                    AudioContext.prototype.createDynamicsCompressor = function() {
                        const compressor = originalCreateDynamicsCompressor.call(this);
                        
                        // Минимизируем различия в параметрах
                        Object.defineProperty(compressor, 'threshold', {
                            get: () => -24,
                            configurable: false
                        });
                        
                        Object.defineProperty(compressor, 'knee', {
                            get: () => 30,
                            configurable: false
                        });
                        
                        Object.defineProperty(compressor, 'ratio', {
                            get: () => 12,
                            configurable: false
                        });
                        
                        Object.defineProperty(compressor, 'attack', {
                            get: () => 0.003,
                            configurable: false
                        });
                        
                        Object.defineProperty(compressor, 'release', {
                            get: () => 0.250,
                            configurable: false
                        });
                        
                        return compressor;
                    };
                    
                    // 6. Защита Web Audio API от fingerprinting
                    if (window.OfflineAudioContext) {
                        const originalOfflineAudioContext = OfflineAudioContext;
                        window.OfflineAudioContext = function(numberOfChannels, length, sampleRate) {
                            // Используем стандартные значения
                            const ctx = new originalOfflineAudioContext(
                                numberOfChannels || 2,
                                length || 44100,
                                sampleRate || 44100
                            );
                            
                            // Применяем те же защиты
                            const originalCreateOscillatorOffline = ctx.createOscillator;
                            ctx.createOscillator = function() {
                                const oscillator = originalCreateOscillatorOffline.call(this);
                                
                                const originalStartOffline = oscillator.start;
                                oscillator.start = function(when) {
                                    return originalStartOffline.call(this, when + (Math.random() * 0.0001));
                                };
                                
                                return oscillator;
                            };
                            
                            return ctx;
                        };
                    }
                    
                    // 7. Скрываем аудио устройства
                    if (navigator.mediaDevices && navigator.mediaDevices.enumerateDevices) {
                        const originalEnumerateDevices = navigator.mediaDevices.enumerateDevices;
                        navigator.mediaDevices.enumerateDevices = async function() {
                            const devices = await originalEnumerateDevices.call(this);
                            
                            // Фильтруем аудио устройства
                            return devices.map(device => {
                                if (device.kind === 'audioinput' || device.kind === 'audiooutput') {
                                    return {
                                        deviceId: 'default',
                                        groupId: 'default-group',
                                        kind: device.kind,
                                        label: '',
                                        toJSON: device.toJSON
                                    };
                                }
                                return device;
                            });
                        };
                    }
                    
                    console.debug('[Anti-Detect] Audio fingerprint protection applied');
                    
                })();
                """,
                    audioSampleRate
            );
        } catch (Exception e) {
            log.warn("Failed to generate audio script: {}", e.getMessage());
            return generateBasicAudioScript(profile);
        }
    }

    /**
     * Генерирует базовый скрипт для аудио защиты
     */
    private String generateBasicAudioScript(Profile profile) {
        Integer audioSampleRate = profile.getAudioSampleRate() != null ? profile.getAudioSampleRate() : 48000;

        return String.format("""
            (function() {
                'use strict';
                
                // Базовая защита AudioContext
                Object.defineProperty(AudioContext.prototype, 'sampleRate', {
                    get: () => %d,
                    configurable: false
                });
                
                // Скрываем WebDriver в аудио контексте
                if (window.AudioContext) {
                    const originalAudioContext = window.AudioContext;
                    window.AudioContext = function() {
                        const context = new originalAudioContext();
                        Object.defineProperty(context, 'state', {
                            get: () => 'running',
                            configurable: false
                        });
                        return context;
                    };
                }
                
                console.debug('[Anti-Detect] Basic audio protection applied');
            })();
            """,
                audioSampleRate
        );
    }

    /**
     * Генерирует комплексный скрипт с защитой от всех типов fingerprinting
     */
    public String generateComprehensiveScript(Profile profile) {
        try {
            StringBuilder scriptBuilder = new StringBuilder();

            // 1. Основной инъекционный скрипт
            String mainScript = generateInjectionScript(profile);
            scriptBuilder.append(mainScript).append("\n\n");

            // 2. Аудио скрипт
            String audioScript = generateAudioScript(profile);
            if (audioScript != null) {
                scriptBuilder.append(audioScript).append("\n\n");
            }

            // 3. Медиа устройства скрипт
            String mediaScript = generateMediaDevicesScript(profile);
            if (mediaScript != null) {
                scriptBuilder.append(mediaScript).append("\n\n");
            }

            // 4. WebGL скрипт
            String webglScript = generateWebGLExtensionsScript(profile);
            if (webglScript != null) {
                scriptBuilder.append(webglScript).append("\n\n");
            }

            // 5. Дополнительная защита
            scriptBuilder.append(generateAdditionalProtectionScript(profile));

            return scriptBuilder.toString();

        } catch (Exception e) {
            log.error("Failed to generate comprehensive script for profile {}", profile.getId(), e);
            return generateInjectionScript(profile);
        }
    }

    /**
     * Генерирует дополнительную защиту
     */
    private String generateAdditionalProtectionScript(Profile profile) {
        return """
            (function() {
                'use strict';
                
                // Защита от performance timing attacks
                if (window.performance && window.performance.now) {
                    const originalNow = performance.now;
                    performance.now = function() {
                        return originalNow.call(performance) + (Math.random() * 0.1 - 0.05);
                    };
                }
                
                // Защита от requestAnimationFrame fingerprinting
                const originalRequestAnimationFrame = window.requestAnimationFrame;
                window.requestAnimationFrame = function(callback) {
                    // Добавляем минимальные вариации
                    return originalRequestAnimationFrame.call(window, function(timestamp) {
                        if (callback) {
                            callback(timestamp + (Math.random() * 0.1));
                        }
                    });
                };
                
                // Скрываем automation флаги
                const descriptors = Object.getOwnPropertyDescriptors(window);
                for (const [key, descriptor] of Object.entries(descriptors)) {
                    if (key.includes('webdriver') || 
                        key.includes('selenium') || 
                        key.includes('automation') ||
                        key.includes('driver') ||
                        key.includes('phantom') ||
                        key.includes('nightmare') ||
                        key.includes('__nightmare') ||
                        key.includes('_selenium') ||
                        key.includes('callPhantom')) {
                        try {
                            delete window[key];
                        } catch (e) {
                            // Игнорируем ошибки удаления
                        }
                    }
                }
                
                console.debug('[Anti-Detect] Additional protections applied');
            })();
            """;
    }

    /**
     * Генерирует базовый инъекционный скрипт
     */
    private String generateBasicInjectionScript(Profile profile) {
        // Базовый скрипт без расширенных функций
        String userAgent = profile.getUserAgent() != null ? profile.getUserAgent() : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36";
        String platform = profile.getPlatform() != null ? profile.getPlatform() : "Win32";
        Integer width = profile.getScreenWidth() != null ? profile.getScreenWidth() : 1920;
        Integer height = profile.getScreenHeight() != null ? profile.getScreenHeight() : 1080;

        return String.format("""
            (function() {
                Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                Object.defineProperty(navigator, 'userAgent', { get: () => "%s" });
                Object.defineProperty(navigator, 'platform', { get: () => "%s" });
                Object.defineProperty(screen, 'width', { get: () => %d });
                Object.defineProperty(screen, 'height', { get: () => %d });
            })();
            """,
                userAgent,
                platform,
                width,
                height
        );
    }

    /**
     * Генерирует скрипт для защиты от canvas fingerprinting
     */
    public String generateCanvasProtectionScript(Profile profile) {
        try {
            String canvasFingerprint = profile.getCanvasFingerprint() != null ?
                    profile.getCanvasFingerprint() : "defaultCanvasHash";
            String canvasNoiseHash = profile.getCanvasNoiseHash() != null ?
                    profile.getCanvasNoiseHash() : "defaultNoiseHash";

            return String.format("""
                (function() {
                    'use strict';
                    
                    // Canvas fingerprint protection
                    const originalToDataURL = HTMLCanvasElement.prototype.toDataURL;
                    HTMLCanvasElement.prototype.toDataURL = function(type, quality) {
                        const canvas = this;
                        
                        // Получаем оригинальный canvas
                        const context = canvas.getContext('2d');
                        if (context) {
                            // Добавляем минимальный шум
                            const imageData = context.getImageData(0, 0, 1, 1);
                            if (imageData && imageData.data && imageData.data.length >= 4) {
                                // Минимальное изменение в альфа-канале
                                imageData.data[3] = (imageData.data[3] + 1) %% 256;
                                context.putImageData(imageData, 0, 0);
                            }
                        }
                        
                        return originalToDataURL.call(this, type, quality);
                    };
                    
                    // WebGL canvas protection
                    const originalGetContext = HTMLCanvasElement.prototype.getContext;
                    HTMLCanvasElement.prototype.getContext = function(contextType, contextAttributes) {
                        const context = originalGetContext.call(this, contextType, contextAttributes);
                        
                        if (contextType === 'webgl' || contextType === 'experimental-webgl' || contextType === 'webgl2') {
                            // Защита WebGL fingerprinting
                            const originalGetParameter = context.getParameter;
                            context.getParameter = function(pname) {
                                const value = originalGetParameter.call(this, pname);
                                
                                // Добавляем вариации для определенных параметров
                                if (pname === 37446) { // RENDERER
                                    return "%s";
                                }
                                
                                return value;
                            };
                        }
                        
                        return context;
                    };
                    
                    console.debug('[Anti-Detect] Canvas protection applied');
                })();
                """,
                    canvasFingerprint
            );
        } catch (Exception e) {
            log.warn("Failed to generate canvas protection script: {}", e.getMessage());
            return null;
        }
    }



    /**
     * Генерирует скрипт для защиты от WebRTC fingerprinting
     */
    public String generateWebRTCProtectionScript(Profile profile) {
        return """
            (function() {
                'use strict';
                
                // WebRTC protection
                if (window.RTCPeerConnection) {
                    const originalRTCPeerConnection = window.RTCPeerConnection;
                    window.RTCPeerConnection = function(configuration) {
                        const pc = new originalRTCPeerConnection(configuration);
                        
                        // Скрываем реальные IP адреса
                        const originalCreateOffer = pc.createOffer;
                        pc.createOffer = async function(options) {
                            const offer = await originalCreateOffer.call(this, options);
                            
                            // Модифицируем SDP для скрытия IP
                            if (offer.sdp) {
                                offer.sdp = offer.sdp.replace(/\\r\\na=candidate.*\\r\\n/g, '\\r\\n');
                                offer.sdp = offer.sdp.replace(/a=ice-options.*\\r\\n/g, '');
                            }
                            
                            return offer;
                        };
                        
                        return pc;
                    };
                }
                
                // Защита от перечисления устройств WebRTC
                if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
                    const originalGetUserMedia = navigator.mediaDevices.getUserMedia;
                    navigator.mediaDevices.getUserMedia = function(constraints) {
                        // Убираем требования к конкретным устройствам
                        if (constraints && constraints.video && constraints.video.deviceId) {
                            delete constraints.video.deviceId;
                        }
                        if (constraints && constraints.audio && constraints.audio.deviceId) {
                            delete constraints.audio.deviceId;
                        }
                        
                        return originalGetUserMedia.call(this, constraints);
                    };
                }
                
                console.debug('[Anti-Detect] WebRTC protection applied');
            })();
            """;
    }

    public String generateConsistencyScript(Profile profile) {
        boolean ios = false;
        String ua = profile.getUserAgent() != null ? profile.getUserAgent() : "";
        String platform = profile.getPlatform() != null ? profile.getPlatform() : "";
        ios = ua.contains("iPhone") || ua.contains("iPad") || ua.contains("CPU iPhone OS") || platform.toLowerCase().contains("iphone") || platform.toLowerCase().contains("ipad");

        String navPlatform = ios ? (ua.contains("iPad") ? "iPad" : "iPhone") : "Linux armv8l";
        String navVendor = ios ? "Apple Computer, Inc." : "Google Inc.";

        // ВАЖНО: делаем только то, что реально можно “подправить” без ломания сайтов:
        // - navigator.platform / vendor через defineProperty
        // - navigator.webdriver=false
        // - iOS: прячем userAgentData (Safari обычно его не имеет), window.chrome -> undefined
        return """
        (function() {
          try {
            const isIOS = %s;

            const define = (obj, prop, val) => {
              try {
                Object.defineProperty(obj, prop, {
                  get: () => val,
                  configurable: true
                });
              } catch (e) {}
            };

            // webdriver
            try {
              define(Navigator.prototype, 'webdriver', false);
            } catch (e) {}

            // platform + vendor
            try { define(Navigator.prototype, 'platform', %s); } catch (e) {}
            try { define(Navigator.prototype, 'vendor', %s); } catch (e) {}

            if (isIOS) {
              // Safari-like: no userAgentData
              try { define(Navigator.prototype, 'userAgentData', undefined); } catch (e) {}
              // window.chrome отсутствует
              try { define(window, 'chrome', undefined); } catch (e) {}
            }
          } catch (e) {}
        })();
        """.formatted(
                ios ? "true" : "false",
                jsString(navPlatform),
                jsString(navVendor)
        );
    }

    private String jsString(String s) {
        if (s == null) s = "";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /**
     * Генерирует скрипт для защиты от font fingerprinting
     */
    public String generateFontProtectionScript(Profile profile) {
        try {
            List<String> fonts = profile.getFontsList();
            String fontsJson = fonts != null ? objectMapper.writeValueAsString(fonts) : "[]";

            return String.format("""
                (function() {
                    'use strict';
                    
                    // Font fingerprint protection
                    const originalFonts = %s;
                    
                    // Защита FontFaceSet API
                    if ('fonts' in document) {
                        Object.defineProperty(document, 'fonts', {
                            value: {
                                ready: Promise.resolve(),
                                check: function(font, text) {
                                    return true;
                                },
                                load: function(font, text) {
                                    return Promise.resolve();
                                },
                                addEventListener: function() {},
                                removeEventListener: function() {},
                                dispatchEvent: function() { return true; }
                            },
                            configurable: false,
                            enumerable: true
                        });
                    }
                    
                    // Переопределяем методы измерения текста
                    const originalMeasureText = CanvasRenderingContext2D.prototype.measureText;
                    CanvasRenderingContext2D.prototype.measureText = function(text) {
                        const result = originalMeasureText.call(this, text);
                        
                        // Добавляем минимальные вариации
                        if (result && result.width) {
                            result.width += (Math.random() * 0.1 - 0.05);
                        }
                        
                        return result;
                    };
                    
                    console.debug('[Anti-Detect] Font protection applied');
                })();
                """,
                    fontsJson
            );
        } catch (Exception e) {
            log.warn("Failed to generate font protection script: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Генерирует полный набор всех защитных скриптов
     */
    public List<String> generateAllProtectionScripts(Profile profile) {
        List<String> scripts = new ArrayList<>();

        // Добавляем все доступные скрипты
        addScriptIfNotNull(scripts, generateInjectionScript(profile));
        addScriptIfNotNull(scripts, generateAudioScript(profile));
        addScriptIfNotNull(scripts, generateMediaDevicesScript(profile));
        addScriptIfNotNull(scripts, generateWebGLExtensionsScript(profile));
        addScriptIfNotNull(scripts, generateCanvasProtectionScript(profile));
        addScriptIfNotNull(scripts, generateWebRTCProtectionScript(profile));
        addScriptIfNotNull(scripts, generateFontProtectionScript(profile));
        addScriptIfNotNull(scripts, generateAdditionalProtectionScript(profile));

        log.debug("Generated {} protection scripts for profile {}", scripts.size(), profile.getId());
        return scripts;
    }

    /**
     * Вспомогательный метод для добавления скрипта в список
     */
    private void addScriptIfNotNull(List<String> scripts, String script) {
        if (script != null && !script.trim().isEmpty()) {
            scripts.add(script);
        }
    }

    /**
     * Получает длину скрипта в байтах
     */
    public int getScriptSize(String script) {
        if (script == null) {
            return 0;
        }
        return script.getBytes().length;
    }

    /**
     * Валидирует скрипт на наличие опасных конструкций
     */
    public boolean validateScript(String script) {
        if (script == null || script.trim().isEmpty()) {
            return false;
        }

        // Проверяем на опасные конструкции
        String[] dangerousPatterns = {
                "eval\\(",
                "Function\\(",
                "setTimeout\\(",
                "setInterval\\(",
                "execScript\\(",
                "document\\.write",
                "document\\.writeln",
                "innerHTML\\s*=",
                "outerHTML\\s*=",
                "script\\s*src",
                "iframe\\s*src",
                "onload\\s*=",
                "onerror\\s*=",
                "alert\\(",
                "confirm\\(",
                "prompt\\("
        };

        for (String pattern : dangerousPatterns) {
            if (script.matches(".*" + pattern + ".*")) {
                log.warn("Script contains dangerous pattern: {}", pattern);
                return false;
            }
        }

        return true;
    }

    /**
     * ✅ Инжект через уже открытую CDP-сессию (page target).
     * Только Page.addScriptToEvaluateOnNewDocument (без второго WS).
     */
    public int injectForProfile(Long profileId, DevToolsSession cdp) {
        if (!injectEnabled) {
            log.debug("CDP injection disabled");
            return 0;
        }
        if (cdp == null) {
            log.warn("injectForProfile: cdp is null (profileId={})", profileId);
            return 0;
        }

        Profile profile = profileRepository.findById(profileId).orElse(null);
        if (profile == null) {
            log.warn("injectForProfile: profile not found (profileId={})", profileId);
            return 0;
        }

        List<String> scripts = qaScriptGenerator.generateAll(profile);
        if (scripts == null || scripts.isEmpty()) {
            log.debug("injectForProfile: no scripts (profileId={})", profileId);
            return 0;
        }

        int added = 0;
        long totalBytes = 0;

        for (String src : scripts) {
            if (src == null || src.isBlank()) continue;

            int bytes = src.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

            if (bytes > maxScriptBytes) {
                log.warn("Skip script: too large ({} bytes > {}), profileId={}", bytes, maxScriptBytes, profileId);
                continue;
            }
            if (totalBytes + bytes > maxTotalBytes) {
                log.warn("Stop injecting: total bytes limit reached ({} > {}), profileId={}",
                        (totalBytes + bytes), maxTotalBytes, profileId);
                break;
            }

            // ✅ важное: работает только в page-WS
            cdp.send("Page.addScriptToEvaluateOnNewDocument",
                    Map.of("source", src),
                    cmdTimeoutMs);

            added++;
            totalBytes += bytes;
        }

        log.info("Injected {} script(s) via existing CDP session (profileId={}, totalBytes={})",
                added, profileId, totalBytes);

        return added;
    }




    public boolean injectScripts(String wsUrl, Long profileId, List<String> scripts) {
        try (DevToolsSession cdp = devToolsClient.connect(wsUrl)) {

            long cmdTimeoutMs = 3000; // или @Value("${browser.cdp.cmd-timeout-ms:3000}")
            cdp.enableCommonDomains(cmdTimeoutMs);

            for (String src : scripts) {
                if (src == null || src.isBlank()) continue;

                cdp.send("Page.addScriptToEvaluateOnNewDocument",
                        Map.of("source", src),
                        cmdTimeoutMs
                );
            }

            // опционально: применить сразу, если страница уже открыта
            for (String src : scripts) {
                if (src == null || src.isBlank()) continue;
                cdp.send("Runtime.evaluate",
                        Map.of("expression", src, "returnByValue", true),
                        cmdTimeoutMs
                );
            }

            log.info("Injected {} script(s) via CDP (profileId={})", scripts.size(), profileId);
            return true;

        } catch (Exception e) {
            log.warn("CDP injection failed (profileId={}): {}", profileId, e.getMessage(), e);
            return false;
        }
    }

}

