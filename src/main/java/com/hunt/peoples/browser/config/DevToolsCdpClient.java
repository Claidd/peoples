package com.hunt.peoples.browser.config;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Одна WS-сессия -> много CDP команд.
 * pendingRequests НЕ глобальный, а внутри сессии => нет гонок между профилями.
 */
@Component
@Slf4j
public class DevToolsCdpClient {

    private final ObjectMapper objectMapper;

    public DevToolsCdpClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DevToolsSession openSession(String wsUrl, long connectTimeoutMs, int connectionLostTimeoutSec) {
        return new SessionImpl(wsUrl, connectTimeoutMs, connectionLostTimeoutSec);
    }

    private final class SessionImpl implements DevToolsSession {

        private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final AtomicInteger idGen = new AtomicInteger(1);
        private final CompletableFuture<Void> opened = new CompletableFuture<>();
        private final WebSocketClient ws;

        private SessionImpl(String wsUrl, long connectTimeoutMs, int connectionLostTimeoutSec) {
            try {
                ws = new WebSocketClient(new URI(wsUrl)) {
                    @Override
                    public void onOpen(ServerHandshake handshake) {
                        opened.complete(null);
                        log.debug("CDP WS opened: {}", wsUrl);
                    }

                    @Override
                    public void onMessage(String message) {
                        try {
                            JsonNode node = objectMapper.readTree(message);
                            if (node.has("id")) {
                                int id = node.get("id").asInt();
                                CompletableFuture<JsonNode> fut = pending.remove(id);
                                if (fut != null) fut.complete(node);
                            }
                        } catch (Exception e) {
                            log.debug("CDP parse error: {}", e.getMessage());
                        }
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        RuntimeException ex = new RuntimeException("CDP WS closed: " + code + " " + reason);
                        if (!opened.isDone()) opened.completeExceptionally(ex);
                        pending.forEach((k, fut) -> fut.completeExceptionally(ex));
                        pending.clear();
                        log.debug("CDP WS closed: {} {}", code, reason);
                    }

                    @Override
                    public void onError(Exception ex) {
                        if (!opened.isDone()) opened.completeExceptionally(ex);
                        pending.forEach((k, fut) -> fut.completeExceptionally(ex));
                        pending.clear();
                        log.debug("CDP WS error: {}", ex.getMessage());
                    }
                };

                ws.setConnectionLostTimeout(connectionLostTimeoutSec);

                boolean ok = ws.connectBlocking(connectTimeoutMs, TimeUnit.MILLISECONDS);
                if (!ok) throw new TimeoutException("CDP connectBlocking timeout: " + wsUrl);

                // дождёмся onOpen
                opened.get(Math.max(1000, connectTimeoutMs), TimeUnit.MILLISECONDS);

            } catch (Exception e) {
                throw new RuntimeException("Failed to open CDP session: " + e.getMessage(), e);
            }
        }

        @Override
        public JsonNode send(String method, Map<String, Object> params, long timeoutMs) {
            int id = idGen.getAndIncrement();
            CompletableFuture<JsonNode> fut = new CompletableFuture<>();
            pending.put(id, fut);

            try {
                Map<String, Object> msg = new HashMap<>();
                msg.put("id", id);
                msg.put("method", method);
                if (params != null && !params.isEmpty()) msg.put("params", params);

                ws.send(objectMapper.writeValueAsString(msg));

                JsonNode resp = fut.get(timeoutMs, TimeUnit.MILLISECONDS);

                if (resp != null && resp.has("error")) {
                    throw new RuntimeException("CDP error for " + method + ": " + resp.get("error"));
                }
                return resp;

            } catch (TimeoutException te) {
                pending.remove(id);
                throw new RuntimeException("CDP timeout for " + method + " (" + timeoutMs + "ms)", te);
            } catch (Exception e) {
                pending.remove(id);
                throw new RuntimeException("CDP send failed for " + method + ": " + e.getMessage(), e);
            }
        }

        @Override
        public void close() {
            try {
                if (ws != null && ws.isOpen()) ws.close();
            } catch (Exception ignored) {}
        }
    }
}
