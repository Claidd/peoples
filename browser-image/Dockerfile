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





