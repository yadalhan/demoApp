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

    private String phone;

    // 검색용 HMAC(blind index) - 평문 컬럼. id_no/phone 자체는 암호문이라 등호 검색이 불가능해서 별도로 둔다.
    private String residentRegistrationNumberBlindIndex;

    private String phoneBlindIndex;

    @Builder
    public User(String userId, String password, String username, String residentRegistrationNumber,
                String phone, String residentRegistrationNumberBlindIndex, String phoneBlindIndex) {
        this.userId = userId;
        this.password = password;
        this.username = username;
        this.residentRegistrationNumber = residentRegistrationNumber;
        this.phone = phone;
        this.residentRegistrationNumberBlindIndex = residentRegistrationNumberBlindIndex;
        this.phoneBlindIndex = phoneBlindIndex;
    }
}
