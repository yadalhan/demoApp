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

    public UserResponseDto(User entity, String decryptedResidentRegistrationNumber, String decryptedPhone) {
        this.id = entity.getId();
        this.userId = entity.getUserId();
        this.username = entity.getUsername();
        this.residentRegistrationNumber = maskResidentRegistrationNumber(decryptedResidentRegistrationNumber);
        this.phone = maskPhone(decryptedPhone);
    }

    // 목록 화면에는 복호화된 원문을 그대로 노출하지 않고 일부만 보여준다 - 전화번호는 앞 3자리,
    // 주민등록번호는 3번째 자리부터 3자리만 남기고 나머지는 '*'로 가린다. "(복호화 실패)" 같은 표시
    // 문구는 숫자로만 이루어져 있지 않으므로 그대로 통과시킨다(마스킹 대상이 아님).
    private static String maskPhone(String phone) {
        if (!isAllDigits(phone) || phone.length() <= 3) {
            return phone;
        }
        return phone.substring(0, 3) + "*".repeat(phone.length() - 3);
    }

    private static String maskResidentRegistrationNumber(String residentRegistrationNumber) {
        if (!isAllDigits(residentRegistrationNumber) || residentRegistrationNumber.length() < 5) {
            return residentRegistrationNumber;
        }
        return "*".repeat(2)
                + residentRegistrationNumber.substring(2, 5)
                + "*".repeat(residentRegistrationNumber.length() - 5);
    }

    private static boolean isAllDigits(String value) {
        return value != null && !value.isEmpty() && value.chars().allMatch(Character::isDigit);
    }
}
