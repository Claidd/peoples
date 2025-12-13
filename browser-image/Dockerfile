FROM debian:12-slim

ENV DEBIAN_FRONTEND=noninteractive \
    DISPLAY=:99 \
    XAUTHORITY=/tmp/.Xauthority

# Системные пакеты
RUN apt-get update && apt-get install -y \
    tzdata \
    locales \
    ca-certificates \
    wget \
    curl \
    gnupg \
    supervisor \
    x11vnc \
    xvfb \
    fluxbox \
    websockify \
    novnc \
    chromium \
    chromium-sandbox \
    xauth \
    x11-xserver-utils \
    dbus-x11 \
    procps \
    fonts-liberation \
    fonts-noto \
    fonts-noto-cjk \
    fonts-noto-color-emoji \
    fonts-freefont-ttf \
    fonts-dejavu-core \
    fonts-dejavu-extra \
    fonts-roboto \
    jq \
    && rm -rf /var/lib/apt/lists/*

# Таймзона
RUN ln -fs /usr/share/zoneinfo/Europe/Moscow /etc/localtime && \
    dpkg-reconfigure --frontend noninteractive tzdata

# Локали
RUN sed -i 's/# en_US.UTF-8 UTF-8/en_US.UTF-8 UTF-8/' /etc/locale.gen && \
    sed -i 's/# ru_RU.UTF-8 UTF-8/ru_RU.UTF-8 UTF-8/' /etc/locale.gen && \
    locale-gen

# --- Создаём пользователя chrome ---
ARG CHROME_UID=1000
ARG CHROME_GID=1000

RUN groupadd -g ${CHROME_GID} chrome && \
    useradd  -m -u ${CHROME_UID} -g ${CHROME_GID} -s /bin/bash chrome

# Папки
RUN mkdir -p /data/user-data /scripts && \
    chown -R chrome:chrome /data /scripts && \
    chmod 700 /data/user-data

# Копируем скрипты
COPY start.sh /scripts/start.sh
RUN chmod +x /scripts/start.sh && chown chrome:chrome /scripts/start.sh

COPY fingerprint_patch.js /scripts/inject.js
RUN chmod +x /scripts/inject.js && chown chrome:chrome /scripts/inject.js

WORKDIR /scripts

# Порты
EXPOSE 6080
EXPOSE 9222
# (опционально, если хочешь прямой VNC)
EXPOSE 5900

HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:6080/ || exit 1

# --- Запускаем контейнер НЕ от root ---
USER chrome

CMD ["/scripts/start.sh"]

