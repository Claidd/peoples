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
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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

    private final class SessionImpl implements DevToolsSession, AutoCloseable {

        private final String wsUrl;
        private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final AtomicInteger idGen = new AtomicInteger(1);

        private final CompletableFuture<Void> opened = new CompletableFuture<>();
        private volatile Consumer<JsonNode> eventHandler;

        private volatile WebSocketClient ws;

        private SessionImpl(String wsUrl, long connectTimeoutMs, int connectionLostTimeoutSec) {
            this.wsUrl = Objects.requireNonNull(wsUrl, "wsUrl");
            try {
                // ✅ connect с retry и новым ws на каждую попытку
                this.ws = connectWithRetry(wsUrl, connectTimeoutMs, connectionLostTimeoutSec);

                // дождёмся onOpen (с запасом)
                long waitMs = Math.max(1000, connectTimeoutMs);
                opened.get(waitMs, TimeUnit.MILLISECONDS);

            } catch (Exception e) {
                safeCloseWs();
                throw new RuntimeException("Failed to open CDP session: " + e.getMessage(), e);
            }
        }

        private WebSocketClient connectWithRetry(String wsUrl, long connectTimeoutMs, int connectionLostTimeoutSec) throws Exception {
            Exception last = null;

            for (int attempt = 1; attempt <= 2; attempt++) {
                WebSocketClient client = null;
                try {
                    client = newWsClient(wsUrl);
                    client.setConnectionLostTimeout(connectionLostTimeoutSec);

                    boolean ok = client.connectBlocking(connectTimeoutMs, TimeUnit.MILLISECONDS);
                    if (!ok) throw new TimeoutException("CDP connectBlocking timeout: " + wsUrl);

                    return client;

                } catch (Exception e) {
                    last = e;
                    log.debug("CDP connect attempt {} failed: {}", attempt, e.getMessage());

                    // закрываем текущий client (если успел создать)
                    if (client != null) {
                        try { client.close(); } catch (Exception ignore) {}
                    }

                    // перед повтором чуть подождём
                    if (attempt == 1) {
                        try { Thread.sleep(250); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                }
            }

            throw (last != null ? last : new RuntimeException("CDP connect failed: " + wsUrl));
        }

        private WebSocketClient newWsClient(String wsUrl) throws Exception {
            return new WebSocketClient(new URI(wsUrl)) {

                @Override
                public void onOpen(ServerHandshake handshake) {
                    opened.complete(null);
                    log.debug("CDP WS opened: {}", wsUrl);
                }

                @Override
                public void onMessage(String message) {
                    try {
                        JsonNode node = objectMapper.readTree(message);

                        // Ответ на команду: {"id":..., "result":...} или {"id":..., "error":...}
                        if (node.has("id")) {
                            int id = node.get("id").asInt();
                            CompletableFuture<JsonNode> fut = pending.remove(id);
                            if (fut != null) fut.complete(node);
                            return;
                        }

                        // Событие CDP: {"method":"...","params":{...}}
                        Consumer<JsonNode> h = eventHandler;
                        if (h != null) {
                            try { h.accept(node); }
                            catch (Exception handlerEx) {
                                log.debug("CDP event handler error: {}", handlerEx.getMessage());
                            }
                        }

                    } catch (Exception e) {
                        log.debug("CDP parse error: {}", e.getMessage());
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    RuntimeException ex = new RuntimeException("CDP WS closed: " + code + " " + reason);
                    if (!opened.isDone()) opened.completeExceptionally(ex);
                    failAllPending(ex);
                    log.debug("CDP WS closed: {} {}", code, reason);
                }

                @Override
                public void onError(Exception ex) {
                    if (!opened.isDone()) opened.completeExceptionally(ex);
                    failAllPending(ex);
                    log.debug("CDP WS error: {}", ex.getMessage());
                }
            };
        }

        @Override
        public String getWsUrl() {
            return wsUrl;
        }

        @Override
        public void setEventHandler(Consumer<JsonNode> handler) {
            this.eventHandler = handler;
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

                String json = objectMapper.writeValueAsString(msg);

                WebSocketClient c = this.ws;
                if (c == null || !c.isOpen()) {
                    pending.remove(id);
                    throw new RuntimeException("CDP WS is not open (method=" + method + ")");
                }

                c.send(json);

                JsonNode resp = fut.get(timeoutMs, TimeUnit.MILLISECONDS);

                if (resp != null && resp.has("error")) {
                    throw new RuntimeException("CDP error for " + method + ": " + resp.get("error").toString());
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
            RuntimeException ex = new RuntimeException("CDP session closed by client");
            failAllPending(ex);
            safeCloseWs();
        }

        private void failAllPending(Exception ex) {
            try {
                pending.forEach((k, fut) -> fut.completeExceptionally(ex));
            } finally {
                pending.clear();
            }
        }

        private void safeCloseWs() {
            try {
                WebSocketClient c = this.ws;
                this.ws = null;
                if (c != null) {
                    try { if (c.isOpen()) c.close(); } catch (Exception ignore) {}
                }
            } catch (Exception ignore) {}
        }
    }
}

