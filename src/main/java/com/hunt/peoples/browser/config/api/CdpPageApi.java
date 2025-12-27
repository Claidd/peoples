package com.hunt.peoples.browser.config.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.hunt.peoples.browser.config.DevToolsSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class CdpPageApi {

    /** ✅ блокируем картинки через Fetch */
    public AutoCloseable blockImages(DevToolsSession page, long timeoutMs) {

        page.safeSend("Fetch.enable", Map.of(
                "patterns", List.of(
                        Map.of(
                                "urlPattern", "*",
                                "resourceType", "Image",
                                "requestStage", "Request"
                        )
                )
        ), timeoutMs);

        AutoCloseable unsub = page.onEvent("Fetch.requestPaused", evt -> {
            JsonNode p = evt.path("params");
            String requestId = p.path("requestId").asText(null);
            if (requestId == null) return;

            try {
                page.send("Fetch.failRequest", Map.of(
                        "requestId", requestId,
                        "errorReason", "BlockedByClient"
                ), 2000);
            } catch (Exception ignore) {}
        });

        return () -> {
            try { unsub.close(); } catch (Exception ignore) {}
            page.safeSend("Fetch.disable", timeoutMs);
        };
    }


    public void navigate(DevToolsSession page, String url, long timeoutMs) {
        page.send("Page.navigate", Map.of("url", url), timeoutMs);
    }

    /** ✅ ждём loadEventFired после навигации */
    public void navigateAndWait(DevToolsSession page, String url, long timeoutMs) {
        waitForNavigation(page, () -> navigate(page, url, 10000), timeoutMs);
    }

    /** ✅ универсально: подписались -> сделали action -> ждём loadEventFired */
    public void waitForNavigation(DevToolsSession page, Runnable action, long timeoutMs) {
        CompletableFuture<Void> loaded = new CompletableFuture<>();

        AutoCloseable unsub = page.onEvent("Page.loadEventFired", evt -> loaded.complete(null));

        try {
            action.run();
            loaded.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("waitForNavigation timeout/fail: " + e.getMessage(), e);
        } finally {
            try { unsub.close(); } catch (Exception ignore) {}
        }
    }

    public byte[] screenshotPng(DevToolsSession page, long timeoutMs) {
        JsonNode resp = page.send("Page.captureScreenshot", Map.of(
                "format", "png",
                "fromSurface", true
        ), timeoutMs);

        String b64 = resp.path("result").path("data").asText(null);
        if (b64 == null) return null;
        return Base64.getDecoder().decode(b64);
    }

    public String getHtml(DevToolsSession page, long timeoutMs) {
        JsonNode resp = page.evaluate("document.documentElement ? document.documentElement.outerHTML : ''", timeoutMs);
        return resp.path("result").path("result").path("value").asText("");
    }

    public String queryText(DevToolsSession page, String css, long timeoutMs) {
        String expr =
                "(function(){try{" +
                        "const el=document.querySelector(" + jsString(css) + ");" +
                        "return el ? (el.innerText || el.textContent || '') : '';" +
                        "}catch(e){return ''}})()";

        JsonNode resp = page.evaluate(expr, timeoutMs);
        return resp.path("result").path("result").path("value").asText("");
    }



    private String jsString(String s) {
        if (s == null) s = "";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
