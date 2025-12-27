//package com.hunt.peoples.profiles.service;
//
//import com.hunt.peoples.browser.config.DevToolsClient;
//import com.hunt.peoples.browser.config.DevToolsSession;
//import com.hunt.peoples.browser.service.BrowserContainerService;
//import com.hunt.peoples.browser.service.BrowserScriptInjector;
//import com.hunt.peoples.profiles.entity.Profile;
//import com.hunt.peoples.profiles.repository.ProfileRepository;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.stereotype.Service;
//
//import java.nio.charset.StandardCharsets;
//import java.security.MessageDigest;
//import java.time.Instant;
//import java.util.Map;
//
//@Service
//@RequiredArgsConstructor
//@Slf4j
//public class InjectionScriptService {
//
//    private final ProfileRepository profileRepository;
//    private final BrowserContainerService browserContainerService;
//    private final DevToolsClient devToolsClient;
//    private final BrowserScriptInjector scriptInjector;
//
//    @Value("${browser.cdp.websocket.connect-timeout:5000}")
//    private int wsConnectTimeoutMs;
//
//    @Value("${browser.cdp.inject.cmd-timeout-ms:3000}")
//    private long cmdTimeoutMs;
//
//    public ApplyResult generateApplyAndSave(Long profileId, boolean reload) {
//        Profile profile = profileRepository.findById(profileId)
//                .orElseThrow(() -> new IllegalArgumentException("Profile not found: " + profileId));
//
//        String devToolsBaseUrl = browserContainerService.getDevToolsBaseUrl(profileId);
//        if (devToolsBaseUrl == null || devToolsBaseUrl.isBlank()) {
//            throw new IllegalStateException("Browser is not running for profileId=" + profileId);
//        }
//
//        String pageWsUrl = browserContainerService.getDevToolsWebSocketUrl(devToolsBaseUrl);
//        if (pageWsUrl == null || !pageWsUrl.contains("/devtools/page/")) {
//            throw new IllegalStateException("No PAGE wsUrl for profileId=" + profileId + " base=" + devToolsBaseUrl);
//        }
//
//        String finalScript = scriptInjector.generateInjectionScript(profile);
//        if (finalScript.isBlank()) {
//            return new ApplyResult(profileId, 0, false, "empty script");
//        }
//
//        int injected = 0;
//
//
//        try (DevToolsSession cdp = devToolsClient.connect(pageWsUrl, wsConnectTimeoutMs, 30)) {
//            cdp.enableCommonDomains(3000);
//
//            // 1) инжект (одним большим скриптом)
//            cdp.send("Page.addScriptToEvaluateOnNewDocument",
//                    Map.of("source", finalScript),
//                    cmdTimeoutMs);
//            injected = 1;
//
//            // 2) чтобы “сразу применилось”
//            if (reload) {
//                cdp.send("Page.reload", Map.of("ignoreCache", true), 15000);
//            }
//        }
//
//        // 3) сохраняем в Profile
//        profile.setInjectionScript(finalScript);
//        profile.setInjectionScriptHash(sha256hex(finalScript));
//        profile.setInjectionScriptUpdatedAt(Instant.now());
//        profileRepository.save(profile);
//
//        return new ApplyResult(profileId, injected, reload, "ok");
//    }
//
//    private static String sha256hex(String s) {
//        try {
//            MessageDigest md = MessageDigest.getInstance("SHA-256");
//            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
//            StringBuilder sb = new StringBuilder();
//            for (byte b : d) sb.append(String.format("%02x", b));
//            return sb.toString();
//        } catch (Exception e) {
//            return "sha256_error";
//        }
//    }
//
//    public record ApplyResult(Long profileId, int injected, boolean reloaded, String status) {}
//}

