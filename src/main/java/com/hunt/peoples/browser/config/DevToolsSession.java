package com.hunt.peoples.browser.config;


import com.fasterxml.jackson.databind.JsonNode;

import java.io.Closeable;
import java.util.Map;
import java.util.function.Consumer;


public interface DevToolsSession extends Closeable {

    /**
     * Отправляет CDP команду и ждёт ответ (JSON), или кидает RuntimeException по таймауту/ошибке.
     */
    JsonNode send(String method, Map<String, Object> params, long timeoutMs);

    default JsonNode send(String method, long timeoutMs) {
        return send(method, Map.of(), timeoutMs);
    }

    String getWsUrl();

    /**
     * ✅ подписка на конкретное событие + возможность отписаться
     */
    AutoCloseable onEvent(String method, Consumer<JsonNode> handler);

    default JsonNode evaluate(String expression, long timeoutMs) {
        return send("Runtime.evaluate", Map.of(
                "expression", expression,
                "returnByValue", true
        ), timeoutMs);
    }

    default JsonNode evaluate(String expression) {
        return evaluate(expression, 5000);
    }

    /**
     * Часто нужная инициализация доменов.
     */
    default void enableCommonDomains(long timeoutMs) {
        safeSend("Runtime.enable", timeoutMs);
        safeSend("Network.enable", timeoutMs);
        safeSend("Page.enable", timeoutMs);
    }

    default void safeSend(String method, long timeoutMs) {
        try { send(method, timeoutMs); } catch (Exception ignore) {}
    }

    @Override
    void close();

    default void safeSend(String method, Map<String, Object> params, long timeoutMs) {
        try { send(method, params, timeoutMs); } catch (Exception ignore) {}
    }
}

