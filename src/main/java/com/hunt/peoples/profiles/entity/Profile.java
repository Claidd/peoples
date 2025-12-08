package com.hunt.peoples.profiles.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


import java.time.Instant;

@Entity
@Table(name = "profiles")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Profile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    /** Путь на сервере, где лежит userDataDir */
    private String userDataPath;

    /** Прокси для профиля */
    private String proxyUrl;

    /** FREE, BUSY, DISABLED */
    private String status;

    /** Кто сейчас использует */
    private Long lockedByUserId;

    private Instant lastUsedAt;
}
