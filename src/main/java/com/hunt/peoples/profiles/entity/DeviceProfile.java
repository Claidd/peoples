package com.hunt.peoples.profiles.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * DTO класс для DeviceProfile
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceProfile {

    // Базовые параметры устройства
    private String deviceType;          // iphone_14_pro, samsung_galaxy_s23 и т.д.
    private String userAgent;           // Полный User Agent
    private Integer width;              // Ширина экрана
    private Integer height;             // Высота экрана
    private Double pixelRatio;          // Pixel ratio (2.0, 3.0 и т.д.)
    private String platform;            // iPhone, Linux armv8l и т.д.

    // Аппаратные возможности
    private Integer maxTouchPoints;     // Макс. количество касаний
    private Integer hardwareConcurrency; // Количество ядер CPU
    private Integer deviceMemory;       // Память устройства в GB

    // WebGL параметры
    private String webglVendor;         // Вендор WebGL
    private String webglRenderer;       // Рендерер WebGL
    private String webglVersion;        // Версия WebGL

    // Audio параметры
    private Double audioContextLatency; // Задержка аудиоконтекста

    // Canvas fingerprint
    private String canvasFingerprint;   // Хэш canvas
    private String canvasNoiseHash;     // Хэш шума canvas

    // Дополнительные параметры
    private String vendor;              // Производитель устройства
    private String renderer;            // Графический рендерер

    // Метаданные
    private Instant generatedAt;        // Когда сгенерирован
    private Boolean withVariations;     // С вариациями или нет

    /**
     * Получает строку разрешения экрана
     */
    @JsonIgnore
    public String getScreenResolution() {
        if (width != null && height != null) {
            return width + "x" + height;
        }
        return "N/A";
    }

    /**
     * Проверяет, является ли устройство мобильным
     */
    @JsonIgnore
    public boolean isMobile() {
        if (userAgent == null) return false;
        String ua = userAgent.toLowerCase();
        return ua.contains("mobile") || ua.contains("android") || ua.contains("iphone");
    }

    /**
     * Проверяет, является ли устройство iOS
     */
    @JsonIgnore
    public boolean isIos() {
        if (platform == null) return false;
        return platform.toLowerCase().contains("iphone") ||
                platform.toLowerCase().contains("ipad");
    }

    /**
     * Проверяет, является ли устройство Android
     */
    @JsonIgnore
    public boolean isAndroid() {
        if (userAgent == null) return false;
        return userAgent.toLowerCase().contains("android");
    }

    /**
     * Создает копию профиля с микро-вариациями
     */
    public DeviceProfile withMicroVariations() {
        Random random = new Random();

        return DeviceProfile.builder()
                .deviceType(this.deviceType)
                .userAgent(addUaVariation(this.userAgent))
                .width(this.width)
                .height(this.height)
                .pixelRatio(round(this.pixelRatio + random.nextDouble() * 0.02 - 0.01, 3))
                .platform(this.platform)
                .maxTouchPoints(this.maxTouchPoints)
                .hardwareConcurrency(this.hardwareConcurrency)
                .deviceMemory(this.deviceMemory)
                .webglVendor(this.webglVendor)
                .webglRenderer(this.webglRenderer)
                .webglVersion(this.webglVersion)
                .audioContextLatency(this.audioContextLatency != null ?
                        round(this.audioContextLatency + random.nextDouble() * 0.002 - 0.001, 4) : null)
                .canvasFingerprint(generateCanvasHash())
                .canvasNoiseHash(generateNoiseHash())
                .vendor(this.vendor)
                .renderer(this.renderer)
                .generatedAt(Instant.now())
                .withVariations(true)
                .build();
    }

    private String addUaVariation(String userAgent) {
        if (userAgent == null || !userAgent.contains("Chrome/")) {
            return userAgent;
        }

        try {
            // Меняем минорную версию Chrome на +/- 1
            Pattern pattern = Pattern.compile("Chrome/(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");
            Matcher matcher = pattern.matcher(userAgent);

            if (matcher.find()) {
                String major = matcher.group(1);
                String minor = matcher.group(2);
                String build = matcher.group(3);
                String patch = matcher.group(4);

                int minorInt = Integer.parseInt(minor);
                int newMinor = minorInt + (new Random().nextBoolean() ? 1 : -1);
                if (newMinor < 0) newMinor = 0;

                return userAgent.replace(
                        "Chrome/" + major + "." + minor + "." + build + "." + patch,
                        "Chrome/" + major + "." + newMinor + "." + build + "." + patch
                );
            }
        } catch (Exception e) {
            // В случае ошибки возвращаем оригинальный UA
        }

        return userAgent;
    }

    private double round(double value, int places) {
        if (places < 0) throw new IllegalArgumentException();
        BigDecimal bd = BigDecimal.valueOf(value);
        bd = bd.setScale(places, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }

    private String generateCanvasHash() {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest((this.userAgent + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash).substring(0, 16);
        } catch (Exception e) {
            return "defaultCanvasHash";
        }
    }

    private String generateNoiseHash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash).substring(0, 32);
        } catch (Exception e) {
            return "defaultNoiseHash";
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
