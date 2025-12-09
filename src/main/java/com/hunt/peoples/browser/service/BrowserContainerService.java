package com.hunt.peoples.browser.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;
import com.hunt.peoples.browser.config.AppProperties;
import com.hunt.peoples.browser.dto.BrowserStartResult;

import com.hunt.peoples.profiles.entity.Profile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrowserContainerService {

    private final DockerClient dockerClient;
    private final AppProperties appProperties;   // hostBaseUrl

    private static final String IMAGE_NAME = "multi-browser-chrome-vnc";
    private static final int   VNC_CONTAINER_PORT = 6080;

    /**
     * Запускаем контейнер для профиля.
     * proxyOverride – если не null/не пустой, переопределяет proxyUrl из профиля.
     */
    public BrowserStartResult startBrowser(Profile profile, String proxyOverride) {

        Long   profileId   = profile.getId();
        String userDataDir = profile.getUserDataPath();      // уже лежит в профиле (полный путь до userDataDir)
        String hostBaseUrl = appProperties.getHostBaseUrl(); // например, "http://localhost"

        String containerName = "browser_profile_" + profileId;

        log.info("Starting browser container {} for profile {}, userDataPath={}",
                containerName, profileId, userDataDir);

        // На всякий случай убираем старый контейнер с тем же именем
        try {
            dockerClient.removeContainerCmd(containerName).withForce(true).exec();
        } catch (Exception e) {
            // не критично, просто логнем как debug
            log.debug("Cannot remove old container {} before start: {}", containerName, e.getMessage());
        }

        // Находим свободный порт на хосте
        int hostPort = findFreePort();
        log.info("Using host port {} for container {}", hostPort, containerName);

        ExposedPort vncPort = ExposedPort.tcp(VNC_CONTAINER_PORT);
        Ports portBindings = new Ports();
        portBindings.bind(vncPort, Ports.Binding.bindPort(hostPort));

        // Нормализуем путь под конкретную ОС
        String hostPath = normalizePath(userDataDir);

        // На всякий случай создаём директорию userDataDir на хосте
        ensureDirectoryExists(hostPath);

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withPortBindings(portBindings)
                .withBinds(new Bind(hostPath, new Volume("/home/chrome/user-data")));

        String proxyToUse = resolveProxy(proxyOverride, profile.getProxyUrl());

        CreateContainerResponse container = dockerClient.createContainerCmd(IMAGE_NAME)
                .withName(containerName)
                .withHostConfig(hostConfig)
                .withEnv(
                        "USER_DATA_DIR=/home/chrome/user-data",
                        "PROXY_URL=" + (proxyToUse == null ? "" : proxyToUse)
                )
                .withExposedPorts(vncPort)
                .exec();

        dockerClient.startContainerCmd(container.getId()).exec();

        String vncUrl = hostBaseUrl + ":" + hostPort + "/vnc.html";
        log.info("Browser container {} started, URL={}", containerName, vncUrl);

        // Ждём, пока VNC реально станет доступен, чтобы не ловить ERR_EMPTY_RESPONSE
        waitForVncReady(vncUrl, Duration.ofSeconds(20));

        return new BrowserStartResult(profileId, vncUrl, profile.getExternalKey());
    }

    /**
     * Аккуратно останавливаем/удаляем контейнер для профиля.
     */
    public void stopBrowser(Long profileId) {
        String containerName = "browser_profile_" + profileId;
        log.info("Stopping browser container {}", containerName);

        try {
            dockerClient.stopContainerCmd(containerName).exec();
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("No such container")) {
                // контейнер уже исчез — это ОК
                log.info("Container {} already removed when trying to stop: {}", containerName, msg);
            } else {
                log.warn("Error stopping container {}: {}", containerName, msg);
            }
        }

        try {
            dockerClient.removeContainerCmd(containerName).withForce(true).exec();
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null &&
                    (msg.contains("No such container")
                            || msg.contains("removal of container") && msg.contains("already in progress"))) {
                // либо уже удаляется, либо уже удалён — тоже ОК
                log.info("Container {} is already gone or being removed: {}", containerName, msg);
            } else {
                log.warn("Error removing container {}: {}", containerName, msg);
            }
        }
    }

    // ================== Вспомогательные методы ==================

    private String resolveProxy(String override, String fromProfile) {
        if (override != null && !override.isBlank()) {
            return override;
        }
        return (fromProfile == null || fromProfile.isBlank()) ? null : fromProfile;
    }

    private void ensureDirectoryExists(String path) {
        if (path == null) return;
        try {
            File dir = new File(path);
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    log.warn("Cannot create userDataDir at {}", path);
                } else {
                    log.info("Created userDataDir at {}", path);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to ensure userDataDir exists at {}: {}", path, e.getMessage());
        }
    }

    private int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Cannot find free port", e);
        }
    }

    private String normalizePath(String path) {
        if (path == null) return null;
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            // заменяем слэши, но НЕ трогаем остальные символы
            return path.replace('/', '\\');
        }
        return path;
    }

    private void waitForVncReady(String vncUrl, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        int attempt = 0;

        while (System.nanoTime() < deadline) {
            attempt++;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(vncUrl).openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setRequestMethod("GET");

                int code   = conn.getResponseCode();
                int length = conn.getContentLength();

                if (code == 200 && length != 0) {
                    log.info("VNC READY after {} attempts at {}", attempt, vncUrl);
                    try (InputStream is = conn.getInputStream()) {
                        byte[] buf = new byte[256];
                        //noinspection ResultOfMethodCallIgnored
                        is.read(buf);
                    }
                    return;
                } else {
                    log.debug("VNC not ready yet (code={}, len={}), attempt {}", code, length, attempt);
                }
            } catch (IOException e) {
                log.debug("VNC not ready yet ({}), attempt {}", e.toString(), attempt);
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        log.warn("VNC did NOT become ready within {} seconds, продолжаем всё равно: {}",
                timeout.getSeconds(), vncUrl);
    }
}

