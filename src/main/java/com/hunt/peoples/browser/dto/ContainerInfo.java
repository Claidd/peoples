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
public class ContainerInfo {
    private String containerId;
    private String containerName;
    private Long profileId;
    private int hostVncPort;
    private int hostDevToolsPort;
    private Instant startedAt;

    public Duration getUptime() {
        return startedAt == null ? Duration.ZERO : Duration.between(startedAt, Instant.now());
    }
}