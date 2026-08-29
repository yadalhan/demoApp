package com.xaan.demo.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class UserRegisterRequestDto {
    private String userId;
    private String password;
    private String username;
    private String residentRegistrationNumberFront;
    private String residentRegistrationNumberBack;
    private String phone;

    public String getResidentRegistrationNumber() {
        return residentRegistrationNumberFront + residentRegistrationNumberBack;
    }
}
