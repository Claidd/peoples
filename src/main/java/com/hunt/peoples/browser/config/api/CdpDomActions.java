package com.hunt.peoples.browser.config.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.hunt.peoples.browser.config.DevToolsSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

//ждём document.querySelector(css)
//считаем координаты getBoundingClientRect()
//кликаем через Input.dispatchMouseEvent
//вводим через Input.insertText
//String base = buildDevToolsUrl(hostBaseUrl, hostDevToolsPort);
//
//        String targetId = cdpBrowserApi.openTab(base, "https://example.com", 5000);
//
//try (DevToolsSession page = cdpBrowserApi.connectToPageByTargetId(base, targetId)) {
//        page.enableCommonDomains(3000);
//
//        AutoCloseable block = cdpBrowserApi.blockImages(page, 3000);
//
//    cdpBrowserApi.navigate(page, "https://google.com", 15000);
//    cdpBrowserApi.click(page, 200, 200, 3000);
//    cdpBrowserApi.typeText(page, "hello", 3000);
//    cdpBrowserApi.pressEnter(page, 3000);
//
//        byte[] png = cdpBrowserApi.screenshotPng(page, 15000);
//
//    block.close();
//}


@Slf4j
@Component
public class CdpDomActions {

    // =========================
    // 0) WAIT FOR SELECTOR (APPEAR)
    // =========================

    public void waitForSelector(DevToolsSession page, String css, long timeoutMs) {
        waitForSelector(page, css, timeoutMs, 150);
    }

    public void waitForSelector(DevToolsSession page, String css, long timeoutMs, long pollMs) {
        Objects.requireNonNull(page, "page");

        long deadline = System.currentTimeMillis() + timeoutMs;

        String expr =
                "(function(){try{" +
                        "return document.querySelector(" + jsString(css) + ")!==null;" +
                        "}catch(e){return false}})()";

        while (System.currentTimeMillis() < deadline) {
            try {
                JsonNode r = page.evaluate(expr, 1500);
                boolean ok = r.path("result").path("result").path("value").asBoolean(false);
                if (ok) return;
            } catch (Exception ignore) {}
            sleep(pollMs);
        }

        throw new RuntimeException("waitForSelector timeout: " + css);
    }

    // =========================
    // 1) WAIT FOR SELECTOR GONE (DISAPPEAR)
    // =========================

    public void waitForSelectorGone(DevToolsSession page, String css, long timeoutMs) {
        waitForSelectorGone(page, css, timeoutMs, 150);
    }

    public void waitForSelectorGone(DevToolsSession page, String css, long timeoutMs, long pollMs) {
        Objects.requireNonNull(page, "page");
        long deadline = System.currentTimeMillis() + timeoutMs;

        String expr =
                "(function(){try{" +
                        "return document.querySelector(" + jsString(css) + ")===null;" +
                        "}catch(e){return true}})()";

        while (System.currentTimeMillis() < deadline) {
            try {
                JsonNode r = page.evaluate(expr, 1500);
                boolean gone = r.path("result").path("result").path("value").asBoolean(false);
                if (gone) return;
            } catch (Exception ignore) {}
            sleep(pollMs);
        }

        throw new RuntimeException("waitForSelectorGone timeout: " + css);
    }

    // =========================
    // 2) WAIT FOR TEXT CONTAINS
    // =========================

    /** ✅ Ждём пока element.textContent содержит нужную подстроку */
    public void waitForTextContains(DevToolsSession page, String css, String expectedSubstr, long timeoutMs) {
        waitForTextContains(page, css, expectedSubstr, timeoutMs, 200);
    }

    public void waitForTextContains(DevToolsSession page, String css, String expectedSubstr, long timeoutMs, long pollMs) {
        Objects.requireNonNull(page, "page");

        String needle = expectedSubstr == null ? "" : expectedSubstr;
        long deadline = System.currentTimeMillis() + timeoutMs;

        String expr =
                "(function(){try{" +
                        "const el=document.querySelector(" + jsString(css) + ");" +
                        "if(!el) return false;" +
                        "const t=(el.textContent||'');" +
                        "return t.includes(" + jsString(needle) + ");" +
                        "}catch(e){return false}})()";

        while (System.currentTimeMillis() < deadline) {
            try {
                JsonNode r = page.evaluate(expr, 1500);
                boolean ok = r.path("result").path("result").path("value").asBoolean(false);
                if (ok) return;
            } catch (Exception ignore) {}
            sleep(pollMs);
        }

        throw new RuntimeException("waitForTextContains timeout: css=" + css + " text=" + needle);
    }

    // =========================
    // 3) QUERY ATTR / VALUE
    // =========================

    public String queryAttr(DevToolsSession page, String css, String attr, long timeoutMs) {
        Objects.requireNonNull(page, "page");

        String expr =
                "(function(){try{" +
                        "const el=document.querySelector(" + jsString(css) + ");" +
                        "if(!el) return '';" +
                        "const v=el.getAttribute(" + jsString(attr) + ");" +
                        "return (v===null||v===undefined)?'':String(v);" +
                        "}catch(e){return ''}})()";

        JsonNode r = page.evaluate(expr, timeoutMs);
        return r.path("result").path("result").path("value").asText("");
    }

