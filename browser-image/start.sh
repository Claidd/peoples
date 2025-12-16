#!/usr/bin/env bash
set -Eeuo pipefail

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# =============================
# ENV (safe defaults)
# =============================
DISPLAY_NUM="${DISPLAY_NUM:-0}"
export DISPLAY=":${DISPLAY_NUM}"

SCREEN_WIDTH="${SCREEN_WIDTH:-1920}"
SCREEN_HEIGHT="${SCREEN_HEIGHT:-1080}"
SCREEN_DEPTH="${SCREEN_COLOR_DEPTH:-24}"
SCREEN_GEOMETRY="${SCREEN_WIDTH}x${SCREEN_HEIGHT}x${SCREEN_DEPTH}"

# DPR НЕ задаём флагами Chromium — пусть делает только DevTools эмуляция у тебя в Java
PIXEL_RATIO="${PIXEL_RATIO:-1.0}"

USER_DATA_DIR="${USER_DATA_DIR:-/data/user-data}"
LANGUAGE_SAFE="${LANGUAGE:-en-US}"
TZ_SAFE="${TIMEZONE:-${TZ:-}}"

# Наружный DevTools порт (его пробрасывает Docker)
DEVTOOLS_PORT="${DEVTOOLS_PORT:-9223}"
# Внутренний порт Chromium (loopback, всегда стабильно)
DEVTOOLS_PORT_INTERNAL="${DEVTOOLS_PORT_INTERNAL:-9222}"

NOVNC_PORT="${NOVNC_PORT:-6080}"

CHROMIUM_BIN="${CHROMIUM_BIN:-$(command -v chromium || true)}"
if [[ -z "${CHROMIUM_BIN}" ]]; then
  log "FATAL: chromium binary not found"
  exit 1
fi

CHROME_PID=""
CHROME_PGID=""
XVFB_PID=""
SYNC_PID=""
SOCAT_PID=""

# =============================
# helpers
# =============================
get_pgid() {
  local pid="$1"
  ps -o pgid= -p "${pid}" 2>/dev/null | tr -d ' ' || true
}

start_sync_loop() {
  (
    while true; do
      [[ -n "${CHROME_PID}" ]] && kill -0 "${CHROME_PID}" 2>/dev/null || exit 0
      sync || true
      sleep 5
    done
  ) &
  SYNC_PID=$!
}

stop_process_group_gracefully() {
  local pgid="$1"
  local title="$2"

  [[ -z "${pgid}" ]] && return 0

  log "Stopping ${title} process group PGID=${pgid} (TERM, wait up to 45s)..."
  kill -TERM "-${pgid}" 2>/dev/null || true

  for _ in $(seq 1 45); do
    if ! ps -o pid= --pgid "${pgid}" 2>/dev/null | grep -q '[0-9]'; then
      return 0
    fi
    sleep 1
  done

  log "${title} still running -> INT (wait 10s)..."
  kill -INT "-${pgid}" 2>/dev/null || true
  sleep 10

  if ps -o pid= --pgid "${pgid}" 2>/dev/null | grep -q '[0-9]'; then
    log "${title} still running -> KILL"
    kill -KILL "-${pgid}" 2>/dev/null || true
  fi
}

