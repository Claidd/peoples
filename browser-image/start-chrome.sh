#!/usr/bin/env bash
set -euo pipefail

export DISPLAY=:0
export XAUTHORITY=/tmp/.Xauthority

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# =============================
# ENV (safe defaults)
# =============================
SCREEN_WIDTH=${SCREEN_WIDTH:-1920}
SCREEN_HEIGHT=${SCREEN_HEIGHT:-1080}
SCREEN_COLOR_DEPTH=${SCREEN_COLOR_DEPTH:-24}
SCREEN_GEOMETRY="${SCREEN_WIDTH}x${SCREEN_HEIGHT}x${SCREEN_COLOR_DEPTH}"

PIXEL_RATIO=${PIXEL_RATIO:-1.0}
USER_DATA_DIR="${USER_DATA_DIR:-/data/user-data}"

LOCALE=${LOCALE:-en_US.UTF-8}
LANGUAGE_SAFE="${LANGUAGE:-en-US}"
TIMEZONE_SAFE="${TIMEZONE:-}"
USER_AGENT_SAFE="${USER_AGENT:-}"

export LANG="$LOCALE"
export LC_ALL="$LOCALE"
export LANGUAGE="${LANGUAGE_TAG:-en_US:en}"

# UA preview (no bash slicing errors)
UA_PREVIEW="<none>"
if [ -n "$USER_AGENT_SAFE" ]; then
  UA_PREVIEW="${USER_AGENT_SAFE:0:80}"
fi

# TZ
if [ -n "$TIMEZONE_SAFE" ]; then
  log "Using timezone via TZ env: $TIMEZONE_SAFE"
  export TZ="$TIMEZONE_SAFE"
fi

# =============================
# CLEANUP
# =============================
log "=== CLEANUP ==="

# Do not hard-kill chromium here; it breaks profile flushing.
pkill -TERM -f "chromium" 2>/dev/null || true

pkill -TERM -f "websockify" 2>/dev/null || true
pkill -TERM -f "x11vnc" 2>/dev/null || true
pkill -TERM -f "fluxbox" 2>/dev/null || true
pkill -TERM -f "Xvfb" 2>/dev/null || true

sleep 1

pkill -KILL -f "websockify" 2>/dev/null || true
pkill -KILL -f "x11vnc" 2>/dev/null || true
pkill -KILL -f "fluxbox" 2>/dev/null || true
pkill -KILL -f "Xvfb" 2>/dev/null || true

