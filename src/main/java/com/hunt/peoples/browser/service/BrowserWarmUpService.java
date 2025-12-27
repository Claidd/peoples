package com.hunt.peoples.browser.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hunt.peoples.browser.config.DevToolsSession;
import com.hunt.peoples.profiles.entity.Profile;
import com.hunt.peoples.profiles.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
@Service
@Slf4j
@RequiredArgsConstructor
public class BrowserWarmUpService {

    private final ProfileRepository profilesRepository;

    /**
     * Основной метод прогрева: имитирует реальное поведение пользователя
     */
    public void runWarmUp(DevToolsSession cdp, Profile profile) {
        List<String> urls = profile.getCommonWebsites();
        if (urls == null || urls.isEmpty()) {
            log.info("⏭️ Список сайтов пуст, прогрев отменен для профиля {}", profile.getId());
            return;
        }

        try {
            log.info("🚀 Начинаем мобильный цикл прогрева для профиля {}", profile.getId());
            int typingSpeed = profile.getTypingSpeed() > 0 ? profile.getTypingSpeed() : 100;

            // Перед началом убедимся, что мы на чистой странице
            cdp.send("Page.navigate", Map.of("url", "about:blank"), 10000L);

            for (String targetUrl : urls) {
                // Извлекаем чистый домен для поиска
                String domainOnly = targetUrl.replace("https://", "")
                        .replace("http://", "")
                        .replace("www.", "")
                        .split("/")[0];

                log.info("🔍 Имитация органического перехода на: {}", domainOnly);

                // Переходим через поиск Google
                boolean searchSuccess = performSearchAndNavigate(cdp, domainOnly, typingSpeed);

                if (searchSuccess) {
                    // Даем странице "прогрузиться" и пожить
                    randomSleep(3000, 5000);

                    clickCommonAcceptButtons(cdp);
                    simulateHumanActivity(cdp);

                    // Имитация чтения контента
                    log.info("📖 Имитация чтения контента...");
                    randomSleep(7000, 15000);
                }
            }

            // ВАЖНО: Финализация. Сохраняем куки в БД после всех манипуляций.
            log.info("💾 Сохранение накопленных куки в репозиторий...");
            saveProfileCookies(cdp, profile);

        } catch (Exception e) {
            log.error("❌ Критическая ошибка при прогреве профиля {}: {}", profile.getId(), e.getMessage());
        } finally {
            try {
                // Возвращаемся на пустую страницу, чтобы остановить тяжелые скрипты сайтов
                cdp.send("Page.navigate", Map.of("url", "about:blank"), 5000L);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Имитирует ввод названия сайта в Google и клик по результату.
     * Возвращает true, если переход удался.
     */
    private boolean performSearchAndNavigate(DevToolsSession cdp, String query, int typingSpeed) {
        try {
            cdp.send("Page.navigate", Map.of("url", "https://www.google.com"), 30000L);
            waitForSmartLoad(cdp);

            // Закрываем плашки Google (Consent)
            clickCommonAcceptButtons(cdp);

            String searchSelector = "textarea[name='q'], input[name='q'], [role='combobox']";

            // Тапаем в поиск
            tapElement(cdp, searchSelector);
            randomSleep(400, 800);

            // Печатаем домен
            typeTextWithHumanErrors(cdp, searchSelector, query, typingSpeed);
            randomSleep(500, 1000);

            sendEnterKey(cdp);
            waitForSmartLoad(cdp);

            // Кликаем по результату. Используем более точный селектор для мобильной выдачи.
            log.info("🖱️ Ищем ссылку на {} в выдаче...", query);
            String resultSelector = "h3, .g a, a h3, [role='link'] h3";
            tapElement(cdp, resultSelector);

            waitForSmartLoad(cdp);
            return true;
        } catch (Exception e) {
            log.warn("⚠️ Поиск не удался для {}, прямой переход...", query);
            try {
                cdp.send("Page.navigate", Map.of("url", "https://" + query), 20000L);
                waitForSmartLoad(cdp);
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }

    /**
     * Посимвольный ввод с имитацией опечаток
     */
    public void typeTextWithHumanErrors(DevToolsSession cdp, String selector, String text, int baseSpeed) {
        try {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);

                // Опечатка (3% шанс)
                if (Math.random() < 0.03 && i > 0) {
                    sendKey(cdp, "q"); // Ошибка
                    randomSleep(150, 300);
                    sendKey(cdp, "Backspace");
                    randomSleep(200, 400);
                }

                sendKey(cdp, String.valueOf(c));

                // Задержка между клавишами
                long delay = (60000 / (Math.max(baseSpeed, 50) * 5)) + (long)(Math.random() * 80);
                Thread.sleep(delay);
            }
        } catch (Exception e) {
            log.error("❌ Ошибка ввода: {}", e.getMessage());
        }
    }

    private void sendKey(DevToolsSession cdp, String key) {
        Map<String, Object> params = new HashMap<>(Map.of("type", "keyDown", "text", key, "unmodifiedText", key));
        if (key.equals("Backspace")) params.put("windowsVirtualKeyCode", 8);

        cdp.send("Input.dispatchKeyEvent", params, 5000L);
        try { Thread.sleep(40 + (long)(Math.random() * 30)); } catch (InterruptedException ignored) {}
        cdp.send("Input.dispatchKeyEvent", Map.of("type", "keyUp"), 5000L);
    }

    /**
     * Органический мобильный свайп
     */
    private void simulateHumanActivity(DevToolsSession cdp) throws InterruptedException {
        int swipes = 2 + (int)(Math.random() * 3);
        for (int i = 0; i < swipes; i++) {
            int startY = 700 + (int)(Math.random() * 150);
            int endY = 200 + (int)(Math.random() * 150);
            int startX = 150 + (int)(Math.random() * 50);

            cdp.send("Input.dispatchTouchEvent", Map.of("type", "touchStart",
                    "touchPoints", List.of(Map.of("x", startX, "y", startY))), 5000L);

            int steps = 15;
            for (int j = 1; j <= steps; j++) {
                double t = (double) j / steps;
                int curX = startX + (int)(Math.sin(t * Math.PI) * 10);
                int curY = startY + (int)((endY - startY) * t);

                cdp.send("Input.dispatchTouchEvent", Map.of("type", "touchMove",
                        "touchPoints", List.of(Map.of("x", curX, "y", curY))), 1000L);
                Thread.sleep(40 + (int)(Math.random() * 20));
            }

            cdp.send("Input.dispatchTouchEvent", Map.of("type", "touchEnd", "touchPoints", List.of()), 5000L);
            randomSleep(1000, 3000);
        }
    }

    private void waitForSmartLoad(DevToolsSession cdp) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            Thread.sleep(1000);
            try {
                var rs = cdp.send("Runtime.evaluate", Map.of("expression", "document.readyState", "returnByValue", true), 5000L);
                if ("complete".equals(rs.path("result").path("value").asText())) {
                    Thread.sleep(2000); // Даем время на рендеринг JS
                    return;
                }
            } catch (Exception e) {
                log.debug("Waiting for page load...");
            }
        }
    }

    private void clickCommonAcceptButtons(DevToolsSession cdp) {
        String js = "(() => { " +
                "  const selectors = ['button[aria-label*=\"Accept\"]', '#L2AGLb', 'button[aria-label*=\"принять\"]', 'button[id*=\"consent\"]']; " +
                "  for (let s of selectors) { " +
                "    const el = document.querySelector(s); " +
                "    if (el && el.offsetHeight > 0) { el.click(); return true; } " +
                "  } " +
                "  const btn = Array.from(document.querySelectorAll('button')) " +
                "    .find(el => /принять|согласен|accept|agree|ok/i.test(el.innerText)); " +
                "  if(btn) btn.click(); " +
                "})();";
        try {
            cdp.send("Runtime.evaluate", Map.of("expression", js), 5000L);
        } catch (Exception ignored) {}
    }

    public void tapElement(DevToolsSession cdp, String selector) {
        try {
            String js = "(() => { " +
                    "  const el = document.querySelector(\"" + selector + "\"); " +
                    "  if(!el) return null; " +
                    "  el.scrollIntoView({block: 'center', behavior: 'instant'}); " +
                    "  const r = el.getBoundingClientRect(); " +
                    "  return {x: r.left + r.width/2, y: r.top + r.height/2}; " +
                    "})()";
            var res = cdp.send("Runtime.evaluate", Map.of("expression", js, "returnByValue", true), 5000L);
            var val = res.path("result").path("value");

            if (!val.isNull() && !val.isMissingNode()) {
                int x = val.get("x").asInt() + (int)(Math.random() * 10 - 5);
                int y = val.get("y").asInt() + (int)(Math.random() * 10 - 5);

                cdp.send("Input.dispatchTouchEvent", Map.of("type", "touchStart", "touchPoints", List.of(Map.of("x", x, "y", y))), 5000L);
                Thread.sleep(100);
                cdp.send("Input.dispatchTouchEvent", Map.of("type", "touchEnd", "touchPoints", List.of()), 5000L);
            }
        } catch (Exception e) {
            log.warn("Tap failed on {}: {}", selector, e.getMessage());
        }
    }

    private void sendEnterKey(DevToolsSession cdp) {
        cdp.send("Input.dispatchKeyEvent", Map.of("type", "keyDown", "windowsVirtualKeyCode", 13), 5000L);
        cdp.send("Input.dispatchKeyEvent", Map.of("type", "keyUp", "windowsVirtualKeyCode", 13), 5000L);
    }

    private void saveProfileCookies(DevToolsSession cdp, Profile profile) {
        try {
            // Получаем ВСЕ куки (включая HttpOnly)
            var response = cdp.send("Network.getAllCookies", Map.of(), 15000L);
            JsonNode cookies = response.path("cookies");

            if (!cookies.isMissingNode() && cookies.isArray()) {
                profile.setCookiesJson(cookies.toString());
                profilesRepository.save(profile);
                log.info("✅ Успешно сохранено {} куки в БД для профиля {}", cookies.size(), profile.getId());
            }
        } catch (Exception e) {
            log.error("❌ Ошибка при экспорте куки: {}", e.getMessage());
        }
    }

    private void randomSleep(long min, long max) throws InterruptedException {
        Thread.sleep(min + (long)(Math.random() * (max - min)));
    }
}