package com.hunt.peoples.browser.config.api;

import com.hunt.peoples.browser.config.DevToolsClient;
import com.hunt.peoples.browser.config.DevToolsSession;
import com.hunt.peoples.browser.config.DevToolsTargetsResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CdpTabs {

    private final CdpBrowserApi browserApi;
    private final DevToolsClient devToolsClient;
    private final DevToolsTargetsResolver resolver;

    public TabHandle openTabAndConnect(String devToolsBaseUrl, String url) {
        CdpBrowserApi.TabInfo tab = browserApi.openTab(devToolsBaseUrl, url);

        String pageWs = tab.getPageWsUrl();
        if (pageWs == null) {
            pageWs = waitPageWs(devToolsBaseUrl, tab.getTargetId(), 2000);
        }
        if (pageWs == null || !pageWs.contains("/devtools/page/")) {
            throw new IllegalStateException("Cannot resolve PAGE wsUrl for targetId=" + tab.getTargetId());
        }

        DevToolsSession page = devToolsClient.connect(pageWs);
        page.enableCommonDomains(3000);

        return new TabHandle(devToolsBaseUrl, tab.getTargetId(), pageWs, page, browserApi);
    }

    private String waitPageWs(String devToolsBaseUrl, String targetId, long waitMs) {
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline) {
            String ws = resolver.resolvePageWsUrlByTargetId(devToolsBaseUrl, targetId);
            if (ws != null && ws.contains("/devtools/page/")) return ws;

            try { Thread.sleep(120); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        return null;
    }
}

