package com.hunt.peoples.profiles.service;


import com.hunt.peoples.profiles.entity.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;


@Component
@RequiredArgsConstructor
@Slf4j
public class QaScriptGenerator {

    private final ObjectMapper objectMapper;

    // Модульные флаги (можно конфигурировать)
    @Value("${browser.cdp.inject.mod.consistency:true}")
    private boolean modConsistency;

    @Value("${browser.cdp.inject.mod.localeTimezone:true}")
    private boolean modLocaleTimezone;

    @Value("${browser.cdp.inject.mod.webgl:true}")
    private boolean modWebgl;

    @Value("${browser.cdp.inject.mod.canvas:true}")
    private boolean modCanvas;

    @Value("${browser.cdp.inject.mod.permissions:true}")
    private boolean modPermissions;

    @Value("${browser.cdp.inject.mod.mediaDevices:false}")
    private boolean modMediaDevices;

    @Value("${browser.cdp.inject.mod.webrtc:false}")
    private boolean modWebrtc;

    @Value("${browser.cdp.inject.mod.performance:true}")
    private boolean modPerformance;

    /**
     * Главный метод: возвращает список небольших модулей-скриптов.
     */
    public List<String> generateAll(Profile profile) {
        if (profile == null) return List.of();

        List<String> scripts = new ArrayList<>();

        String seed = buildSeed(profile);

        // 1) consistency: webdriver, platform/vendor, chrome, userAgentData и т.п.
        if (modConsistency) {
            scripts.add(scriptConsistency(profile, seed));
        }

        // 2) locale/timezone (JS-side) — CDP emulation всё равно главная
        if (modLocaleTimezone) {
            scripts.add(scriptLocaleTimezone(profile, seed));
        }

        // 3) WebGL vendor/renderer + блок debug extension
        if (modWebgl) {
            scripts.add(scriptWebgl(profile, seed));
        }

        // 4) Canvas: минимальный детерминированный “noise”
        if (modCanvas) {
            scripts.add(scriptCanvas(profile, seed));
        }

        // 5) Permissions API (часто дергают детекторы)
        if (modPermissions) {
            scripts.add(scriptPermissions(profile, seed));
        }

        // 6) MediaDevices (может ломать сайты, поэтому по умолчанию false)
        if (modMediaDevices) {
            scripts.add(scriptMediaDevices(profile, seed));
        }

        // 7) WebRTC (может ломать звонки/чат, поэтому по умолчанию false)
        if (modWebrtc) {
            scripts.add(scriptWebrtc(profile, seed));
        }

        // 8) performance.now jitter (детерминированный, маленький)
        if (modPerformance) {
            scripts.add(scriptPerformance(profile, seed));
        }

        // чистим пустые
        scripts.removeIf(s -> s == null || s.isBlank());

        // debug по размерам
        if (log.isDebugEnabled()) {
            long total = 0;
            for (int i = 0; i < scripts.size(); i++) {
                int b = scripts.get(i).getBytes(StandardCharsets.UTF_8).length;
                total += b;
                log.debug("QA script #{} bytes={}", i + 1, b);
            }
            log.debug("QA scripts total={}, count={}", total, scripts.size());
        }

        return scripts;
    }

    // ------------------------------
    // Modules
    // ------------------------------

