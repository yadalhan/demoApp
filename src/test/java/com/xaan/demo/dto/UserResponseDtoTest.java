package com.xaan.demo.dto;

import com.xaan.demo.domain.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserResponseDtoTest {

    @Test
    void phoneShowsOnlyTheFirstThreeDigits() {
        UserResponseDto dto = new UserResponseDto(sampleUser(), "9001011234568", "01012345678");

        assertThat(dto.getPhone()).isEqualTo("010********");
    }

    @Test
    void residentRegistrationNumberShowsOnlyTheThirdThroughFifthDigits() {
        UserResponseDto dto = new UserResponseDto(sampleUser(), "9001011234568", "01012345678");

        assertThat(dto.getResidentRegistrationNumber()).isEqualTo("**010********");
    }

    @Test
    void decryptionFailurePlaceholderIsPassedThroughUnmasked() {
        UserResponseDto dto = new UserResponseDto(sampleUser(), "(복호화 실패)", "(복호화 실패)");

        assertThat(dto.getResidentRegistrationNumber()).isEqualTo("(복호화 실패)");
        assertThat(dto.getPhone()).isEqualTo("(복호화 실패)");
    }

    @Test
    void nullAndEmptyValuesPassThroughUnmasked() {
        UserResponseDto dto = new UserResponseDto(sampleUser(), null, "");

        assertThat(dto.getResidentRegistrationNumber()).isNull();
        assertThat(dto.getPhone()).isEmpty();
    }

    private static User sampleUser() {
        return User.builder()
                .userId("tester")
                .password("hashed")
                .username("Tester")
                .build();
    }
}
