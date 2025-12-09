package com.hunt.peoples.profiles.dto;

public record ConnectRequest(
        String externalKey,   // обязателен
        String proxyUrl       // опционален
) {}
