#!/bin/bash
set -e

echo "Starting Browser Container..."

# Проверяем переменные окружения
echo "USER_DATA_DIR: $USER_DATA_DIR"
echo "PROXY_URL: $PROXY_URL"
echo "SCREEN_WIDTH: $SCREEN_WIDTH"
echo "SCREEN_HEIGHT: $SCREEN_HEIGHT"

# Проверяем, существует ли директория user-data
if [ ! -d "$USER_DATA_DIR" ]; then
    echo "Creating user data directory: $USER_DATA_DIR"
    mkdir -p "$USER_DATA_DIR"
    # Меняем владельца на chrome пользователя
    if command -v chown &> /dev/null; then
        chown -R chrome:chrome "$USER_DATA_DIR" 2>/dev/null || true
    fi
    chmod 755 "$USER_DATA_DIR" 2>/dev/null || true
else
    echo "User data directory already exists: $USER_DATA_DIR"
    # Не пытаемся менять права на существующих директориях
    # при монтировании volume из хоста
fi

# Экспортируем DISPLAY для X11 (если нужно)
export DISPLAY=:99

# Запускаем VNC сервер в фоновом режиме
echo "Starting Xvfb and VNC server..."
Xvfb :99 -screen 0 ${SCREEN_WIDTH}x${SCREEN_HEIGHT}x24 -ac +extension RANDR &
sleep 2

# Запускаем x11vnc
x11vnc -forever -display :99 -nopw -rfbport 5900 -shared &
sleep 2

# Запускаем noVNC
websockify --web /usr/share/novnc 6080 localhost:5900 &
sleep 2

# Запускаем Chrome с настройками профиля
echo "Starting Chrome browser..."

# Собираем аргументы для Chrome
CHROME_ARGS="--no-sandbox \
             --disable-dev-shm-usage \
             --disable-gpu \
             --disable-software-rasterizer \
             --remote-debugging-port=9222 \
             --remote-debugging-address=0.0.0.0 \
             --user-data-dir=$USER_DATA_DIR \
             --window-size=${SCREEN_WIDTH},${SCREEN_HEIGHT} \
             --window-position=0,0"

# Добавляем user-agent если указан
if [ -n "$USER_AGENT" ]; then
    CHROME_ARGS="$CHROME_ARGS --user-agent=\"$USER_AGENT\""
fi

# Добавляем прокси если указан
if [ -n "$PROXY_URL" ]; then
    CHROME_ARGS="$CHROME_ARGS --proxy-server=\"$PROXY_URL\""
fi

echo "Chrome arguments: $CHROME_ARGS"

# Запускаем Chrome
exec google-chrome-stable $CHROME_ARGS --no-first-run --no-default-browser-check "$@"