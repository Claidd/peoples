#!/bin/bash
set -e

# Проверяем и создаем директории если нужно
if [ ! -d "/app/profiles" ]; then
    echo "Creating profiles directory..."
    mkdir -p /app/profiles
    chmod 755 /app/profiles
fi

echo "Waiting for PostgreSQL to be ready..."
# Ждем готовности PostgreSQL
while ! nc -z postgres 5432; do
  sleep 1
done
echo "PostgreSQL is ready!"

echo "Waiting for application to be ready..."
while ! nc -z localhost 8081; do
  sleep 1
done
echo "Application is ready!"

echo "Starting application..."
# Запуск приложения


# Запускаем приложение
exec java -jar /app/app.jar