    public String queryValue(DevToolsSession page, String css, long timeoutMs) {
        Objects.requireNonNull(page, "page");

        String expr =
                "(function(){try{" +
                        "const el=document.querySelector(" + jsString(css) + ");" +
                        "if(!el) return '';" +
                        "return (el.value===undefined||el.value===null)?'':String(el.value);" +
                        "}catch(e){return ''}})()";

        JsonNode r = page.evaluate(expr, timeoutMs);
        return r.path("result").path("result").path("value").asText("");
    }

    // =========================
    // 4) SCROLL
    // =========================

    public void scrollToSelector(DevToolsSession page, String css, long timeoutMs) {
        Objects.requireNonNull(page, "page");

        String expr =
                "(function(){try{" +
                        "const el=document.querySelector(" + jsString(css) + ");" +
                        "if(!el) return false;" +
                        "el.scrollIntoView({block:'center', inline:'center', behavior:'instant'});" +
                        "return true;" +
                        "}catch(e){return false}})()";

        JsonNode r = page.evaluate(expr, timeoutMs);
        boolean ok = r.path("result").path("result").path("value").asBoolean(false);
        if (!ok) throw new RuntimeException("scrollToSelector: element not found: " + css);
    }

    public void scrollBy(DevToolsSession page, int deltaY, long timeoutMs) {
        Objects.requireNonNull(page, "page");
        String expr = "(function(){try{window.scrollBy(0," + deltaY + ");return true}catch(e){return false}})()";
        page.evaluate(expr, timeoutMs);
    }

    // =========================
    // 5) CLICK SELECTOR (PURE CDP)
    // =========================

    public void clickSelector(DevToolsSession page, String css, long timeoutMs) {
        Objects.requireNonNull(page, "page");

        int nodeId = resolveNodeId(page, css, timeoutMs);

        boolean scrolled = scrollIntoViewIfNeeded(page, nodeId, 1500);
        if (!scrolled) {
            scrollToSelector(page, css, 3000);
        }

        Point center = getNodeCenter(page, nodeId, timeoutMs);

        // чуть "навести" мышь
        clickMove(page, center.x, center.y, 0, 800);

        // click
        clickXY(page, center.x, center.y, 1, timeoutMs);
    }

    // =========================
    // 6) TYPE INTO SELECTOR (FOCUS + OPTIONAL CLEAR + INSERT TEXT)
    // =========================

    public void typeIntoSelector(DevToolsSession page, String css, String text, long timeoutMs) {
        typeIntoSelector(page, css, text, true, timeoutMs);
    }

    public void typeIntoSelector(DevToolsSession page, String css, String text, boolean clearBefore, long timeoutMs) {
        Objects.requireNonNull(page, "page");

        clickSelector(page, css, timeoutMs);

        // явный focus()
        page.safeSend("Runtime.evaluate", Map.of(
                "expression",
                "(function(){try{const el=document.querySelector(" + jsString(css) + "); if(el) el.focus(); return true;}catch(e){return false}})()",
                "returnByValue", true
        ), 2000);

        if (clearBefore) {
            pressCtrlA(page, 1000);
            pressKey(page, Key.BACKSPACE, 0, 1000);
        }

        if (text != null && !text.isEmpty()) {
            type(page, text, Math.max(1500, timeoutMs));
        }
    }

    // =========================
    // 7) NEW: CLICK(x,y) + TYPE(text) + PRESS KEYS
    // =========================

    /** ✅ Клик по координатам */
    public void click(DevToolsSession page, double x, double y, long timeoutMs) {
        clickXY(page, x, y, 1, timeoutMs);
    }

    /** ✅ Ввод текста в активный элемент */
    public void type(DevToolsSession page, String text, long timeoutMs) {
        Objects.requireNonNull(page, "page");
        if (text == null || text.isEmpty()) return;
        page.send("Input.insertText", Map.of("text", text), timeoutMs);
    }

    public void pressEnter(DevToolsSession page, long timeoutMs) { pressKey(page, Key.ENTER, 0, timeoutMs); }
    public void pressTab(DevToolsSession page, long timeoutMs)   { pressKey(page, Key.TAB, 0, timeoutMs); }
    public void pressEsc(DevToolsSession page, long timeoutMs)   { pressKey(page, Key.ESCAPE, 0, timeoutMs); }

    /** ✅ Ctrl+A */
    public void pressCtrlA(DevToolsSession page, long timeoutMs) {
        Objects.requireNonNull(page, "page");
        keyDown(page, Key.CONTROL, 0, timeoutMs);
        pressKey(page, Key.A, Mod.CTRL, timeoutMs);
        keyUp(page, Key.CONTROL, 0, timeoutMs);
    }

