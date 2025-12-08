#!/usr/bin/env bash
set -e

export DISPLAY=:0
SCREEN_GEOMETRY=${SCREEN_GEOMETRY:-1920x1000x24}
USER_DATA_DIR="${USER_DATA_DIR:-/home/chrome/user-data}"

mkdir -p "$USER_DATA_DIR"

echo "Using user data dir: $USER_DATA_DIR"

echo "Cleaning up Chromium lock files..."
rm -f "$USER_DATA_DIR/SingletonLock" \
      "$USER_DATA_DIR/SingletonCookie" \
      "$USER_DATA_DIR/SingletonSocket" \
      "$USER_DATA_DIR/Singleton*" 2>/dev/null || true



echo "Proxy: ${PROXY_URL:-<none>}"

echo "Starting Xvfb..."
Xvfb :0 -screen 0 "$SCREEN_GEOMETRY" -ac +extension GLX -noreset &
XVFB_PID=$!
sleep 1


echo "Starting fluxbox..."
fluxbox &

echo "Starting x11vnc..."
x11vnc -display :0 -rfbport 5900 -forever -shared -nopw -quiet &
X11VNC_PID=$!

NOVNC_DIR="/usr/share/novnc"
WEBSOCKIFY_BIN="/usr/bin/websockify"

if [ -d "$NOVNC_DIR" ] && [ -x "$WEBSOCKIFY_BIN" ]; then
  echo "Starting noVNC on :6080..."
  $WEBSOCKIFY_BIN --web "$NOVNC_DIR" 0.0.0.0:6080 localhost:5900 &
  NOVNC_PID=$!
else
  echo "WARNING: noVNC or websockify not found, only VNC on 5900 will be available"
fi

CHROME_PROXY_ARGS=""
if [ -n "$PROXY_URL" ]; then
  CHROME_PROXY_ARGS="--proxy-server=${PROXY_URL}"
fi

echo "Starting Chromium..."
chromium \
  --no-sandbox \
  --disable-dev-shm-usage \
  --user-data-dir="$USER_DATA_DIR" \
  --window-size=1920,1080 \
  --start-maximized \
  $CHROME_PROXY_ARGS \
  "https://2gis.ru" &

CHROME_PID=$!

cleanup() {
  echo "Stopping Chromium..."
  kill $CHROME_PID 2>/dev/null || true

  echo "Stopping noVNC/websockify..."
  [ -n "$NOVNC_PID" ] && kill $NOVNC_PID 2>/dev/null || true

  echo "Stopping x11vnc..."
  kill $X11VNC_PID 2>/dev/null || true

  echo "Stopping Xvfb..."
  kill $XVFB_PID 2>/dev/null || true

  wait || true
}

trap cleanup EXIT
wait $CHROME_PID