cleanup() {
  log "=== SHUTDOWN ==="

  if [[ -n "${SYNC_PID}" ]] && kill -0 "${SYNC_PID}" 2>/dev/null; then
    kill -TERM "${SYNC_PID}" 2>/dev/null || true
  fi

  if [[ -n "${SOCAT_PID}" ]] && kill -0 "${SOCAT_PID}" 2>/dev/null; then
    kill -TERM "${SOCAT_PID}" 2>/dev/null || true
  fi

  if [[ -n "${CHROME_PID}" ]] && kill -0 "${CHROME_PID}" 2>/dev/null; then
    log "Pre-shutdown wait (flush profile to disk)..."
    sync || true
    sleep 8
  fi

  if [[ -n "${CHROME_PGID}" ]]; then
    stop_process_group_gracefully "${CHROME_PGID}" "Chromium"
  elif [[ -n "${CHROME_PID}" ]] && kill -0 "${CHROME_PID}" 2>/dev/null; then
    log "Stopping Chromium by PID (fallback)..."
    kill -TERM "${CHROME_PID}" 2>/dev/null || true
  fi

  sync || true
  sleep 2

  pkill -TERM -f "novnc_proxy|websockify" 2>/dev/null || true
  pkill -TERM -f "x11vnc" 2>/dev/null || true
  pkill -TERM -f "fluxbox" 2>/dev/null || true
  pkill -TERM -f "socat.*TCP-LISTEN:${DEVTOOLS_PORT}" 2>/dev/null || true

  if [[ -n "${XVFB_PID}" ]] && kill -0 "${XVFB_PID}" 2>/dev/null; then
    log "Stopping Xvfb..."
    kill -TERM "${XVFB_PID}" 2>/dev/null || true
    for _ in $(seq 1 10); do
      kill -0 "${XVFB_PID}" 2>/dev/null || break
      sleep 1
    done
    kill -KILL "${XVFB_PID}" 2>/dev/null || true
  else
    pkill -TERM -f "Xvfb :${DISPLAY_NUM}" 2>/dev/null || true
    pkill -KILL -f "Xvfb :${DISPLAY_NUM}" 2>/dev/null || true
  fi

  rm -f "/tmp/.X${DISPLAY_NUM}-lock" 2>/dev/null || true
  rm -f "/tmp/.X11-unix/X${DISPLAY_NUM}" 2>/dev/null || true

  pkill -TERM -f "dbus-daemon --session" 2>/dev/null || true

  log "=== SHUTDOWN COMPLETE ==="
}
trap cleanup EXIT
trap 'exit 0' TERM INT

# =============================
# timezone
# =============================
if [[ -n "${TZ_SAFE}" ]]; then
  log "Timezone: ${TZ_SAFE}"
  export TZ="${TZ_SAFE}"
fi

# =============================
# dbus (session)
# =============================
if ! pgrep -f "dbus-daemon --session" >/dev/null 2>&1; then
  log "Starting session dbus-daemon..."
  dbus-daemon --session --fork --nopidfile >/tmp/dbus-session.log 2>&1 || true
fi

# =============================
# cleanup stale X locks
# =============================
rm -f "/tmp/.X${DISPLAY_NUM}-lock" 2>/dev/null || true
rm -f "/tmp/.X11-unix/X${DISPLAY_NUM}" 2>/dev/null || true

# =============================
# ensure profile writable
# =============================
mkdir -p "${USER_DATA_DIR}/Default" || true

TEST_FILE="${USER_DATA_DIR}/__write_test"
echo "ok" > "${TEST_FILE}" 2>/dev/null || {
  log "FATAL: USER_DATA_DIR is not writable: ${USER_DATA_DIR}"
  ls -la "${USER_DATA_DIR}" || true
  exit 1
}
rm -f "${TEST_FILE}" 2>/dev/null || true

find "${USER_DATA_DIR}" -maxdepth 2 -name "Singleton*" -delete 2>/dev/null || true

log "Profile dir: ${USER_DATA_DIR}"
ls -la "${USER_DATA_DIR}" 2>/dev/null || true

# =============================
# Xvfb
# =============================
log "Starting Xvfb on ${DISPLAY} (${SCREEN_GEOMETRY})..."
Xvfb "${DISPLAY}" -screen 0 "${SCREEN_GEOMETRY}" -ac +extension RANDR +extension RENDER -noreset >/tmp/xvfb.log 2>&1 &
XVFB_PID=$!
sleep 1
if ! kill -0 "${XVFB_PID}" 2>/dev/null; then
  log "FATAL: Xvfb failed to start. Tail:"
  tail -n 80 /tmp/xvfb.log || true
  exit 1
fi

log "Waiting for X11 display..."
for i in $(seq 1 30); do
  if xdpyinfo >/dev/null 2>&1; then
    log "X11 ready after ${i}s"
    break
  fi
  sleep 1
done

# =============================
# WM + VNC + noVNC
# =============================
log "Starting fluxbox..."
fluxbox >/tmp/fluxbox.log 2>&1 &

