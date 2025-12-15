// Основной скрипт инъекции fingerprint
if (typeof navigator.webdriver !== 'undefined') {
    Object.defineProperty(navigator, 'webdriver', {
        get: () => false,
        configurable: true
    });
}

// Маскировка других анти-бот детекторов
Object.defineProperty(navigator, 'plugins', {
    get: () => [1, 2, 3, 4, 5]
});

Object.defineProperty(navigator, 'languages', {
    get: () => ['en-US', 'en']
});