rm -rf /tmp/.X*-lock /tmp/.X11-unix/* 2>/dev/null || true

rm -f "$XAUTHORITY" 2>/dev/null || true
touch "$XAUTHORITY"
chmod 600 "$XAUTHORITY"

# =============================
# PROFILE DIR
# =============================
mkdir -p "$USER_DATA_DIR/Default"
mkdir -p "$USER_DATA_DIR/Default/Sessions"
mkdir -p "$USER_DATA_DIR/Default/Session Storage"
mkdir -p "$USER_DATA_DIR/Default/Downloads"

log "Using user data dir: $USER_DATA_DIR"
log "Profile ID: ${PROFILE_ID:-N/A}"
log "External Key: ${EXTERNAL_KEY:-N/A}"
log "Screen: ${SCREEN_WIDTH}x${SCREEN_HEIGHT}, Pixel ratio: ${PIXEL_RATIO}"
log "Language: ${LANGUAGE_SAFE}, Timezone: ${TIMEZONE_SAFE:-system}"
log "Platform: ${PLATFORM:-Win32}, User Agent: ${UA_PREVIEW}"

# Remove only singleton/lock files (safe)
find "$USER_DATA_DIR" -name "Singleton*" -delete 2>/dev/null || true
find "$USER_DATA_DIR" -name "*lock*" -delete 2>/dev/null || true

# =============================
# Preferences / Local State
# =============================
PREFERENCES_FILE="$USER_DATA_DIR/Default/Preferences"
LOCAL_STATE_FILE="$USER_DATA_DIR/Local State"

log "Setting up profile preferences..."

# Create Preferences if missing
if [ ! -f "$PREFERENCES_FILE" ]; then
  log "Creating new Preferences file..."
  cat > "$PREFERENCES_FILE" << EOF
{
  "session": {
    "restore_on_startup": 1,
    "startup_urls": []
  },
  "profile": {
    "exited_cleanly": true,
    "exit_type": "Normal"
  },
  "browser": {
    "has_seen_welcome_page": true,
    "check_default_browser": false
  },
  "intl": {
    "accept_languages": "${LANGUAGE_SAFE},${LOCALE:-en-US}",
    "selected_languages": "${LANGUAGE_SAFE}"
  }
}
EOF
else
  # Keep everything, but ensure: language + continue where left off
  if command -v jq >/dev/null 2>&1; then
    jq --arg lang "${LANGUAGE_SAFE}" --arg locale "${LOCALE:-en-US}" '
      .intl.accept_languages = ($lang + "," + $locale)
      | .intl.selected_languages = $lang
      | .session.restore_on_startup = 1
    ' "$PREFERENCES_FILE" > "${PREFERENCES_FILE}.tmp" && mv "${PREFERENCES_FILE}.tmp" "$PREFERENCES_FILE"
  else
    # Best-effort sed
    sed -i "s|\"accept_languages\"[[:space:]]*:[[:space:]]*\"[^\"]*\"|\"accept_languages\":\"${LANGUAGE_SAFE},${LOCALE:-en-US}\"|g" "$PREFERENCES_FILE" 2>/dev/null || true
    sed -i "s|\"selected_languages\"[[:space:]]*:[[:space:]]*\"[^\"]*\"|\"selected_languages\":\"${LANGUAGE_SAFE}\"|g" "$PREFERENCES_FILE" 2>/dev/null || true
    sed -i "s|\"restore_on_startup\"[[:space:]]*:[[:space:]]*[0-9]\+|\"restore_on_startup\": 1|g" "$PREFERENCES_FILE" 2>/dev/null || true
  fi
fi

if [ ! -f "$LOCAL_STATE_FILE" ]; then
  log "Creating new Local State file..."
  cat > "$LOCAL_STATE_FILE" << EOF
{
  "browser": {
    "last_known_quit_time": "$(date +%s)"
  }
}
EOF
fi

log "Proxy: ${PROXY_URL:-<none>}"
log "Detection Level: ${DETECTION_LEVEL:-ENHANCED}"

# =============================
# Xvfb / fluxbox / vnc / novnc
# =============================
log "Starting Xvfb: $SCREEN_GEOMETRY"
Xvfb :0 -screen 0 "$SCREEN_GEOMETRY" -ac +extension GLX +extension RANDR +extension RENDER -noreset &
XVFB_PID=$!
sleep 2
kill -0 "$XVFB_PID" >/dev/null 2>&1 || { log "ERROR: Xvfb failed"; exit 1; }

log "Setting up X11 auth..."
xauth generate :0 . trusted 2>/dev/null || true

log "Starting fluxbox..."
fluxbox -display :0 &

log "Starting x11vnc..."
x11vnc -display :0 -rfbport 5900 -forever -shared -quiet -xkb \
  -noxrecord -noxfixes -noxdamage -wait 5 -noshm -auth "$XAUTHORITY" &

NOVNC_DIR="/usr/share/novnc"
WEBSOCKIFY_BIN="/usr/bin/websockify"
if [ -d "$NOVNC_DIR" ] && [ -x "$WEBSOCKIFY_BIN" ]; then
  log "Starting noVNC on :6080..."
  "$WEBSOCKIFY_BIN" --web "$NOVNC_DIR" 0.0.0.0:6080 localhost:5900 >/dev/null 2>&1 &
#    "$WEBSOCKIFY_BIN" --web "$NOVNC_DIR" --ignore-origin 0.0.0.0:6080 localhost:5900 >/dev/null 2>&1 &

fi

log "Checking X11..."
for i in {1..30}; do
  if xdpyinfo >/dev/null 2>&1; then
    log "X11 ready in ${i}s"
    break
  fi
  sleep 1
  if [ "$i" -eq 30 ]; then
    log "ERROR: X11 not ready"
    exit 1
  fi
done

# =============================
# CHROMIUM ARGS
# =============================
CHROME_ARGS=()
CHROME_ARGS+=(--window-size="${SCREEN_WIDTH},${SCREEN_HEIGHT}")
CHROME_ARGS+=(--no-first-run)
CHROME_ARGS+=(--no-default-browser-check)
CHROME_ARGS+=(--log-level=1)

CHROME_ARGS+=(--disable-dev-shm-usage)
CHROME_ARGS+=(--disable-gpu)
CHROME_ARGS+=(--disable-software-rasterizer)

[ -n "$USER_AGENT_SAFE" ] && CHROME_ARGS+=("--user-agent=${USER_AGENT_SAFE}")
CHROME_ARGS+=("--force-device-scale-factor=${PIXEL_RATIO}")
CHROME_ARGS+=("--lang=${LANGUAGE_SAFE}")

if [ -n "${PROXY_URL:-}" ]; then
  CHROME_ARGS+=("--proxy-server=${PROXY_URL}")
#  CHROME_ARGS+=("--host-resolver-rules=MAP * ~NOTFOUND , EXCLUDE 127.0.0.1")
fi

# IMPORTANT for docker port-forwarding:
CHROME_ARGS+=(--remote-debugging-port=9222)
if [[ "${CHROME_FLAGS:-}" == *"remote-debugging-address"* ]] || [[ "${CHROME_FLAGS:-}" == *"remote-debugging-port"* ]]; then
  log "WARNING: CHROME_FLAGS contains remote-debugging flags; it may override defaults!"
fi

# sometimes needed on modern chromium:
CHROME_ARGS+=(--remote-allow-origins=*)

if [ -n "${CHROME_FLAGS:-}" ]; then
  # shellcheck disable=SC2206
  EXTRA_FLAGS=($CHROME_FLAGS)
  CHROME_ARGS+=("${EXTRA_FLAGS[@]}")
fi

CHROME_LOG="/tmp/chromium.log"
rm -f "$CHROME_LOG" 2>/dev/null || true

# =============================
# START CHROMIUM
# =============================
log "Starting Chromium..."
log "Args count: ${#CHROME_ARGS[@]}"
chromium --user-data-dir="$USER_DATA_DIR" "${CHROME_ARGS[@]}" >"$CHROME_LOG" 2>&1 &
CHROME_PID=$!
log "Chromium PID: $CHROME_PID"

sleep 2
if ! kill -0 "$CHROME_PID" 2>/dev/null; then
  log "ERROR: Chromium exited immediately."
  tail -n 200 "$CHROME_LOG" || true
  exit 1
fi

# Wait DevTools (inside container)
log "Waiting for DevTools inside container: http://127.0.0.1:9222/json/version"
for i in {1..30}; do
  if curl -sf http://127.0.0.1:9222/json/version >/dev/null 2>&1; then
    log "DevTools ready (inside container)."
    break
  fi
  sleep 1
  if [ "$i" -eq 30 ]; then
    log "ERROR: DevTools not ready"
    exit 1
  fi
done

# Check listen address: if still 127.0.0.1, create proxy 0.0.0.0:9223 -> 127.0.0.1:9222
if command -v netstat >/dev/null 2>&1; then
  if netstat -lntp 2>/dev/null | grep -qE '127\.0\.0\.1:9222.*LISTEN'; then
    log "DevTools is bound to 127.0.0.1:9222. Creating TCP proxy 0.0.0.0:9223 -> 127.0.0.1:9222"
    if command -v socat >/dev/null 2>&1; then
      log "Starting DevTools TCP proxy 0.0.0.0:9223 -> 127.0.0.1:9222"
      socat TCP-LISTEN:9223,fork,reuseaddr TCP:127.0.0.1:9222 &
      SOCAT_PID=$!
      log "socat PID: ${SOCAT_PID}"
    else
      log "ERROR: socat not found. Install it or ensure Chromium binds to 0.0.0.0"
    fi
  fi
fi



log "Chromium started successfully!"
log "VNC URL (container): http://localhost:6080/vnc.html"
log "DevTools URL (container): http://localhost:9222"

# Copy fingerprint script (does not affect session)
if [ -f "/scripts/inject.js" ] && [ -s "/scripts/inject.js" ]; then
  log "Fingerprint script found, copying into profile..."
  cp -f /scripts/inject.js "$USER_DATA_DIR/Default/fingerprint_inject.js" || true
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
    pkill -TERM -f "socat TCP-LISTEN:9223" 2>/dev/null || true
    pkill -KILL -f "socat TCP-LISTEN:9223" 2>/dev/null || true


    # Копируем Current Session в Last Session для восстановления
    if [ -f "$CURRENT_SESSION_FILE" ] && [ -s "$CURRENT_SESSION_FILE" ]; then
        echo "Copying Current Session to Last Session..."
        cp -f "$CURRENT_SESSION_FILE" "$SESSION_FILE" 2>/dev/null || true
    fi

    echo "Shutdown completed"
    sleep 5
}

trap cleanup EXIT INT TERM

# Ждем завершения Chromium
echo "Waiting for Chromium to exit..."
wait $CHROME_PID 2>/dev/null || true
echo "Chromium exited"






(function() {
//     'use strict';
//
//     // Проверка, не был ли уже патч применен
//     if (window._fingerprintPatched) {
//         console.log('[Fingerprint] Already patched, skipping');
//         return;
//     }
//     window._fingerprintPatched = true;
//
//     console.log('[Fingerprint Patch] Starting full injection...');
//
//     // ========== ОСНОВНЫЕ ПАТЧИ ==========
//
//     // 1. WebDriver - самый важный патч
//     Object.defineProperty(navigator, 'webdriver', {
//         get: () => false,
//         configurable: true
//     });
//
//     // 2. Permissions API - для sandbox
//     const originalPermissionsQuery = navigator.permissions?.query;
//     if (originalPermissionsQuery) {
//         navigator.permissions.query = function(parameters) {
//             // Возвращаем granted для sandbox запросов
//             if (parameters && parameters.name === 'sandbox') {
//                 return Promise.resolve({
//                     state: 'granted',
//                     onchange: null
//                 });
//             }
//             // Для notifications тоже можно патчить
//             if (parameters && parameters.name === 'notifications') {
//                 return Promise.resolve({
//                     state: 'prompt',
//                     onchange: null
//                 });
//             }
//             return originalPermissionsQuery.call(this, parameters);
//         };
//     }
//
//     // 3. Chrome runtime API
//     if (typeof window.chrome !== 'undefined') {
//         const chromePatch = {
//             runtime: {
//                 id: 'fingerprintpatch',
//                 getManifest: () => ({}),
//                 connect: () => ({ onMessage: { addListener: () => {} }, postMessage: () => {} }),
//                 sendMessage: () => Promise.resolve({}),
//                 onMessage: { addListener: () => {} }
//             },
//             loadTimes: () => ({
//                 requestTime: Date.now() / 1000,
//                 startLoadTime: Date.now() / 1000,
//                 commitLoadTime: Date.now() / 1000,
//                 finishDocumentLoadTime: Date.now() / 1000,
//                 finishLoadTime: Date.now() / 1000,
//                 navigationType: 'Reload',
//                 wasFetchedViaSpdy: false,
//                 wasNpnNegotiated: false,
//                 npnNegotiatedProtocol: '',
//                 wasAlternateProtocolAvailable: false,
//                 connectionInfo: 'http/1.1'
//             }),
//             csi: () => ({
//                 onloadT: Date.now(),
//                 startE: Date.now() - 100,
//                 pageT: 100,
//                 tran: 15
//             }),
//             app: {
//                 isInstalled: false,
//                 InstallState: {
//                     DISABLED: 'disabled',
//                     INSTALLED: 'installed',
//                     NOT_INSTALLED: 'not_installed'
//                 },
//                 RunningState: {
//                     CANNOT_RUN: 'cannot_run',
//                     READY_TO_RUN: 'ready_to_run',
//                     RUNNING: 'running'
//                 }
//             }
//         };
//
//         Object.assign(window.chrome, chromePatch);
//     }
//
//     // 4. Плагины
//     const mockPlugins = [
//         { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
//         { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai', description: '' },
//         { name: 'Native Client', filename: 'internal-nacl-plugin', description: '' }
//     ];
//
//     Object.defineProperty(navigator, 'plugins', {
//         get: () => ({
//             length: mockPlugins.length,
//             item: (index) => mockPlugins[index] || null,
//             namedItem: (name) => mockPlugins.find(p => p.name === name) || null,
//             refresh: () => {},
//             [Symbol.iterator]: function* () {
//                 for (let i = 0; i < mockPlugins.length; i++) {
//                     yield mockPlugins[i];
//                 }
//             }
//         }),
//         configurable: true
//     });
//
//     // 5. Languages
//     Object.defineProperty(navigator, 'languages', {
//         get: () => ['en-US', 'en', 'ru-RU', 'ru'],
//         configurable: true
//     });
//
//     // 6. UserAgent патч (опционально)
//     const originalUserAgent = navigator.userAgent;
//     if (originalUserAgent.includes('Headless') || originalUserAgent.includes('X11; Linux')) {
//         Object.defineProperty(navigator, 'userAgent', {
//             get: () => originalUserAgent.replace(/HeadlessChrome|X11; Linux/g, 'Windows NT 10.0; Win64; x64'),
//             configurable: true
//         });
//     }
//
//     // 7. Удаление атрибутов автоматизации из DOM
//     document.documentElement.removeAttribute('webdriver');
//     document.documentElement.removeAttribute('selenium');
//     document.documentElement.removeAttribute('driver');
//
//     // 8. Перехват window.open для инъекции в новые окна
//     const originalWindowOpen = window.open;
//     if (originalWindowOpen) {
//         window.open = function(...args) {
//             const newWindow = originalWindowOpen.apply(this, args);
//             if (newWindow) {
//                 setTimeout(() => {
//                     try {
//                         // Инжектим патч в новое окно
//                         const scriptContent = `(${this.inject.toString()})();`;
//                         newWindow.eval(scriptContent);
//                     } catch(e) {}
//                 }, 500);
//             }
//             return newWindow;
//         };
//     }
//
//     console.log('[Fingerprint Patch] Full injection completed successfully!');
//
//     // ========== ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ ==========
//
//     // Функция для проверки работы патча
//     window.checkFingerprint = function() {
//         console.log('=== Fingerprint Check ===');
//         console.log('1. webdriver:', navigator.webdriver);
//         console.log('2. plugins count:', navigator.plugins.length);
//         console.log('3. languages:', navigator.languages);
//         console.log('4. has chrome.runtime:', !!window.chrome?.runtime);
//         console.log('5. userAgent:', navigator.userAgent.substring(0, 80) + '...');
//         console.log('=== End Check ===');
//     };
//
//     // Запускаем проверку автоматически
//     setTimeout(() => window.checkFingerprint && window.checkFingerprint(), 2000);
//
// })();











// последний рабочий
#!/usr/bin/env bash
set -euo pipefail

# =============================
# ENVIRONMENT
# =============================
export DISPLAY=:0
export XAUTHORITY=/tmp/.Xauthority
export NO_AT_BRIDGE=1

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# =============================
# CONFIGURATION
# =============================
SCREEN_WIDTH=${SCREEN_WIDTH:-1920}
SCREEN_HEIGHT=${SCREEN_HEIGHT:-1080}
SCREEN_COLOR_DEPTH=${SCREEN_COLOR_DEPTH:-24}
SCREEN_GEOMETRY="${SCREEN_WIDTH}x${SCREEN_HEIGHT}x${SCREEN_COLOR_DEPTH}"

PIXEL_RATIO=${PIXEL_RATIO:-1.0}
USER_DATA_DIR="${USER_DATA_DIR:-/data/user-data}"

LOCALE=${LOCALE:-en_US.UTF-8}
LANGUAGE=${LANGUAGE:-en-US}
TIMEZONE=${TIMEZONE:-}
USER_AGENT=${USER_AGENT:-}
PROXY_URL=${PROXY_URL:-}

export LANG="$LOCALE"
export LC_ALL="$LOCALE"

if [ -n "${TIMEZONE}" ]; then
  export TZ="$TIMEZONE"
fi

# =============================
# INITIAL CLEANUP
# =============================
log "=== STARTUP CLEANUP ==="

pkill -TERM -f "websockify" 2>/dev/null || true
pkill -TERM -f "x11vnc" 2>/dev/null || true
pkill -TERM -f "fluxbox" 2>/dev/null || true
pkill -TERM -f "Xvfb" 2>/dev/null || true
pkill -TERM -f "socat" 2>/dev/null || true
sleep 1
pkill -KILL -f "websockify" 2>/dev/null || true
pkill -KILL -f "x11vnc" 2>/dev/null || true
pkill -KILL -f "fluxbox" 2>/dev/null || true
pkill -KILL -f "Xvfb" 2>/dev/null || true
pkill -KILL -f "socat" 2>/dev/null || true

rm -rf /tmp/.X*-lock /tmp/.X11-unix/* 2>/dev/null || true
rm -f "$XAUTHORITY" 2>/dev/null || true
touch "$XAUTHORITY"
chmod 600 "$XAUTHORITY"

# =============================
# SETUP USER DIRECTORIES
# =============================
log "Setting up user directories..."
mkdir -p /home/chrome/.config 2>/dev/null || true
mkdir -p /home/chrome/.pki/nssdb 2>/dev/null || true
chown -R chrome:chrome /home/chrome 2>/dev/null || true

if [ ! -w "$USER_DATA_DIR" ] || [ ! -d "$USER_DATA_DIR" ]; then
  log "Fixing permissions for $USER_DATA_DIR..."
  mkdir -p /data 2>/dev/null || true
  mkdir -p "$USER_DATA_DIR" 2>/dev/null || true
  sudo chown -R chrome:chrome "$USER_DATA_DIR" 2>/dev/null || \
  chown -R chrome:chrome "$USER_DATA_DIR" 2>/dev/null || true
  sudo chmod -R 755 "$USER_DATA_DIR" 2>/dev/null || \
  chmod -R 755 "$USER_DATA_DIR" 2>/dev/null || true
fi

mkdir -p "$USER_DATA_DIR/Default" 2>/dev/null || { log "ERROR: Cannot create Default directory"; exit 1; }
mkdir -p "$USER_DATA_DIR/Default/Downloads" 2>/dev/null || true
find "$USER_DATA_DIR" -name "Singleton*" -delete 2>/dev/null || true

log "Using user data dir: $USER_DATA_DIR"
log "Screen: ${SCREEN_WIDTH}x${SCREEN_HEIGHT}, Pixel ratio: ${PIXEL_RATIO}"
log "Language: ${LANGUAGE}, Timezone: ${TIMEZONE:-system}"

# ================== ЗАПУСК Xvfb ==================
log "Starting Xvfb..."
Xvfb :0 -screen 0 "$SCREEN_GEOMETRY" -ac +extension GLX +extension RANDR +extension RENDER -noreset &
XVFB_PID=$!
sleep 3

if ! kill -0 $XVFB_PID 2>/dev/null; then
    log "ERROR: Xvfb failed to start!"
    exit 1
fi

# ================== X11 AUTH ==================
log "Setting up X11 auth..."
xauth generate :0 . trusted 2>/dev/null || true

# ================== ЗАПУСК fluxbox ==================
log "Starting fluxbox..."
fluxbox &

# ================== ЗАПУСК x11vnc ==================
log "Starting x11vnc..."
x11vnc -display :0 -rfbport 5900 -forever -shared -nopw -quiet -xkb -noxrecord -noxfixes -noxdamage -wait 5 -noshm -auth "$XAUTHORITY" &

# ================== ЗАПУСК noVNC ==================
NOVNC_DIR="/usr/share/novnc"
WEBSOCKIFY_BIN="/usr/bin/websockify"

if [ -d "$NOVNC_DIR" ] && [ -x "$WEBSOCKIFY_BIN" ]; then
  log "Starting noVNC on port 6080..."
  $WEBSOCKIFY_BIN --web "$NOVNC_DIR" 0.0.0.0:6080 localhost:5900 2>/dev/null &
fi

# ================== ПРОВЕРКА X11 DISPLAY ==================
log "Checking X11 display..."
for i in {1..30}; do
    if xdpyinfo >/dev/null 2>&1; then
        log "X11 display is ready after $i seconds"
        break
    fi
    sleep 1
    if [ $i -eq 30 ]; then
        log "ERROR: X11 display not ready after 30 seconds"
        exit 1
    fi
done

# ================== ЗАПУСК CHROMIUM ==================
log "Starting Chromium..."

# Base arguments for maximum anti-detection
CHROME_ARGS=(
    --window-size="${SCREEN_WIDTH},${SCREEN_HEIGHT}"
    --no-first-run
    --no-default-browser-check
    --disable-dev-shm-usage
    --disable-accelerated-2d-canvas
    --disable-accelerated-video-decode
    --disable-background-networking
    --disable-breakpad
    --disable-component-update
    --disable-domain-reliability
    --disable-features=VizDisplayCompositor
    --disable-hang-monitor
    --disable-ipc-flooding-protection
    --disable-popup-blocking
    --disable-prompt-on-repost
    --disable-renderer-backgrounding
    --disable-sync
    --force-device-scale-factor="${PIXEL_RATIO}"
    --lang="${LANGUAGE}"
    --remote-debugging-port=9222
    --remote-allow-origins=*
    --user-data-dir="${USER_DATA_DIR}"
    --use-gl=swiftshader
    --disable-software-rasterizer
    --disable-blink-features=AutomationControlled
    --homepage="https://www.google.com"
)

# ВАЖНО: Для максимального антидетекта мы ДОЛЖНЫ использовать --no-sandbox в Docker
# но скрываем это от детекции
CHROME_ARGS+=(--no-sandbox --disable-setuid-sandbox)

# User agent
if [ -n "${USER_AGENT}" ]; then
    CHROME_ARGS+=(--user-agent="${USER_AGENT}")
fi

# Proxy
if [ -n "${PROXY_URL}" ]; then
    CHROME_ARGS+=(--proxy-server="${PROXY_URL}")
fi

# Custom flags
if [ -n "${CHROME_FLAGS:-}" ]; then
  EXTRA_FLAGS=($CHROME_FLAGS)
  CHROME_ARGS+=("${EXTRA_FLAGS[@]}")
fi

# Запускаем Chromium
/usr/lib/chromium/chromium "${CHROME_ARGS[@]}" &
CHROME_PID=$!
log "Chromium PID: $CHROME_PID"

# ================== COPY FINGERPRINT SCRIPT ==================
if [ -f "/scripts/inject.js" ] && [ -s "/scripts/inject.js" ]; then
  log "Copying fingerprint injection script..."
  cp -f /scripts/inject.js "$USER_DATA_DIR/Default/fingerprint_inject.js" 2>/dev/null || true
fi

# ================== STARTUP COMPLETE ==================
log "=== STARTUP COMPLETE ==="
log "VNC URL: http://localhost:6080/vnc.html"
log "DevTools URL: http://localhost:9222"
log "Screen: ${SCREEN_GEOMETRY}"
log "Profile: ${USER_DATA_DIR}"
log "Anti-detection mode: ENHANCED (with sandbox bypass)"

# ================== WAIT FOR DEVTOOLS ==================
log "Waiting for DevTools API..."
for i in {1..60}; do
  if curl -sf http://127.0.0.1:9222/json/version >/dev/null 2>&1; then
    log "DevTools API ready after ${i}s"
    break
  fi
  sleep 1
  if [ $i -eq 60 ]; then
    log "WARNING: DevTools API not ready after 60s"
  fi
done

# ================== MONITOR CHROMIUM ==================
log "Monitoring Chromium process..."
wait "$CHROME_PID" 2>/dev/null || true
log "Chromium process ended"

# Cleanup
log "=== SHUTDOWN INITIATED ==="
pkill -TERM -f "websockify" 2>/dev/null || true
pkill -TERM -f "x11vnc" 2>/dev/null || true
pkill -TERM -f "fluxbox" 2>/dev/null || true
pkill -TERM -f "Xvfb" 2>/dev/null || true
sleep 1
log "=== SHUTDOWN COMPLETE ==="
exit 0


15ю12 17-00

FROM debian:12-slim

ENV DEBIAN_FRONTEND=noninteractive \
    DISPLAY=:0 \
    LANG=en_US.UTF-8 \
    LC_ALL=en_US.UTF-8 \
    TZ=Europe/Moscow

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl wget gnupg tzdata locales \
    xvfb x11vnc fluxbox xauth x11-utils \
    novnc websockify \
    chromium \
    dbus dbus-x11 \
    xdotool wmctrl \
    sqlite3 \
    fonts-liberation fonts-noto fonts-noto-cjk fonts-noto-color-emoji \
    procps jq socat tini \
    && rm -rf /var/lib/apt/lists/*

RUN sed -i 's/# en_US.UTF-8 UTF-8/en_US.UTF-8 UTF-8/' /etc/locale.gen && \
    locale-gen

# Ensure machine-id exists & stable inside the container filesystem
RUN dbus-uuidgen --ensure=/etc/machine-id

RUN mkdir -p /data/user-data /scripts /tmp/.X11-unix /run/dbus && \
    chmod 1777 /tmp/.X11-unix

COPY start.sh /scripts/start.sh
RUN chmod +x /scripts/start.sh

WORKDIR /scripts
EXPOSE 6080 9222 9223

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS http://127.0.0.1:6080/ >/dev/null || exit 1

ENTRYPOINT ["/usr/bin/tini","--"]
CMD ["/scripts/start.sh"]




#!/usr/bin/env bash
set -Eeuo pipefail

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

DISPLAY_NUM="${DISPLAY_NUM:-0}"
export DISPLAY=":${DISPLAY_NUM}"

SCREEN_WIDTH="${SCREEN_WIDTH:-1920}"
SCREEN_HEIGHT="${SCREEN_HEIGHT:-1080}"
SCREEN_DEPTH="${SCREEN_COLOR_DEPTH:-24}"
SCREEN_GEOMETRY="${SCREEN_WIDTH}x${SCREEN_HEIGHT}x${SCREEN_DEPTH}"

USER_DATA_DIR="${USER_DATA_DIR:-/data/user-data}"
LANGUAGE_SAFE="${LANGUAGE:-en-US}"
TZ_SAFE="${TIMEZONE:-${TZ:-}}"

DEVTOOLS_PORT="${DEVTOOLS_PORT:-9222}"
DEVTOOLS_PROXY_PORT="${DEVTOOLS_PROXY_PORT:-9223}"
NOVNC_PORT="${NOVNC_PORT:-6080}"

CHROMIUM_BIN="${CHROMIUM_BIN:-$(command -v chromium || true)}"
if [[ -z "${CHROMIUM_BIN}" ]]; then
  log "FATAL: chromium binary not found"
  exit 1
fi

CHROME_PID=""
XVFB_PID=""

cleanup() {
  log "=== SHUTDOWN ==="

 # stop chromium gracefully
  # shellcheck disable=SC2317
  if [[ -n "${CHROME_PID}" ]] && kill -0 "${CHROME_PID}" 2>/dev/null; then
    log "Pre-shutdown wait (flush profile to disk)..."
    sleep 10

    # попытка попросить Chromium завершиться мягко
    log "Stopping Chromium (TERM, wait up to 55s)..."
    kill -TERM "${CHROME_PID}" 2>/dev/null || true

    for i in $(seq 1 55); do
      kill -0 "${CHROME_PID}" 2>/dev/null || break
      sleep 1
    done

    if kill -0 "${CHROME_PID}" 2>/dev/null; then
      log "Chromium still running -> KILL"
      kill -KILL "${CHROME_PID}" 2>/dev/null || true
    fi
  fi

  sync || true
  sleep 2

  # stop aux
  pkill -TERM -f "socat TCP-LISTEN:${DEVTOOLS_PROXY_PORT}" 2>/dev/null || true
  pkill -TERM -f "novnc_proxy|websockify" 2>/dev/null || true
  pkill -TERM -f "x11vnc" 2>/dev/null || true
  pkill -TERM -f "fluxbox" 2>/dev/null || true

  # stop xvfb and wait
  if [[ -n "${XVFB_PID}" ]] && kill -0 "${XVFB_PID}" 2>/dev/null; then
    log "Stopping Xvfb..."
    kill -TERM "${XVFB_PID}" 2>/dev/null || true
    for i in $(seq 1 10); do
      kill -0 "${XVFB_PID}" 2>/dev/null || break
      sleep 1
    done
    kill -KILL "${XVFB_PID}" 2>/dev/null || true
  else
    pkill -TERM -f "Xvfb :${DISPLAY_NUM}" 2>/dev/null || true
    pkill -KILL -f "Xvfb :${DISPLAY_NUM}" 2>/dev/null || true
  fi

  # IMPORTANT: remove stale lock files
  rm -f "/tmp/.X${DISPLAY_NUM}-lock" 2>/dev/null || true
  rm -f "/tmp/.X11-unix/X${DISPLAY_NUM}" 2>/dev/null || true

  pkill -TERM -f "dbus-daemon --system" 2>/dev/null || true

  log "=== SHUTDOWN COMPLETE ==="
}
trap cleanup EXIT
trap 'exit 0' TERM INT

# ---- timezone ----
if [[ -n "${TZ_SAFE}" ]]; then
  log "Timezone: ${TZ_SAFE}"
  export TZ="${TZ_SAFE}"
fi

# ---- start system dbus ----
mkdir -p /run/dbus
if ! pgrep -f "dbus-daemon --system" >/dev/null 2>&1; then
  log "Starting system dbus-daemon..."
  dbus-daemon --system --fork --nopidfile >/tmp/dbus-system.log 2>&1 || true
fi

# ---- cleanup possible stale X locks BEFORE Xvfb start ----
rm -f "/tmp/.X${DISPLAY_NUM}-lock" 2>/dev/null || true
rm -f "/tmp/.X11-unix/X${DISPLAY_NUM}" 2>/dev/null || true

# (optional) kill leftovers if container is restarting too fast
pkill -TERM -f "Xvfb :${DISPLAY_NUM}" 2>/dev/null || true
pkill -TERM -f "x11vnc" 2>/dev/null || true
pkill -TERM -f "fluxbox" 2>/dev/null || true
pkill -TERM -f "novnc_proxy|websockify" 2>/dev/null || true
sleep 1

# ---- ensure profile writable ----
mkdir -p "${USER_DATA_DIR}/Default" || true
chmod -R 777 "${USER_DATA_DIR}" 2>/dev/null || true

TEST_FILE="${USER_DATA_DIR}/__write_test"
echo "ok" > "${TEST_FILE}" 2>/dev/null || {
  log "FATAL: USER_DATA_DIR is not writable: ${USER_DATA_DIR}"
  ls -la "${USER_DATA_DIR}" || true
  exit 1
}
rm -f "${TEST_FILE}" 2>/dev/null || true

# remove only singleton locks
find "${USER_DATA_DIR}" -maxdepth 2 -name "Singleton*" -delete 2>/dev/null || true

# preferences restore session
PREFERENCES_FILE="${USER_DATA_DIR}/Default/Preferences"
if [[ ! -f "${PREFERENCES_FILE}" ]]; then
  log "Creating Preferences (restore last session)..."
  cat > "${PREFERENCES_FILE}" <<'EOF'
{
  "session": { "restore_on_startup": 1 },
  "profile": { "exited_cleanly": true, "exit_type": "Normal" }
}
EOF
fi

# ---- Xvfb ----
log "Starting Xvfb on ${DISPLAY} (${SCREEN_GEOMETRY})..."
Xvfb "${DISPLAY}" -screen 0 "${SCREEN_GEOMETRY}" -ac +extension RANDR +extension RENDER -noreset >/tmp/xvfb.log 2>&1 &
XVFB_PID=$!
sleep 1
if ! kill -0 "${XVFB_PID}" 2>/dev/null; then
  log "FATAL: Xvfb failed to start. Tail:"
  tail -n 50 /tmp/xvfb.log || true
  exit 1
fi

log "Waiting for X11 display..."
for i in $(seq 1 30); do
  if xdpyinfo >/dev/null 2>&1; then
    log "X11 ready after ${i}s"
    break
  fi
  sleep 1
  if [[ "${i}" == "30" ]]; then
    log "FATAL: X11 not ready"
    exit 1
  fi
done

# ---- WM + VNC + noVNC ----
log "Starting fluxbox..."
fluxbox >/tmp/fluxbox.log 2>&1 &

log "Starting x11vnc on :5900..."
x11vnc -display "${DISPLAY}" -rfbport 5900 -forever -shared -nopw -xkb \
  -noxrecord -noxfixes -noxdamage >/tmp/x11vnc.log 2>&1 &

NOVNC_PROXY="/usr/share/novnc/utils/novnc_proxy"
log "Starting noVNC on :${NOVNC_PORT} ..."
"${NOVNC_PROXY}" --listen "0.0.0.0:${NOVNC_PORT}" --vnc "127.0.0.1:5900" >/tmp/novnc.log 2>&1 &

# ---- Chromium ----
CHROME_LOG="/tmp/chromium.log"
rm -f "${CHROME_LOG}" 2>/dev/null || true

CHROME_ARGS=(
  "--user-data-dir=${USER_DATA_DIR}"
  "--window-size=${SCREEN_WIDTH},${SCREEN_HEIGHT}"
  "--lang=${LANGUAGE_SAFE}"
  "--restore-last-session"
  "--no-first-run"
  "--no-default-browser-check"
  "--disable-dev-shm-usage"
  "--remote-debugging-address=0.0.0.0"
  "--remote-debugging-port=${DEVTOOLS_PORT}"
  "--remote-allow-origins=*"
  "--password-store=basic"
  "--use-mock-keychain"
  "--no-sandbox"
  "--disable-setuid-sandbox"
  "--disable-features=UseChromeOSDirectVideoDecoder"
  "--disable-background-timer-throttling"
  "--disable-renderer-backgrounding"
  "--disable-backgrounding-occluded-windows"
)

log "Starting Chromium: ${CHROMIUM_BIN}"
"${CHROMIUM_BIN}" "${CHROME_ARGS[@]}" >"${CHROME_LOG}" 2>&1 &
CHROME_PID=$!
log "Chromium PID: ${CHROME_PID}"

sleep 2
if ! kill -0 "${CHROME_PID}" 2>/dev/null; then
  log "FATAL: Chromium exited immediately. Tail:"
  tail -n 200 "${CHROME_LOG}" || true
  exit 1
fi

log "Waiting for DevTools API..."
for i in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:${DEVTOOLS_PORT}/json/version" >/dev/null 2>&1; then
    log "DevTools ready after ${i}s"
    break
  fi
  sleep 1
done

if command -v socat >/dev/null 2>&1; then
  log "Starting DevTools proxy :${DEVTOOLS_PROXY_PORT} -> :${DEVTOOLS_PORT}"
  socat "TCP-LISTEN:${DEVTOOLS_PROXY_PORT},fork,reuseaddr" "TCP:127.0.0.1:${DEVTOOLS_PORT}" >/tmp/socat.log 2>&1 &
fi

log "=== STARTUP COMPLETE ==="
log "noVNC:    http://localhost:${NOVNC_PORT}/vnc.html"
log "DevTools: http://localhost:${DEVTOOLS_PORT}"
log "Proxy:    http://localhost:${DEVTOOLS_PROXY_PORT}"
log "Profile:  ${USER_DATA_DIR}"

wait "${CHROME_PID}" || true
log "Chromium exited. Tail log:"
tail -n 200 "${CHROME_LOG}" || true
exit 0
