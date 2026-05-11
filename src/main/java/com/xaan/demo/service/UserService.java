package com.xaan.demo.service;

import com.xaan.demo.domain.entity.User;
import com.xaan.demo.domain.repository.UserRepository;
import com.xaan.demo.dto.UserRegisterRequestDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@RequiredArgsConstructor
@Service
public class UserService {
    private final UserRepository userRepository;
    private final PasswordService passwordService;

    @Transactional
    public Long register(UserRegisterRequestDto dto) {
        if (dto.getUserId() == null || dto.getUserId().isEmpty()) {
            throw new IllegalArgumentException("사용자 ID를 입력해주세요.");
        }
        if (dto.getPassword() == null || dto.getPassword().isEmpty()) {
            throw new IllegalArgumentException("비밀번호를 입력해주세요.");
        }
        if (dto.getUsername() == null || dto.getUsername().isEmpty()) {
            throw new IllegalArgumentException("사용자 이름을 입력해주세요.");
        }

        if (userRepository.existsByUserId(dto.getUserId())) {
            throw new IllegalArgumentException("이미 존재하는 사용자 ID입니다.");
        }

        String encryptedPassword = passwordService.encryptPassword(dto.getPassword());

        User user = User.builder()
                .userId(dto.getUserId())
                .password(encryptedPassword)
                .username(dto.getUsername())
                .build();

        return userRepository.save(user).getId();
    }

    public Optional<User> findByUserId(String userId) {
        return userRepository.findByUserId(userId);
    }

    public boolean validateLogin(String userId, String rawPassword) {
        Optional<User> userOpt = userRepository.findByUserId(userId);
        if (userOpt.isEmpty()) {
            return false;
        }
        User user = userOpt.get();
        try {
            // Try to decrypt stored password and compare
            String decryptedPassword = passwordService.decryptPassword(user.getPassword());
            return decryptedPassword != null && decryptedPassword.equals(rawPassword);
        } catch (Exception e) {
            // Fallback to validate method if decryption fails
            return passwordService.validatePassword(rawPassword, user.getPassword());
        }
    }
}