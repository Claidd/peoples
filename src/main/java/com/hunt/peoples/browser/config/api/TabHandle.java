package com.hunt.peoples.browser.config.api;

import com.hunt.peoples.browser.config.DevToolsSession;
import lombok.Getter;
//TabHandle хранит:
//targetId pageWsUrl//уже открытую DevToolsSession page
//close() делает:
//закрывает page WS
//закрывает вкладку через browser target (Target.closeTarget)
@Getter
public class TabHandle implements AutoCloseable {

    private final String devToolsBaseUrl;
    private final String targetId;
    private final String pageWsUrl;
    private final DevToolsSession page;
    private final CdpBrowserApi browserApi;

    public TabHandle(String devToolsBaseUrl, String targetId, String pageWsUrl, DevToolsSession page, CdpBrowserApi browserApi) {
        this.devToolsBaseUrl = devToolsBaseUrl;
        this.targetId = targetId;
        this.pageWsUrl = pageWsUrl;
        this.page = page;
        this.browserApi = browserApi;
    }

    @Override
    public void close() {
        try { if (page != null) page.close(); } catch (Exception ignore) {}
        try { browserApi.closeTab(devToolsBaseUrl, targetId); } catch (Exception ignore) {}
    }
}

