#!/usr/bin/env bash
set -e

export DISPLAY=:0
export XAUTHORITY=/root/.Xauthority

# === ПАРАМЕТРЫ ИЗ ПРОФИЛЯ ===
SCREEN_WIDTH=${SCREEN_WIDTH:-1920}
SCREEN_HEIGHT=${SCREEN_HEIGHT:-1080}
SCREEN_AVAIL_WIDTH=${SCREEN_AVAIL_WIDTH:-$SCREEN_WIDTH}
SCREEN_AVAIL_HEIGHT=${SCREEN_AVAIL_HEIGHT:-$SCREEN_HEIGHT}
SCREEN_COLOR_DEPTH=${SCREEN_COLOR_DEPTH:-24}
SCREEN_PIXEL_DEPTH=${SCREEN_PIXEL_DEPTH:-24}
SCREEN_GEOMETRY=${SCREEN_WIDTH}x${SCREEN_HEIGHT}x${SCREEN_COLOR_DEPTH}

PIXEL_RATIO=${PIXEL_RATIO:-1.0}
USER_DATA_DIR="${USER_DATA_DIR:-/data/user-data}"

# === ЯЗЫК И РЕГИОН ===
LOCALE=${LOCALE:-en_US.UTF-8}
LANGUAGE_TAG=${LANGUAGE:-en_US:en}

export LANG="$LOCALE"
export LC_ALL="$LOCALE"
export LANGUAGE="$LANGUAGE_TAG"


# Временная зона
if [ -n "$TIMEZONE" ]; then
    echo "Setting timezone to $TIMEZONE"
    ln -sf /usr/share/zoneinfo/$TIMEZONE /etc/localtime
    echo "$TIMEZONE" > /etc/timezone
    dpkg-reconfigure --frontend noninteractive tzdata
fi