    private String scriptConsistency(Profile p, String seed) {
        boolean ios = isIOS(p);
        String ua = nvl(p.getUserAgent(), "");
        String platform = nvl(p.getPlatform(), ios ? "iPhone" : "Linux armv8l");
        String vendor = ios ? "Apple Computer, Inc." : "Google Inc.";

        // ВАЖНО: не делаем configurable:false — иначе можно поломать сайты/расширения.
        // Для антидетекта лучше “аккуратно и совместимо”, чем “жёстко и ломко”.
        return """
        (() => {
          try {
            const SEED = %s;

            const defineGet = (obj, prop, val) => {
              try {
                Object.defineProperty(obj, prop, { get: () => val, configurable: true });
              } catch (e) {}
            };

            // webdriver -> undefined
            try { defineGet(Navigator.prototype, 'webdriver', undefined); } catch (e) {}

            // platform/vendor
            try { defineGet(Navigator.prototype, 'platform', %s); } catch (e) {}
            try { defineGet(Navigator.prototype, 'vendor', %s); } catch (e) {}

            // userAgent (часто реально лучше через CDP, но JS тоже подстрахуем)
            try { defineGet(Navigator.prototype, 'userAgent', %s); } catch (e) {}

            // userAgentData: если iOS/Safari-like — лучше спрятать
            const isIOS = %s;
            if (isIOS) {
              try { defineGet(Navigator.prototype, 'userAgentData', undefined); } catch (e) {}
              try { defineGet(window, 'chrome', undefined); } catch (e) {}
            } else {
              // На Chromium userAgentData существует — не удаляем, чтобы не было “дыры”
              // но можно подправить brands через CDP Emulation.setUserAgentOverride.
            }

            // убрать типовые selenium/puppeteer артефакты (мягко)
            const bad = [
              'cdc_adoQpoasnfa76pfcZLmcfl',
              '_Selenium_IDE_Recorder',
              '__nightmare',
              'callPhantom',
              '_phantom'
            ];
            for (const k of bad) {
              try { if (k in window) delete window[k]; } catch (e) {}
            }

          } catch (e) {}
        })();
        """.formatted(
                jsString(seed),
                jsString(platform),
                jsString(vendor),
                jsString(ua),
                ios ? "true" : "false"
        );
    }

    private String scriptLocaleTimezone(Profile p, String seed) {
        // Лучше всего TZ/locale делать через CDP emulation, но этот скрипт помогает,
        // когда сайты дергают Intl/Date напрямую.
        String lang = nvl(p.getLanguage(), "en-US");
        String locale = nvl(p.getLocale(), lang);
        Integer tzOffset = p.getTimezoneOffset(); // минуты, как Date.getTimezoneOffset()
        if (tzOffset == null) tzOffset = 0;

        // timeZone строкой лучше хранить в профиле (Europe/Moscow, Asia/Shanghai…)
        String tzName = nvl(p.getTimezone(), "UTC");

        return """
        (() => {
          try {
            const defineGet = (obj, prop, val) => {
              try { Object.defineProperty(obj, prop, { get: () => val, configurable: true }); } catch (e) {}
            };

            // language(s)
            try { defineGet(Navigator.prototype, 'language', %s); } catch (e) {}
            try { defineGet(Navigator.prototype, 'languages', [%s, %s]); } catch (e) {}

            // Date.getTimezoneOffset
            try {
              const orig = Date.prototype.getTimezoneOffset;
              Date.prototype.getTimezoneOffset = function() { return %d; };
              // сохраним ссылку (иногда проверяют "native code")
              defineGet(Date.prototype.getTimezoneOffset, 'toString', orig.toString.bind(orig));
            } catch (e) {}

            // Intl.DateTimeFormat().resolvedOptions().timeZone
            try {
              const orig = Intl.DateTimeFormat.prototype.resolvedOptions;
              Intl.DateTimeFormat.prototype.resolvedOptions = function() {
                const o = orig.call(this);
                try { o.timeZone = %s; } catch (e) {}
                return o;
              };
            } catch (e) {}

          } catch (e) {}
        })();
        """.formatted(
                jsString(lang),
                jsString(lang),
                jsString(locale),
                tzOffset,
                jsString(tzName)
        );
    }

