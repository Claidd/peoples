package com.hunt.peoples.browser.config;


import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Фабрика DevToolsSession (CDP over WebSocket).
 * Создаёт изолированную сессию под один браузер/профиль.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DevToolsClient {

    private final DevToolsCdpClient cdpClient;

    @Value("${browser.cdp.connect-timeout-ms:3000}")
    private int defaultConnectTimeoutMs;

    @Value("${browser.cdp.connection-lost-timeout-sec:30}")
    private int defaultConnectionLostTimeoutSec;

    /**
     * Удобный overload: берёт таймауты из application.yml
     */
    public DevToolsSession connect(String wsUrl) {
        return connect(wsUrl, defaultConnectTimeoutMs, defaultConnectionLostTimeoutSec);
    }

    /**
     * @param wsUrl CDP webSocketDebuggerUrl
     * @param connectTimeoutMs таймаут на connectBlocking
     * @param connectionLostTimeoutSec ping/pong watchdog (java-websocket)
     */
    public DevToolsSession connect(String wsUrl, int connectTimeoutMs, int connectionLostTimeoutSec) {

        return cdpClient.openSession(wsUrl, connectTimeoutMs, connectionLostTimeoutSec);
    }
}
