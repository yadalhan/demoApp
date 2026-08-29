package com.xaan.demo.domain.entity;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

// Serializable - Redis 캐싱(UserService.searchRawCached, 사용자목록2)에서 기본 JDK 직렬화로
// 값을 저장하려면 필요하다. 캐시에 담기는 값은 항상 id_no/phone이 ciphertext 상태인 User 객체뿐이다
// (UserMapper.search()가 그렇게 반환함) - 복호화된 평문은 캐시 이후 단계에서만 만들어진다.
@Getter
@NoArgsConstructor
public class User implements Serializable {
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
