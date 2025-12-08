FROM debian:12-slim

ENV DEBIAN_FRONTEND=noninteractive \
    DISPLAY=:0 \
    LANG=ru_RU.UTF-8 \
    LANGUAGE=ru_RU:ru \
    LC_ALL=ru_RU.UTF-8

# Базовые пакеты + Chromium + noVNC
RUN apt-get update && apt-get install -y \
    locales \
    ca-certificates \
    wget \
    supervisor \
    x11vnc \
    xvfb \
    fluxbox \
    websockify \
    novnc \
    chromium \
    && rm -rf /var/lib/apt/lists/*

# Локаль
RUN sed -i 's/# ru_RU.UTF-8 UTF-8/ru_RU.UTF-8 UTF-8/' /etc/locale.gen && \
    locale-gen

# Пользователь для браузера
RUN useradd -m -s /bin/bash chrome
USER chrome
WORKDIR /home/chrome

# Папка для профиля (userDataDir)
RUN mkdir -p /home/chrome/user-data
ENV USER_DATA_DIR=/home/chrome/user-data

# Переменная для прокси (пока можно не задавать)
ENV PROXY_URL=""

USER root
COPY start.sh /usr/local/bin/start.sh
RUN chmod +x /usr/local/bin/start.sh && chown chrome:chrome /usr/local/bin/start.sh
USER chrome

# noVNC будет слушать 6080
EXPOSE 6080

CMD ["/usr/local/bin/start.sh"]