# === ОЧИСТКА ===
echo "=== CLEANUP ==="
pkill -9 -f "Xvfb\|x11vnc\|fluxbox\|websockify\|chromium" 2>/dev/null || true
rm -rf /tmp/.X*-lock /tmp/.X11-unix/* 2>/dev/null || true
rm -f "$XAUTHORITY" 2>/dev/null || true
sleep 1

# === ПОДГОТОВКА ПРОФИЛЯ ===
mkdir -p "$USER_DATA_DIR"
mkdir -p "$USER_DATA_DIR/Default"
mkdir -p "$USER_DATA_DIR/Default/Sessions"
mkdir -p "$USER_DATA_DIR/Default/Session Storage"

echo "Using user data dir: $USER_DATA_DIR"
echo "Profile ID: ${PROFILE_ID:-N/A}"
echo "External Key: ${EXTERNAL_KEY:-N/A}"
echo "Screen: ${SCREEN_WIDTH}x${SCREEN_HEIGHT}, Pixel ratio: ${PIXEL_RATIO}"
echo "Language: ${LANGUAGE}, Timezone: ${TIMEZONE:-system}"
echo "Platform: ${PLATFORM:-Win32}, User Agent: ${USER_AGENT:0:50}..."

# Очищаем ТОЛЬКО lock-файлы, но НЕ трогаем данные профиля
find "$USER_DATA_DIR" -name "*lock*" -delete 2>/dev/null || true
find "$USER_DATA_DIR" -name "Singleton*" -delete 2>/dev/null || true

# === НАСТРОЙКА ПРЕФЕРЕНСОВ ПРОФИЛЯ ===
PREFERENCES_FILE="$USER_DATA_DIR/Default/Preferences"
echo "Setting up profile preferences..."

# Создаем файл Preferences ТОЛЬКО если он не существует
if [ ! -f "$PREFERENCES_FILE" ]; then
    echo "Creating new Preferences file..."
    cat > "$PREFERENCES_FILE" << EOF
{
  "session": {
    "restore_on_startup": 4,
    "startup_urls": [],
    "last_session": []
  },
  "profile": {
    "content_settings": {
      "exceptions": {
        "cookies": {},
        "images": {},
        "javascript": {},
        "plugins": {},
        "popups": {},
        "notifications": {}
      }
    },
    "password_manager_enabled": true,
    "exited_cleanly": true,
    "exit_type": "Normal",
    "last_visited": "",
    "created_by_version": "120.0.0.0"
  },
  "browser": {
    "enabled_labs_experiments": [
      "restore-session@1"
    ],
    "window_placement": {
      "work_area": {
        "bottom": $SCREEN_HEIGHT,
        "left": 0,
        "right": $SCREEN_WIDTH,
        "top": 0
      },
      "maximized": true,
      "normal": {
        "bottom": $SCREEN_HEIGHT,
        "left": 0,
        "right": $SCREEN_WIDTH,
        "top": 0
      }
    },
    "has_seen_welcome_page": true,
    "check_default_browser": false
  },
  "intl": {
    "accept_languages": "${LANGUAGE:-en-US},${LOCALE:-en-US}",
    "selected_languages": "${LANGUAGE:-en-US}"
  },
  "webkit": {
    "webprefs": {
      "default_font_size": 16,
      "default_fixed_font_size": 13
    }
  },
  "protection": {
    "macs": {}
  },
  "sync": {
    "remaining_rollback_tries": 0
  },
  "savefile": {
    "default_directory": "/home/chromium/Downloads"
  },
  "download": {
    "default_directory": "/home/chromium/Downloads",
    "directory_upgrade": true,
    "extensions_to_open": ""
  },
  "local_state": {
    "profile_info_cache": {
      "number_of_profiles": 1
    }
  },
  "extensions": {
    "theme": {
      "use_system": true
    }
  }
}
EOF
else
    # Если файл уже существует, только обновляем языковые настройки
    echo "Preferences file already exists, updating language settings only..."
    if command -v jq >/dev/null 2>&1; then
        jq --arg lang "${LANGUAGE:-en-US}" --arg locale "${LOCALE:-en-US}" \
           '.intl.accept_languages = ($lang + "," + $locale) | .intl.selected_languages = $lang' \
           "$PREFERENCES_FILE" > "${PREFERENCES_FILE}.tmp" && mv "${PREFERENCES_FILE}.tmp" "$PREFERENCES_FILE"
    else
        # Fallback на sed если jq нет
        sed -i "s|\"accept_languages\":\".*\"|\"accept_languages\":\"${LANGUAGE:-en-US},${LOCALE:-en-US}\"|g" "$PREFERENCES_FILE" 2>/dev/null || true
        sed -i "s|\"selected_languages\":\".*\"|\"selected_languages\":\"${LANGUAGE:-en-US}\"|g" "$PREFERENCES_FILE" 2>/dev/null || true
    fi
fi

# Создаем файл Local State ТОЛЬКО если он не существует
LOCAL_STATE_FILE="$USER_DATA_DIR/Local State"
if [ ! -f "$LOCAL_STATE_FILE" ]; then
    echo "Creating new Local State file..."
    cat > "$LOCAL_STATE_FILE" << EOF
{
  "profile": {
    "info_cache": {
      "Default": {
        "name": "Default",
        "user_name": "Chromium User",
        "is_consented_primary_account": false,
        "is_using_default_name": true,
        "is_using_default_avatar": true
      }
    }
  },
  "browser": {
    "last_known_quit_time": "$(date +%s)"
  }
}
EOF
fi

# Создаем базовые файлы сессии ТОЛЬКО если их нет
TABS_FILE="$USER_DATA_DIR/Default/Last Tabs"
if [ ! -f "$TABS_FILE" ]; then
    echo "chrome://newtab/" > "$TABS_FILE"
fi

SESSION_FILE="$USER_DATA_DIR/Default/Last Session"
if [ ! -f "$SESSION_FILE" ]; then
    echo '{"windows":[]}' > "$SESSION_FILE"
fi

CURRENT_SESSION_FILE="$USER_DATA_DIR/Default/Current Session"
if [ ! -f "$CURRENT_SESSION_FILE" ]; then
    echo '{"windows":[]}' > "$CURRENT_SESSION_FILE"
fi

# Создаем папку Downloads если ее нет
mkdir -p "$USER_DATA_DIR/Default/Downloads"

echo "Proxy: ${PROXY_URL:-<none>}"
echo "Detection Level: ${DETECTION_LEVEL:-ENHANCED}"

# === ЗАПУСК Xvfb ===
echo "Starting Xvfb with geometry: $SCREEN_GEOMETRY..."
Xvfb :0 -screen 0 "$SCREEN_GEOMETRY" -ac +extension GLX +extension RANDR +extension RENDER -noreset &
XVFB_PID=$!
sleep 3

if ! kill -0 $XVFB_PID 2>/dev/null; then
    echo "ERROR: Xvfb failed to start!"
    exit 1
fi

# === НАСТРОЙКА X11 ===
echo "Setting up X11 auth..."
touch "$XAUTHORITY"
chmod 600 "$XAUTHORITY"
xauth generate :0 . trusted 2>/dev/null || true

# === ЗАПУСК FLUXBOX ===
echo "Starting fluxbox..."
fluxbox -display :0 &

# === ЗАПУСК x11vnc ===
echo "Starting x11vnc..."
x11vnc -display :0 -rfbport 5900 -forever -shared -quiet -xkb -noxrecord -noxfixes -noxdamage -wait 5 -shared -noshm -auth "$XAUTHORITY" &

# === ЗАПУСК noVNC ===
NOVNC_DIR="/usr/share/novnc"
WEBSOCKIFY_BIN="/usr/bin/websockify"

if [ -d "$NOVNC_DIR" ] && [ -x "$WEBSOCKIFY_BIN" ]; then
  echo "Starting noVNC on :6080..."
  $WEBSOCKIFY_BIN --web "$NOVNC_DIR" 0.0.0.0:6080 localhost:5900 2>/dev/null &
  NOVNC_PID=$!
fi

# === ПРОВЕРКА X11 DISPLAY ===
echo "Checking X11 display..."
for i in {1..30}; do
    if xdpyinfo >/dev/null 2>&1; then
        echo "X11 display is ready after $i seconds"
        break
    fi
    sleep 1
    if [ $i -eq 30 ]; then
        echo "ERROR: X11 display not ready after 30 seconds"
        exit 1
    fi
done

# === НАСТРОЙКА CHROMIUM ARGS ===
#CHROME_ARGS="--no-sandbox --disable-dev-shm-usage"

# Базовые параметры
if [ -n "$USER_AGENT" ]; then
    CHROME_ARGS="$CHROME_ARGS --user-agent=\"$USER_AGENT\""
fi

CHROME_ARGS="$CHROME_ARGS --window-size=$SCREEN_WIDTH,$SCREEN_HEIGHT"
CHROME_ARGS="$CHROME_ARGS --restore-last-session"  # Ключевой флаг для восстановления сессии

if [ -n "$PIXEL_RATIO" ]; then
    CHROME_ARGS="$CHROME_ARGS --force-device-scale-factor=$PIXEL_RATIO"
fi

if [ -n "$LANGUAGE" ]; then
    CHROME_ARGS="$CHROME_ARGS --lang=$LANGUAGE"
fi

# Прокси
if [ -n "$PROXY_URL" ]; then
    CHROME_ARGS="$CHROME_ARGS --proxy-server=$PROXY_URL"
    CHROME_ARGS="$CHROME_ARGS --host-resolver-rules=\"MAP * ~NOTFOUND , EXCLUDE 127.0.0.1\""
fi

# DevTools
CHROME_ARGS="$CHROME_ARGS --remote-debugging-port=9222"
CHROME_ARGS="$CHROME_ARGS --remote-debugging-address=0.0.0.0"

# Важные флаги для работы куков и сессий
CHROME_ARGS="$CHROME_ARGS --log-level=3"  # Уменьшаем вывод логов
CHROME_ARGS="$CHROME_ARGS --disable-features=GlobalShortcutsPortal"

# Флаги для сохранения сессии (убираем мешающие)
CHROME_ARGS="$CHROME_ARGS --disable-blink-features=AutomationControlled"
CHROME_ARGS="$CHROME_ARGS --disable-features=TranslateUI,BlinkGenPropertyTrees"
CHROME_ARGS="$CHROME_ARGS --disable-gpu"
CHROME_ARGS="$CHROME_ARGS --disable-software-rasterizer"
CHROME_ARGS="$CHROME_ARGS --disable-client-side-phishing-detection"
CHROME_ARGS="$CHROME_ARGS --no-first-run"
CHROME_ARGS="$CHROME_ARGS --no-default-browser-check"
CHROME_ARGS="$CHROME_ARGS --disable-popup-blocking"
CHROME_ARGS="$CHROME_ARGS --disable-prompt-on-repost"
CHROME_ARGS="$CHROME_ARGS --disable-breakpad"
CHROME_ARGS="$CHROME_ARGS --disable-background-networking"
CHROME_ARGS="$CHROME_ARGS --disable-component-update"
CHROME_ARGS="$CHROME_ARGS --disable-background-timer-throttling"
CHROME_ARGS="$CHROME_ARGS --disable-renderer-backgrounding"
CHROME_ARGS="$CHROME_ARGS --disable-backgrounding-occluded-windows"
CHROME_ARGS="$CHROME_ARGS --disable-web-security"  # Для доступа к file:// URLs

# ВАЖНО: НЕ используем эти флаги (они мешают кукам и сессии):
# --disable-web-security (ОСОБЕННО ВРЕДЕН для куков)
# --disable-sync (может мешать сохранению сессии)
# --disable-domain-reliability

# Дополнительные флаги из переменной
if [ -n "$CHROME_FLAGS" ]; then
    CHROME_ARGS="$CHROME_ARGS $CHROME_FLAGS"
fi

# === ЗАПУСК CHROMIUM ===
echo "Starting Chromium..."
echo "Chrome args length: ${#CHROME_ARGS}"
echo "Chrome args preview: ${CHROME_ARGS:0:200}..."

# Запускаем Chromium с сохранением PID
chromium \
  --user-data-dir="$USER_DATA_DIR" \
  $CHROME_ARGS &
CHROME_PID=$!
echo "Chromium PID: $CHROME_PID"

# Проверяем запуск
sleep 10  # Даем больше времени на запуск
if ! kill -0 $CHROME_PID 2>/dev/null; then
    echo "ERROR: Chromium failed to start!"
    echo "Checking processes..."
    ps aux | grep -i chrom
    exit 1
fi

echo "Chromium started successfully!"
echo "VNC URL: http://localhost:6080/vnc.html"
echo "DevTools URL: http://localhost:9222"

# === АВТОМАТИЧЕСКАЯ ИНЪЕКЦИЯ FINGERPRINT ===
if [ -f "/scripts/inject.js" ] && [ -s "/scripts/inject.js" ]; then
    echo "Setting up automatic fingerprint injection..."

    # Создаем HTML-страницу для автоматической инъекции
    INJECTION_HTML="$USER_DATA_DIR/Default/fingerprint_injector.html"
    cat > "$INJECTION_HTML" << 'HTML_EOF'
<!DOCTYPE html>
<html>
<head>
    <title>Fingerprint Injection</title>
    <meta charset="utf-8">
    <script>
    // Автоматическая инъекция при загрузке страницы
    (function() {
        'use strict';

        console.log('[Auto-Inject] Starting fingerprint injection...');

        // Загружаем основной скрипт инъекции
        fetch('/data/user-data/Default/fingerprint_inject.js')
            .then(response => {
                if (!response.ok) throw new Error('Failed to load script');
                return response.text();
            })
            .then(scriptContent => {
                // Выполняем скрипт
                const script = document.createElement('script');
                script.textContent = scriptContent;
                document.documentElement.appendChild(script);
                script.remove();

                console.log('[Auto-Inject] Fingerprint script executed successfully');

                // Закрываем эту вкладку через 1 секунду
                setTimeout(() => {
                    if (window.location.href.includes('fingerprint_injector.html')) {
                        window.close();
                        console.log('[Auto-Inject] Injection tab closed');
                    }
                }, 1000);
            })
            .catch(error => {
                console.error('[Auto-Inject] Error:', error);
            });

        // Инжектим также напрямую базовый патч
        if (typeof window.chrome !== 'undefined') {
            Object.defineProperty(navigator, 'webdriver', {
                get: () => false,
                configurable: true
            });

            const originalQuery = window.navigator.permissions.query;
            if (originalQuery) {
                window.navigator.permissions.query = function(parameters) {
                    if (parameters && parameters.name === 'sandbox') {
                        return Promise.resolve({ state: 'granted', onchange: null });
                    }
                    return originalQuery.call(this, parameters);
                };
            }
            console.log('[Auto-Inject] Basic fingerprint patch applied');
        }
    })();
    </script>
</head>
<body>
    <div style="display: none;">Fingerprint injection in progress...</div>
</body>
</html>
HTML_EOF

    echo "Injection HTML page created at $INJECTION_HTML"

    # Копируем основной скрипт инъекции
    cp /scripts/inject.js "$USER_DATA_DIR/Default/fingerprint_inject.js"
    echo "Fingerprint script copied to profile"

    # Настраиваем Preferences для автоматической загрузки инъектора
    if [ -f "$PREFERENCES_FILE" ]; then
        echo "Configuring auto-injection in Preferences..."

        if command -v jq >/dev/null 2>&1; then
            # Используем jq для обновления настроек
            jq '.session.restore_on_startup = 1' "$PREFERENCES_FILE" > "${PREFERENCES_FILE}.tmp" && mv "${PREFERENCES_FILE}.tmp" "$PREFERENCES_FILE"
            jq '.session.startup_urls = ["file:///data/user-data/Default/fingerprint_injector.html"]' "$PREFERENCES_FILE" > "${PREFERENCES_FILE}.tmp" && mv "${PREFERENCES_FILE}.tmp" "$PREFERENCES_FILE"

            # Добавляем флаг для отключения предупреждения о закрытии вкладок
            jq '.profile.exit_type = "Normal" | .profile.exited_cleanly = true' "$PREFERENCES_FILE" > "${PREFERENCES_FILE}.tmp" && mv "${PREFERENCES_FILE}.tmp" "$PREFERENCES_FILE"
        else
            # Fallback на sed
            sed -i 's/"restore_on_startup":4/"restore_on_startup":1/g' "$PREFERENCES_FILE" 2>/dev/null || true
            sed -i 's/"startup_urls":\[\]/"startup_urls":["file:\/\/\/data\/user-data\/Default\/fingerprint_injector.html"]/g' "$PREFERENCES_FILE" 2>/dev/null || true
        fi

        echo "Preferences updated for auto-injection"
    fi

    # Добавляем флаг Chromium для запуска инъектора
    CHROME_ARGS="$CHROME_ARGS --restore-last-session"
    echo "Auto-injection configured - will run on browser startup"

else
    echo "No fingerprint injection script found"
fi

# === GRACEFUL SHUTDOWN ===
cleanup() {
    echo "=== GRACEFUL SHUTDOWN ==="

    # Даем время Chromium сохранить сессию
    echo "Waiting for Chromium to save session..."
    sleep 10

    # Функция для обновления JSON с jq или без
    update_json_with_jq_or_sed() {
        local file="$1"
        local updates="$2"

        if command -v jq >/dev/null 2>&1; then
            # Используем jq если доступен
            jq "$updates" "$file" > "${file}.tmp" && mv "${file}.tmp" "$file"
        else
            # Fallback на sed для базовых замен
            echo "WARNING: jq not found, using sed for basic updates"
            # Для Preferences: устанавливаем exited_cleanly и exit_type
            if [[ "$file" == *"Preferences" ]]; then
                sed -i 's/"exited_cleanly":false/"exited_cleanly":true/g' "$file" 2>/dev/null || true
                sed -i 's/"exit_type":"Crashed"/"exit_type":"Normal"/g' "$file" 2>/dev/null || true
            fi
        fi
    }

    # Обновляем флаги в Preferences
    if [ -f "$PREFERENCES_FILE" ]; then
        echo "Updating exit flags in Preferences..."
        update_json_with_jq_or_sed "$PREFERENCES_FILE" \
            '.profile.exited_cleanly = true | .profile.exit_type = "Normal"'

        # Обновляем время последнего закрытия
        LAST_VISITED=$(date +%s%3N)  # миллисекунды
        if command -v jq >/dev/null 2>&1; then
            jq --arg time "$LAST_VISITED" '.profile.last_visited = $time' "$PREFERENCES_FILE" > "${PREFERENCES_FILE}.tmp" && mv "${PREFERENCES_FILE}.tmp" "$PREFERENCES_FILE"
        fi
    fi

    # Обновляем Local State
    if [ -f "$LOCAL_STATE_FILE" ]; then
        echo "Updating Local State..."
        if command -v jq >/dev/null 2>&1; then
            jq --arg time "$(date +%s)" '.browser.last_known_quit_time = $time' "$LOCAL_STATE_FILE" > "${LOCAL_STATE_FILE}.tmp" && mv "${LOCAL_STATE_FILE}.tmp" "$LOCAL_STATE_FILE"
        else
            echo "WARNING: jq not available, skipping Local State update"
        fi
    fi

    echo "Stopping Chromium gracefully..."
    if kill -0 $CHROME_PID 2>/dev/null; then
        # Отправляем SIGTERM для graceful shutdown
        kill -TERM $CHROME_PID 2>/dev/null || true
        echo "Waiting for Chromium to shut down..."
        wait $CHROME_PID 2>/dev/null || true
        sleep 5

        if kill -0 $CHROME_PID 2>/dev/null; then
            echo "Chromium still running, sending SIGKILL..."
            kill -KILL $CHROME_PID 2>/dev/null || true
        fi
    fi

    echo "Stopping other processes..."
    pkill -9 -f "websockify" 2>/dev/null || true
    pkill -9 -f "x11vnc" 2>/dev/null || true
    pkill -9 -f "fluxbox" 2>/dev/null || true
    pkill -9 -f "Xvfb" 2>/dev/null || true

    # Копируем Current Session в Last Session для восстановления
    if [ -f "$CURRENT_SESSION_FILE" ] && [ -s "$CURRENT_SESSION_FILE" ]; then
        echo "Copying Current Session to Last Session..."
        cp -f "$CURRENT_SESSION_FILE" "$SESSION_FILE" 2>/dev/null || true
    fi

    echo "Shutdown completed"
}

trap cleanup EXIT INT TERM

# Ждем завершения Chromium
echo "Waiting for Chromium to exit..."
wait $CHROME_PID 2>/dev/null || true
echo "Chromium exited"
