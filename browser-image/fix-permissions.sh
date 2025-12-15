#!/usr/bin/env bash
set -euo pipefail

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

USER_DATA_DIR="${USER_DATA_DIR:-/data/user-data}"

log "Checking permissions for $USER_DATA_DIR..."

# Check if directory exists
if [ ! -d "$USER_DATA_DIR" ]; then
    log "Directory $USER_DATA_DIR does not exist, creating..."
    mkdir -p "$USER_DATA_DIR"
fi

# Check ownership
CURRENT_OWNER=$(stat -c '%u:%g' "$USER_DATA_DIR" 2>/dev/null || echo "0:0")
EXPECTED_OWNER="${CHROME_UID:-1000}:${CHROME_GID:-1000}"

if [ "$CURRENT_OWNER" != "$EXPECTED_OWNER" ]; then
    log "Fixing ownership: $CURRENT_OWNER -> $EXPECTED_OWNER"

    # Use sudo if available and we're not root
    if [ "$(id -u)" = "0" ]; then
        chown -R "${EXPECTED_OWNER}" "$USER_DATA_DIR"
    elif command -v sudo >/dev/null 2>&1; then
        sudo chown -R "${EXPECTED_OWNER}" "$USER_DATA_DIR"
    else
        log "WARNING: Cannot fix ownership, no sudo available"
    fi
fi

# Check permissions
CURRENT_PERM=$(stat -c '%a' "$USER_DATA_DIR" 2>/dev/null || echo "000")
if [ "$CURRENT_PERM" != "755" ] && [ "$CURRENT_PERM" != "775" ]; then
    log "Fixing permissions: $CURRENT_PERM -> 755"

    if [ "$(id -u)" = "0" ]; then
        chmod -R 755 "$USER_DATA_DIR"
    elif command -v sudo >/dev/null 2>&1; then
        sudo chmod -R 755 "$USER_DATA_DIR"
    else
        log "WARNING: Cannot fix permissions, no sudo available"
    fi
fi

log "Permissions check complete"