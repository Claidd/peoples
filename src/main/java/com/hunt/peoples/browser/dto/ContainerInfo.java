package com.hunt.peoples.browser.dto;

// ContainerInfo.java
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerInfo {
    private String containerId;
    private String containerName;
    private Long profileId;
    private Integer hostVncPort;
    private Integer hostDevToolsPort;
    private Instant startedAt;
    private Instant stoppedAt;

    public Duration getUptime() {
        if (startedAt == null) return Duration.ZERO;
        Instant end = stoppedAt != null ? stoppedAt : Instant.now();
        return Duration.between(startedAt, end);
    }

    public boolean isRunning() {
        return stoppedAt == null;
    }

    // Метод getVncUrl может потребовать дополнительной логики,
    // если он зависит от BrowserContainerService
}