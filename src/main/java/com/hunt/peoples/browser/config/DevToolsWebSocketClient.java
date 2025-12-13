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

@Component
@Slf4j
public class DevToolsWebSocketClient {

    private final ObjectMapper objectMapper;
    private final Map<Integer, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private int messageIdCounter = 1;

    public DevToolsWebSocketClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Выполняет скрипт через WebSocket DevTools Protocol
     */
    public CompletableFuture<JsonNode> executeScript(String wsUrl, String script) {
        CompletableFuture<JsonNode> future = new CompletableFuture<>();

        try {
            WebSocketClient wsClient = new WebSocketClient(new URI(wsUrl)) {
                private int currentMessageId;

                @Override
                public void onOpen(ServerHandshake handshake) {
                    log.debug("WebSocket connection opened to DevTools: {}", wsUrl);

                    // Создаем сообщение для выполнения скрипта
                    currentMessageId = messageIdCounter++;
                    pendingRequests.put(currentMessageId, future);

                    Map<String, Object> message = new HashMap<>();
                    message.put("id", currentMessageId);
                    message.put("method", "Runtime.evaluate");

                    Map<String, Object> params = new HashMap<>();
                    params.put("expression", script);
                    params.put("includeCommandLineAPI", true);
                    params.put("silent", false);
                    params.put("returnByValue", false);
                    params.put("generatePreview", true);
                    params.put("userGesture", true);
                    params.put("awaitPromise", false);

                    message.put("params", params);

                    try {
                        String jsonMessage = objectMapper.writeValueAsString(message);
                        log.debug("Sending script to DevTools (id={}, length={}): {}",
                                currentMessageId, script.length(), script.substring(0, Math.min(100, script.length())));
                        send(jsonMessage);
                    } catch (Exception e) {
                        log.error("Failed to serialize WebSocket message", e);
                        future.completeExceptionally(e);
                        close();
                    }
                }

                @Override
                public void onMessage(String message) {
                    try {
                        JsonNode response = objectMapper.readTree(message);

                        // Проверяем, является ли это ответом на наш запрос
                        if (response.has("id")) {
                            int responseId = response.get("id").asInt();
                            CompletableFuture<JsonNode> pendingFuture = pendingRequests.remove(responseId);

                            if (pendingFuture != null) {
                                if (response.has("error")) {
                                    JsonNode error = response.get("error");
                                    log.error("DevTools error: {}", error);
                                    pendingFuture.completeExceptionally(
                                            new RuntimeException("DevTools error: " + error)
                                    );
                                } else {
                                    log.debug("Received successful response from DevTools for id={}", responseId);
                                    pendingFuture.complete(response);
                                }
                                close();
                            }
                        } else if (response.has("method")) {
                            // Это событие, а не ответ - можем обработать если нужно
                            String method = response.get("method").asText();
                            log.debug("Received DevTools event: {}", method);
                        }
                    } catch (Exception e) {
                        log.error("Failed to parse WebSocket message", e);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    log.debug("WebSocket connection closed: {} - {}", code, reason);

                    // Если есть незавершенные запросы, помечаем их как неудачные
                    if (currentMessageId > 0) {
                        CompletableFuture<JsonNode> pendingFuture = pendingRequests.remove(currentMessageId);
                        if (pendingFuture != null && !pendingFuture.isDone()) {
                            pendingFuture.completeExceptionally(
                                    new RuntimeException("WebSocket connection closed before response")
                            );
                        }
                    }
                }

                @Override
                public void onError(Exception ex) {
                    log.error("WebSocket error", ex);

                    if (currentMessageId > 0) {
                        CompletableFuture<JsonNode> pendingFuture = pendingRequests.remove(currentMessageId);
                        if (pendingFuture != null && !pendingFuture.isDone()) {
                            pendingFuture.completeExceptionally(ex);
                        }
                    }
                }
            };

            // Устанавливаем таймауты
            wsClient.setConnectionLostTimeout(30);

            // Подключаемся с таймаутом
            wsClient.connectBlocking(5, TimeUnit.SECONDS);

            // Устанавливаем таймаут на выполнение запроса
            future.orTimeout(10, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        if (ex instanceof TimeoutException) {
                            log.warn("Script execution timeout for {}", wsUrl);
                            wsClient.close();
                        }
                        return null;
                    });

        } catch (Exception e) {
            log.error("Failed to connect to DevTools WebSocket", e);
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Инжектирует скрипт и ожидает его выполнения
     */
    public boolean injectScript(String wsUrl, String script) {
        try {
            CompletableFuture<JsonNode> future = executeScript(wsUrl, script);

            // Ждем результат
            JsonNode result = future.get(10, TimeUnit.SECONDS);

            if (result != null) {
                // Проверяем, был ли скрипт выполнен успешно
                if (result.has("result")) {
                    JsonNode scriptResult = result.get("result");

                    if (scriptResult.has("type") && "object".equals(scriptResult.get("type").asText())) {
                        if (scriptResult.has("subtype") && "error".equals(scriptResult.get("subtype").asText())) {
                            log.error("Script execution error: {}", scriptResult);
                            return false;
                        }
                    }

                    log.info("Script injected successfully via DevTools");
                    return true;
                }
            }

            return false;

        } catch (TimeoutException e) {
            log.warn("Script injection timeout for {}", wsUrl);
            return false;
        } catch (Exception e) {
            log.error("Failed to inject script via WebSocket", e);
            return false;
        }
    }
}