if command -v wmctrl >/dev/null 2>&1; then
  for _ in $(seq 1 40); do
    wmctrl -m >/dev/null 2>&1 && break
    sleep 0.25
  done
fi

log "Starting x11vnc on :5900..."
x11vnc -display "${DISPLAY}" -rfbport 5900 -forever -shared -nopw -xkb \
  -noxrecord -noxfixes -noxdamage >/tmp/x11vnc.log 2>&1 &

NOVNC_PROXY="/usr/share/novnc/utils/novnc_proxy"
log "Starting noVNC on :${NOVNC_PORT} ..."
"${NOVNC_PROXY}" --listen "0.0.0.0:${NOVNC_PORT}" --vnc "127.0.0.1:5900" >/tmp/novnc.log 2>&1 &

# =============================
# Chromium
# =============================
CHROME_LOG="/tmp/chromium.log"
rm -f "${CHROME_LOG}" 2>/dev/null || true

CHROME_ARGS=(
  "--user-data-dir=${USER_DATA_DIR}"
  "--profile-directory=Default"
  "--window-size=${SCREEN_WIDTH},${SCREEN_HEIGHT}"
  "--window-position=0,0"
  "--lang=${LANGUAGE_SAFE}"
  "--restore-last-session"
  "--no-first-run"
  "--no-default-browser-check"
  "--disable-dev-shm-usage"
  "--disable-session-crashed-bubble"

  # Важно: Chromium слушает только внутри (loopback) — наружу отдаём через socat
  "--remote-debugging-address=127.0.0.1"
  "--remote-debugging-port=${DEVTOOLS_PORT_INTERNAL}"
  "--remote-allow-origins=*"

  "--password-store=basic"
  "--use-mock-keychain"
  "--no-sandbox"
)

log "Starting Chromium (new process group via setsid): ${CHROMIUM_BIN}"
setsid "${CHROMIUM_BIN}" "${CHROME_ARGS[@]}" >"${CHROME_LOG}" 2>&1 &
CHROME_PID=$!
sleep 0.5
CHROME_PGID="$(get_pgid "${CHROME_PID}")"

log "Chromium PID: ${CHROME_PID}, PGID: ${CHROME_PGID}"

sleep 2
if ! kill -0 "${CHROME_PID}" 2>/dev/null; then
  log "FATAL: Chromium exited immediately. Tail:"
  tail -n 200 "${CHROME_LOG}" || true
  exit 1
fi

start_sync_loop

# =============================
# DevTools wait (INTERNAL)
# =============================
log "Waiting for INTERNAL DevTools API..."
for i in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:${DEVTOOLS_PORT_INTERNAL}/json/version" >/dev/null 2>&1; then
    log "Internal DevTools ready after ${i}s (port ${DEVTOOLS_PORT_INTERNAL})"
    break
  fi
  sleep 1
done

# =============================
# DevTools proxy (EXTERNAL) 9223 -> 9222
# =============================
if command -v socat >/dev/null 2>&1; then
  log "Starting DevTools proxy 0.0.0.0:${DEVTOOLS_PORT} -> 127.0.0.1:${DEVTOOLS_PORT_INTERNAL}"
  socat "TCP-LISTEN:${DEVTOOLS_PORT},fork,reuseaddr" "TCP:127.0.0.1:${DEVTOOLS_PORT_INTERNAL}" >/tmp/socat.log 2>&1 &
  SOCAT_PID=$!
else
  log "WARN: socat not found, external DevTools may not work"
fi

log "Waiting for EXTERNAL DevTools API..."
for i in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:${DEVTOOLS_PORT}/json/version" >/dev/null 2>&1; then
    log "External DevTools ready after ${i}s (port ${DEVTOOLS_PORT})"
    break
  fi
  sleep 1
done