    /** Универсальная "клавиша" (keyDown+keyUp) */
    public void pressKey(DevToolsSession page, Key key, int modifiers, long timeoutMs) {
        Objects.requireNonNull(page, "page");
        keyDown(page, key, modifiers, timeoutMs);
        keyUp(page, key, modifiers, timeoutMs);
    }

    // =========================
    // INTERNAL: DOM helpers
    // =========================

    private int resolveNodeId(DevToolsSession page, String css, long timeoutMs) {
        JsonNode doc = page.send("DOM.getDocument", Map.of(
                "depth", 1,
                "pierce", true
        ), timeoutMs);

        int rootId = doc.path("result").path("root").path("nodeId").asInt(0);
        if (rootId == 0) throw new RuntimeException("DOM.getDocument: root nodeId is 0");

        JsonNode q = page.send("DOM.querySelector", Map.of(
                "nodeId", rootId,
                "selector", css
        ), timeoutMs);

        int nodeId = q.path("result").path("nodeId").asInt(0);
        if (nodeId == 0) throw new RuntimeException("DOM.querySelector: element not found: " + css);

        return nodeId;
    }

    private boolean scrollIntoViewIfNeeded(DevToolsSession page, int nodeId, long timeoutMs) {
        try {
            page.send("DOM.scrollIntoViewIfNeeded", Map.of("nodeId", nodeId), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Point getNodeCenter(DevToolsSession page, int nodeId, long timeoutMs) {
        JsonNode bm = page.send("DOM.getBoxModel", Map.of("nodeId", nodeId), timeoutMs);

        JsonNode quad = bm.path("result").path("model").path("content");
        if (!quad.isArray() || quad.size() < 8) {
            quad = bm.path("result").path("model").path("border");
        }
        if (!quad.isArray() || quad.size() < 8) {
            throw new RuntimeException("DOM.getBoxModel: no quad for nodeId=" + nodeId);
        }

        double x1 = quad.get(0).asDouble();
        double y1 = quad.get(1).asDouble();
        double x2 = quad.get(2).asDouble();
        double y2 = quad.get(3).asDouble();
        double x3 = quad.get(4).asDouble();
        double y3 = quad.get(5).asDouble();
        double x4 = quad.get(6).asDouble();
        double y4 = quad.get(7).asDouble();

        double cx = (x1 + x2 + x3 + x4) / 4.0;
        double cy = (y1 + y2 + y3 + y4) / 4.0;

        return new Point(cx, cy);
    }

    // =========================
    // INTERNAL: Input helpers
    // =========================

    private void clickMove(DevToolsSession page, double x, double y, int modifiers, long timeoutMs) {
        page.send("Input.dispatchMouseEvent", Map.of(
                "type", "mouseMoved",
                "x", x,
                "y", y,
                "modifiers", modifiers,
                "button", "none",
                "clickCount", 0
        ), timeoutMs);
    }

    private void clickXY(DevToolsSession page, double x, double y, int clickCount, long timeoutMs) {
        page.send("Input.dispatchMouseEvent", Map.of(
                "type", "mousePressed",
                "x", x,
                "y", y,
                "modifiers", 0,
                "button", "left",
                "clickCount", clickCount
        ), timeoutMs);

        page.send("Input.dispatchMouseEvent", Map.of(
                "type", "mouseReleased",
                "x", x,
                "y", y,
                "modifiers", 0,
                "button", "left",
                "clickCount", clickCount
        ), timeoutMs);
    }

    private void keyDown(DevToolsSession page, Key key, int modifiers, long timeoutMs) {
        page.send("Input.dispatchKeyEvent", keyEvent("keyDown", key, modifiers), timeoutMs);
    }

    private void keyUp(DevToolsSession page, Key key, int modifiers, long timeoutMs) {
        page.send("Input.dispatchKeyEvent", keyEvent("keyUp", key, modifiers), timeoutMs);
    }

    private Map<String, Object> keyEvent(String type, Key key, int modifiers) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put("key", key.key);
        m.put("code", key.code);
        m.put("windowsVirtualKeyCode", key.vk);
        m.put("nativeVirtualKeyCode", key.vk);
        m.put("modifiers", modifiers);
        return m;
    }

    // =========================
    // helpers
    // =========================

    private String jsString(String s) {
        if (s == null) s = "";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private record Point(double x, double y) {}

    public static final class Mod {
        public static final int ALT   = 1;
        public static final int CTRL  = 2;
        public static final int META  = 4;
        public static final int SHIFT = 8;
        private Mod() {}
    }

    public enum Key {
        ENTER("Enter", "Enter", 13),
        TAB("Tab", "Tab", 9),
        ESCAPE("Escape", "Escape", 27),
        BACKSPACE("Backspace", "Backspace", 8),
        CONTROL("Control", "ControlLeft", 17),
        A("a", "KeyA", 65);

        public final String key;
        public final String code;
        public final int vk;

        Key(String key, String code, int vk) {
            this.key = key;
            this.code = code;
            this.vk = vk;
        }
    }
}

