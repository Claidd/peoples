package com.hunt.peoples.browser.config.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.hunt.peoples.browser.config.DevToolsClient;
import com.hunt.peoples.browser.config.DevToolsSession;
import com.hunt.peoples.browser.config.DevToolsTargetsResolver;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class CdpBrowserApi {

    private final DevToolsClient devToolsClient;
    private final DevToolsTargetsResolver targets;

    // дефолтные таймауты
    private static final long TMO_SHORT = 2_000;
    private static final long TMO_MED   = 5_000;

    @Getter
    public static final class TabInfo {
        private final String targetId;
        private final String pageWsUrl;

        public TabInfo(String targetId, String pageWsUrl) {
            this.targetId = targetId;
            this.pageWsUrl = pageWsUrl;
        }
    }

    // -------------------------
    // Tabs (Browser target)
    // -------------------------

    /** Overload без timeout */
    public TabInfo openTab(String devToolsBaseUrl, String url) {
        return openTab(devToolsBaseUrl, url, TMO_MED);
    }

    /** Создать вкладку и попытаться сразу вернуть page wsUrl (может быть null на момент создания) */
    public TabInfo openTab(String devToolsBaseUrl, String url, long timeoutMs) {
        String browserWs = targets.resolveBrowserWsUrl(devToolsBaseUrl);
        if (browserWs == null) throw new IllegalStateException("Browser wsUrl is null: " + devToolsBaseUrl);

        try (DevToolsSession browser = devToolsClient.connect(browserWs)) {
            JsonNode resp = browser.send("Target.createTarget", Map.of("url", url), timeoutMs);
            String targetId = resp.path("result").path("targetId").asText(null);
            if (targetId == null || targetId.isBlank()) {
                throw new IllegalStateException("Target.createTarget returned empty targetId: " + resp);
            }

            // page ws может появиться не мгновенно — пробуем сразу, но допускаем null
            String pageWs = targets.resolvePageWsUrlByTargetId(devToolsBaseUrl, targetId);
            if (pageWs != null && !pageWs.contains("/devtools/page/")) pageWs = null;

            return new TabInfo(targetId, pageWs);
        }
    }

    /** Overload без timeout */
    public void closeTab(String devToolsBaseUrl, String targetId) {
        closeTab(devToolsBaseUrl, targetId, TMO_SHORT);
    }

    public void closeTab(String devToolsBaseUrl, String targetId, long timeoutMs) {
        String browserWs = targets.resolveBrowserWsUrl(devToolsBaseUrl);
        if (browserWs == null) throw new IllegalStateException("Browser wsUrl is null: " + devToolsBaseUrl);

        try (DevToolsSession browser = devToolsClient.connect(browserWs)) {
            browser.send("Target.closeTarget", Map.of("targetId", targetId), timeoutMs);
        }
    }

    /** Overload без timeout */
    public void activateTab(String devToolsBaseUrl, String targetId) {
        activateTab(devToolsBaseUrl, targetId, TMO_SHORT);
    }

    public void activateTab(String devToolsBaseUrl, String targetId, long timeoutMs) {
        String browserWs = targets.resolveBrowserWsUrl(devToolsBaseUrl);
        if (browserWs == null) throw new IllegalStateException("Browser wsUrl is null: " + devToolsBaseUrl);

        try (DevToolsSession browser = devToolsClient.connect(browserWs)) {
            browser.send("Target.activateTarget", Map.of("targetId", targetId), timeoutMs);
        }
    }

    // -------------------------
    // Connect to page target
    // -------------------------

    /** Подключиться к PAGE ws по targetId (через /json/list) */
    public DevToolsSession connectToPageByTargetId(String devToolsBaseUrl, String targetId) {
        String pageWs = targets.resolvePageWsUrlByTargetId(devToolsBaseUrl, targetId);
        if (pageWs == null || !pageWs.contains("/devtools/page/")) {
            throw new IllegalStateException("Page wsUrl not found for targetId=" + targetId + " base=" + devToolsBaseUrl);
        }
        return devToolsClient.connect(pageWs);
    }

    /** Подключиться к первой PAGE вкладке */
    public DevToolsSession connectToFirstPage(String devToolsBaseUrl) {
        String pageWs = targets.resolveFirstPageWsUrl(devToolsBaseUrl);
        if (pageWs == null) throw new IllegalStateException("First PAGE wsUrl not found: " + devToolsBaseUrl);
        return devToolsClient.connect(pageWs);
    }

    // -------------------------
    // Blocking (Page target)
    // -------------------------

    /** Блокировать картинки через Fetch */
    public AutoCloseable blockImages(DevToolsSession page, long timeoutMs) {
        page.enableCommonDomains(timeoutMs);

        page.send("Fetch.enable", Map.of(
                "patterns", List.of(Map.of("urlPattern", "*", "requestStage", "Request"))
        ), timeoutMs);

        AutoCloseable unsub = page.onEvent("Fetch.requestPaused", evt -> {
            try {
                JsonNode p = evt.path("params");
                String requestId = p.path("requestId").asText("");
                String resourceType = p.path("resourceType").asText("");

                if (requestId.isBlank()) return;

                if ("Image".equals(resourceType)) {
                    page.safeSend("Fetch.failRequest", Map.of(
                            "requestId", requestId,
                            "errorReason", "BlockedByClient"
                    ), 2000);
                } else {
                    page.safeSend("Fetch.continueRequest", Map.of("requestId", requestId), 2000);
                }
            } catch (Exception e) {
                log.debug("blockImages handler error: {}", e.getMessage());
            }
        });

        // Отключение: Fetch.disable + отписка
        return () -> {
            try { unsub.close(); } catch (Exception ignore) {}
            try { page.safeSend("Fetch.disable", 2000); } catch (Exception ignore) {}
        };
    }

    /** Блокировать по url-паттернам через Network.setBlockedURLs (быстрее, но без resourceType) */
    public AutoCloseable blockByUrlPattern(DevToolsSession page, List<String> patterns, long timeoutMs) {
        page.enableCommonDomains(timeoutMs);
        List<String> p = (patterns == null) ? List.of() : patterns;

        // сохраняем текущий список нельзя (CDP не даёт getBlockedURLs), поэтому просто ставим и при close -> очищаем
        page.send("Network.setBlockedURLs", Map.of("urls", p), timeoutMs);

        return () -> {
            try { page.safeSend("Network.setBlockedURLs", Map.of("urls", List.of()), 2000); } catch (Exception ignore) {}
        };
    }

    // -------------------------
    // Page actions
    // -------------------------

    public void navigate(DevToolsSession page, String url, long timeoutMs) {
        page.enableCommonDomains(3000);

        CompletableFuture<Void> loaded = new CompletableFuture<>();
        AutoCloseable unsub = page.onEvent("Page.loadEventFired", evt -> loaded.complete(null));

        try {
            page.send("Page.navigate", Map.of("url", url), timeoutMs);

            // ждём loadEventFired
            loaded.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("navigate failed: " + e.getMessage(), e);
        } finally {
            try { unsub.close(); } catch (Exception ignore) {}
        }
    }

    /** Вернёт PNG bytes */
    public byte[] screenshotPng(DevToolsSession page, long timeoutMs) {
        page.enableCommonDomains(3000);

        JsonNode resp = page.send("Page.captureScreenshot", Map.of(
                "format", "png",
                "fromSurface", true
        ), timeoutMs);

        String b64 = resp.path("result").path("data").asText(null);
        if (b64 == null || b64.isBlank()) {
            throw new IllegalStateException("captureScreenshot returned empty data");
        }
        return Base64.getDecoder().decode(b64);
    }

    public void click(DevToolsSession page, int x, int y, long timeoutMs) {
        page.enableCommonDomains(3000);

        // mousePressed
        page.send("Input.dispatchMouseEvent", Map.of(
                "type", "mousePressed",
                "x", x,
                "y", y,
                "button", "left",
                "clickCount", 1
        ), timeoutMs);

        // mouseReleased
        page.send("Input.dispatchMouseEvent", Map.of(
                "type", "mouseReleased",
                "x", x,
                "y", y,
                "button", "left",
                "clickCount", 1
        ), timeoutMs);
    }

    /** Набор текста (лучше чем keyDown/keyUp для обычного текста) */
    public void typeText(DevToolsSession page, String text, long timeoutMs) {
        page.enableCommonDomains(3000);

        if (text == null) return;
        // Input.insertText поддерживается в Chrome; для спец-клавиш делай dispatchKeyEvent отдельно
        page.send("Input.insertText", Map.of("text", text), timeoutMs);
    }

    /** Нажать Enter */
    public void pressEnter(DevToolsSession page, long timeoutMs) {
        page.enableCommonDomains(3000);

        page.send("Input.dispatchKeyEvent", Map.of(
                "type", "keyDown",
                "key", "Enter",
                "code", "Enter",
                "windowsVirtualKeyCode", 13,
                "nativeVirtualKeyCode", 13
        ), timeoutMs);

        page.send("Input.dispatchKeyEvent", Map.of(
                "type", "keyUp",
                "key", "Enter",
                "code", "Enter",
                "windowsVirtualKeyCode", 13,
                "nativeVirtualKeyCode", 13
        ), timeoutMs);
    }
}


