package com.xaan.demo.dto;

import com.xaan.demo.domain.entity.User;
import lombok.Getter;

@Getter
public class UserResponseDto {
    private Long id;
    private String userId;
    private String username;

    public UserResponseDto(User user) {
        this.id = user.getId();
        this.userId = user.getUserId();
        this.username = user.getUsername();
    }
}