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

    /**
     * WS url сессии (для логов / отладки).
     */
    String getWsUrl();

    /**
     * Подписка на CDP events (сообщения без поля "id": {"method":"...","params":...}).
     * Можно поставить null, чтобы отключить.
     */
    void setEventHandler(Consumer<JsonNode> handler);

    default JsonNode send(String method, long timeoutMs) {
        return send(method, Map.of(), timeoutMs);
    }

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
        try {
            send(method, timeoutMs);
        } catch (Exception ignore) {
            // логируй debug, но не падай
        }
    }

    @Override
    void close();
}
