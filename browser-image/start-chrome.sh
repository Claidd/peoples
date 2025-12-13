#!/bin/bash

# Создаем user-data директорию
mkdir -p /home/chrome/user-data

# Собираем аргументы для Chrome
CHROME_ARGS="--no-sandbox \
             --disable-dev-shm-usage \
             --disable-gpu \
             --disable-software-rasterizer \
             --disable-background-timer-throttling \
             --disable-renderer-backgrounding \
             --disable-backgrounding-occluded-windows \
             --disable-client-side-phishing-detection \
             --disable-component-update \
             --disable-default-apps \
             --disable-domain-reliability \
             --disable-extensions \
             --disable-features=TranslateUI,BlinkGenPropertyTrees \
             --disable-ipc-flooding-protection \
             --disable-popup-blocking \
             --disable-prompt-on-repost \
             --disable-sync"

# Добавляем user-agent из переменной окружения
if [ -n "$USER_AGENT" ]; then
    CHROME_ARGS="$CHROME_ARGS --user-agent=\"$USER_AGENT\""
fi

# Добавляем размер окна
if [ -n "$SCREEN_WIDTH" ] && [ -n "$SCREEN_HEIGHT" ]; then
    CHROME_ARGS="$CHROME_ARGS --window-size=$SCREEN_WIDTH,$SCREEN_HEIGHT"
fi

# Добавляем pixel ratio
if [ -n "$PIXEL_RATIO" ]; then
    CHROME_ARGS="$CHROME_ARGS --force-device-scale-factor=$PIXEL_RATIO"
fi

# Добавляем прокси
if [ -n "$PROXY_URL" ] && [ "$PROXY_URL" != "" ]; then
    CHROME_ARGS="$CHROME_ARGS --proxy-server=$PROXY_URL"
fi

# Запускаем Chrome
google-chrome-stable $CHROME_ARGS \
    --user-data-dir=/home/chrome/user-data \
    --remote-debugging-port=9222 \
    --no-first-run \
    https://whatismyuseragent.org/