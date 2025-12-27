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
import java.util.concurrent.atomic.AtomicBoolean;
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

    private final class SessionImpl implements DevToolsSession {

        private final String wsUrl;

        private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final AtomicInteger idGen = new AtomicInteger(1);

        // handlers by method
        private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<JsonNode>>> handlers = new ConcurrentHashMap<>();

        private volatile WebSocketClient ws;

        // ✅ created in ctor (fixes NPE)
        private final ExecutorService eventExec;

        // optional: avoid leaking if someone forgets close()
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private SessionImpl(String wsUrl, long connectTimeoutMs, int connectionLostTimeoutSec) {
            this.wsUrl = Objects.requireNonNull(wsUrl, "wsUrl");

            this.eventExec = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cdp-events-" + Math.abs(this.wsUrl.hashCode()));
                t.setDaemon(true);
                return t;
            });

            try {
                this.ws = connectWithRetry(this.wsUrl, connectTimeoutMs, connectionLostTimeoutSec);
            } catch (Exception e) {
                safeCloseWs();
                safeShutdownExec();
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

                    if (!client.isOpen()) throw new RuntimeException("CDP connected but ws not open: " + wsUrl);

                    log.debug("CDP WS connected (attempt {}): {}", attempt, wsUrl);
                    return client;

                } catch (Exception e) {
                    last = e;
                    log.debug("CDP connect attempt {} failed: {}", attempt, e.getMessage());

                    if (client != null) {
                        try { client.close(); } catch (Exception ignore) {}
                    }
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
                    log.debug("CDP WS opened: {}", wsUrl);
                }

                @Override
                public void onMessage(String message) {
                    try {
                        JsonNode node = objectMapper.readTree(message);

                        // response to command
                        if (node.has("id")) {
                            int id = node.get("id").asInt();
                            CompletableFuture<JsonNode> fut = pending.remove(id);
                            if (fut != null) fut.complete(node);
                            return;
                        }

                        // event: {"method":"...","params":...}
                        String method = node.path("method").asText(null);
                        if (method == null) return;

                        var list = handlers.get(method);
                        if (list == null || list.isEmpty()) return;

                        // do not block WS thread
                        eventExec.execute(() -> {
                            for (Consumer<JsonNode> h : list) {
                                try { h.accept(node); }
                                catch (Exception ex) {
                                    log.debug("CDP handler error ({}): {}", method, ex.getMessage());
                                }
                            }
                        });

                    } catch (Exception e) {
                        log.debug("CDP parse error: {}", e.getMessage());
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    shutdownFromRemote(new RuntimeException("CDP WS closed: " + code + " " + reason));
                    log.debug("CDP WS closed: {} {}", code, reason);
                }

                @Override
                public void onError(Exception ex) {
                    shutdownFromRemote(ex);
                    log.debug("CDP WS error: {}", ex.getMessage());
                }
            };
        }

        private void shutdownFromRemote(Exception ex) {
            if (!closed.compareAndSet(false, true)) return;
            failAllPending(ex);
            safeCloseWs();
            safeShutdownExec();
            handlers.clear();
        }

        @Override
        public String getWsUrl() {
            return wsUrl;
        }

        @Override
        public AutoCloseable onEvent(String method, Consumer<JsonNode> handler) {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(handler, "handler");

            handlers.computeIfAbsent(method, k -> new CopyOnWriteArrayList<>()).add(handler);

            // unsubscribe
            return () -> {
                var list = handlers.get(method);
                if (list != null) {
                    list.remove(handler);
                    if (list.isEmpty()) handlers.remove(method);
                }
            };
        }

        @Override
        public JsonNode send(String method, Map<String, Object> params, long timeoutMs) {
            if (closed.get()) throw new RuntimeException("CDP session already closed");

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
            if (!closed.compareAndSet(false, true)) return;

            RuntimeException ex = new RuntimeException("CDP session closed by client");
            failAllPending(ex);
            safeCloseWs();
            safeShutdownExec();
            handlers.clear();
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

        private void safeShutdownExec() {
            try { eventExec.shutdownNow(); } catch (Exception ignore) {}
        }


    }

}


