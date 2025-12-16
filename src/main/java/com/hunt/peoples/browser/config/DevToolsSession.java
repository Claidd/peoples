package com.hunt.peoples.browser.config;


import com.fasterxml.jackson.databind.JsonNode;

import java.io.Closeable;
import java.util.Map;

public interface DevToolsSession extends Closeable {

    JsonNode send(String method, Map<String, Object> params, long timeoutMs);

    default JsonNode evaluate(String expression, long timeoutMs) {
        return send("Runtime.evaluate", Map.of(
                "expression", expression,
                "returnByValue", true
        ), timeoutMs);
    }
}