# =============================
# FIX geometry
# =============================
if command -v xdotool >/dev/null 2>&1; then
  log "Fixing Chromium window geometry + reset zoom..."
  WIN_ID=""
  for _ in $(seq 1 80); do
    WIN_ID="$(xdotool search --onlyvisible --class chromium 2>/dev/null | tail -n 1 || true)"
    [[ -n "${WIN_ID}" ]] && break
    sleep 0.25
  done

  if [[ -n "${WIN_ID}" ]]; then
    xdotool windowmove "${WIN_ID}" 0 0 || true
    xdotool windowsize "${WIN_ID}" "${SCREEN_WIDTH}" "${SCREEN_HEIGHT}" || true
    xdotool windowactivate "${WIN_ID}" || true
    xdotool key --window "${WIN_ID}" ctrl+0 || true
    xdotool windowsize "${WIN_ID}" $((SCREEN_WIDTH-10)) "${SCREEN_HEIGHT}" || true
    xdotool windowsize "${WIN_ID}" "${SCREEN_WIDTH}" "${SCREEN_HEIGHT}" || true
  else
    log "WARN: Chromium window not found for xdotool resize"
  fi
fi

log "=== STARTUP COMPLETE ==="
log "noVNC:    http://localhost:${NOVNC_PORT}/vnc.html"
log "DevTools: http://localhost:${DEVTOOLS_PORT} (proxy -> ${DEVTOOLS_PORT_INTERNAL})"
log "Profile:  ${USER_DATA_DIR}"
log "Xvfb:     ${SCREEN_GEOMETRY}"
log "Note:     DPR is handled by DevTools emulation (PIXEL_RATIO=${PIXEL_RATIO})"

wait "${CHROME_PID}" || true

sync || true
sleep 2

log "Chromium exited. Tail log:"
tail -n 200 "${CHROME_LOG}" || true
exit 0







