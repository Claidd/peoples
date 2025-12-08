package com.hunt.peoples.browser.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.vnc")
@Getter
@Setter
public class AppProperties {
    private String hostBaseUrl;
}
