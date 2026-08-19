package com.xaan.demo.domain.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 50, nullable = false, unique = true)
    private String userId;

    @Column(length = 255, nullable = false)
    private String password;

    @Column(length = 100, nullable = false)
    private String username;

    @Column(name = "id_no", length = 255)
    private String residentRegistrationNumber;

    @Builder
    public User(String userId, String password, String username, String residentRegistrationNumber) {
        this.userId = userId;
        this.password = password;
        this.username = username;
        this.residentRegistrationNumber = residentRegistrationNumber;
    }

    public void updatePassword(String password) {
        this.password = password;
    }

    public void updateResidentRegistrationNumber(String residentRegistrationNumber) {
        this.residentRegistrationNumber = residentRegistrationNumber;
    }
}
