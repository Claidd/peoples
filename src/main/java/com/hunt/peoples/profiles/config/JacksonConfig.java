package com.hunt.peoples.profiles.config;


import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();

        // Регистрируем модуль для работы с Java 8 Date/Time API
        objectMapper.registerModule(new JavaTimeModule());

        // Отключаем запись дат в виде timestamp
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Включаем красивый вывод JSON
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        // Игнорируем неизвестные свойства при десериализации
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return objectMapper;
    }
}