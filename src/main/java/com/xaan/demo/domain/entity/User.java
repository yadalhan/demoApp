package com.xaan.demo.domain.entity;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class User {
    private Long id;

    private String userId;

    private String password;

    private String username;

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
