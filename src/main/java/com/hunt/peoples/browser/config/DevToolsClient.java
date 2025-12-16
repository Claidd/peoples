package com.hunt.peoples.browser.config;


import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    public DevToolsSession connect(String wsUrl, int connectTimeoutMs, int connectionLostTimeoutSec) {
        return cdpClient.openSession(wsUrl, connectTimeoutMs, connectionLostTimeoutSec);
    }
}
