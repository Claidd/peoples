package com.hunt.peoples.browser.config;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;


import java.time.Duration;

@Slf4j
@Component
public class DevToolsTargetsResolver {

    private final RestTemplate rt;

    public DevToolsTargetsResolver() {
        this.rt = buildRestTemplate(Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    public DevToolsTargetsResolver(Duration connectTimeout, Duration readTimeout) {
        this.rt = buildRestTemplate(connectTimeout, readTimeout);
    }

    /** ws://.../devtools/browser/... */
    public String resolveBrowserWsUrl(String devToolsBaseUrl) {
        try {
            String u = normalize(devToolsBaseUrl) + "/json/version";
            ResponseEntity<JsonNode> resp = rt.getForEntity(u, JsonNode.class);
            JsonNode body = resp.getBody();
            if (body == null) return null;
            return text(body, "webSocketDebuggerUrl");
        } catch (Exception e) {
            log.debug("resolveBrowserWsUrl failed: {}", e.getMessage());
            return null;
        }
    }

    /** ws://.../devtools/page/... по targetId */
    public String resolvePageWsUrlByTargetId(String devToolsBaseUrl, String targetId) {
        try {
            JsonNode list = listTargets(devToolsBaseUrl);
            if (list == null || !list.isArray()) return null;

            for (JsonNode n : list) {
                String id = text(n, "id");
                if (targetId != null && targetId.equals(id)) {
                    return text(n, "webSocketDebuggerUrl");
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("resolvePageWsUrlByTargetId failed: {}", e.getMessage());
            return null;
        }
    }

    /** первая page из /json/list */
    public String resolveFirstPageWsUrl(String devToolsBaseUrl) {
        try {
            JsonNode list = listTargets(devToolsBaseUrl);
            if (list == null || !list.isArray()) return null;

            for (JsonNode n : list) {
                if ("page".equalsIgnoreCase(text(n, "type"))) {
                    String ws = text(n, "webSocketDebuggerUrl");
                    if (ws != null && ws.contains("/devtools/page/")) return ws;
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("resolveFirstPageWsUrl failed: {}", e.getMessage());
            return null;
        }
    }

    public JsonNode listTargets(String devToolsBaseUrl) {
        try {
            String u = normalize(devToolsBaseUrl) + "/json/list";
            return rt.getForObject(u, JsonNode.class);
        } catch (Exception e) {
            log.debug("listTargets failed: {}", e.getMessage());
            return null;
        }
    }

    private String normalize(String base) {
        if (base == null) return "";
        base = base.trim();
        if (base.endsWith("/")) return base.substring(0, base.length() - 1);
        return base;
    }

    private String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v != null && !v.isNull()) ? v.asText() : null;
    }

    private RestTemplate buildRestTemplate(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) connectTimeout.toMillis());
        f.setReadTimeout((int) readTimeout.toMillis());
        RestTemplate rt = new RestTemplate();
        rt.setRequestFactory(f);
        return rt;
    }
}


