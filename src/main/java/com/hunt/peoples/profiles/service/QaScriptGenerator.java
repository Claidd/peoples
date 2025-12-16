package com.hunt.peoples.profiles.service;


import com.hunt.peoples.profiles.entity.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class QaScriptGenerator {

    public List<String> generateAll(Profile profile) {
        List<String> scripts = new ArrayList<>();
        scripts.add(generateBootstrap(profile));
        scripts.add(generateErrorTrap(profile));
        return scripts;
    }

    private String generateBootstrap(Profile profile) {
        Long id = profile.getId();
        String key = profile.getExternalKey() == null ? "" : profile.getExternalKey();

        // Никакого спуфинга/маскировки. Только “пометки” и диагностика.
        return """
            (function() {
              try {
                window.__MB_PROFILE = { id: %d, externalKey: %s, injectedAt: Date.now() };
                console.debug("[MB] injected bootstrap", window.__MB_PROFILE);
              } catch (e) {}
            })();
            """.formatted(id == null ? -1 : id, jsString(key));
    }

    private String generateErrorTrap(Profile profile) {
        return """
            (function() {
              try {
                window.addEventListener("error", function(ev) {
                  console.warn("[MB] window.error", ev && (ev.message || ev));
                });
                window.addEventListener("unhandledrejection", function(ev) {
                  console.warn("[MB] unhandledrejection", ev && (ev.reason || ev));
                });
              } catch (e) {}
            })();
            """;
    }

    private String jsString(String s) {
        if (s == null) s = "";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}