    private String scriptWebgl(Profile p, String seed) {
        String vendor = nvl(p.getWebglVendor(), "Google Inc.");
        String renderer = nvl(p.getWebglRenderer(), "ANGLE (Google, Vulkan 1.3)");
        String version = nvl(p.getWebglVersion(), "WebGL 1.0");

        return """
        (() => {
          try {
            const VENDOR = %s;
            const RENDERER = %s;
            const VERSION = %s;

            const badExt = new Set([
              'WEBGL_debug_renderer_info',
              'WEBGL_debug_shaders'
            ]);

            const hook = (proto) => {
              if (!proto || !proto.getParameter) return;

              const origGetParameter = proto.getParameter;
              proto.getParameter = function(pname) {
                // 37445 VENDOR, 37446 RENDERER, 37444 VERSION
                if (pname === 37445) return VENDOR;
                if (pname === 37446) return RENDERER;
                if (pname === 37444) return VERSION;
                return origGetParameter.call(this, pname);
              };

              const origGetExt = proto.getExtension;
              proto.getExtension = function(name) {
                if (badExt.has(name)) return null;
                return origGetExt.call(this, name);
              };

              const origSup = proto.getSupportedExtensions;
              proto.getSupportedExtensions = function() {
                const arr = origSup.call(this) || [];
                return arr.filter(x => !badExt.has(x));
              };
            };

            hook(WebGLRenderingContext && WebGLRenderingContext.prototype);
            hook(WebGL2RenderingContext && WebGL2RenderingContext.prototype);

          } catch (e) {}
        })();
        """.formatted(
                jsString(vendor),
                jsString(renderer),
                jsString(version)
        );
    }

    private String scriptCanvas(Profile p, String seed) {
        // Детерминированный PRNG, чтобы отпечаток был стабильным на профиле
        // и не “прыгал” от запроса к запросу.
        return """
        (() => {
          try {
            const SEED = %s;

            // xorshift32 от seed-хэша
            let s = 0;
            for (let i = 0; i < SEED.length; i++) s = (s * 31 + SEED.charCodeAt(i)) | 0;
            const rnd = () => {
              s ^= s << 13; s |= 0;
              s ^= s >>> 17; s |= 0;
              s ^= s << 5; s |= 0;
              return (s >>> 0) / 4294967296;
            };

            // 2D getImageData noise (микро, детерминированный)
            const orig = CanvasRenderingContext2D.prototype.getImageData;
            CanvasRenderingContext2D.prototype.getImageData = function(sx, sy, sw, sh) {
              const img = orig.call(this, sx, sy, sw, sh);
              try {
                if (img && img.data && img.data.length >= 16) {
                  // меняем только пару байт, чтобы не ломать рендер
                  const i = 4 * (1 + ((rnd() * 3) | 0));
                  img.data[i] = (img.data[i] + 1) & 255;
                  img.data[i + 1] = (img.data[i + 1] + 1) & 255;
                }
              } catch (e) {}
              return img;
            };

            // toDataURL / toBlob оставляем без шума (часто ломает графику/капчи)

          } catch (e) {}
        })();
        """.formatted(jsString(seed));
    }

    private String scriptPermissions(Profile p, String seed) {
        // Частая проверка: navigator.permissions.query({name:'notifications'})
        // Делать “granted” не стоит — подозрительно. Обычно "default"/"prompt".
        String notif = nvl(p.getNotificationPermission(), "default"); // default|denied|granted
        return """
        (() => {
          try {
            const PERM = %s;

            if (navigator.permissions && navigator.permissions.query) {
              const orig = navigator.permissions.query.bind(navigator.permissions);

              navigator.permissions.query = (params) => {
                try {
                  if (params && params.name === 'notifications') {
                    return Promise.resolve({ state: PERM, onchange: null });
                  }
                } catch (e) {}
                return orig(params);
              };
            }

            if ('Notification' in window && window.Notification && window.Notification.permission) {
              try {
                Object.defineProperty(Notification, 'permission', { get: () => PERM, configurable: true });
              } catch (e) {}
            }

          } catch (e) {}
        })();
        """.formatted(jsString(notif));
    }

    private String scriptMediaDevices(Profile p, String seed) {
        // Осторожно: это может ломать реальные сайты с getUserMedia.
        // По умолчанию выключено флагом.
        return """
        (() => {
          try {
            if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) return;

            const orig = navigator.mediaDevices.enumerateDevices.bind(navigator.mediaDevices);

            navigator.mediaDevices.enumerateDevices = async () => {
              const list = await orig();
              // скрываем label пока нет permission — это норма браузера
              return list.map(d => ({
                deviceId: d.deviceId ? 'default' : '',
                groupId: d.groupId ? 'default' : '',
                kind: d.kind,
                label: '',
                toJSON: d.toJSON ? d.toJSON.bind(d) : undefined
              }));
            };
          } catch (e) {}
        })();
        """;
    }

