// Основной скрипт инъекции fingerprint
(function() {
    'use strict';

    // Проверка, не был ли уже патч применен
    if (window._fingerprintPatched) {
        console.log('[Fingerprint] Already patched, skipping');
        return;
    }
    window._fingerprintPatched = true;

    console.log('[Fingerprint Patch] Starting full injection...');

    // ========== ОСНОВНЫЕ ПАТЧИ ==========

    // 1. WebDriver - самый важный патч
    Object.defineProperty(navigator, 'webdriver', {
        get: () => false,
        configurable: true
    });

    // 2. Permissions API - для sandbox
    const originalPermissionsQuery = navigator.permissions?.query;
    if (originalPermissionsQuery) {
        navigator.permissions.query = function(parameters) {
            // Возвращаем granted для sandbox запросов
            if (parameters && parameters.name === 'sandbox') {
                return Promise.resolve({
                    state: 'granted',
                    onchange: null
                });
            }
            // Для notifications тоже можно патчить
            if (parameters && parameters.name === 'notifications') {
                return Promise.resolve({
                    state: 'prompt',
                    onchange: null
                });
            }
            return originalPermissionsQuery.call(this, parameters);
        };
    }

    // 3. Chrome runtime API
    if (typeof window.chrome !== 'undefined') {
        const chromePatch = {
            runtime: {
                id: 'fingerprintpatch',
                getManifest: () => ({}),
                connect: () => ({ onMessage: { addListener: () => {} }, postMessage: () => {} }),
                sendMessage: () => Promise.resolve({}),
                onMessage: { addListener: () => {} }
            },
            loadTimes: () => ({
                requestTime: Date.now() / 1000,
                startLoadTime: Date.now() / 1000,
                commitLoadTime: Date.now() / 1000,
                finishDocumentLoadTime: Date.now() / 1000,
                finishLoadTime: Date.now() / 1000,
                navigationType: 'Reload',
                wasFetchedViaSpdy: false,
                wasNpnNegotiated: false,
                npnNegotiatedProtocol: '',
                wasAlternateProtocolAvailable: false,
                connectionInfo: 'http/1.1'
            }),
            csi: () => ({
                onloadT: Date.now(),
                startE: Date.now() - 100,
                pageT: 100,
                tran: 15
            }),
            app: {
                isInstalled: false,
                InstallState: {
                    DISABLED: 'disabled',
                    INSTALLED: 'installed',
                    NOT_INSTALLED: 'not_installed'
                },
                RunningState: {
                    CANNOT_RUN: 'cannot_run',
                    READY_TO_RUN: 'ready_to_run',
                    RUNNING: 'running'
                }
            }
        };

        Object.assign(window.chrome, chromePatch);
    }

    // 4. Плагины
    const mockPlugins = [
        { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
        { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai', description: '' },
        { name: 'Native Client', filename: 'internal-nacl-plugin', description: '' }
    ];

    Object.defineProperty(navigator, 'plugins', {
        get: () => ({
            length: mockPlugins.length,
            item: (index) => mockPlugins[index] || null,
            namedItem: (name) => mockPlugins.find(p => p.name === name) || null,
            refresh: () => {},
            [Symbol.iterator]: function* () {
                for (let i = 0; i < mockPlugins.length; i++) {
                    yield mockPlugins[i];
                }
            }
        }),
        configurable: true
    });

    // 5. Languages
    Object.defineProperty(navigator, 'languages', {
        get: () => ['en-US', 'en', 'ru-RU', 'ru'],
        configurable: true
    });

    // 6. UserAgent патч (опционально)
    const originalUserAgent = navigator.userAgent;
    if (originalUserAgent.includes('Headless') || originalUserAgent.includes('X11; Linux')) {
        Object.defineProperty(navigator, 'userAgent', {
            get: () => originalUserAgent.replace(/HeadlessChrome|X11; Linux/g, 'Windows NT 10.0; Win64; x64'),
            configurable: true
        });
    }

    // 7. Удаление атрибутов автоматизации из DOM
    document.documentElement.removeAttribute('webdriver');
    document.documentElement.removeAttribute('selenium');
    document.documentElement.removeAttribute('driver');

    // 8. Перехват window.open для инъекции в новые окна
    const originalWindowOpen = window.open;
    if (originalWindowOpen) {
        window.open = function(...args) {
            const newWindow = originalWindowOpen.apply(this, args);
            if (newWindow) {
                setTimeout(() => {
                    try {
                        // Инжектим патч в новое окно
                        const scriptContent = `(${this.inject.toString()})();`;
                        newWindow.eval(scriptContent);
                    } catch(e) {}
                }, 500);
            }
            return newWindow;
        };
    }

    console.log('[Fingerprint Patch] Full injection completed successfully!');

    // ========== ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ ==========

    // Функция для проверки работы патча
    window.checkFingerprint = function() {
        console.log('=== Fingerprint Check ===');
        console.log('1. webdriver:', navigator.webdriver);
        console.log('2. plugins count:', navigator.plugins.length);
        console.log('3. languages:', navigator.languages);
        console.log('4. has chrome.runtime:', !!window.chrome?.runtime);
        console.log('5. userAgent:', navigator.userAgent.substring(0, 80) + '...');
        console.log('=== End Check ===');
    };

    // Запускаем проверку автоматически
    setTimeout(() => window.checkFingerprint && window.checkFingerprint(), 2000);

})();