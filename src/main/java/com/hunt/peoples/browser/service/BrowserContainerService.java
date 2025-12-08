package com.hunt.peoples.browser.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ServerSocket;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrowserContainerService {

    private final DockerClient dockerClient;

    private static final String IMAGE_NAME = "multi-browser-chrome-vnc";

    public String startBrowser(Long profileId,
                               String userDataPath,
                               String proxyUrl,
                               String hostBaseUrl) {

        String containerName = "browser_profile_" + profileId;

        log.info("Starting browser container {} for profile {}, userDataPath={}",
                containerName, profileId, userDataPath);

        // На всякий случай удаляем старый контейнер с тем же именем
        try {
            dockerClient.removeContainerCmd(containerName).withForce(true).exec();
        } catch (Exception ignored) {}

        // Находим свободный порт на хосте
        int hostPort = findFreePort();
        log.info("Using host port {} for container {}", hostPort, containerName);

        ExposedPort vncPort = ExposedPort.tcp(6080);
        Ports portBindings = new Ports();
        portBindings.bind(vncPort, Ports.Binding.bindPort(hostPort));

        String hostPath = normalizePath(userDataPath);

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withPortBindings(portBindings)
                .withBinds(new Bind(hostPath, new Volume("/home/chrome/user-data")));

        // ВАЖНО: просто прокидываем путь как есть, без каких-либо parse()
//        HostConfig hostConfig = HostConfig.newHostConfig()
//                .withPortBindings(portBindings)
//                .withBinds(new Bind(userDataPath, new Volume("/home/chrome/user-data")));

        CreateContainerResponse container = dockerClient.createContainerCmd(IMAGE_NAME)
                .withName(containerName)
                .withHostConfig(hostConfig)
                .withEnv(
                        "USER_DATA_DIR=/home/chrome/user-data",
                        "PROXY_URL=" + (proxyUrl == null ? "" : proxyUrl)
                )
                .withExposedPorts(vncPort)
                .exec();

        dockerClient.startContainerCmd(container.getId()).exec();

        String url = hostBaseUrl + ":" + hostPort + "/vnc.html";
        log.info("Browser container {} started, URL={}", containerName, url);

        return url;
    }

    public void stopBrowser(Long profileId) {
        String containerName = "browser_profile_" + profileId;
        log.info("Stopping browser container {}", containerName);

        try {
            dockerClient.stopContainerCmd(containerName).exec();
        } catch (Exception e) {
            log.warn("Error stopping container {}: {}", containerName, e.getMessage());
        }

        try {
            dockerClient.removeContainerCmd(containerName).withForce(true).exec();
        } catch (Exception e) {
            log.warn("Error removing container {}: {}", containerName, e.getMessage());
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
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return path.replace('/', '\\');
        }
        return path;
    }
}