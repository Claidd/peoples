# ===== ЭТАП 1: сборка JAR =====
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

# сначала pom.xml (для кеша зависимостей)
COPY pom.xml .
# если есть .mvn/mvnw — тоже можно скопировать
# COPY .mvn .mvn
# COPY mvnw mvnw.cmd .

# затем исходники
COPY src ./src

# сборка приложения
RUN mvn -B clean package -DskipTests

# ===== ЭТАП 2: финальный образ =====
FROM eclipse-temurin:17-jdk

WORKDIR /app

# утилиты для диагностики сети и т.п.
RUN apt-get update && apt-get install -y \
    curl \
    wget \
    iputils-ping \
    netcat-openbsd \
    && rm -rf /var/lib/apt/lists/*

# Проверяем, существует ли группа с GID 1000
RUN if getent group 1000 > /dev/null; then \
      echo "Group with GID 1000 already exists, using existing group"; \
      EXISTING_GROUP=$(getent group 1000 | cut -d: -f1); \
      echo "Existing group name: $EXISTING_GROUP"; \
      GROUP_NAME="$EXISTING_GROUP"; \
    else \
      echo "Creating new group with GID 1000"; \
      groupadd -g 1000 appuser; \
      GROUP_NAME="appuser"; \
    fi && \
    if getent passwd 1000 > /dev/null; then \
      echo "User with UID 1000 already exists, using existing user"; \
      EXISTING_USER=$(getent passwd 1000 | cut -d: -f1); \
      echo "Existing user name: $EXISTING_USER"; \
      USER_NAME="$EXISTING_USER"; \
    else \
      echo "Creating new user with UID 1000"; \
      useradd -u 1000 -g "$GROUP_NAME" -s /bin/bash -m appuser; \
      USER_NAME="appuser"; \
    fi && \
    mkdir -p /app/profiles && \
    chown -R $USER_NAME:$GROUP_NAME /app

# копируем JAR из первого этапа
COPY --from=build /app/target/*.jar /app/app.jar

# входной скрипт
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# Устанавливаем владельца для JAR и entrypoint
RUN chown 1000:1000 /app/app.jar /entrypoint.sh

# Переключаемся на пользователя с UID 1000
USER 1000:1000

EXPOSE 8081

ENTRYPOINT ["/entrypoint.sh"]