    private String scriptWebrtc(Profile p, String seed) {
        // Очень “скользкий” модуль: часто ломает звонки/чат.
        // Даю мягкий вариант: фильтруем host candidates.
        return """
        (() => {
          try {
            if (!window.RTCPeerConnection) return;

            const Orig = window.RTCPeerConnection;
            window.RTCPeerConnection = function(cfg) {
              const pc = new Orig(cfg);

              const addIce = pc.addIceCandidate;
              pc.addIceCandidate = function(candidate, ...rest) {
                try {
                  const c = candidate && candidate.candidate ? candidate.candidate : '';
                  if (typeof c === 'string' && c.includes(' typ host ')) {
                    return Promise.resolve();
                  }
                } catch (e) {}
                return addIce.call(this, candidate, ...rest);
              };

              return pc;
            };
          } catch (e) {}
        })();
        """;
    }

    private String scriptPerformance(Profile p, String seed) {
        // Микро-сдвиг performance.now(), детерминированный от seed (стабильный для профиля)
        return """
        (() => {
          try {
            const SEED = %s;
            let h = 0;
            for (let i = 0; i < SEED.length; i++) h = (h * 33 + SEED.charCodeAt(i)) | 0;
            const offset = (Math.abs(h) %% 7) * 0.01; // 0..0.06ms

            if (performance && performance.now) {
              const orig = performance.now.bind(performance);
              performance.now = () => orig() + offset;
            }
          } catch (e) {}
        })();
        """.formatted(jsString(seed));
    }

    // ------------------------------
    // Utils
    // ------------------------------

    private String buildSeed(Profile p) {
        // важно: стабильный seed для профиля
        String key = nvl(p.getExternalKey(), "");
        if (!key.isBlank()) return "k:" + key;
        if (p.getId() != null) return "id:" + p.getId();
        return "fallback:" + System.identityHashCode(p);
    }

    private boolean isIOS(Profile p) {
        String ua = nvl(p.getUserAgent(), "");
        String pl = nvl(p.getPlatform(), "").toLowerCase();
        return ua.contains("iPhone") || ua.contains("iPad") || ua.contains("CPU iPhone OS")
                || pl.contains("iphone") || pl.contains("ipad");
    }

    private String nvl(String s, String def) {
        return (s == null || s.isBlank()) ? def : s;
    }

    private String jsString(String s) {
        if (s == null) s = "";
        // JSON-эскейп (надежнее ручного)
        try {
            return objectMapper.writeValueAsString(s);
        } catch (Exception e) {
            // fallback
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }
}


//@Component
//public class QaScriptGenerator {
//
//    public List<String> generateAll(Profile profile) {
//        List<String> scripts = new ArrayList<>();
//        scripts.add(generateBootstrap(profile));
//        scripts.add(generateErrorTrap(profile));
//        return scripts;
//    }
//
//    private String generateBootstrap(Profile profile) {
//        Long id = profile.getId();
//        String key = profile.getExternalKey() == null ? "" : profile.getExternalKey();
//
//        // Никакого спуфинга/маскировки. Только “пометки” и диагностика.
//        return """
//            (function() {
//              try {
//                window.__MB_PROFILE = { id: %d, externalKey: %s, injectedAt: Date.now() };
//                console.debug("[MB] injected bootstrap", window.__MB_PROFILE);
//              } catch (e) {}
//            })();
//            """.formatted(id == null ? -1 : id, jsString(key));
//    }
//
//    private String generateErrorTrap(Profile profile) {
//        return """
//            (function() {
//              try {
//                window.addEventListener("error", function(ev) {
//                  console.warn("[MB] window.error", ev && (ev.message || ev));
//                });
//                window.addEventListener("unhandledrejection", function(ev) {
//                  console.warn("[MB] unhandledrejection", ev && (ev.reason || ev));
//                });
//              } catch (e) {}
//            })();
//            """;
//    }
//
//    private String jsString(String s) {
//        if (s == null) s = "";
//        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
//    }
//}