#!/usr/bin/env bash
#set -Eeuo pipefail
#
#log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }
#
#DISPLAY_NUM="${DISPLAY_NUM:-0}"
#export DISPLAY=":${DISPLAY_NUM}"
#
#SCREEN_WIDTH="${SCREEN_WIDTH:-1920}"
#SCREEN_HEIGHT="${SCREEN_HEIGHT:-1080}"
#SCREEN_DEPTH="${SCREEN_COLOR_DEPTH:-24}"
#SCREEN_GEOMETRY="${SCREEN_WIDTH}x${SCREEN_HEIGHT}x${SCREEN_DEPTH}"
#
#USER_DATA_DIR="${USER_DATA_DIR:-/data/user-data}"
#LANGUAGE_SAFE="${LANGUAGE:-en-US}"
#TZ_SAFE="${TIMEZONE:-${TZ:-}}"
#
#DEVTOOLS_PORT="${DEVTOOLS_PORT:-9222}"
#DEVTOOLS_PROXY_PORT="${DEVTOOLS_PROXY_PORT:-9223}"
#NOVNC_PORT="${NOVNC_PORT:-6080}"
#
#CHROMIUM_BIN="${CHROMIUM_BIN:-$(command -v chromium || true)}"
#if [[ -z "${CHROMIUM_BIN}" ]]; then
#  log "FATAL: chromium binary not found"
#  exit 1
#fi
#
#CHROME_PID=""
#XVFB_PID=""
#
#cleanup() {
#  log "=== SHUTDOWN ==="
#
# # stop chromium gracefully
#  # shellcheck disable=SC2317
#  if [[ -n "${CHROME_PID}" ]] && kill -0 "${CHROME_PID}" 2>/dev/null; then
#    log "Pre-shutdown wait (flush profile to disk)..."
#    sleep 10
#
#    # попытка попросить Chromium завершиться мягко
#    log "Stopping Chromium (TERM, wait up to 55s)..."
#    kill -TERM "${CHROME_PID}" 2>/dev/null || true
#
#    for i in $(seq 1 55); do
#      kill -0 "${CHROME_PID}" 2>/dev/null || break
#      sleep 1
#    done
#
#    if kill -0 "${CHROME_PID}" 2>/dev/null; then
#      log "Chromium still running -> KILL"
#      kill -KILL "${CHROME_PID}" 2>/dev/null || true
#    fi
#  fi
#
#  sync || true
#  sleep 2
#
#  # stop aux
#  pkill -TERM -f "socat TCP-LISTEN:${DEVTOOLS_PROXY_PORT}" 2>/dev/null || true
#  pkill -TERM -f "novnc_proxy|websockify" 2>/dev/null || true
#  pkill -TERM -f "x11vnc" 2>/dev/null || true
#  pkill -TERM -f "fluxbox" 2>/dev/null || true
#
#  # stop xvfb and wait
#  if [[ -n "${XVFB_PID}" ]] && kill -0 "${XVFB_PID}" 2>/dev/null; then
#    log "Stopping Xvfb..."
#    kill -TERM "${XVFB_PID}" 2>/dev/null || true
#    for i in $(seq 1 10); do
#      kill -0 "${XVFB_PID}" 2>/dev/null || break
#      sleep 1
#    done
#    kill -KILL "${XVFB_PID}" 2>/dev/null || true
#  else
#    pkill -TERM -f "Xvfb :${DISPLAY_NUM}" 2>/dev/null || true
#    pkill -KILL -f "Xvfb :${DISPLAY_NUM}" 2>/dev/null || true
#  fi
#
#  # IMPORTANT: remove stale lock files
#  rm -f "/tmp/.X${DISPLAY_NUM}-lock" 2>/dev/null || true
#  rm -f "/tmp/.X11-unix/X${DISPLAY_NUM}" 2>/dev/null || true
#
#  pkill -TERM -f "dbus-daemon --system" 2>/dev/null || true
#
#  log "=== SHUTDOWN COMPLETE ==="
#}
#trap cleanup EXIT
#trap 'exit 0' TERM INT
#
## ---- timezone ----
#if [[ -n "${TZ_SAFE}" ]]; then
#  log "Timezone: ${TZ_SAFE}"
#  export TZ="${TZ_SAFE}"
#fi
#
## ---- start system dbus ----
#mkdir -p /run/dbus
#if ! pgrep -f "dbus-daemon --system" >/dev/null 2>&1; then
#  log "Starting system dbus-daemon..."
#  dbus-daemon --system --fork --nopidfile >/tmp/dbus-system.log 2>&1 || true
#fi
#
## ---- cleanup possible stale X locks BEFORE Xvfb start ----
#rm -f "/tmp/.X${DISPLAY_NUM}-lock" 2>/dev/null || true
#rm -f "/tmp/.X11-unix/X${DISPLAY_NUM}" 2>/dev/null || true
#
## (optional) kill leftovers if container is restarting too fast
#pkill -TERM -f "Xvfb :${DISPLAY_NUM}" 2>/dev/null || true
#pkill -TERM -f "x11vnc" 2>/dev/null || true
#pkill -TERM -f "fluxbox" 2>/dev/null || true
#pkill -TERM -f "novnc_proxy|websockify" 2>/dev/null || true
#sleep 1
#
## ---- ensure profile writable ----
#mkdir -p "${USER_DATA_DIR}/Default" || true
#chmod -R 777 "${USER_DATA_DIR}" 2>/dev/null || true
#
#TEST_FILE="${USER_DATA_DIR}/__write_test"
#echo "ok" > "${TEST_FILE}" 2>/dev/null || {
#  log "FATAL: USER_DATA_DIR is not writable: ${USER_DATA_DIR}"
#  ls -la "${USER_DATA_DIR}" || true
#  exit 1
#}
#rm -f "${TEST_FILE}" 2>/dev/null || true
#
## remove only singleton locks
#find "${USER_DATA_DIR}" -maxdepth 2 -name "Singleton*" -delete 2>/dev/null || true
#
## preferences restore session
#PREFERENCES_FILE="${USER_DATA_DIR}/Default/Preferences"
#if [[ ! -f "${PREFERENCES_FILE}" ]]; then
#  log "Creating Preferences (restore last session)..."
#  cat > "${PREFERENCES_FILE}" <<'EOF'
#{
#  "session": { "restore_on_startup": 1 },
#  "profile": { "exited_cleanly": true, "exit_type": "Normal" }
#}
#EOF
#fi
#
## ---- Xvfb ----
#log "Starting Xvfb on ${DISPLAY} (${SCREEN_GEOMETRY})..."
#Xvfb "${DISPLAY}" -screen 0 "${SCREEN_GEOMETRY}" -ac +extension RANDR +extension RENDER -noreset >/tmp/xvfb.log 2>&1 &
#XVFB_PID=$!
#sleep 1
#if ! kill -0 "${XVFB_PID}" 2>/dev/null; then
#  log "FATAL: Xvfb failed to start. Tail:"
#  tail -n 50 /tmp/xvfb.log || true
#  exit 1
#fi
#
#log "Waiting for X11 display..."
#for i in $(seq 1 30); do
#  if xdpyinfo >/dev/null 2>&1; then
#    log "X11 ready after ${i}s"
#    break
#  fi
#  sleep 1
#  if [[ "${i}" == "30" ]]; then
#    log "FATAL: X11 not ready"
#    exit 1
#  fi
#done
#
## ---- WM + VNC + noVNC ----
#log "Starting fluxbox..."
#fluxbox >/tmp/fluxbox.log 2>&1 &
#
#log "Starting x11vnc on :5900..."
#x11vnc -display "${DISPLAY}" -rfbport 5900 -forever -shared -nopw -xkb \
#  -noxrecord -noxfixes -noxdamage >/tmp/x11vnc.log 2>&1 &
#
#NOVNC_PROXY="/usr/share/novnc/utils/novnc_proxy"
#log "Starting noVNC on :${NOVNC_PORT} ..."
#"${NOVNC_PROXY}" --listen "0.0.0.0:${NOVNC_PORT}" --vnc "127.0.0.1:5900" >/tmp/novnc.log 2>&1 &
#
## ---- Chromium ----
#CHROME_LOG="/tmp/chromium.log"
#rm -f "${CHROME_LOG}" 2>/dev/null || true
#
#CHROME_ARGS=(
#  "--user-data-dir=${USER_DATA_DIR}"
#  "--window-size=${SCREEN_WIDTH},${SCREEN_HEIGHT}"
#  "--lang=${LANGUAGE_SAFE}"
#  "--restore-last-session"
#  "--no-first-run"
#  "--no-default-browser-check"
#  "--disable-dev-shm-usage"
#  "--remote-debugging-address=0.0.0.0"
#  "--remote-debugging-port=${DEVTOOLS_PORT}"
#  "--remote-allow-origins=*"
#  "--password-store=basic"
#  "--use-mock-keychain"
#  "--no-sandbox"
#  "--disable-features=UseChromeOSDirectVideoDecoder"
#  "--disable-background-timer-throttling"
#  "--disable-renderer-backgrounding"
#  "--disable-backgrounding-occluded-windows"
#)
#
#log "Starting Chromium: ${CHROMIUM_BIN}"
#"${CHROMIUM_BIN}" "${CHROME_ARGS[@]}" >"${CHROME_LOG}" 2>&1 &
#CHROME_PID=$!
#log "Chromium PID: ${CHROME_PID}"
#
#sleep 2
#if ! kill -0 "${CHROME_PID}" 2>/dev/null; then
#  log "FATAL: Chromium exited immediately. Tail:"
#  tail -n 200 "${CHROME_LOG}" || true
#  exit 1
#fi
#
#log "Waiting for DevTools API..."
#for i in $(seq 1 60); do
#  if curl -fsS "http://127.0.0.1:${DEVTOOLS_PORT}/json/version" >/dev/null 2>&1; then
#    log "DevTools ready after ${i}s"
#    break
#  fi
#  sleep 1
#done
#
#if command -v socat >/dev/null 2>&1; then
#  log "Starting DevTools proxy :${DEVTOOLS_PROXY_PORT} -> :${DEVTOOLS_PORT}"
#  socat "TCP-LISTEN:${DEVTOOLS_PROXY_PORT},fork,reuseaddr" "TCP:127.0.0.1:${DEVTOOLS_PORT}" >/tmp/socat.log 2>&1 &
#fi
#
#log "=== STARTUP COMPLETE ==="
#log "noVNC:    http://localhost:${NOVNC_PORT}/vnc.html"
#log "DevTools: http://localhost:${DEVTOOLS_PORT}"
#log "Proxy:    http://localhost:${DEVTOOLS_PROXY_PORT}"
#log "Profile:  ${USER_DATA_DIR}"
#
#wait "${CHROME_PID}" || true
#log "Chromium exited. Tail log:"
#tail -n 200 "${CHROME_LOG}" || true
#exit 0

