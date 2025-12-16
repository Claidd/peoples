package com.hunt.peoples.browser.config;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class DevToolsWebSocketClient {

    private final ObjectMapper objectMapper;

    public DevToolsWebSocketClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Универсальная отправка CDP-команды: {id, method, params}
     * Возвращает полный ответ CDP (обычно содержит "result" или "error").
     */
    public JsonNode sendCdpCommand(String wsUrl, String method, Map<String, Object> params, long timeoutMs) {
        try (CdpSession session = new CdpSession(wsUrl, objectMapper)) {
            session.connectBlocking(5, TimeUnit.SECONDS);
            return session.send(method, params, timeoutMs);
        } catch (Exception e) {
            throw new RuntimeException("CDP command failed: " + method + " via " + wsUrl, e);
        }
    }

    /**
     * Вариант “как мы хотим”: addScriptToEvaluateOnNewDocument + reload в одном WS.
     */
    public void addScriptAndReload(String wsUrl, String injectionScript) {
        try (CdpSession session = new CdpSession(wsUrl, objectMapper)) {
            session.connectBlocking(5, TimeUnit.SECONDS);

            // часто полезно включить домен Page (не всегда обязательно, но безопасно)
            session.send("Page.enable", Map.of(), 5000);

            session.send(
                    "Page.addScriptToEvaluateOnNewDocument",
                    Map.of("source", injectionScript),
                    5000
            );

            session.send(
                    "Page.reload",
                    Map.of("ignoreCache", true),
                    10000
            );

        } catch (Exception e) {
            throw new RuntimeException("addScriptAndReload failed via " + wsUrl, e);
        }
    }

    /**
     * Внутренняя “сессия” — держит одно WS соединение и умеет ждать ответы по id.
     */
    static class CdpSession extends WebSocketClient implements AutoCloseable {

        private final ObjectMapper objectMapper;
        private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final AtomicInteger idCounter = new AtomicInteger(1);

        CdpSession(String wsUrl, ObjectMapper objectMapper) {
            super(URI.create(wsUrl));
            this.objectMapper = objectMapper;
            setConnectionLostTimeout(30);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            log.debug("CDP WS opened: {}", getURI());
        }

        @Override
        public void onMessage(String message) {
            try {
                JsonNode response = objectMapper.readTree(message);

                if (response.has("id")) {
                    int id = response.get("id").asInt();
                    CompletableFuture<JsonNode> fut = pending.remove(id);
                    if (fut != null) {
                        fut.complete(response);
                    }
                } else if (response.has("method")) {
                    // CDP event (опционально логировать)
                    log.trace("CDP event: {}", response.get("method").asText());
                }

            } catch (Exception e) {
                log.warn("Failed to parse CDP message", e);
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            log.debug("CDP WS closed: {} {} remote={}", code, reason, remote);
            Exception ex = new RuntimeException("CDP WS closed: " + code + " " + reason);
            pending.forEach((id, fut) -> fut.completeExceptionally(ex));
            pending.clear();
        }

        @Override
        public void onError(Exception ex) {
            log.error("CDP WS error", ex);
            pending.forEach((id, fut) -> fut.completeExceptionally(ex));
            pending.clear();
        }

        JsonNode send(String method, Map<String, Object> params, long timeoutMs) throws Exception {
            int id = idCounter.getAndIncrement();
            CompletableFuture<JsonNode> fut = new CompletableFuture<>();
            pending.put(id, fut);

            Map<String, Object> msg = new HashMap<>();
            msg.put("id", id);
            msg.put("method", method);
            if (params != null && !params.isEmpty()) {
                msg.put("params", params);
            }

            String json = objectMapper.writeValueAsString(msg);
            send(json);

            JsonNode resp;
            try {
                resp = fut.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                pending.remove(id);
                throw new TimeoutException("CDP timeout " + timeoutMs + "ms for " + method);
            }

            if (resp.has("error")) {
                throw new RuntimeException("CDP error for " + method + ": " + resp.get("error"));
            }
            return resp;
        }

        @Override
        public void close() {
            try {
                if (isOpen()) closeBlocking();
            } catch (Exception ignored) {
            }
        }
    }
}


//@Component
//@Slf4j
//public class DevToolsWebSocketClient {
//
//    private final ObjectMapper objectMapper;
//    private final Map<Integer, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
//    private int messageIdCounter = 1;
//
//    public DevToolsWebSocketClient(ObjectMapper objectMapper) {
//        this.objectMapper = objectMapper;
//    }
//
//    /**
//     * Выполняет скрипт через WebSocket DevTools Protocol
//     */
//    public CompletableFuture<JsonNode> executeScript(String wsUrl, String script) {
//        CompletableFuture<JsonNode> future = new CompletableFuture<>();
//
//        try {
//            WebSocketClient wsClient = new WebSocketClient(new URI(wsUrl)) {
//                private int currentMessageId;
//
//                @Override
//                public void onOpen(ServerHandshake handshake) {
//                    log.debug("WebSocket connection opened to DevTools: {}", wsUrl);
//
//                    // Создаем сообщение для выполнения скрипта
//                    currentMessageId = messageIdCounter++;
//                    pendingRequests.put(currentMessageId, future);
//
//                    Map<String, Object> message = new HashMap<>();
//                    message.put("id", currentMessageId);
//                    message.put("method", "Runtime.evaluate");
//
//                    Map<String, Object> params = new HashMap<>();
//                    params.put("expression", script);
//                    params.put("includeCommandLineAPI", true);
//                    params.put("silent", false);
//                    params.put("returnByValue", false);
//                    params.put("generatePreview", true);
//                    params.put("userGesture", true);
//                    params.put("awaitPromise", false);
//
//                    message.put("params", params);
//
//                    try {
//                        String jsonMessage = objectMapper.writeValueAsString(message);
//                        log.debug("Sending script to DevTools (id={}, length={}): {}",
//                                currentMessageId, script.length(), script.substring(0, Math.min(100, script.length())));
//                        send(jsonMessage);
//                    } catch (Exception e) {
//                        log.error("Failed to serialize WebSocket message", e);
//                        future.completeExceptionally(e);
//                        close();
//                    }
//                }
//
//                @Override
//                public void onMessage(String message) {
//                    try {
//                        JsonNode response = objectMapper.readTree(message);
//
//                        // Проверяем, является ли это ответом на наш запрос
//                        if (response.has("id")) {
//                            int responseId = response.get("id").asInt();
//                            CompletableFuture<JsonNode> pendingFuture = pendingRequests.remove(responseId);
//
//                            if (pendingFuture != null) {
//                                if (response.has("error")) {
//                                    JsonNode error = response.get("error");
//                                    log.error("DevTools error: {}", error);
//                                    pendingFuture.completeExceptionally(
//                                            new RuntimeException("DevTools error: " + error)
//                                    );
//                                } else {
//                                    log.debug("Received successful response from DevTools for id={}", responseId);
//                                    pendingFuture.complete(response);
//                                }
//                                close();
//                            }
//                        } else if (response.has("method")) {
//                            // Это событие, а не ответ - можем обработать если нужно
//                            String method = response.get("method").asText();
//                            log.debug("Received DevTools event: {}", method);
//                        }
//                    } catch (Exception e) {
//                        log.error("Failed to parse WebSocket message", e);
//                    }
//                }
//
//                @Override
//                public void onClose(int code, String reason, boolean remote) {
//                    log.debug("WebSocket connection closed: {} - {}", code, reason);
//
//                    // Если есть незавершенные запросы, помечаем их как неудачные
//                    if (currentMessageId > 0) {
//                        CompletableFuture<JsonNode> pendingFuture = pendingRequests.remove(currentMessageId);
//                        if (pendingFuture != null && !pendingFuture.isDone()) {
//                            pendingFuture.completeExceptionally(
//                                    new RuntimeException("WebSocket connection closed before response")
//                            );
//                        }
//                    }
//                }
//
//                @Override
//                public void onError(Exception ex) {
//                    log.error("WebSocket error", ex);
//
//                    if (currentMessageId > 0) {
//                        CompletableFuture<JsonNode> pendingFuture = pendingRequests.remove(currentMessageId);
//                        if (pendingFuture != null && !pendingFuture.isDone()) {
//                            pendingFuture.completeExceptionally(ex);
//                        }
//                    }
//                }
//            };
//
//            // Устанавливаем таймауты
//            wsClient.setConnectionLostTimeout(30);
//
//            // Подключаемся с таймаутом
//            wsClient.connectBlocking(5, TimeUnit.SECONDS);
//
//            // Устанавливаем таймаут на выполнение запроса
//            future.orTimeout(10, TimeUnit.SECONDS)
//                    .exceptionally(ex -> {
//                        if (ex instanceof TimeoutException) {
//                            log.warn("Script execution timeout for {}", wsUrl);
//                            wsClient.close();
//                        }
//                        return null;
//                    });
//
//        } catch (Exception e) {
//            log.error("Failed to connect to DevTools WebSocket", e);
//            future.completeExceptionally(e);
//        }
//
//        return future;
//    }
//
//    /**
//     * Инжектирует скрипт и ожидает его выполнения
//     */
//    public boolean injectScript(String wsUrl, String script) {
//        try {
//            CompletableFuture<JsonNode> future = executeScript(wsUrl, script);
//
//            // Ждем результат
//            JsonNode result = future.get(10, TimeUnit.SECONDS);
//
//            if (result != null) {
//                // Проверяем, был ли скрипт выполнен успешно
//                if (result.has("result")) {
//                    JsonNode scriptResult = result.get("result");
//
//                    if (scriptResult.has("type") && "object".equals(scriptResult.get("type").asText())) {
//                        if (scriptResult.has("subtype") && "error".equals(scriptResult.get("subtype").asText())) {
//                            log.error("Script execution error: {}", scriptResult);
//                            return false;
//                        }
//                    }
//
//                    log.info("Script injected successfully via DevTools");
//                    return true;
//                }
//            }
//
//            return false;
//
//        } catch (TimeoutException e) {
//            log.warn("Script injection timeout for {}", wsUrl);
//            return false;
//        } catch (Exception e) {
//            log.error("Failed to inject script via WebSocket", e);
//            return false;
//        }
//    }
//}