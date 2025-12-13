package com.hunt.peoples.browser.config;

import lombok.experimental.UtilityClass;

@UtilityClass
public class DevToolsUtils {

    /**
     * Экранирует JavaScript код для безопасной вставки в JSON
     */
    public static String escapeJavaScript(String script) {
        if (script == null) return "";

        return script.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\f", "\\f");
    }

    /**
     * Создает команду для выполнения скрипта через DevTools Protocol
     */
    public static String createEvaluateCommand(int id, String script) {
        return String.format(
                "{\"id\":%d,\"method\":\"Runtime.evaluate\",\"params\":{\"expression\":\"%s\",\"includeCommandLineAPI\":true,\"silent\":false}}",
                id, escapeJavaScript(script)
        );
    }

    /**
     * Проверяет WebSocket URL на валидность
     */
    public static boolean isValidWebSocketUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        return url.startsWith("ws://") || url.startsWith("wss://");
    }
}
