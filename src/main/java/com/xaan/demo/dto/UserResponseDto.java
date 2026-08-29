package com.xaan.demo.dto;

import com.xaan.demo.domain.entity.User;
import lombok.Getter;

@Getter
public class UserResponseDto {
    private final Long id;
    private final String userId;
    private final String username;
    private final String residentRegistrationNumber;
    private final String phone;

    public UserResponseDto(User entity) {
        this.id = entity.getId();
        this.userId = entity.getUserId();
        this.username = entity.getUsername();
        this.residentRegistrationNumber = entity.getResidentRegistrationNumber();
        this.phone = entity.getPhone();
    }
}
